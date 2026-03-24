package com.softropic.payam.webhook.service;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.common.payment.ProviderResult;
import com.softropic.payam.mtn.service.MtnMoMoPort;
import com.softropic.payam.mtn.service.MtnStatusMapper;
import com.softropic.payam.orange.service.OrangeMoneyPort;
import com.softropic.payam.orange.service.OrangeStatusMapper;
import com.softropic.payam.transaction.contract.TransactionEventType;
import com.softropic.payam.transaction.contract.TransactionStatus;
import com.softropic.payam.transaction.repo.Transaction;
import com.softropic.payam.transaction.repo.TransactionRepository;
import com.softropic.payam.transaction.service.EventLogService;
import com.softropic.payam.webhook.contract.WebhookReceivedEvent;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class WebhookDoubleCheckHandler {

    private static final Logger log = LoggerFactory.getLogger(WebhookDoubleCheckHandler.class);

    private final OrangeMoneyPort orangeMoneyPort;
    private final MtnMoMoPort mtnMoMoPort;
    private final TransactionRepository transactionRepository;
    private final EventLogService eventLogService;

    public WebhookDoubleCheckHandler(OrangeMoneyPort orangeMoneyPort,
                                     MtnMoMoPort mtnMoMoPort,
                                     TransactionRepository transactionRepository,
                                     EventLogService eventLogService) {
        this.orangeMoneyPort = orangeMoneyPort;
        this.mtnMoMoPort = mtnMoMoPort;
        this.transactionRepository = transactionRepository;
        this.eventLogService = eventLogService;
    }

    /**
     * Double-check handler — fires after the dedup transaction commits.
     *
     * NOT @Transactional here — provider HTTP call must not hold DB connection (P1.1/Pitfall 7).
     * Uses @TransactionalEventListener(AFTER_COMMIT) to ensure the dedup record is visible
     * before calling the provider status API.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleWebhookReceived(WebhookReceivedEvent event) {
        log.info("Double-check triggered: transactionId={}, provider={}",
            event.transactionId(), event.provider());

        // Step 1: Call provider status API — NEVER skip (P1.4, Pitfall 1)
        ProviderResult result;
        try {
            if (event.provider() == MobilePaymentProvider.ORANGE) {
                result = orangeMoneyPort.getTransactionStatus(event.providerRef());
            } else {
                result = mtnMoMoPort.getTransactionStatus(event.providerRef());
            }
        } catch (CallNotPermittedException e) {
            // Circuit open — leave transaction in PROCESSING; poller will retry
            log.warn("Circuit open during double-check for transactionId={} — skipping state transition",
                event.transactionId());
            return;
        } catch (Exception e) {
            log.error("Double-check failed for transactionId={}: {}", event.transactionId(), e.getMessage());
            return; // Poller safety net will pick this up
        }

        // Step 2: If still PROCESSING, do nothing — poller handles it
        if (result.pending()) {
            log.info("Double-check: transactionId={} still PROCESSING — poller will handle",
                event.transactionId());
            return;
        }

        // Step 3: Final status confirmed — apply state transition with PESSIMISTIC_WRITE lock
        applyFinalTransition(event, result);
    }

    /**
     * Apply the confirmed final state transition under PESSIMISTIC_WRITE lock.
     * Separate @Transactional method so the provider HTTP call (above) does not hold a DB connection.
     */
    @Transactional
    protected void applyFinalTransition(WebhookReceivedEvent event, ProviderResult result) {
        Transaction tx = transactionRepository
            .findByTransactionIdForUpdate(event.transactionId())
            .orElseThrow(() -> new IllegalStateException(
                "Transaction not found during double-check: " + event.transactionId()));

        // Guard: already in terminal state (webhook race with poller — Pitfall 2)
        if (tx.getTxStatus() == TransactionStatus.SUCCESS
                || tx.getTxStatus() == TransactionStatus.FAILED) {
            log.info("Double-check: transactionId={} already in terminal state {} — skipping",
                event.transactionId(), tx.getTxStatus());
            return;
        }

        // Map raw provider status to target TransactionStatus
        TransactionStatus target = resolveTarget(event.provider(), result.rawStatus());

        tx.applyTransition(target);
        transactionRepository.save(tx);

        TransactionEventType eventType = target == TransactionStatus.SUCCESS
            ? TransactionEventType.PROVIDER_SUCCESS
            : TransactionEventType.PROVIDER_FAILED;

        String metadata = target == TransactionStatus.SUCCESS
            ? null
            : "\"" + result.rawStatus() + "\""; // JSON-quoted for jsonb column (05-02 decision)

        eventLogService.append(
            tx.getTransactionId(), tx.getTraceId(), tx.getExternalReference(),
            eventType,
            TransactionStatus.PROCESSING, target,
            "WEBHOOK_DOUBLE_CHECK", metadata
        );

        log.info("Double-check: transactionId={} transitioned to {}", event.transactionId(), target);
    }

    /**
     * Resolve target TransactionStatus from provider-specific raw status string.
     * Delegates to the same mapper used by each provider's poller for consistency.
     */
    private TransactionStatus resolveTarget(MobilePaymentProvider provider, String rawStatus) {
        TransactionStatus mapped = provider == MobilePaymentProvider.ORANGE
            ? OrangeStatusMapper.toInternal(rawStatus)
            : MtnStatusMapper.toInternal(rawStatus);
        // If mapper returns PROCESSING (unknown/still-pending), default to FAILED conservatively
        // (this path should not be reached since pending() check above guards against it)
        return mapped == TransactionStatus.PROCESSING ? TransactionStatus.FAILED : mapped;
    }
}
