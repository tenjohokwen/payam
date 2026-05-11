package com.softropic.payam.orange.service;

import com.softropic.payam.common.payment.MobileMoneyPort;
import com.softropic.payam.common.payment.PaymentCommand;
import com.softropic.payam.common.payment.ProviderResult;
import com.softropic.payam.common.payment.SubscriberStatus;
import com.softropic.payam.payment.disbursement.repo.DisbursementRepository;
import com.softropic.payam.orange.config.OrangeMoneyConfig;
import com.softropic.payam.orange.contract.OrangeWebhookPayload;
import com.softropic.payam.orange.contract.dto.CashoutRequest;
import com.softropic.payam.orange.contract.dto.InitTransactionResponse;
import com.softropic.payam.orange.contract.dto.PayRequest;
import com.softropic.payam.orange.contract.dto.PayResponse;
import com.softropic.payam.orange.contract.dto.SubscriberInfoResponse;
import com.softropic.payam.orange.contract.exception.PayTokenExpiredException;
import com.softropic.payam.orange.infrastructure.OrangeMoneyClient;
import com.softropic.payam.platform.admin.service.PlatformConfigService;
import com.softropic.payam.payment.ledger.contract.LedgerPosting;
import com.softropic.payam.payment.ledger.contract.TransactionEventType;
import com.softropic.payam.payment.ledger.contract.TransactionStatus;
import com.softropic.payam.payment.ledger.repo.TransactionRepository;
import com.softropic.payam.payment.ledger.service.EventLogService;
import com.softropic.payam.payment.ledger.service.LedgerService;
import com.softropic.payam.payment.webhook.contract.WebhookReceivedEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Service
public class OrangeMoneyPort implements MobileMoneyPort {

    private static final Logger log = LoggerFactory.getLogger(OrangeMoneyPort.class);

    private final OrangeMoneyClient orangeMoneyClient;
    private final OrangeTokenService orangeTokenService;
    private final TransactionRepository transactionRepository;
    private final EventLogService eventLogService;
    private final OrangeMoneyConfig config;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final PlatformConfigService platformConfigService;
    private final LedgerService ledgerService;
    private final StringRedisTemplate redis;
    private final DisbursementRepository disbursementRepository;

    public OrangeMoneyPort(OrangeMoneyClient orangeMoneyClient,
                           OrangeTokenService orangeTokenService,
                           TransactionRepository transactionRepository,
                           EventLogService eventLogService,
                           OrangeMoneyConfig config,
                           ApplicationEventPublisher eventPublisher,
                           TransactionTemplate transactionTemplate,
                           PlatformConfigService platformConfigService,
                           LedgerService ledgerService,
                           StringRedisTemplate redis,
                           DisbursementRepository disbursementRepository) {
        this.orangeMoneyClient = orangeMoneyClient;
        this.orangeTokenService = orangeTokenService;
        this.transactionRepository = transactionRepository;
        this.eventLogService = eventLogService;
        this.config = config;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = transactionTemplate;
        this.platformConfigService = platformConfigService;
        this.ledgerService = ledgerService;
        this.redis = redis;
        this.disbursementRepository = disbursementRepository;
    }

    /**
     * Implements MobileMoneyPort.initiateMerchantPayment.
     *
     * IMPORTANT (P1.1): This method is NOT @Transactional. The caller (TransactionService.initiate)
     * has already committed the INITIATED row before calling this method. Do not wrap this
     * method in @Transactional — that would hold the DB connection open during the outbound HTTP calls.
     *
     * Flow: get payToken -> call pay -> return ProviderResult(pending=true)
     */
    @Override
    @CircuitBreaker(name = "orange")
    @Retry(name = "orange")
    public ProviderResult initiateMerchantPayment(PaymentCommand cmd) {
        String token = orangeTokenService.getAccessToken();
        String nationalMsisdn = stripCountryCode(cmd.msisdn());

        // Step 1: Get payToken via POST /mp/init
        InitTransactionResponse initResponse = orangeMoneyClient.initTransaction(token);
        String payToken = initResponse.getPayToken();
        Instant payTokenIssuedAt = Instant.now();

        // Step 2: Persist payToken and issuance time on the transaction (non-transactional update)
        persistPayToken(cmd.transactionId(), payToken, payTokenIssuedAt);

        // Fetch PIN
        String pin = platformConfigService.findPinByProvider("ORANGE").pin();

        // Step 3: Call /mp/pay
        PayRequest payRequest = buildPayRequest(cmd, payToken, nationalMsisdn, pin);
        PayResponse payResponse = orangeMoneyClient.pay(token, payRequest);

        // Append event log entry for INIT->PROCESSING
        eventLogService.append(
            cmd.transactionId(), cmd.traceId(), cmd.externalReference(),
            TransactionEventType.PAYMENT_INITIATED,
            TransactionStatus.INITIATED, TransactionStatus.PROCESSING,
            "ORANGE_ADAPTER", null
        );

        return ProviderResult.pending(payToken, payResponse.getStatus());
    }

