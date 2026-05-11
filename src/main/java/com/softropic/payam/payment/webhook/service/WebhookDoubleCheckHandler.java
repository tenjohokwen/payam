package com.softropic.payam.payment.webhook.service;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.common.payment.ProviderResult;
import com.softropic.payam.payment.disbursement.service.DisbursementCallbackTransitionService;
import com.softropic.payam.payment.provider.mtn.service.MtnMoMoPort;
import com.softropic.payam.payment.provider.orange.service.OrangeMoneyPort;
import com.softropic.payam.payment.webhook.contract.WebhookReceivedEvent;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

import static net.logstash.logback.argument.StructuredArguments.kv;
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
    private final DisbursementCallbackTransitionService disbursementCallbackTransitionService;

    public WebhookDoubleCheckHandler(OrangeMoneyPort orangeMoneyPort,
                                     MtnMoMoPort mtnMoMoPort,
                                     WebhookTransitionService webhookTransitionService,
                                     DisbursementCallbackTransitionService disbursementCallbackTransitionService) {
        this.orangeMoneyPort = orangeMoneyPort;
        this.mtnMoMoPort = mtnMoMoPort;
        this.webhookTransitionService = webhookTransitionService;
        this.disbursementCallbackTransitionService = disbursementCallbackTransitionService;
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
        log.info("Double-check triggered",
            kv("operation", "webhook_double_check"),
            kv("transactionId", event.transactionId()),
            kv("provider", event.provider()));

        // Step 1: Call provider status API — NEVER skip (P1.4, Pitfall 1)
        ProviderResult result;
        try {
            if (event.provider() == MobilePaymentProvider.ORANGE) {
                result = event.flow() == com.softropic.payam.payment.ledger.contract.LedgerFlow.COLLECTION
                    ? orangeMoneyPort.getCollectionTransactionStatus(event.providerRef())
                    : orangeMoneyPort.getDisbursementTransactionStatus(event.providerRef());
            } else {
                result = event.flow() == com.softropic.payam.payment.ledger.contract.LedgerFlow.COLLECTION
                    ? mtnMoMoPort.getCollectionTransactionStatus(event.providerRef())
                    : mtnMoMoPort.getDisbursementTransactionStatus(event.providerRef());
            }
        } catch (CallNotPermittedException e) {
            // Circuit open — leave transaction in PROCESSING; poller will retry
            log.warn("Double-check: circuit open",
                kv("operation", "webhook_double_check"),
                kv("transactionId", event.transactionId()),
                kv("status", "CIRCUIT_OPEN"));
            return;
        } catch (Exception e) {
            log.error("Double-check failed",
                kv("operation", "webhook_double_check"),
                kv("transactionId", event.transactionId()),
                kv("status", "ERROR"),
                e);
            return; // Poller safety net will pick this up
        }

        // Step 2: If still PROCESSING, do nothing — poller handles it
        if (result.pending()) {
            log.info("Double-check: still PROCESSING",
                kv("operation", "webhook_double_check"),
                kv("transactionId", event.transactionId()),
                kv("status", "STILL_PROCESSING"));
            return;
        }

        // Step 3: Final status confirmed — apply state transition with PESSIMISTIC_WRITE lock.
        // Route by flow: DISBURSEMENT goes to DisbursementCallbackTransitionService (BAL-02
        // wallet release on FAILED happens inside that bean's REQUIRES_NEW transaction);
        // COLLECTION continues to WebhookTransitionService (ledger posting on SUCCESS happens
        // inside its REQUIRES_NEW transaction). Both beans are separate from this handler so
        // @Transactional is honoured by Spring AOP (self-invocation would bypass the proxy).
        if (event.flow() == com.softropic.payam.payment.ledger.contract.LedgerFlow.DISBURSEMENT) {
            disbursementCallbackTransitionService.applyDisbursementTransition(event, result);
        } else {
            webhookTransitionService.applyFinalTransition(event, result);
        }
    }
}
