package com.softropic.payam.mtn.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * MTN sends PUT callbacks with this payload. externalId is the correlation key —
 * financialTransactionId is absent on FAILED status.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MtnCallbackPayload {

    @JsonProperty("financialTransactionId")
    private String financialTransactionId; // null on FAILED

    @JsonProperty("externalId")
    private String externalId; // your transactionId

    @JsonProperty("status")
    private String status;

    @JsonProperty("reason")
    private String reason; // present on FAILED only

    public String getFinancialTransactionId() { return financialTransactionId; }
    public void setFinancialTransactionId(String financialTransactionId) { this.financialTransactionId = financialTransactionId; }

    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
