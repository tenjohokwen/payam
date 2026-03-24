package com.softropic.payam.webhook.contract;

/**
 * Payload sent to tenant's configured webhookUrl after a transaction reaches a terminal state.
 *
 * The HMAC-SHA256 signature of the JSON-serialized payload is included in the
 * X-Payam-Signature header as "sha256=<hex>" by WebhookDeliveryService.
 *
 * externalReference echoes back the tenant's own reference from payment creation.
 */
public record OutboundWebhookPayload(
    String transactionId,
    String status,            // "SUCCESS" or "FAILED"
    String eventType,         // e.g. "PROVIDER_SUCCESS"
    String timestamp,         // ISO-8601 UTC
    String externalReference  // echoed back to tenant — their own reference from payment creation
) {}
