package com.softropic.payam.webhook.contract;

import com.softropic.payam.transaction.contract.TransactionStatus;

import java.math.BigDecimal;

/**
 * Payload sent to tenant's configured webhookUrl after a transaction reaches a terminal state.
 *
 * The HMAC-SHA256 signature of the JSON-serialized payload is included in the
 * X-Payam-Signature header as "sha256=<hex>" by WebhookDeliveryService.
 *
 * externalReference echoes back the tenant's own reference from payment creation.
 * feeAmount is the applied fee (BigDecimal); zero when no fee rule matched, never null.
 */
public record OutboundWebhookPayload(
    String transactionId,
    String status,            // "SUCCESS" or "FAILED"
    String eventType,         // e.g. "PROVIDER_SUCCESS"
    String timestamp,         // ISO-8601 UTC
    String externalReference, // echoed back to tenant — their own reference from payment creation
    BigDecimal feeAmount      // applied fee; BigDecimal.ZERO when no rule matched
) {
    /**
     * Build a payload deriving the wire-level status from an authoritative TransactionStatus
     * enum value. SEC-06: collection event types (PROVIDER_SUCCESS/FAILED) and disbursement
     * event types (DISBURSEMENT_COMPLETED/FAILED) BOTH map correctly without string contains-checks.
     *
     * Mapping: SUCCESS -> "SUCCESS"; everything else -> "FAILED".
     */
    public static OutboundWebhookPayload of(String transactionId,
                                             TransactionStatus status,
                                             String eventType,
                                             String timestamp,
                                             String externalReference,
                                             BigDecimal feeAmount) {
        String wire = status == TransactionStatus.SUCCESS ? "SUCCESS" : "FAILED";
        return new OutboundWebhookPayload(
            transactionId, wire, eventType, timestamp, externalReference,
            feeAmount != null ? feeAmount : BigDecimal.ZERO);
    }
}
