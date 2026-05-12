package com.softropic.payam.payment.webhook.service;

import com.softropic.payam.payment.core.contract.MobilePaymentProvider;
import com.softropic.payam.payment.core.contract.ProviderResult;
import com.softropic.payam.payment.provider.mtn.service.MtnStatusMapper;
import com.softropic.payam.payment.provider.orange.service.OrangeStatusMapper;
import com.softropic.payam.payment.ledger.contract.LedgerPosting;
import com.softropic.payam.payment.ledger.contract.TransactionEventType;
import com.softropic.payam.payment.ledger.contract.TransactionStatus;
import com.softropic.payam.payment.ledger.repo.Transaction;
import com.softropic.payam.payment.ledger.repo.TransactionRepository;
import com.softropic.payam.payment.ledger.service.EventLogService;
import com.softropic.payam.payment.ledger.service.LedgerService;
import com.softropic.payam.payment.webhook.contract.WebhookEnqueueRequestedEvent;
import com.softropic.payam.payment.webhook.contract.WebhookReceivedEvent;

import static net.logstash.logback.argument.StructuredArguments.kv;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
    private final LedgerService ledgerService;
    private final ApplicationEventPublisher eventPublisher;

    public WebhookTransitionService(TransactionRepository transactionRepository,
                                    EventLogService eventLogService,
                                    WebhookDeliveryService webhookDeliveryService,
                                    LedgerService ledgerService,
                                    ApplicationEventPublisher eventPublisher) {
        this.transactionRepository = transactionRepository;
        this.eventLogService = eventLogService;
        this.webhookDeliveryService = webhookDeliveryService;
        this.ledgerService = ledgerService;
        this.eventPublisher = eventPublisher;
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

        // Map raw provider status to target TransactionStatus using provider-specific mapper
        TransactionStatus target = resolveTarget(event.provider(), result.rawStatus());

        // Guard: only apply the transition if the current state allows it.
        // This covers two races:
        //   1. Already-terminal (SUCCESS/FAILED/REVERSED have no allowed transitions) — Pitfall 2
        //   2. Still-INITIATED: orchestrator hasn't reached applyTransition(PROCESSING) yet because
        //      the provider callback arrived in the gap after persistPayToken/persistProviderRef
        //      committed but before the orchestrator's PROCESSING transition committed.
        //      In this case the webhook double-check must skip; the poller will apply the
        //      terminal state once the orchestrator has moved the row to PROCESSING.
        if (!tx.getTxStatus().allowedTransitions().contains(target)) {
            log.info("Double-check: skipping — transition not valid in current state",
                kv("operation", "webhook_double_check"),
                kv("transactionId", event.transactionId()),
                kv("currentState", tx.getTxStatus()),
                kv("targetState", target),
                kv("status", "TRANSITION_SKIPPED"));
            return;
        }

        tx.applyTransition(target);
        transactionRepository.save(tx);

        if (target == TransactionStatus.SUCCESS) {
            ledgerService.postEntry(
                tx.getTransactionId(),
                tx.getTenantId(),
                LedgerPosting.collection(tx.getAmount(), tx.getCurrency())
            );
        }

        TransactionEventType eventType = target == TransactionStatus.SUCCESS
            ? TransactionEventType.PROVIDER_SUCCESS
            : TransactionEventType.PROVIDER_FAILED;

        String metadata = target == TransactionStatus.SUCCESS
            ? null
            : "\"" + result.rawStatus() + "\""; // JSON-quoted for jsonb column (05-02 decision)

        log.info("Transaction state changed",
            kv("operation", "transaction_state_change"),
            kv("transactionId", tx.getTransactionId()),
            kv("fromState", TransactionStatus.PROCESSING.name()),
            kv("toState", target.name()),
            kv("actor", "WEBHOOK_DOUBLE_CHECK"));

        eventLogService.append(
            tx.getTransactionId(), tx.getTraceId(), tx.getExternalReference(),
            eventType,
            TransactionStatus.PROCESSING, target,
            "WEBHOOK_DOUBLE_CHECK", metadata
        );

        // Publish AFTER_COMMIT event so enqueue happens only after THIS transaction commits (WEBHOOK-02).
        // If this REQUIRES_NEW transaction rolls back, no listener fires and no delivery log row is
        // created. The listener (WebhookDeliveryService.onEnqueueRequested) swallows any enqueue
        // exception so delivery-pipeline failures cannot propagate back and affect the state transition.
        eventPublisher.publishEvent(new WebhookEnqueueRequestedEvent(
            tx.getTransactionId(),
            tx.getTenantId(),
            eventType.name(),
            target,
            tx.getExternalReference(),
            tx.getFeeAmount()
        ));
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
