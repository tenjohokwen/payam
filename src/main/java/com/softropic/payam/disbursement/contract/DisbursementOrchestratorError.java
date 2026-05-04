package com.softropic.payam.disbursement.contract;

import com.softropic.payam.common.exception.ErrorCode;

/**
 * Standardized error vocabulary for the disbursement orchestration layer.
 *
 * <p>Each entry maps to a specific failure mode encountered during disbursement
 * initiation or confirmation. Plan 04 ({@code DisbursementResource}) maps these
 * codes to HTTP status codes as follows:
 *
 * <ul>
 *   <li>{@link #INSUFFICIENT_BALANCE}        — HTTP 422 (wallet check failed)</li>
 *   <li>{@link #RECIPIENT_NOT_FOUND}          — HTTP 422 (subscriber inactive or not found)</li>
 *   <li>{@link #FRAUD_BLOCK}                  — HTTP 422 (fraud score exceeds threshold)</li>
 *   <li>{@link #DAILY_LIMIT_EXCEEDED}         — HTTP 422 (&gt;10 disbursements/day to same MSISDN)</li>
 *   <li>{@link #VELOCITY_EXCEEDED}            — HTTP 429 (&gt;20/min or &gt;200/hr per tenant)</li>
 *   <li>{@link #INVALID_STATE}                — HTTP 422 (confirm on non-PENDING_CONFIRMATION)</li>
 *   <li>{@link #UNKNOWN_MSISDN_PREFIX}        — HTTP 422 (unroutable MSISDN)</li>
 *   <li>{@link #PROVIDER_UNAVAILABLE}         — HTTP 503 (Resilience4j circuit open)</li>
 *   <li>{@link #PROVIDER_ERROR}               — HTTP 502 (4xx/5xx from provider)</li>
 *   <li>{@link #DISBURSEMENT_ALREADY_PROCESSING} — HTTP 202 (duplicate Idempotency-Key replay)</li>
 *   <li>{@link #INVALID_TRANSACTION}         — HTTP 422 (TXN-01/TXN-02 ownership/status/flow)</li>
 *   <li>{@link #TRANSACTION_CLAIMED}         — HTTP 422 (TXN-03 active-claim conflict)</li>
 *   <li>{@link #AMOUNT_MISMATCH}             — HTTP 422 (TXN-04 sum mismatch)</li>
 * </ul>
 */
public enum DisbursementOrchestratorError implements ErrorCode {

    /**
     * Merchant wallet balance is insufficient to cover the disbursement amount plus fee.
     * Triggered by {@link com.softropic.payam.disbursement.service.WalletBalanceService#checkAndReserve}.
     * Maps to HTTP 422.
     */
    INSUFFICIENT_BALANCE,

    /**
     * The recipient MSISDN's mobile money account is inactive or not registered with the
     * provider (equivalent to subscriber validation failure). Maps to HTTP 422.
     */
    RECIPIENT_NOT_FOUND,

    /**
     * Disbursement blocked by the fraud engine — velocity threshold exceeded or risk
     * score above the configured threshold (typically 80). Maps to HTTP 422.
     */
    FRAUD_BLOCK,

    /**
     * More than 10 disbursements have already been sent to the same recipient MSISDN
     * within the current calendar day (tenant-scoped). Maps to HTTP 422.
     */
    DAILY_LIMIT_EXCEEDED,

    /**
     * Tenant-level velocity limit exceeded: more than 20 disbursements per minute or
     * more than 200 disbursements per hour. Maps to HTTP 429.
     */
    VELOCITY_EXCEEDED,

    /**
     * A confirm request was received for a disbursement that is not in
     * {@code PENDING_CONFIRMATION} state — typically because it already transitioned to
     * PROCESSING, SUCCESS, or FAILED. Maps to HTTP 422.
     */
    INVALID_STATE,

    /**
     * The recipient MSISDN prefix is not recognized as any supported Cameroonian operator
     * (neither MTN nor Orange). Cannot route the disbursement. Maps to HTTP 422.
     */
    UNKNOWN_MSISDN_PREFIX,

    /**
     * Resilience4j circuit breaker is open for the target provider — provider is
     * temporarily unavailable. Maps to HTTP 503.
     */
    PROVIDER_UNAVAILABLE,

    /**
     * Provider returned a 4xx or 5xx HTTP error during disbursement initiation or status
     * polling. Maps to HTTP 502.
     */
    PROVIDER_ERROR,

    /**
     * A duplicate {@code Idempotency-Key} was submitted while the original disbursement
     * request is still in-flight (RESERVED sentinel). The caller should treat HTTP 202 as
     * an acknowledgment that the original is still processing and retry with polling.
     * Maps to HTTP 202.
     */
    DISBURSEMENT_ALREADY_PROCESSING,

    /**
     * One or more supplied transactionIds failed validation: empty list, max>500,
     * not owned by the requesting tenant, or has txStatus != SUCCESS / flow != COLLECTION.
     * Maps to HTTP 422 (default in {@code DisbursementResource.resolveHttpStatus}).
     * Triggered by {@code TransactionClaimValidationService.validateAndClaim} (TXN-01, TXN-02).
     */
    INVALID_TRANSACTION,

    /**
     * One or more supplied transactionIds already have an active claim
     * ({@code DisbursementTransactionRef.refStatus IN ('PENDING','CLAIMED')}). The
     * partial unique index {@code uq_dtr_txn_active_claim} (V31) enforces this at the
     * DB layer; the orchestrator surfaces it as this code (TXN-03). Maps to HTTP 422.
     */
    TRANSACTION_CLAIMED,

    /**
     * The disbursement {@code request.amount} is not equal to the sum of
     * {@code transaction.amount - feeAmount} across all supplied transactions
     * (BigDecimal.compareTo, scale-insensitive). Maps to HTTP 422 (TXN-04).
     */
    AMOUNT_MISMATCH;

    @Override
    public String getErrorCode() {
        return this.name();
    }
}
