package com.softropic.payam.disbursement.contract.exception;

/**
 * Thrown by TransactionClaimValidationService when one or more supplied
 * transactionIds already have an active claim
 * (DisbursementTransactionRef.refStatus IN ('PENDING','CLAIMED')). The DB partial
 * unique index uq_dtr_txn_active_claim is the authoritative final guard; this
 * exception surfaces the violation at the application layer (TXN-03). Maps to
 * HTTP 422 TRANSACTION_CLAIMED in the orchestrator.
 */
public class TransactionClaimedException extends RuntimeException {
    public TransactionClaimedException(String message) { super(message); }
}