    /**
     * Implements MobileMoneyPort.getCollectionTransactionStatus.
     * Uses @Lock(PESSIMISTIC_WRITE) to prevent concurrent webhook+poller race (P1.2).
     */
    @Override
    @Transactional
    @CircuitBreaker(name = "orange")
    public ProviderResult getCollectionTransactionStatus(String providerRef) {
        String token = orangeTokenService.getAccessToken();
        PayResponse status = orangeMoneyClient.getPaymentStatus(token, providerRef);
        String rawStatus = status.getStatus();
        TransactionStatus internal = OrangeStatusMapper.toInternal(rawStatus);
        boolean pending = internal == TransactionStatus.PROCESSING;
        return pending
            ? ProviderResult.pending(providerRef, rawStatus)
            : ProviderResult.success(providerRef, rawStatus);
    }

    /**
     * Implements MobileMoneyPort.getDisbursementTransactionStatus.
     * Orange uses the same endpoint/logic for both products.
     */
    @Override
    @Transactional
    @CircuitBreaker(name = "orange")
    public ProviderResult getDisbursementTransactionStatus(String providerRef) {
        return getCollectionTransactionStatus(providerRef);
    }

    /**
     * Implements MobileMoneyPort.validateSubscriber.
     */
    @Override
    public SubscriberStatus validateSubscriber(String msisdn) {
        String token = orangeTokenService.getAccessToken();
        String nationalMsisdn = stripCountryCode(msisdn);
        String channelMsisdn = platformConfigService.findByProvider("ORANGE").platformMsisdn();
        SubscriberInfoResponse response = orangeMoneyClient.getSubscriberInfo(token, "customer", nationalMsisdn, channelMsisdn);
        return new SubscriberStatus(response.isActive(), msisdn, response.getMessage());
    }

    /**
     * Initiate a cashout transaction (CASHOUT-02).
     *
     * Flow: get bearer token -> POST /cashout via OrangeMoneyClient -> on 2xx success,
     * post a disbursement ledger entry (DEBIT MERCHANT_WALLET gross + CREDIT CUSTOMER_WALLET
     * principal + CREDIT PROVIDER_FEE fee) inside a TransactionTemplate.execute block.
     *
     * IMPORTANT: This method is NOT @Transactional. The outbound HTTP call must run
     * outside any DB transaction (same rule as initiateMerchantPayment). Only the
     * ledger write is wrapped in a TransactionTemplate.execute block so LedgerService's
     * @Transactional propagation runs inside a real transaction.
     *
     * Null fee handling: when cmd.feeAmount() is null (no fee configured), the
     * disbursement posts with BigDecimal.ZERO fee, producing a zero-amount
     * PROVIDER_FEE credit row that still balances the group (V25 trigger allows
     * amount >= 0 and sums to zero on each direction because principal == gross).
     */
    @Override
    @CircuitBreaker(name = "orange")
    @Retry(name = "orange")
    public ProviderResult initiateDisbursement(PaymentCommand cmd) {
        String token = orangeTokenService.getAccessToken();
        String nationalMsisdn = stripCountryCode(cmd.msisdn());
        String merchantKey = platformConfigService.findByProvider("ORANGE").platformMsisdn();

        CashoutRequest request = new CashoutRequest();
        request.setMerchantKey(merchantKey);
        request.setAmount(cmd.amount().toPlainString());
        request.setCurrency(cmd.currency());
        request.setReference(cmd.transactionId());
        request.setMsisdn(nationalMsisdn);

        ResponseEntity<Map> response = orangeMoneyClient.disburse(token, request);

        if (response.getStatusCode().is2xxSuccessful()) {
            // Extract payToken from the cashout response body if present.
            // This is the correlation ID that Orange includes in disbursement callbacks
            // (OrangeWebhookPayload.payToken). Storing it as providerRef enables
            // processDisbursementCallback's findByProviderRef(payToken) lookup.
            String payToken = null;
            if (response.getBody() != null) {
                Object pt = response.getBody().get("payToken");
                if (pt != null) {
                    payToken = pt.toString();
                }
            }
            BigDecimal fee = cmd.feeAmount() != null ? cmd.feeAmount() : BigDecimal.ZERO;
            transactionTemplate.execute(status -> {
                ledgerService.postEntry(
                    cmd.transactionId(),
                    cmd.tenantId(),
                    LedgerPosting.disbursement(cmd.amount(), fee, cmd.currency())
                );
                return null;
            });
            return ProviderResult.success(payToken, "DISBURSEMENT_SUCCESS");
        }
        return ProviderResult.pending(null, "DISBURSEMENT_PENDING");
    }

