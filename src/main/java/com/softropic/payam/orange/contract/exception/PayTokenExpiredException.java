package com.softropic.payam.orange.contract.exception;

public class PayTokenExpiredException extends RuntimeException {
    private final String transactionId;

    public PayTokenExpiredException(String transactionId) {
        super("payToken expired for transaction: " + transactionId);
        this.transactionId = transactionId;
    }

    public String getTransactionId() { return transactionId; }
}
