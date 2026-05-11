package com.softropic.payam.payment.disbursement.contract;

import java.math.BigDecimal;

/**
 * Response DTO returned to clients from POST /v1/disbursements.
 *
 * <p>On success (202 Accepted): {@code errorCode} and {@code errorMessage} are null.
 * {@code fee} is always non-null — {@link BigDecimal#ZERO} when no fee rule matched.
 *
 * <p>On failure: {@code status} is "FAILED", {@code providerRef} and several other
 * fields are null. {@code fee} is still non-null ({@link BigDecimal#ZERO}).
 *
 * <p>Use the static factories {@link #accepted} and {@link #failed} rather than the
 * canonical record constructor to ensure consistent field population.
 */
public record DisbursementResponse(

        /** Our internal disbursement identifier. May be null on very early failures. */
        String disbursementId,

        /**
         * Current lifecycle status (e.g. "PROCESSING", "PENDING_CONFIRMATION", "FAILED").
         * Maps to {@link DisbursementStatus}.
         */
        String status,

        /**
         * Provider reference token (e.g. payToken for Orange, referenceId for MTN).
         * Null until the provider call returns successfully.
         */
        String providerRef,

        /** Recipient MSISDN as provided in the request. */
        String recipientMsisdn,

        /** Disbursement principal amount. */
        BigDecimal amount,

        /** Applied fee. Always non-null; {@link BigDecimal#ZERO} when no fee rule matched. */
        BigDecimal fee,

        /** ISO-4217 currency code (e.g. "XAF"). */
        String currency,

        /** Tenant-side external reference echoed from the request. */
        String reference,

        /**
         * String form of {@link com.softropic.payam.common.payment.MobilePaymentProvider}
         * (e.g. "MTN", "ORANGE"). Null on early failures before routing.
         */
        String provider,

        /**
         * Error code from {@link DisbursementOrchestratorError}. Null on success.
         */
        String errorCode,

        /**
         * Human-readable error description. Null on success.
         */
        String errorMessage

) {

    /**
     * Successful dispatch — provider accepted the disbursement request.
     *
     * @param disbursementId  our internal disbursement identifier
     * @param status          current status (typically "PROCESSING" or "PENDING_CONFIRMATION")
     * @param providerRef     provider reference token; null until provider responds
     * @param recipientMsisdn recipient MSISDN as provided
     * @param amount          principal amount
     * @param fee             applied fee; set to {@link BigDecimal#ZERO} when null
     * @param currency        ISO-4217 currency code
     * @param reference       tenant-side external reference
     * @param provider        string form of {@link com.softropic.payam.common.payment.MobilePaymentProvider}
     */
    public static DisbursementResponse accepted(
            String disbursementId,
            String status,
            String providerRef,
            String recipientMsisdn,
            BigDecimal amount,
            BigDecimal fee,
            String currency,
            String reference,
            String provider) {
        return new DisbursementResponse(
                disbursementId, status, providerRef, recipientMsisdn,
                amount, fee != null ? fee : BigDecimal.ZERO,
                currency, reference, provider,
                null, null);
    }

    /**
     * Failed dispatch — provider rejected or an error occurred before/during dispatch.
     *
     * @param disbursementId our internal disbursement identifier; may be null for pre-initiation failures
     * @param errorCode      {@link DisbursementOrchestratorError} code name
     * @param errorMessage   human-readable error description
     */
    public static DisbursementResponse failed(
            String disbursementId,
            String errorCode,
            String errorMessage) {
        return new DisbursementResponse(
                disbursementId, "FAILED", null, null,
                null, BigDecimal.ZERO,
                null, null, null,
                errorCode, errorMessage);
    }
}