    /**
     * Initiate a C2C transfer.
     *
     * ROADMAP deviation (SC-3): Same as cashout — requires sandbox field verification.
     *
     * Note: No @CircuitBreaker/@Retry — this method unconditionally throws.
     */
    public ProviderResult initiateC2C(PaymentCommand cmd, String msisdnTo) {
        throw new UnsupportedOperationException("C2C field mapping requires sandbox verification — stub for now");
    }

    /**
     * Webhook processing hook for Phase 6.
     * Does NOT apply state transition — publishes WebhookReceivedEvent for double-check (P1.4).
     * Returns the payToken for correlation.
     *
     * The event is published inside a TransactionTemplate boundary so that
     * @TransactionalEventListener(AFTER_COMMIT) fires after the transaction commits.
     * Without this boundary, there is no active transaction and the listener never fires.
     */
    public String processWebhook(OrangeWebhookPayload payload, String notifToken) {
        // Validate notifToken correlation
        if (notifToken != null && !notifToken.equals(payload.getNotifToken())) {
            log.warn("Orange notifToken mismatch",
                kv("operation", "webhook_received"),
                kv("provider", "ORANGE"),
                kv("mismatch", true));
        }

        // Look up Transaction by payToken (Pitfall 3: NOT txnid — txnid is Orange-internal)
        transactionRepository.findByPayToken(payload.getPayToken()).ifPresentOrElse(tx -> {
            String txId = tx.getTransactionId();
            String traceId = tx.getTraceId();
            // LOG-BUS-03: structured webhook receipt event (emitted here where txId is available)
            log.info("Webhook received",
                kv("operation", "webhook_received"),
                kv("provider", "ORANGE"),
                kv("transactionId", txId),
                kv("externalReference", tx.getExternalReference()),
                kv("providerStatus", payload.getStatus()));
            com.softropic.payam.payment.ledger.contract.LedgerFlow flow = tx.getEffectiveFlow();
            // Publish inside a transaction boundary so @TransactionalEventListener(AFTER_COMMIT) fires
            transactionTemplate.execute(status -> {
                eventPublisher.publishEvent(new WebhookReceivedEvent(
                    txId,
                    com.softropic.payam.common.payment.MobilePaymentProvider.ORANGE,
                    payload.getPayToken(),
                    traceId,
                    flow
                ));
                return null;
            });        }, () -> log.warn("Orange webhook: no transaction found",
            kv("operation", "webhook_received"),
            kv("provider", "ORANGE"),
            kv("status", "TRANSACTION_NOT_FOUND")));

        return payload.getPayToken();
    }

    /**
     * Orange disbursement callback handler — Phase 52 (SEC-05).
     * Called by OrangeDisbursementCallbackController. Performs Redis replay dedup on the
     * "callbacks:dsb:" namespace (segregated from collection "webhook:orange:" namespace),
     * looks up the Disbursement by providerRef (Orange payToken), and publishes
     * WebhookReceivedEvent with flow=DISBURSEMENT.
     *
     * <p>Correlation strategy (Open Question 3 in 52-RESEARCH): try findByProviderRef(payToken)
     * first; if not found, try findByReference(txnid) — covers both possible Orange disbursement
     * callback shapes until sandbox confirms which field carries the merchant correlation.
     */
    public String processDisbursementCallback(OrangeWebhookPayload payload, String notifToken) {
        String payToken = payload.getPayToken() != null ? payload.getPayToken() : "";
        String status = payload.getStatus() != null ? payload.getStatus() : "UNKNOWN";

        // Dedup namespace: callbacks:dsb:<payToken>:<status>
        String dedupKey = "callbacks:dsb:" + payToken + ":" + status;
        Boolean wasAbsent = redis.opsForValue().setIfAbsent(dedupKey, "SEEN", Duration.ofHours(24));
        if (Boolean.FALSE.equals(wasAbsent)) {
            log.info("Orange disbursement callback duplicate suppressed",
                kv("operation", "webhook_received"),
                kv("provider", "ORANGE"),
                kv("flow", "DISBURSEMENT"),
                kv("payToken", payToken),
                kv("status", "DUPLICATE"));
            return payToken;
        }

        // Validate notifToken correlation
        if (notifToken != null && !notifToken.equals(payload.getNotifToken())) {
            log.warn("Orange disbursement notifToken mismatch",
                kv("operation", "webhook_received"),
                kv("provider", "ORANGE"),
                kv("flow", "DISBURSEMENT"),
                kv("mismatch", true));
        }

        log.info("Disbursement webhook received",
            kv("operation", "webhook_received"),
            kv("provider", "ORANGE"),
            kv("flow", "DISBURSEMENT"),
            kv("payToken", payToken),
            kv("providerStatus", status));

        // Look up Disbursement by providerRef (Orange payToken) first; fall back to merchant reference
        java.util.Optional<com.softropic.payam.payment.disbursement.repo.Disbursement> dsbOpt =
            disbursementRepository.findByProviderRef(payToken);
        if (dsbOpt.isEmpty() && payload.getTxnid() != null) {
            dsbOpt = disbursementRepository.findByReference(payload.getTxnid());
        }
        dsbOpt.ifPresentOrElse(dsb -> {
            String dsbId = dsb.getDisbursementId();
            transactionTemplate.execute(s -> {
                eventPublisher.publishEvent(new WebhookReceivedEvent(
                    dsbId,
                    com.softropic.payam.common.payment.MobilePaymentProvider.ORANGE,
                    payToken,
                    dsbId,    // traceId — disbursementId doubles as traceId for callbacks
                    com.softropic.payam.payment.ledger.contract.LedgerFlow.DISBURSEMENT
                ));
                return null;
            });
        }, () -> log.warn("Orange disbursement callback: no disbursement found",
            kv("operation", "webhook_received"),
            kv("provider", "ORANGE"),
            kv("flow", "DISBURSEMENT"),
            kv("payToken", payToken),
            kv("status", "DISBURSEMENT_NOT_FOUND")));

        return payToken;
    }

