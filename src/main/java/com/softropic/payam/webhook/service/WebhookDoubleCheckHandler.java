package com.softropic.payam.webhook.service;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.common.payment.ProviderResult;
import com.softropic.payam.mtn.service.MtnMoMoPort;
import com.softropic.payam.orange.service.OrangeMoneyPort;
import com.softropic.payam.webhook.contract.WebhookReceivedEvent;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class WebhookDoubleCheckHandler {

    private static final Logger log = LoggerFactory.getLogger(WebhookDoubleCheckHandler.class);

    private final OrangeMoneyPort orangeMoneyPort;
    private final MtnMoMoPort mtnMoMoPort;
    private final WebhookTransitionService webhookTransitionService;

    public WebhookDoubleCheckHandler(OrangeMoneyPort orangeMoneyPort,
                                     MtnMoMoPort mtnMoMoPort,
                                     WebhookTransitionService webhookTransitionService) {
        this.orangeMoneyPort = orangeMoneyPort;
        this.mtnMoMoPort = mtnMoMoPort;
        this.webhookTransitionService = webhookTransitionService;
    }

    /**
     * Double-check handler — fires after the dedup transaction commits.
     *
     * NOT @Transactional here — provider HTTP call must not hold DB connection (P1.1/Pitfall 7).
     * Uses @TransactionalEventListener(AFTER_COMMIT) to ensure the dedup record is committed
     * before calling the provider status API.
     *
     * The transactional state transition is delegated to WebhookTransitionService (separate bean)
     * to ensure @Transactional is effective via Spring AOP proxy — self-invocation bypasses proxy.
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
        // Delegated to WebhookTransitionService (separate bean) so @Transactional is effective
        webhookTransitionService.applyFinalTransition(event, result);
    }
}
