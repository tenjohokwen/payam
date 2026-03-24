package com.softropic.payam.webhook.service;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.common.payment.ProviderResult;
import com.softropic.payam.mtn.service.MtnStatusMapper;
import com.softropic.payam.orange.service.OrangeStatusMapper;
import com.softropic.payam.transaction.contract.TransactionEventType;
import com.softropic.payam.transaction.contract.TransactionStatus;
import com.softropic.payam.transaction.repo.Transaction;
import com.softropic.payam.transaction.repo.TransactionRepository;
import com.softropic.payam.transaction.service.EventLogService;
import com.softropic.payam.webhook.contract.WebhookReceivedEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles the transactional portion of webhook double-check state transitions.
 *
 * Separated from WebhookDoubleCheckHandler so that @Transactional is honoured
 * by Spring AOP — self-invocation from the same bean would bypass the proxy.
 */
@Service
public class WebhookTransitionService {

    private static final Logger log = LoggerFactory.getLogger(WebhookTransitionService.class);

    private final TransactionRepository transactionRepository;
    private final EventLogService eventLogService;
    private final WebhookDeliveryService webhookDeliveryService;

    public WebhookTransitionService(TransactionRepository transactionRepository,
                                    EventLogService eventLogService,
                                    WebhookDeliveryService webhookDeliveryService) {
        this.transactionRepository = transactionRepository;
        this.eventLogService = eventLogService;
        this.webhookDeliveryService = webhookDeliveryService;
    }

    /**
     * Apply the confirmed final state transition under PESSIMISTIC_WRITE lock (Pitfall 2).
     *
     * Called from WebhookDoubleCheckHandler after the provider HTTP call confirms a terminal status.
     * @Transactional here is effective because this is a separate Spring bean — AOP proxy applies.
     */
    /**
     * REQUIRES_NEW: The @TransactionalEventListener(AFTER_COMMIT) fires after the commit synchronization
     * phase where no transaction is active. REQUIRES_NEW creates a fresh independent transaction.
     * REQUIRED would attempt to join a non-existent transaction and fail with TransactionRequiredException.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyFinalTransition(WebhookReceivedEvent event, ProviderResult result) {
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

        // Map raw provider status to target TransactionStatus using provider-specific mapper
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

        // Enqueue outbound tenant notification — async delivery via WebhookDeliveryJob
        webhookDeliveryService.enqueue(
            tx.getTransactionId(),
            tx.getTenantId(),
            eventType.name(),
            target,
            tx.getExternalReference()
        );
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
        // (this path should not be reached since pending() check in handler guards against it)
        return mapped == TransactionStatus.PROCESSING ? TransactionStatus.FAILED : mapped;
    }
}
