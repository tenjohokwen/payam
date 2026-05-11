package com.softropic.payam.payment.disbursement.contract.exception;

/**
 * Thrown by DisbursementVelocityService.checkMsisdnDailyLimit when the per-(tenant,MSISDN)
 * 24-hour bucket (>10) is exhausted. Maps to HTTP 422 DAILY_LIMIT_EXCEEDED in the
 * orchestrator. SEC-02.
 */
public class DailyLimitExceededException extends RuntimeException {
    public DailyLimitExceededException(String message) { super(message); }
}
