package com.softropic.payam.payment.provider.mtn.contract.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RequestToPayStatusResponse {

    private String status;                   // PENDING, SUCCESSFUL, FAILED
    private String financialTransactionId;   // null on FAILED
    private String externalId;               // echoed back
    private String reason;                   // error reason on FAILED

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getFinancialTransactionId() { return financialTransactionId; }
    public void setFinancialTransactionId(String financialTransactionId) { this.financialTransactionId = financialTransactionId; }

    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
