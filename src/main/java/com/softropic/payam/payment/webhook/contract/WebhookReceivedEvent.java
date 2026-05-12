package com.softropic.payam.payment.webhook.contract;

import com.softropic.payam.payment.core.contract.MobilePaymentProvider;
import com.softropic.payam.payment.ledger.contract.LedgerFlow;

/**
 * Internal Spring event published after an inbound webhook dedup check passes.
 * Consumed by WebhookDoubleCheckHandler via @TransactionalEventListener(AFTER_COMMIT).
 *
 * transactionId — our transactionId (correlation key for DB lookup + lock)
 * provider      — ORANGE or MTN (determines which port to call for double-check)
 * providerRef   — Orange: payToken; MTN: providerRef (referenceId UUID)
 * traceId       — propagated to EventLogService.append()
 * flow          — COLLECTION or DISBURSEMENT (determines which status method to call)
 */
public record WebhookReceivedEvent(
    String transactionId,
    MobilePaymentProvider provider,
    String providerRef,
    String traceId,
    LedgerFlow flow
) {}
