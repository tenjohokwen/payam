package com.softropic.payam.payment.webhook.contract;

import com.softropic.payam.payment.ledger.contract.TransactionStatus;

import java.math.BigDecimal;

/**
 * Internal Spring application event published by WebhookTransitionService after a final state
 * transition is committed. Consumed by WebhookDeliveryService via
 * @TransactionalEventListener(phase = AFTER_COMMIT) so enqueue happens only once the triggering
 * transaction is durable (WEBHOOK-02).
 *
 * Carries all fields needed by WebhookDeliveryService.enqueue(...) so the listener does not
 * need to reload the Transaction entity.
 */
public record WebhookEnqueueRequestedEvent(
    String transactionId,
    Long tenantId,
    String eventType,
    TransactionStatus status,
    String externalReference,
    BigDecimal feeAmount
) {}
