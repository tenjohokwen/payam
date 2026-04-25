package com.softropic.payam.disbursement.service;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.common.payment.ProviderResult;
import com.softropic.payam.disbursement.contract.DisbursementStatus;
import com.softropic.payam.disbursement.repo.Disbursement;
import com.softropic.payam.disbursement.repo.DisbursementRepository;
import com.softropic.payam.mtn.service.MtnStatusMapper;
import com.softropic.payam.orange.service.OrangeStatusMapper;
import com.softropic.payam.transaction.contract.TransactionStatus;
import com.softropic.payam.webhook.contract.WebhookEnqueueRequestedEvent;
import com.softropic.payam.webhook.contract.WebhookReceivedEvent;

import static net.logstash.logback.argument.StructuredArguments.kv;

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
 * <p>Atomicity contract: the disbursement state transition AND the wallet release (when
 * target=FAILED) commit in the SAME REQUIRES_NEW transaction. A crash between the two
 * cannot leave the row FAILED with the wallet still reserved (Pitfall 3 in 52-RESEARCH).
 *
 * <p>Idempotent replay guard: if the disbursement is already in a terminal state
 * (allowedTransitions empty), the method silently returns without side effects.
 * This protects against the same callback being delivered twice and the second arrival
 * sneaking past the Redis dedup (e.g. dedup key TTL expired).
 */
@Service
public class DisbursementCallbackTransitionService {

    private static final Logger log =
        LoggerFactory.getLogger(DisbursementCallbackTransitionService.class);

    private final DisbursementRepository disbursementRepository;
    private final WalletBalanceService walletBalanceService;
    private final ApplicationEventPublisher eventPublisher;

    public DisbursementCallbackTransitionService(
            DisbursementRepository disbursementRepository,
            WalletBalanceService walletBalanceService,
            ApplicationEventPublisher eventPublisher) {
        this.disbursementRepository = disbursementRepository;
        this.walletBalanceService = walletBalanceService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Apply the confirmed final disbursement state transition. Called by
     * WebhookDoubleCheckHandler after the provider double-check returns a non-pending result.
     *
     * <p>REQUIRES_NEW: the @TransactionalEventListener(AFTER_COMMIT) fires when no transaction
     * is active. REQUIRES_NEW creates a fresh independent transaction so the
     * findByDisbursementIdForUpdate row lock + applyTransition + walletBalanceService.release
     * + event publish all commit atomically.
     *
     * <p>Atomic scope (BAL-02): on FAILED, walletBalanceService.release runs in this same
     * transaction. WalletBalanceService.release is @Transactional with default propagation
     * (REQUIRED) — Spring joins the existing transaction.
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

        // BAL-02: release ONLY for FAILED. SUCCESS keeps the reservation as committed spend.
        // EXPIRED is reserved for the expiry job (BAL-03) — never reached via this method
        // because allowedTransitions does not include EXPIRED on a callback-driven path.
        if (target == DisbursementStatus.FAILED) {
            walletBalanceService.release(locked.getTenantId(), locked.getReservedAmount());
            log.info("Disbursement transition committed",
                kv("operation", "dsb_callback_transition"),
                kv("disbursementId", event.transactionId()),
                kv("toState", target.name()),
                kv("walletReleased", locked.getReservedAmount()),
                kv("actor", "DSB_CALLBACK"));
        } else {
            log.info("Disbursement transition committed",
                kv("operation", "dsb_callback_transition"),
                kv("disbursementId", event.transactionId()),
                kv("toState", target.name()),
                kv("actor", "DSB_CALLBACK"));
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
