package com.softropic.payam.disbursement.contract.exception;

/**
 * Thrown by TransactionClaimValidationService when one or more supplied
 * transactionIds fail validation: empty/oversized list, not owned by the requesting
 * tenant, or has txStatus != SUCCESS / flow != COLLECTION. Maps to HTTP 422
 * INVALID_TRANSACTION in the orchestrator (TXN-01, TXN-02).
 */
public class InvalidTransactionException extends RuntimeException {
    public InvalidTransactionException(String message) { super(message); }
}
