package com.softropic.payam.payment.disbursement.contract.exception;

/**
 * Thrown by TransactionClaimValidationService when request.amount differs from
 * SUM(transaction.amount - feeAmount) across all supplied transactions, compared
 * via BigDecimal.compareTo (scale-insensitive). Maps to HTTP 422 AMOUNT_MISMATCH
 * in the orchestrator (TXN-04).
 */
public class AmountMismatchException extends RuntimeException {
    public AmountMismatchException(String message) { super(message); }
}
