package com.softropic.payam.payment.service;

import com.softropic.payam.common.client.exception.HttpClientException;
import com.softropic.payam.common.payment.MobileMoneyPort;
import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.common.payment.PaymentCommand;
import com.softropic.payam.common.payment.ProviderResult;
import com.softropic.payam.common.util.JsonUtil;
import com.softropic.payam.platform.admin.service.PaymentMetricsService;
import com.softropic.payam.payment.fee.service.FeeEvaluationService;
import com.softropic.payam.payment.fraud.contract.FraudDecision;
import com.softropic.payam.payment.fraud.service.FraudScoringService;
import com.softropic.payam.mtn.contract.exception.MtnAccountInactiveException;
import com.softropic.payam.mtn.service.MtnMoMoPort;
import com.softropic.payam.orange.contract.exception.SubscriberInactiveException;
import com.softropic.payam.orange.service.OrangeMoneyPort;
import com.softropic.payam.payment.contract.OrchestratorError;
import com.softropic.payam.payment.contract.PaymentRequest;
import com.softropic.payam.payment.contract.PaymentResponse;
import com.softropic.payam.payment.contract.exception.UnknownMsisdnPrefixException;
import com.softropic.payam.platform.security.common.util.RequestMetadata;
import com.softropic.payam.platform.security.common.util.RequestMetadataProvider;
import com.softropic.payam.transaction.contract.CachedResponse;
import com.softropic.payam.transaction.contract.TransactionEventType;
import com.softropic.payam.transaction.contract.TransactionStatus;
import com.softropic.payam.transaction.repo.Transaction;
import com.softropic.payam.transaction.repo.TransactionRepository;
import com.softropic.payam.transaction.service.EventLogService;
import com.softropic.payam.transaction.service.IdempotencyService;
import com.softropic.payam.transaction.service.TransactionService;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.softropic.payam.platform.security.common.util.TenantContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Core payment orchestration service — wires MSISDN routing, idempotency, transaction lifecycle,
 * and provider dispatch behind a single {@link #initiate(Long, PaymentRequest)} method.
 *
 * <p><strong>IMPORTANT (P1.1 / P8.1):</strong> This method is deliberately NOT annotated with
 * {@code @Transactional}. Holding a database connection open during outbound HTTP calls to
 * Orange/MTN would exhaust the connection pool under load. Instead, each database interaction
 * uses its own transaction scope via {@link TransactionTemplate} or the downstream service's
 * own {@code @Transactional} boundaries.
 *
 * <p><strong>Circuit breaker placement:</strong> {@code @CircuitBreaker} annotations live on
 * {@link OrangeMoneyPort#initiateMerchantPayment} and {@link MtnMoMoPort#initiateMerchantPayment}
 * (set in Phases 3 and 4). This orchestrator simply catches {@link CallNotPermittedException}
 * when Resilience4j opens the circuit on a port.
 */
@Service
public class PaymentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PaymentOrchestrator.class);

    private final OrangeMoneyPort orangePort;
    private final MtnMoMoPort mtnPort;
    private final MsisdnRouter msisdnRouter;
    private final TransactionService transactionService;
    private final IdempotencyService idempotencyService;
    private final TransactionRepository transactionRepository;
    private final EventLogService eventLogService;
    private final TransactionTemplate transactionTemplate;
    private final FraudScoringService fraudScoringService;
    private final PaymentMetricsService metricsService;
    private final FeeEvaluationService feeEvaluationService;

    public PaymentOrchestrator(OrangeMoneyPort orangePort,
                               MtnMoMoPort mtnPort,
                               MsisdnRouter msisdnRouter,
                               TransactionService transactionService,
                               IdempotencyService idempotencyService,
                               TransactionRepository transactionRepository,
                               EventLogService eventLogService,
                               TransactionTemplate transactionTemplate,
                               FraudScoringService fraudScoringService,
                               PaymentMetricsService metricsService,
                               FeeEvaluationService feeEvaluationService) {
        this.orangePort = orangePort;
        this.mtnPort = mtnPort;
        this.msisdnRouter = msisdnRouter;
        this.transactionService = transactionService;
        this.idempotencyService = idempotencyService;
        this.transactionRepository = transactionRepository;
        this.eventLogService = eventLogService;
        this.transactionTemplate = transactionTemplate;
        this.fraudScoringService = fraudScoringService;
        this.metricsService = metricsService;
        this.feeEvaluationService = feeEvaluationService;
    }

    /**
     * Initiate a payment for the given tenant.
     *
     * <p>Sequence:
     * <ol>
     *   <li>Resolve MSISDN to provider (throws UnknownMsisdnPrefixException if unrecognized)</li>
     *   <li>Idempotency check — return cached response or PAYMENT_ALREADY_PROCESSING if duplicate</li>
     *   <li>Create INITIATED transaction row (committed by TransactionService)</li>
     *   <li>Build PaymentCommand</li>
     *   <li>Dispatch to provider port (outside any @Transactional boundary)</li>
     *   <li>Apply state transitions: INITIATED → AUTH_PENDING → AUTHORIZED → PROCESSING</li>
     *   <li>Store idempotency response</li>
     * </ol>
     *
     * @param tenantId tenant identifier from the authenticated TenantPrincipal
     * @param request  validated payment request
     * @return PaymentResponse — always non-null; check {@code errorCode} for failure
     */
    public PaymentResponse initiate(Long tenantId, PaymentRequest request) {
        long start = System.currentTimeMillis();

        // Step 1: Resolve MSISDN to provider
        MobilePaymentProvider provider;
        try {
            provider = msisdnRouter.resolve(request.msisdn());
        } catch (UnknownMsisdnPrefixException e) {
            log.error("Unknown MSISDN prefix", kv("operation", "route_msisdn"), kv("status", "UNKNOWN_PREFIX"));
            return PaymentResponse.failed(null,
                    OrchestratorError.UNKNOWN_MSISDN_PREFIX.getErrorCode(),
                    e.getMessage());
        }

        // Step 2: Idempotency check
        Optional<CachedResponse> cached = idempotencyService.checkAndReserve(tenantId, request.idempotencyKey());
        if (cached.isPresent()) {
            CachedResponse cr = cached.get();
            if ("RESERVED".equals(cr.responseBody())) {
                log.info("Payment already in progress",
                    kv("operation", "initiate_payment"),
                    kv("tenantId", tenantId),
                    kv("idempotencyKey", request.idempotencyKey()),
                    kv("status", "IN_PROGRESS"));
                return PaymentResponse.failed(null,
                        OrchestratorError.PAYMENT_ALREADY_PROCESSING.getErrorCode(),
                        "Payment already in progress");
            }
            // Deserialize cached successful response and replay it
            log.info("Idempotency replay",
                kv("operation", "initiate_payment"),
                kv("tenantId", tenantId),
                kv("idempotencyKey", request.idempotencyKey()),
                kv("status", "REPLAYED"));
            PaymentResponse cachedResponse = JsonUtil.toObject(cr.responseBody(), PaymentResponse.class);
            // Null-guard feeAmount for cached entries that predate Phase 11 (old JSON has no feeAmount)
            if (cachedResponse.feeAmount() == null) {
                cachedResponse = new PaymentResponse(cachedResponse.transactionId(), cachedResponse.status(), cachedResponse.providerRef(),
                        cachedResponse.errorCode(), cachedResponse.errorMessage(), BigDecimal.ZERO, cachedResponse.feeRuleId());
            }
            return cachedResponse;
        }

        // Step 3: Create INITIATED transaction row (TransactionService.initiate is @Transactional and commits)
        Transaction tx = transactionService.initiate(tenantId, provider, request.amount(), request.currency(), request.externalReference());

        // Step 4: Build PaymentCommand — populate real IP/UA from RequestMetadata and fingerprint from request
        RequestMetadata clientInfo = RequestMetadataProvider.getClientInfo();
        PaymentCommand cmd = new PaymentCommand(
                tx.getTransactionId(),
                tx.getTraceId(),
                tenantId,
                request.msisdn(),
                request.amount(),
                request.currency(),
                request.externalReference(),
                request.idempotencyKey(),
                provider,
                clientInfo.getIpAddress(),
                clientInfo.getUserAgent(),
                request.deviceFingerprint(),
                request.description()     // passed to Orange /mp/pay; ignored by other adapters
        );

        // Step 4.5: Fraud check — evaluate before any provider call (see P7.1 / FRAUD-01)
        FraudDecision fraud = fraudScoringService.evaluate(cmd);
        if (fraud.blocked()) {
            log.warn("Payment initiation blocked",
                kv("operation", "initiate_payment"),
                kv("tenantId", TenantContext.get()),
                kv("transactionId", tx.getTransactionId()),
                kv("provider", provider.name()),
                kv("msisdn", msisdnLast4(request.msisdn())),
                kv("durationMs", System.currentTimeMillis() - start),
                kv("status", "FAILED"),
                kv("errorCode", OrchestratorError.FRAUD_BLOCKED.getErrorCode()));
            applyFailed(tx, TransactionStatus.INITIATED, OrchestratorError.FRAUD_BLOCKED, fraud.reason());
            return PaymentResponse.failed(tx.getTransactionId(),
                    OrchestratorError.FRAUD_BLOCKED.getErrorCode(),
                    "Payment blocked: " + fraud.reason());
        }
        // TXN-01: compute fee BEFORE acquiring the DB row lock — pure in-memory cache operation
        BigDecimal fee = feeEvaluationService.evaluateFee(tenantId, request.amount());
        Long feeRuleIdVal = feeEvaluationService.findRuleForTenant(tenantId)
                .map(r -> r.getId())
                .orElse(null);

        // CASHOUT-01: propagate evaluated fee onto the command so downstream ports
        // (initiateCashout in Plan 02) observe the authoritative fee value.
        // Capture deviceFingerprint before rebind — lambda requires effectively-final variable.
        String deviceFingerprint = cmd.deviceFingerprint();
        cmd = cmd.withFeeAmount(fee);

        // Capture fee values for use in PaymentResponse
        BigDecimal[] capturedFee = {fee};
        Long[] capturedFeeRuleId = {feeRuleIdVal};

        // Persist risk score, device fingerprint, and pre-computed fee (lock covers writes only)
        transactionTemplate.execute(status -> {
            Transaction locked = transactionRepository.findByTransactionIdForUpdate(tx.getTransactionId()).orElseThrow();
            locked.setRiskScore(fraud.riskScore());
            locked.setDeviceFingerprint(deviceFingerprint);
            locked.setFeeAmount(fee);
            if (feeRuleIdVal != null) {
                locked.setFeeRuleId(feeRuleIdVal);
            }
            return null;
        });

        // Step 5: Select port
        MobileMoneyPort port = resolvePort(provider);

        // Step 6: Dispatch OUTSIDE any @Transactional — holding connection during HTTP is Pitfall 1
        long providerStart = System.currentTimeMillis();
        try {
            ProviderResult result;
            try {
                result = port.initiateMerchantPayment(cmd);
            } finally {
                metricsService.recordProviderLatency(provider.name(),
                        System.currentTimeMillis() - providerStart);
            }

            // Transition directly to PROCESSING — intermediate AUTH_PENDING/AUTHORIZED steps
            // have no operational meaning for the synchronous provider call path.
            transactionTemplate.execute(status -> {
                Transaction locked = transactionRepository
                        .findByTransactionIdForUpdate(tx.getTransactionId())
                        .orElseThrow(() -> new IllegalStateException("Transaction not found: " + tx.getTransactionId()));
                locked.applyTransition(TransactionStatus.PROCESSING);
                return null;
            });
            log.info("Transaction state changed",
                kv("operation", "transaction_state_change"),
                kv("transactionId", tx.getTransactionId()),
                kv("fromState", TransactionStatus.INITIATED.name()),
                kv("toState", TransactionStatus.PROCESSING.name()),
                kv("actor", "ORCHESTRATOR"));
            String processingMetadata = result.providerRef() != null
                    ? "\"" + result.providerRef() + "\""
                    : null;
            eventLogService.append(
                    tx.getTransactionId(),
                    tx.getTraceId(),
                    tx.getExternalReference(),
                    TransactionEventType.PROVIDER_PROCESSING,
                    TransactionStatus.INITIATED,
                    TransactionStatus.PROCESSING,
                    "ORCHESTRATOR",
                    processingMetadata
            );

            PaymentResponse response = PaymentResponse.accepted(tx.getTransactionId(), "PROCESSING", result.providerRef(),
                    capturedFee[0], capturedFeeRuleId[0]);
            idempotencyService.store(tenantId, request.idempotencyKey(), 202, JsonUtil.toJson(response));
            metricsService.recordSuccess(provider.name());
            log.info("Payment initiated",
                kv("operation", "initiate_payment"),
                kv("tenantId", TenantContext.get()),
                kv("transactionId", tx.getTransactionId()),
                kv("provider", provider.name()),
                kv("msisdn", msisdnLast4(request.msisdn())),
                kv("durationMs", System.currentTimeMillis() - start),
                kv("status", "SUCCESS"));
            return response;

        } catch (CallNotPermittedException e) {
            log.error("Payment initiation failed",
                kv("operation", "initiate_payment"),
                kv("tenantId", TenantContext.get()),
                kv("transactionId", tx.getTransactionId()),
                kv("provider", provider.name()),
                kv("msisdn", msisdnLast4(request.msisdn())),
                kv("durationMs", System.currentTimeMillis() - start),
                kv("status", "FAILED"),
                kv("errorCode", OrchestratorError.PROVIDER_UNAVAILABLE.getErrorCode()));
            applyFailed(tx, TransactionStatus.INITIATED, OrchestratorError.PROVIDER_UNAVAILABLE, "Circuit open");
            return PaymentResponse.failed(tx.getTransactionId(),
                    OrchestratorError.PROVIDER_UNAVAILABLE.getErrorCode(),
                    "Provider temporarily unavailable");

        } catch (SubscriberInactiveException | MtnAccountInactiveException e) {
            log.error("Payment initiation failed",
                kv("operation", "initiate_payment"),
                kv("tenantId", TenantContext.get()),
                kv("transactionId", tx.getTransactionId()),
                kv("provider", provider.name()),
                kv("msisdn", msisdnLast4(request.msisdn())),
                kv("durationMs", System.currentTimeMillis() - start),
                kv("status", "FAILED"),
                kv("errorCode", OrchestratorError.SUBSCRIBER_INACTIVE.getErrorCode()));
            applyFailed(tx, TransactionStatus.INITIATED, OrchestratorError.SUBSCRIBER_INACTIVE, e.getMessage());
            return PaymentResponse.failed(tx.getTransactionId(),
                    OrchestratorError.SUBSCRIBER_INACTIVE.getErrorCode(),
                    e.getMessage());

        } catch (HttpClientException e) {
            log.error("Payment initiation failed",
                kv("operation", "initiate_payment"),
                kv("tenantId", TenantContext.get()),
                kv("transactionId", tx.getTransactionId()),
                kv("provider", provider.name()),
                kv("msisdn", msisdnLast4(request.msisdn())),
                kv("durationMs", System.currentTimeMillis() - start),
                kv("status", "FAILED"),
                kv("errorCode", OrchestratorError.PROVIDER_ERROR.getErrorCode()));
            applyFailed(tx, TransactionStatus.INITIATED, OrchestratorError.PROVIDER_ERROR, e.getMessage());
            return PaymentResponse.failed(tx.getTransactionId(),
                    OrchestratorError.PROVIDER_ERROR.getErrorCode(),
                    "Provider error: " + e.getHttpStatusCode());

        } catch (Exception e) {
            log.error("Payment initiation failed",
                kv("operation", "initiate_payment"),
                kv("tenantId", TenantContext.get()),
                kv("transactionId", tx.getTransactionId()),
                kv("provider", provider.name()),
                kv("msisdn", msisdnLast4(request.msisdn())),
                kv("durationMs", System.currentTimeMillis() - start),
                kv("status", "FAILED"),
                kv("errorCode", OrchestratorError.PROVIDER_ERROR.getErrorCode()),
                e);
            applyFailed(tx, TransactionStatus.INITIATED, OrchestratorError.PROVIDER_ERROR, e.getMessage());
            return PaymentResponse.failed(tx.getTransactionId(),
                    OrchestratorError.PROVIDER_ERROR.getErrorCode(),
                    "Internal error");
        }
    }

    // ---- private helpers ----

    private static String msisdnLast4(String msisdn) {
        if (msisdn == null || msisdn.length() < 4) return "****";
        return msisdn.substring(msisdn.length() - 4);
    }

    /**
     * Lookup payment status by internal transaction ID.
     */
    public Optional<PaymentResponse> getStatus(Long tenantId, String transactionId) {
        return transactionRepository.findByTransactionId(transactionId)
                .filter(tx -> tx.getTenantId().equals(tenantId))
                .map(this::mapToResponse);
    }

    /**
     * Lookup payment status by merchant-supplied external reference.
     */
    public Optional<PaymentResponse> getStatusByReference(Long tenantId, String externalReference) {
        return transactionRepository.findByTenantIdAndExternalReference(tenantId, externalReference)
                .map(this::mapToResponse);
    }

    private PaymentResponse mapToResponse(Transaction tx) {
        String providerRef = tx.getProvider() == MobilePaymentProvider.ORANGE ? tx.getPayToken() : tx.getProviderRef();
        return new PaymentResponse(
                tx.getTransactionId(),
                tx.getTxStatus().name(),
                providerRef,
                null,
                null,
                tx.getFeeAmount() != null ? tx.getFeeAmount() : BigDecimal.ZERO,
                tx.getFeeRuleId()
        );
    }

    private MobileMoneyPort resolvePort(MobilePaymentProvider provider) {
        return switch (provider) {
            case ORANGE -> orangePort;
            case MTN -> mtnPort;
            case NEXTTEL -> throw new UnsupportedOperationException("NEXTTEL provider not yet supported");
        };
    }

    /**
     * Apply FAILED transition and append a PROVIDER_FAILED event log entry.
     * Uses TransactionTemplate to ensure each failure is committed in its own transaction.
     *
     * @param tx       the transaction to fail
     * @param from     the current status before FAILED (used in event log)
     * @param error    the orchestrator error code
     * @param metadata error detail recorded in the event log
     */
    private void applyFailed(Transaction tx, TransactionStatus from, OrchestratorError error, String metadata) {
        try {
            transactionTemplate.execute(status -> {
                Transaction locked = transactionRepository
                        .findByTransactionIdForUpdate(tx.getTransactionId())
                        .orElseThrow(() -> new IllegalStateException("Transaction not found: " + tx.getTransactionId()));
                locked.applyTransition(TransactionStatus.FAILED);
                log.info("Transaction state changed",
                    kv("operation", "transaction_state_change"),
                    kv("transactionId", tx.getTransactionId()),
                    kv("fromState", from.name()),
                    kv("toState", TransactionStatus.FAILED.name()),
                    kv("actor", "ORCHESTRATOR"));
                return null;
            });
            // Wrap error name in JSON quotes — metadata column is jsonb; bare strings are invalid JSON
            String metadataJson = "\"" + error.name() + "\"";
            eventLogService.append(
                    tx.getTransactionId(),
                    tx.getTraceId(),
                    tx.getExternalReference(),
                    TransactionEventType.PROVIDER_FAILED,
                    from,
                    TransactionStatus.FAILED,
                    "ORCHESTRATOR",
                    metadataJson
            );
            if (error == OrchestratorError.FRAUD_BLOCKED) {
                metricsService.recordFraudBlocked();
            } else {
                metricsService.recordFailed(null);
            }
        } catch (Exception ex) {
            log.error("Failed to apply FAILED transition",
                kv("operation", "transaction_state_change"),
                kv("transactionId", tx.getTransactionId()),
                kv("status", "ERROR"),
                ex);
        }
    }
}
