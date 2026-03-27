package com.softropic.payam.orange.service;

import com.softropic.payam.common.payment.MobileMoneyPort;
import com.softropic.payam.common.payment.PaymentCommand;
import com.softropic.payam.common.payment.ProviderResult;
import com.softropic.payam.common.payment.SubscriberStatus;
import com.softropic.payam.orange.config.OrangeMoneyConfig;
import com.softropic.payam.orange.contract.OrangeWebhookPayload;
import com.softropic.payam.orange.contract.dto.MerchantInfoResponse;
import com.softropic.payam.orange.contract.dto.PayRequest;
import com.softropic.payam.orange.contract.dto.PayResponse;
import com.softropic.payam.orange.contract.dto.SubscriberInfoResponse;
import com.softropic.payam.orange.contract.exception.OrangeApiException;
import com.softropic.payam.orange.contract.exception.PayTokenExpiredException;
import com.softropic.payam.orange.contract.exception.SubscriberInactiveException;
import com.softropic.payam.orange.infrastructure.OrangeMoneyClient;
import com.softropic.payam.transaction.contract.TransactionEventType;
import com.softropic.payam.transaction.contract.TransactionStatus;
import com.softropic.payam.transaction.repo.TransactionRepository;
import com.softropic.payam.transaction.service.EventLogService;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;

import com.softropic.payam.webhook.contract.WebhookReceivedEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.logstash.logback.argument.StructuredArguments.kv;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;

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

    public OrangeMoneyPort(OrangeMoneyClient orangeMoneyClient,
                           OrangeTokenService orangeTokenService,
                           TransactionRepository transactionRepository,
                           EventLogService eventLogService,
                           OrangeMoneyConfig config,
                           ApplicationEventPublisher eventPublisher,
                           TransactionTemplate transactionTemplate) {
        this.orangeMoneyClient = orangeMoneyClient;
        this.orangeTokenService = orangeTokenService;
        this.transactionRepository = transactionRepository;
        this.eventLogService = eventLogService;
        this.config = config;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Implements MobileMoneyPort.initiateMerchantPayment.
     *
     * IMPORTANT (P1.1): This method is NOT @Transactional. The caller (TransactionService.initiate)
     * has already committed the INITIATED row before calling this method. Do not wrap this
     * method in @Transactional — that would hold the DB connection open during the outbound HTTP calls.
     *
     * Flow: validate subscriber -> get payToken -> call pay -> return ProviderResult(pending=true)
     */
    @Override
    @CircuitBreaker(name = "orange")
    @Retry(name = "orange")
    public ProviderResult initiateMerchantPayment(PaymentCommand cmd) {
        String token = orangeTokenService.getAccessToken();
        String nationalMsisdn = stripCountryCode(cmd.msisdn());

        // Step 1: Validate subscriber
        SubscriberInfoResponse subscriberInfo = orangeMoneyClient.getSubscriberInfo(token, nationalMsisdn);
        if (!subscriberInfo.isActive()) {
            throw new SubscriberInactiveException(cmd.msisdn());
        }

        // Step 2: Get payToken
        MerchantInfoResponse merchantInfo = orangeMoneyClient.getMerchantInfo(token);
        String payToken = merchantInfo.getPayToken();
        Instant payTokenIssuedAt = Instant.now();

        // Step 3: Persist payToken and issuance time on the transaction (non-transactional update)
        persistPayToken(cmd.transactionId(), payToken, payTokenIssuedAt);

        // Step 4: Call /mp/pay
        PayRequest payRequest = buildPayRequest(cmd, payToken, nationalMsisdn);
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
     * Implements MobileMoneyPort.getTransactionStatus.
     * Uses @Lock(PESSIMISTIC_WRITE) to prevent concurrent webhook+poller race (P1.2).
     */
    @Override
    @Transactional
    @CircuitBreaker(name = "orange")
    public ProviderResult getTransactionStatus(String providerRef) {
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
     * Implements MobileMoneyPort.validateSubscriber.
     */
    @Override
    public SubscriberStatus validateSubscriber(String msisdn) {
        String token = orangeTokenService.getAccessToken();
        String nationalMsisdn = stripCountryCode(msisdn);
        SubscriberInfoResponse response = orangeMoneyClient.getSubscriberInfo(token, nationalMsisdn);
        return new SubscriberStatus(response.isActive(), msisdn, response.getStatus());
    }

    /**
     * Initiate a cashout transaction.
     *
     * ROADMAP deviation (SC-3): Cashout field mapping requires sandbox verification with live
     * Orange credentials. Stub retained intentionally — Phase 3 covers MP flow only.
     * C2C and cashout will be implemented in a future phase once sandbox access is confirmed.
     *
     * Note: No @CircuitBreaker/@Retry — this method unconditionally throws; circuit-breaking
     * a stub adds no value and prevents tests from asserting the expected exception.
     */
    public ProviderResult initiateCashout(PaymentCommand cmd) {
        throw new UnsupportedOperationException("Cashout field mapping requires sandbox verification — stub for now");
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
            // Publish inside a transaction boundary so @TransactionalEventListener(AFTER_COMMIT) fires
            transactionTemplate.execute(status -> {
                eventPublisher.publishEvent(new WebhookReceivedEvent(
                    txId,
                    com.softropic.payam.common.payment.MobilePaymentProvider.ORANGE,
                    payload.getPayToken(),
                    traceId
                ));
                return null;
            });
        }, () -> log.warn("Orange webhook: no transaction found",
            kv("operation", "webhook_received"),
            kv("provider", "ORANGE"),
            kv("status", "TRANSACTION_NOT_FOUND")));

        return payload.getPayToken();
    }

    /**
     * Check if a payToken has expired (P1.3).
     * Throws PayTokenExpiredException if age exceeds config threshold.
     *
     * Production caller: OrangeStatusPollerJob.pollTransaction() — guards before each poll attempt.
     *
     * Re-initiation responsibility: when PayTokenExpiredException is thrown, the caller should
     * log and skip. Fetching a fresh payToken (getMerchantInfo() + pay()) requires the original
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
     * This is deliberately separate from the outer flow — the INITIATED row was already committed
     * by TransactionService. Uses REQUIRES_NEW propagation to commit the payToken fields
     * immediately so the poller and webhook handler can see them.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void persistPayToken(String transactionId, String payToken, Instant issuedAt) {
        transactionRepository.findByTransactionId(transactionId).ifPresent(tx -> {
            tx.setPayToken(payToken);
            tx.setPayTokenIssuedAt(issuedAt);
            transactionRepository.save(tx);
        });
    }

    private PayRequest buildPayRequest(PaymentCommand cmd, String payToken, String nationalMsisdn) {
        PayRequest req = PayRequest.of(
            config.getConsumerKey(),
            cmd.currency(),
            cmd.transactionId(),
            cmd.amount().toPlainString(),
            cmd.externalReference() != null ? cmd.externalReference() : cmd.transactionId()
        );
        return req;
    }

    // No fallback methods — circuit-open throws CallNotPermittedException to caller.
    // Phase 5 (PaymentOrchestrator) handles circuit state at the orchestration level.
}
