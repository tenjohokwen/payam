package com.softropic.payam.orange.contract.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PayResponse {
    @JsonProperty("payToken") private String payToken;
    @JsonProperty("status") private String status;
    @JsonProperty("txnid") private String txnid;
    @JsonProperty("message") private String message;
    @JsonProperty("inittxnmessage") private String initTxnMessage;
    @JsonProperty("inittxnstatus") private String initTxnStatus;

    public String getPayToken() { return payToken; }
    public String getStatus() { return status; }
    public String getTxnid() { return txnid; }
    public String getMessage() { return message; }
    public String getInitTxnMessage() { return initTxnMessage; }
    public String getInitTxnStatus() { return initTxnStatus; }
}
