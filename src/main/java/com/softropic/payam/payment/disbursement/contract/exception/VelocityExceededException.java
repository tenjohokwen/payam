package com.softropic.payam.payment.disbursement.contract.exception;

/**
 * Thrown by DisbursementVelocityService.checkTenantVelocity when the per-tenant
 * minute (>20) or hour (>200) bucket is exhausted. Maps to HTTP 429 in the orchestrator.
 * SEC-02.
 */
public class VelocityExceededException extends RuntimeException {
    public VelocityExceededException(String message) { super(message); }
}
