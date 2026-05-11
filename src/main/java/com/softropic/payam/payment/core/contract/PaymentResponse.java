package com.softropic.payam.payment.core.contract;

import java.math.BigDecimal;

/**
 * Response DTO returned to clients from POST /v1/payments.
 *
 * <p>On success (202 Accepted): {@code errorCode} and {@code errorMessage} are null.
 * On failure: {@code providerRef} is null and {@code status} is "FAILED".
 *
 * <p>{@code feeAmount} is always non-null: zero when no fee rule is configured for the tenant.
 * {@code feeRuleId} is null when no fee rule matched.
 *
 * <p>Use the static factories {@link #accepted} and {@link #failed} rather than the
 * canonical record constructor to ensure consistent field population.
 */
public record PaymentResponse(
        String transactionId,
        String status,
        String providerRef,
        String errorCode,
        String errorMessage,
        BigDecimal feeAmount,
        Long feeRuleId
) {

    /**
     * Successful dispatch — provider accepted the request.
     *
     * @param transactionId our internal transaction identifier
     * @param status        current transaction status (typically "PROCESSING")
     * @param providerRef   provider reference token (payToken for Orange, referenceId for MTN)
     * @param feeAmount     applied fee; {@link BigDecimal#ZERO} when no rule matched (never null)
     * @param feeRuleId     fee rule ID that produced the fee; null when no rule matched
     */
    public static PaymentResponse accepted(String transactionId, String status, String providerRef,
                                           BigDecimal feeAmount, Long feeRuleId) {
        return new PaymentResponse(transactionId, status, providerRef, null, null,
                feeAmount != null ? feeAmount : BigDecimal.ZERO, feeRuleId);
    }

    /**
     * Failed dispatch — provider rejected or an error occurred.
     *
     * @param transactionId our internal transaction identifier (may be null if failure before initiation)
     * @param errorCode     {@link OrchestratorError} code name
     * @param errorMessage  human-readable error description
     */
    public static PaymentResponse failed(String transactionId, String errorCode, String errorMessage) {
        return new PaymentResponse(transactionId, "FAILED", null, errorCode, errorMessage,
                BigDecimal.ZERO, null);
    }
}
