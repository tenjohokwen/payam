package com.softropic.payam.payment.service;

import com.softropic.payam.common.client.exception.HttpClientException;
import com.softropic.payam.common.payment.MobileMoneyPort;
import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.common.payment.PaymentCommand;
import com.softropic.payam.common.payment.ProviderResult;
import com.softropic.payam.common.util.JsonUtil;
import com.softropic.payam.mtn.contract.exception.MtnAccountInactiveException;
import com.softropic.payam.mtn.service.MtnMoMoPort;
import com.softropic.payam.orange.contract.exception.SubscriberInactiveException;
import com.softropic.payam.orange.service.OrangeMoneyPort;
import com.softropic.payam.payment.contract.OrchestratorError;
import com.softropic.payam.payment.contract.PaymentRequest;
import com.softropic.payam.payment.contract.PaymentResponse;
import com.softropic.payam.payment.contract.exception.UnknownMsisdnPrefixException;
import com.softropic.payam.transaction.contract.CachedResponse;
import com.softropic.payam.transaction.contract.TransactionEventType;
import com.softropic.payam.transaction.contract.TransactionStatus;
import com.softropic.payam.transaction.repo.Transaction;
import com.softropic.payam.transaction.repo.TransactionRepository;
import com.softropic.payam.transaction.service.EventLogService;
import com.softropic.payam.transaction.service.IdempotencyService;
import com.softropic.payam.transaction.service.TransactionService;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

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

    public PaymentOrchestrator(OrangeMoneyPort orangePort,
                               MtnMoMoPort mtnPort,
                               MsisdnRouter msisdnRouter,
                               TransactionService transactionService,
                               IdempotencyService idempotencyService,
                               TransactionRepository transactionRepository,
                               EventLogService eventLogService,
                               TransactionTemplate transactionTemplate) {
        this.orangePort = orangePort;
        this.mtnPort = mtnPort;
        this.msisdnRouter = msisdnRouter;
        this.transactionService = transactionService;
        this.idempotencyService = idempotencyService;
        this.transactionRepository = transactionRepository;
        this.eventLogService = eventLogService;
        this.transactionTemplate = transactionTemplate;
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

        // Step 1: Resolve MSISDN to provider
        MobilePaymentProvider provider;
        try {
            provider = msisdnRouter.resolve(request.msisdn());
        } catch (UnknownMsisdnPrefixException e) {
            log.warn("Unknown MSISDN prefix: msisdn={}", request.msisdn());
            return PaymentResponse.failed(null,
                    OrchestratorError.UNKNOWN_MSISDN_PREFIX.getErrorCode(),
                    e.getMessage());
        }

        // Step 2: Idempotency check
        Optional<CachedResponse> cached = idempotencyService.checkAndReserve(tenantId, request.idempotencyKey());
        if (cached.isPresent()) {
            CachedResponse cr = cached.get();
            if ("RESERVED".equals(cr.responseBody())) {
                log.warn("Payment already in progress: tenantId={}, idempotencyKey={}", tenantId, request.idempotencyKey());
                return PaymentResponse.failed(null,
                        OrchestratorError.PAYMENT_ALREADY_PROCESSING.getErrorCode(),
                        "Payment already in progress");
            }
            // Deserialize cached successful response and replay it
            log.info("Replaying cached idempotency response: tenantId={}, idempotencyKey={}", tenantId, request.idempotencyKey());
            return JsonUtil.toObject(cr.responseBody(), PaymentResponse.class);
        }

        // Step 3: Create INITIATED transaction row (TransactionService.initiate is @Transactional and commits)
        Transaction tx = transactionService.initiate(tenantId, provider, request.amount(), request.currency(), request.externalReference());

        // Step 4: Build PaymentCommand
        PaymentCommand cmd = new PaymentCommand(
                tx.getTransactionId(),
                tx.getTraceId(),
                tenantId,
                request.msisdn(),
                request.amount(),
                request.currency(),
                request.externalReference(),
                request.idempotencyKey(),
                provider
        );

        // Step 5: Select port
        MobileMoneyPort port = resolvePort(provider);

        // Step 6: Dispatch OUTSIDE any @Transactional — holding connection during HTTP is Pitfall 1
        try {
            ProviderResult result = port.initiateMerchantPayment(cmd);

            // Apply state transitions: INITIATED -> AUTH_PENDING -> AUTHORIZED -> PROCESSING
            // Three applyTransition calls required — direct INITIATED->PROCESSING not allowed by state machine
            transactionTemplate.execute(status -> {
                Transaction locked = transactionRepository
                        .findByTransactionIdForUpdate(tx.getTransactionId())
                        .orElseThrow(() -> new IllegalStateException("Transaction not found: " + tx.getTransactionId()));
                locked.applyTransition(TransactionStatus.AUTH_PENDING);
                locked.applyTransition(TransactionStatus.AUTHORIZED);
                locked.applyTransition(TransactionStatus.PROCESSING);
                return null;
            });

            PaymentResponse response = PaymentResponse.accepted(tx.getTransactionId(), "PROCESSING", result.providerRef());
            idempotencyService.store(tenantId, request.idempotencyKey(), 202, JsonUtil.toJson(response));
            log.info("Payment dispatched successfully: transactionId={}, provider={}", tx.getTransactionId(), provider);
            return response;

        } catch (CallNotPermittedException e) {
            log.warn("Circuit open for provider={}: transactionId={}", provider, tx.getTransactionId());
            applyFailed(tx, TransactionStatus.INITIATED, OrchestratorError.PROVIDER_UNAVAILABLE, "Circuit open");
            return PaymentResponse.failed(tx.getTransactionId(),
                    OrchestratorError.PROVIDER_UNAVAILABLE.getErrorCode(),
                    "Provider temporarily unavailable");

        } catch (SubscriberInactiveException | MtnAccountInactiveException e) {
            log.warn("Subscriber inactive: transactionId={}, msisdn={}", tx.getTransactionId(), request.msisdn());
            applyFailed(tx, TransactionStatus.INITIATED, OrchestratorError.SUBSCRIBER_INACTIVE, e.getMessage());
            return PaymentResponse.failed(tx.getTransactionId(),
                    OrchestratorError.SUBSCRIBER_INACTIVE.getErrorCode(),
                    e.getMessage());

        } catch (HttpClientException e) {
            log.warn("Provider HTTP error: transactionId={}, httpStatus={}", tx.getTransactionId(), e.getHttpStatusCode());
            applyFailed(tx, TransactionStatus.INITIATED, OrchestratorError.PROVIDER_ERROR, e.getMessage());
            return PaymentResponse.failed(tx.getTransactionId(),
                    OrchestratorError.PROVIDER_ERROR.getErrorCode(),
                    "Provider error: " + e.getHttpStatusCode());

        } catch (Exception e) {
            log.error("Unexpected error during payment dispatch: transactionId={}", tx.getTransactionId(), e);
            applyFailed(tx, TransactionStatus.INITIATED, OrchestratorError.PROVIDER_ERROR, e.getMessage());
            return PaymentResponse.failed(tx.getTransactionId(),
                    OrchestratorError.PROVIDER_ERROR.getErrorCode(),
                    "Internal error");
        }
    }

    // ---- private helpers ----

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
        } catch (Exception ex) {
            log.error("Failed to apply FAILED transition: transactionId={}", tx.getTransactionId(), ex);
        }
    }
}