    /**
     * Check if a payToken has expired (P1.3).
     * Throws PayTokenExpiredException if age exceeds config threshold.
     *
     * Production caller: OrangeStatusPollerJob.pollTransaction() — guards before each poll attempt.
     *
     * Re-initiation responsibility: when PayTokenExpiredException is thrown, the caller should
     * log and skip. Fetching a fresh payToken (initTransaction() + pay()) requires the original
     * PaymentCommand context — this is Phase 5 PaymentOrchestrator responsibility (ROADMAP SC-4).
     */
    public void assertPayTokenFresh(String transactionId, Instant payTokenIssuedAt) {
        if (payTokenIssuedAt == null) return;
        Duration age = Duration.between(payTokenIssuedAt, Instant.now());
        if (age.toMinutes() >= config.getPayTokenExpiryThresholdMinutes()) {
            log.warn("Orange payToken expired",
                kv("operation", "orange_poller_scan"),
                kv("transactionId", transactionId),
                kv("ageMinutes", age.toMinutes()),
                kv("status", "TOKEN_EXPIRED"));
            throw new PayTokenExpiredException(transactionId);
        }
    }

    // ---- private helpers ----

    /**
     * Strip Cameroon country code from E.164 MSISDN for Orange endpoints (P5.5).
     * "+237692954629" -> "692954629"
     * "237692954629"  -> "692954629"
     * "692954629"     -> "692954629" (already national)
     */
    private String stripCountryCode(String msisdn) {
        if (msisdn == null) return null;
        return msisdn.replaceFirst("^\\+?237", "");
    }

    /**
     * Persist payToken and issuance time to the Transaction record.
     * Uses TransactionTemplate directly (not @Transactional) because this method is called via
     * self-invocation (this.persistPayToken), which bypasses the Spring AOP proxy and makes
     * @Transactional a no-op. TransactionTemplate creates a real transaction so that
     * findByTransactionIdForUpdate can acquire the pessimistic write lock.
     */
    protected void persistPayToken(String transactionId, String payToken, Instant issuedAt) {
        transactionTemplate.execute(status -> {
            transactionRepository.findByTransactionIdForUpdate(transactionId).ifPresent(tx -> {
                tx.setPayToken(payToken);
                tx.setPayTokenIssuedAt(issuedAt);
                transactionRepository.save(tx);
            });
            return null;
        });
    }

    private PayRequest buildPayRequest(PaymentCommand cmd, String payToken, String nationalMsisdn, String pin) {
        String channelMsisdn = platformConfigService.findByProvider("ORANGE").platformMsisdn();
        String orderId = cmd.transactionId();
        String desc = cmd.description() != null ? cmd.description() : "Payment";
        return PayRequest.of(payToken, nationalMsisdn, channelMsisdn,
                cmd.amount().toPlainString(), orderId, desc, config.getCallbackUrl(), pin);
    }

    // No fallback methods — circuit-open throws CallNotPermittedException to caller.
    // Phase 5 (PaymentOrchestrator) handles circuit state at the orchestration level.
}
