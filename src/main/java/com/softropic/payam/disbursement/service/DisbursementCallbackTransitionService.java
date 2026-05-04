package com.softropic.payam.disbursement.service;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.common.payment.ProviderResult;
import com.softropic.payam.disbursement.contract.DisbursementRefStatus;
import com.softropic.payam.disbursement.contract.DisbursementStatus;
import com.softropic.payam.disbursement.contract.event.InsufficientFundsAlertEvent;
import com.softropic.payam.disbursement.repo.Disbursement;
import com.softropic.payam.disbursement.repo.DisbursementRepository;
import com.softropic.payam.mtn.service.MtnStatusMapper;
import com.softropic.payam.orange.service.OrangeStatusMapper;
import com.softropic.payam.transaction.contract.TransactionStatus;
import com.softropic.payam.webhook.contract.WebhookEnqueueRequestedEvent;
import com.softropic.payam.webhook.contract.WebhookReceivedEvent;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * REQUIRES_NEW state transition for disbursement double-check (SEC-05 + SEC-06).
 * Mirror of WebhookTransitionService for collection flow.
 *
 * <p>Why a separate bean: @Transactional self-invocation bypasses Spring AOP proxy.
 * WebhookDoubleCheckHandler injects this bean and calls applyDisbursementTransition.
 *
 * <p>Atomicity contract: the disbursement state transition, the claim transition (SUCCESS or
 * FAILED), and the IF alert (FAILED + Insufficient Funds signal) all commit in the SAME
 * REQUIRES_NEW transaction (Pitfall 4 in 56-RESEARCH).
 *
 * <p>Idempotent replay guard: if the disbursement is already in a terminal state
 * (allowedTransitions empty), the method silently returns without side effects.
 * This protects against the same callback being delivered twice and the second arrival
 * sneaking past the Redis dedup (e.g. dedup key TTL expired).
 *
 * <p>CLAIM-05 invariant: this service produces SUCCESS or FAILED targets only. EXPIRED is
 * produced by Quartz expiry jobs (DisbursementExpiryJob, admin-approval expiry) and is
 * NOT handled here. The PROCESSING→EXPIRED path MUST NOT release claims — that invariant
 * is upheld by design (EXPIRED is never a reachable target via this method).
 */
@Service
public class DisbursementCallbackTransitionService {

    private static final Logger log =
        LoggerFactory.getLogger(DisbursementCallbackTransitionService.class);

    private final DisbursementRepository disbursementRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final DisbursementClaimTransitionService claimTransitionService;
    private final InsufficientFundsDetector insufficientFundsDetector;

    public DisbursementCallbackTransitionService(
            DisbursementRepository disbursementRepository,
            ApplicationEventPublisher eventPublisher,
            DisbursementClaimTransitionService claimTransitionService,
            InsufficientFundsDetector insufficientFundsDetector) {
        this.disbursementRepository = disbursementRepository;
        this.eventPublisher = eventPublisher;
        this.claimTransitionService = claimTransitionService;
        this.insufficientFundsDetector = insufficientFundsDetector;
    }

