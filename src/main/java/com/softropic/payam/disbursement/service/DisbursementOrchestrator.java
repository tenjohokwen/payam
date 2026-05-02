package com.softropic.payam.disbursement.service;

import com.softropic.payam.common.client.exception.HttpClientException;
import com.softropic.payam.common.payment.MobileMoneyPort;
import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.common.payment.PaymentCommand;
import com.softropic.payam.common.payment.ProviderResult;
import com.softropic.payam.common.payment.SubscriberStatus;
import com.softropic.payam.common.util.JsonUtil;
import com.softropic.payam.disbursement.contract.DisbursementOrchestratorError;
import com.softropic.payam.disbursement.contract.DisbursementRequest;
import com.softropic.payam.disbursement.contract.DisbursementResponse;
import com.softropic.payam.disbursement.contract.DisbursementStatus;
import com.softropic.payam.disbursement.contract.exception.DailyLimitExceededException;
import com.softropic.payam.disbursement.contract.exception.VelocityExceededException;
import com.softropic.payam.disbursement.repo.Disbursement;
import com.softropic.payam.disbursement.repo.DisbursementRepository;
import com.softropic.payam.fraud.contract.FraudDecision;
import com.softropic.payam.mtn.service.MtnMoMoPort;
import com.softropic.payam.orange.service.OrangeMoneyPort;
import com.softropic.payam.payment.contract.exception.UnknownMsisdnPrefixException;
import com.softropic.payam.payment.service.MsisdnRouter;
import com.softropic.payam.transaction.contract.CachedResponse;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Disbursement orchestration — mirrors PaymentOrchestrator pattern. NOT @Transactional;
 * uses TransactionTemplate.execute() for each discrete write so the provider HTTP call
 * runs outside any DB transaction (Pitfall 1 in 51-RESEARCH.md).
 *
 * <p>Sequence (initiate):
 * <ol>
 *   <li>Idempotency check (dsb namespace) — replay or reserve</li>
 *   <li>MSISDN routing</li>
 *   <li>Velocity check (tenant minute, tenant hour, msisdn day)</li>
 *   <li>Fraud check</li>
 *   <li>Fee evaluation</li>
 *   <li>Wallet balance reserve (PESSIMISTIC_WRITE inside TransactionTemplate)</li>
 *   <li>Create Disbursement row (INITIATED or PENDING_CONFIRMATION based on amount)</li>
 *   <li>If PENDING_CONFIRMATION → return; else continue:</li>
 *   <li>validateSubscriber → if inactive: release + transitionToFailed → return</li>
 *   <li>Dispatch to provider port (initiateDisbursement)</li>
 *   <li>Transition to PROCESSING</li>
 *   <li>Store idempotency response</li>
 * </ol>
 *
 * <p>SEC-04 step-up: amount &gt; 500,000 XAF returns PENDING_CONFIRMATION. The wallet IS reserved
 * at this point (prevents concurrent disbursements from consuming the funds while awaiting
 * confirmation). Plan 05's Quartz job ages PENDING_CONFIRMATION rows out to EXPIRED after
 * 15 minutes (and does NOT release — per BAL-03).
 *
 * <p><strong>Failure paths and wallet release semantics:</strong>
 * <ul>
 *   <li>Pre-reservation failures (idempotency, MSISDN, velocity, fraud) — wallet is NEVER touched</li>
 *   <li>Post-reservation failures (subscriber inactive, provider error) — wallet IS released via
 *       {@link WalletBalanceService#release} AND disbursement transitions to FAILED (BAL-02)</li>
 *   <li>EXPIRED state (Plan 05 expiry job) — wallet is NOT released (BAL-03)</li>
 * </ul>
 */
@Service
public class DisbursementOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DisbursementOrchestrator.class);

    /** SEC-04: amounts strictly greater than this XAF threshold require explicit confirmation. */
    static final BigDecimal STEP_UP_THRESHOLD = BigDecimal.valueOf(500_000);

    private final DisbursementIdempotencyService idempotencyService;
    private final MsisdnRouter msisdnRouter;
    private final DisbursementVelocityService velocityService;
    private final DisbursementFraudEvaluationService fraudService;
    private final DisbursementService disbursementService;
    private final DisbursementRepository disbursementRepository;
    private final MtnMoMoPort mtnPort;
    private final OrangeMoneyPort orangePort;
    private final TransactionTemplate transactionTemplate;

    public DisbursementOrchestrator(DisbursementIdempotencyService idempotencyService,
                                    MsisdnRouter msisdnRouter,
                                    DisbursementVelocityService velocityService,
                                    DisbursementFraudEvaluationService fraudService,
                                    DisbursementService disbursementService,
                                    DisbursementRepository disbursementRepository,
                                    MtnMoMoPort mtnPort,
                                    OrangeMoneyPort orangePort,
                                    TransactionTemplate transactionTemplate) {
        this.idempotencyService = idempotencyService;
        this.msisdnRouter = msisdnRouter;
        this.velocityService = velocityService;
        this.fraudService = fraudService;
        this.disbursementService = disbursementService;
        this.disbursementRepository = disbursementRepository;
        this.mtnPort = mtnPort;
        this.orangePort = orangePort;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Initiate a disbursement. NOT @Transactional — uses TransactionTemplate per discrete write.
     *
     * @param tenantId the tenant initiating the disbursement
     * @param request  validated disbursement request
     * @return DisbursementResponse — always non-null; check {@code errorCode} for failure
     */
    public DisbursementResponse initiate(Long tenantId, DisbursementRequest request) {

        // ── Step 1: Idempotency check ────────────────────────────────────────────────
        Optional<CachedResponse> cached = idempotencyService.checkAndReserve(tenantId, request.idempotencyKey());
        if (cached.isPresent()) {
            CachedResponse cr = cached.get();
            if ("RESERVED".equals(cr.responseBody())) {
                return DisbursementResponse.failed(null,
                        DisbursementOrchestratorError.DISBURSEMENT_ALREADY_PROCESSING.getErrorCode(),
                        "Disbursement already in progress");
            }
            return JsonUtil.toObject(cr.responseBody(), DisbursementResponse.class);
        }

        // ── Step 2: MSISDN routing ───────────────────────────────────────────────────
        MobilePaymentProvider provider;
        try {
            provider = msisdnRouter.resolve(request.recipientMsisdn());
        } catch (UnknownMsisdnPrefixException e) {
            return DisbursementResponse.failed(null,
                    DisbursementOrchestratorError.UNKNOWN_MSISDN_PREFIX.getErrorCode(), e.getMessage());
        }

        // ── Step 3: Velocity check (BEFORE balance reservation) ─────────────────────
        try {
            velocityService.checkTenantVelocity(tenantId);
            velocityService.checkMsisdnDailyLimit(tenantId, request.recipientMsisdn());
        } catch (VelocityExceededException e) {
            return DisbursementResponse.failed(null,
                    DisbursementOrchestratorError.VELOCITY_EXCEEDED.getErrorCode(), e.getMessage());
        } catch (DailyLimitExceededException e) {
            return DisbursementResponse.failed(null,
                    DisbursementOrchestratorError.DAILY_LIMIT_EXCEEDED.getErrorCode(), e.getMessage());
        }

        // ── Step 4: Fraud check (BEFORE balance reservation) ────────────────────────
        FraudDecision fraud = fraudService.evaluate(tenantId, request.recipientMsisdn(), request.amount());
        if (fraud.blocked()) {
            log.warn("Disbursement blocked by fraud evaluation",
                    kv("operation", "dsb_fraud_block"),
                    kv("tenantId", tenantId),
                    kv("score", fraud.riskScore()),
                    kv("reason", fraud.reason()));
            return DisbursementResponse.failed(null,
                    DisbursementOrchestratorError.FRAUD_BLOCK.getErrorCode(),
                    "Disbursement blocked: " + fraud.reason());
        }

        // ── Step 5: Fee evaluation — disbursements carry no fee (FEE-01) ─────────────
        BigDecimal fee = BigDecimal.ZERO;
        BigDecimal totalAmount = request.amount();

        // ── Step 6: Determine flow — step-up gate (SEC-04) ──────────────────────────
        boolean stepUp = request.amount().compareTo(STEP_UP_THRESHOLD) > 0;
        DisbursementStatus initialStatus = stepUp
                ? DisbursementStatus.PENDING_CONFIRMATION
                : DisbursementStatus.INITIATED;

        // ── Step 7: Create Disbursement row ─────────────────────────────────────────
        Disbursement dsb = disbursementService.create(tenantId, provider, request, initialStatus);
        String disbursementId = dsb.getDisbursementId();

        if (stepUp) {
            DisbursementResponse pendingResponse = DisbursementResponse.accepted(
                    disbursementId, "PENDING_CONFIRMATION", null,
                    request.recipientMsisdn(), request.amount(), fee, request.currency(),
                    request.reference(), provider.name());
            idempotencyService.store(tenantId, request.idempotencyKey(), 202, JsonUtil.toJson(pendingResponse));
            return pendingResponse;
        }

        // ── Steps 9-11: Continue with provider dispatch ──────────────────────────────
        return dispatchToProvider(tenantId, request, provider, dsb, fee, totalAmount);
    }

    /**
     * Confirm a PENDING_CONFIRMATION disbursement (SEC-04).
     *
     * <p>Loads the disbursement record for the given tenant, asserts it is in
     * PENDING_CONFIRMATION state, then runs the validate-subscriber + provider-dispatch +
     * transition-to-PROCESSING tail of {@link #initiate}.
     *
     * @param tenantId      tenant owning the disbursement
     * @param disbursementId the disbursement UUID to confirm
     * @return DisbursementResponse — PROCESSING on success; INVALID_STATE if wrong state
     */
    public DisbursementResponse confirm(Long tenantId, String disbursementId) {
        Optional<Disbursement> dsbOpt = disbursementRepository
                .findByTenantIdAndDisbursementId(tenantId, disbursementId);
        if (dsbOpt.isEmpty()) {
            return DisbursementResponse.failed(disbursementId,
                    DisbursementOrchestratorError.INVALID_STATE.getErrorCode(),
                    "Disbursement not found or not owned by tenant");
        }
        Disbursement dsb = dsbOpt.get();
        if (dsb.getDisbursementStatus() != DisbursementStatus.PENDING_CONFIRMATION) {
            return DisbursementResponse.failed(disbursementId,
                    DisbursementOrchestratorError.INVALID_STATE.getErrorCode(),
                    "Cannot confirm disbursement in status " + dsb.getDisbursementStatus());
        }

        // Reconstruct pseudo-request from stored disbursement data for the dispatch tail
        // FEE-01: disbursements carry no fee
        BigDecimal fee = BigDecimal.ZERO;
        BigDecimal totalAmount = dsb.getAmount();

        DisbursementRequest pseudoRequest = new DisbursementRequest(
                dsb.getRecipientMsisdn(), dsb.getAmount(), dsb.getCurrency(),
                dsb.getReference(), dsb.getDescription(), dsb.getMetadata(),
                dsb.getIdempotencyKey()
        );
        return dispatchToProvider(tenantId, pseudoRequest, dsb.getProvider(), dsb, fee, totalAmount);
    }

    // ── Private helpers ─────────────────────────────────────────────────────────────

    /**
     * Shared provider dispatch tail used by both {@link #initiate} (non-step-up path) and
     * {@link #confirm}. Steps 9 (validate subscriber), 10 (call provider), 11 (transition).
     */
    private DisbursementResponse dispatchToProvider(Long tenantId,
                                                     DisbursementRequest request,
                                                     MobilePaymentProvider provider,
                                                     Disbursement dsb,
                                                     BigDecimal fee,
                                                     BigDecimal totalAmount) {
        MobileMoneyPort port = resolvePort(provider);
        String disbursementId = dsb.getDisbursementId();

        // ── Step 9: Validate recipient (PROV-03) ────────────────────────────────────
        SubscriberStatus subscriber;
        try {
            subscriber = port.validateSubscriber(request.recipientMsisdn());
        } catch (Exception e) {
            log.warn("Subscriber validation threw — treating as inactive",
                    kv("operation", "dsb_validate_subscriber"),
                    kv("disbursementId", disbursementId), e);
            subscriber = new SubscriberStatus(false, request.recipientMsisdn(), "ERROR");
        }
        if (!subscriber.active()) {
            releaseAndFail(tenantId, totalAmount, disbursementId);
            return DisbursementResponse.failed(disbursementId,
                    DisbursementOrchestratorError.RECIPIENT_NOT_FOUND.getErrorCode(),
                    "Recipient inactive on " + provider.name());
        }

        // ── Step 10: Build PaymentCommand and call provider (OUTSIDE any DB transaction) ─
        PaymentCommand cmd = new PaymentCommand(
                disbursementId,           // transactionId — disbursementId is the correlation key
                disbursementId,           // traceId
                tenantId,
                request.recipientMsisdn(),
                request.amount(),
                request.currency(),
                request.reference(),
                request.idempotencyKey(),
                provider,
                null, null, null,         // clientIp, userAgent, deviceFingerprint — n/a for server-initiated
                request.description(),
                fee != null ? fee : BigDecimal.ZERO
        );

        try {
            ProviderResult result = port.initiateDisbursement(cmd);

            // ── Step 11: Transition to PROCESSING (under PESSIMISTIC_WRITE) ─────────
            transactionTemplate.execute(status -> {
                Disbursement locked = disbursementRepository.findByDisbursementIdForUpdate(disbursementId)
                        .orElseThrow(() -> new IllegalStateException("Disbursement vanished: " + disbursementId));
                locked.applyTransition(DisbursementStatus.PROCESSING);
                if (result.providerRef() != null) {
                    locked.setProviderRef(result.providerRef());
                }
                return null;
            });

            DisbursementResponse response = DisbursementResponse.accepted(
                    disbursementId, "PROCESSING", result.providerRef(),
                    request.recipientMsisdn(), request.amount(), fee, request.currency(),
                    request.reference(), provider.name());
            idempotencyService.store(tenantId, request.idempotencyKey(), 202, JsonUtil.toJson(response));
            log.info("Disbursement dispatched to provider",
                    kv("operation", "dsb_dispatch"),
                    kv("disbursementId", disbursementId),
                    kv("provider", provider.name()),
                    kv("status", "PROCESSING"));
            return response;

        } catch (CallNotPermittedException e) {
            releaseAndFail(tenantId, totalAmount, disbursementId);
            return DisbursementResponse.failed(disbursementId,
                    DisbursementOrchestratorError.PROVIDER_UNAVAILABLE.getErrorCode(),
                    "Provider temporarily unavailable");
        } catch (HttpClientException e) {
            releaseAndFail(tenantId, totalAmount, disbursementId);
            return DisbursementResponse.failed(disbursementId,
                    DisbursementOrchestratorError.PROVIDER_ERROR.getErrorCode(),
                    "Provider error: " + e.getHttpStatusCode());
        } catch (Exception e) {
            log.error("Provider dispatch threw",
                    kv("operation", "dsb_provider_dispatch"),
                    kv("disbursementId", disbursementId), e);
            releaseAndFail(tenantId, totalAmount, disbursementId);
            return DisbursementResponse.failed(disbursementId,
                    DisbursementOrchestratorError.PROVIDER_ERROR.getErrorCode(),
                    "Provider error: " + e.getMessage());
        }
    }

    /**
     * Transition disbursement to FAILED. No wallet release — wallet model retired in v11 (SCHEMA-03).
     * Errors are swallowed + logged — the original failure response is returned regardless.
     */
    private void releaseAndFail(Long tenantId, BigDecimal totalAmount, String disbursementId) {
        try {
            transactionTemplate.execute(st -> {
                disbursementService.transitionToFailed(disbursementId);
                return null;
            });
        } catch (Exception ex) {
            log.error("Failed to transition disbursement to FAILED",
                    kv("operation", "dsb_transition_failed"),
                    kv("disbursementId", disbursementId), ex);
        }
    }

    private MobileMoneyPort resolvePort(MobilePaymentProvider provider) {
        return switch (provider) {
            case MTN -> mtnPort;
            case ORANGE -> orangePort;
            case NEXTTEL -> throw new UnsupportedOperationException("NEXTTEL not supported for disbursements");
        };
    }
}
