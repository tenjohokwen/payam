package com.softropic.payam.common.payment;

import java.math.BigDecimal;

public record PaymentCommand(
    String transactionId,
    String traceId,
    Long tenantId,
    String msisdn,            // E.164 format — adapter must strip country code as needed
    BigDecimal amount,
    String currency,
    String externalReference,
    String idempotencyKey,
    MobilePaymentProvider provider,
    String clientIp,          // client IP from RequestMetadata (nullable — null if unavailable)
    String userAgent,         // User-Agent header (nullable)
    String deviceFingerprint, // X-Device-Fingerprint header value (nullable)
    String description,       // human-readable description for Orange /mp/pay; nullable — ignored by other adapters
    BigDecimal feeAmount      // CASHOUT-01: nullable fee populated by PaymentOrchestrator from FeeEvaluationService; null means "no fee configured"
) {
    /**
     * Backward-compat 13-arg constructor — all pre-CASHOUT-01 call sites delegate here
     * so they compile without modification. New feeAmount component defaults to null
     * (meaning "no fee configured"); downstream ports must treat null as BigDecimal.ZERO.
     */
    public PaymentCommand(String transactionId, String traceId, Long tenantId,
                          String msisdn, BigDecimal amount, String currency,
                          String externalReference, String idempotencyKey,
                          MobilePaymentProvider provider, String clientIp,
                          String userAgent, String deviceFingerprint, String description) {
        this(transactionId, traceId, tenantId, msisdn, amount, currency,
             externalReference, idempotencyKey, provider, clientIp,
             userAgent, deviceFingerprint, description, null);
    }

    /**
     * Returns a new PaymentCommand with feeAmount replaced; all other fields preserved.
     * Used by PaymentOrchestrator to carry the FeeEvaluationService-computed fee to ports.
     */
    public PaymentCommand withFeeAmount(BigDecimal feeAmount) {
        return new PaymentCommand(
            this.transactionId, this.traceId, this.tenantId,
            this.msisdn, this.amount, this.currency,
            this.externalReference, this.idempotencyKey,
            this.provider, this.clientIp,
            this.userAgent, this.deviceFingerprint, this.description,
            feeAmount
        );
    }
}
