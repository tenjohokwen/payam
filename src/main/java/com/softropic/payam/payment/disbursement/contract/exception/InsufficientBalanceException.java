package com.softropic.payam.payment.disbursement.contract.exception;

/**
 * Thrown by WalletBalanceService.checkAndReserve when the merchant wallet balance
 * is less than the requested amount. Maps to HTTP 422 INSUFFICIENT_BALANCE in the
 * orchestrator layer (Phase 51). BAL-01.
 */
public class InsufficientBalanceException extends RuntimeException {
    public InsufficientBalanceException(String message) { super(message); }
    public InsufficientBalanceException(String message, Throwable cause) { super(message, cause); }
}