    /**
     * Apply the confirmed final disbursement state transition. Called by
     * WebhookDoubleCheckHandler after the provider double-check returns a non-pending result.
     *
     * <p>REQUIRES_NEW: the @TransactionalEventListener(AFTER_COMMIT) fires when no transaction
     * is active. REQUIRES_NEW creates a fresh independent transaction so the
     * findByDisbursementIdForUpdate row lock + applyTransition + claimTransitionService call
     * + event publish all commit atomically.
     *
     * <p>Claim transition (CLAIM-02/CLAIM-03): immediately after the disbursement row transitions,
     * all PENDING claim rows are bulk-updated to CLAIMED (SUCCESS) or RELEASED (FAILED) within
     * this same REQUIRES_NEW transaction. {@code claimTransitionService} is @Transactional(REQUIRED)
     * so Spring joins the existing transaction — no nested transaction boundary.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyDisbursementTransition(WebhookReceivedEvent event, ProviderResult result) {
        Disbursement locked = disbursementRepository
            .findByDisbursementIdForUpdate(event.transactionId())
            .orElseThrow(() -> new IllegalStateException(
                "Disbursement not found during double-check: " + event.transactionId()));

        DisbursementStatus target = resolveTarget(event.provider(), result.rawStatus());

        // Idempotent replay guard: terminal states have empty allowedTransitions.
        if (!locked.getDisbursementStatus().allowedTransitions().contains(target)) {
            log.info("Disbursement double-check: skipping — transition not valid in current state",
                kv("operation", "dsb_double_check"),
                kv("disbursementId", event.transactionId()),
                kv("currentState", locked.getDisbursementStatus()),
                kv("targetState", target),
                kv("status", "TRANSITION_SKIPPED"));
            return;
        }

        locked.applyTransition(target);
        disbursementRepository.save(locked);

        // Wallet model retired in v11 (SCHEMA-03) — no wallet release on FAILED callback.
        log.info("Disbursement transition committed",
            kv("operation", "dsb_callback_transition"),
            kv("disbursementId", event.transactionId()),
            kv("toState", target.name()),
            kv("actor", "DSB_CALLBACK"));

        // CLAIM-02 / CLAIM-03: bulk-transition all claims atomically in this same
        // REQUIRES_NEW transaction. transitionClaims is @Transactional(REQUIRED) so
        // Spring joins this transaction (Pitfall 4 in 56-RESEARCH.md).
        // CLAIM-05 invariant: this method only emits SUCCESS or FAILED targets — never
        // EXPIRED. PROCESSING→EXPIRED via DisbursementStatusPollerJob handles its own
        // path and MUST NOT release claims. EXPIRED is therefore not handled here by design.
        if (target == DisbursementStatus.SUCCESS) {
            claimTransitionService.transitionClaims(
                    locked.getId(),
                    DisbursementRefStatus.PENDING,
                    DisbursementRefStatus.CLAIMED);
        } else if (target == DisbursementStatus.FAILED) {
            claimTransitionService.transitionClaims(
                    locked.getId(),
                    DisbursementRefStatus.PENDING,
                    DisbursementRefStatus.RELEASED);
            // ALERT-01: detect Insufficient Funds error and publish high-priority alert
            if (insufficientFundsDetector.isInsufficientFunds(result)) {
                log.warn("Provider Insufficient Funds detected on disbursement",
                        kv("operation", "dsb_insufficient_funds"),
                        kv("disbursementId", event.transactionId()),
                        kv("provider", event.provider()),
                        kv("errorCode", result.errorCode()));
                eventPublisher.publishEvent(new InsufficientFundsAlertEvent(
                        event.transactionId(),
                        locked.getTenantId(),
                        event.provider(),
                        locked.getAmount(),
                        locked.getCurrency(),
                        result.errorCode(),
                        result.errorMessage(),
                        Instant.now()));
            }
        }

        // SEC-06: outbound webhook event types preserve the existing OutboundWebhookPayload
        // contains-check fallback ("DISBURSEMENT_COMPLETED" contains nothing matching "SUCCESS",
        // BUT Plan 01 added the explicit TransactionStatus field via OutboundWebhookPayload.of —
        // the explicit status below is authoritative).
        String eventType = target == DisbursementStatus.SUCCESS
            ? "DISBURSEMENT_COMPLETED" : "DISBURSEMENT_FAILED";
        TransactionStatus wireStatus = target == DisbursementStatus.SUCCESS
            ? TransactionStatus.SUCCESS : TransactionStatus.FAILED;

        eventPublisher.publishEvent(new WebhookEnqueueRequestedEvent(
            locked.getDisbursementId(),
            locked.getTenantId(),
            eventType,
            wireStatus,
            locked.getReference(),
            null    // feeAmount: callback path does not carry this; payload uses ZERO fallback
        ));
    }

    /**
     * Resolve the target DisbursementStatus from the provider raw status string.
     * Conservative default: if the mapper returns PROCESSING (i.e. provider still pending),
     * treat as FAILED. The double-check handler already guards via result.pending() — so
     * reaching this with a PROCESSING result is a defensive fallback only.
     */
    private DisbursementStatus resolveTarget(MobilePaymentProvider provider, String rawStatus) {
        TransactionStatus mapped = provider == MobilePaymentProvider.ORANGE
            ? OrangeStatusMapper.toInternal(rawStatus)
            : MtnStatusMapper.toInternal(rawStatus);
        return switch (mapped) {
            case SUCCESS -> DisbursementStatus.SUCCESS;
            default      -> DisbursementStatus.FAILED;   // INITIATED/PROCESSING/FAILED/REVERSED → FAILED
        };
    }
}
