package com.softropic.payam.orange.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class OrangeWebhookPayload {
    @JsonProperty("payToken") private String payToken;
    @JsonProperty("notif_token") private String notifToken;
    @JsonProperty("status") private String status;
    @JsonProperty("txnid") private String txnid;
    @JsonProperty("msisdn") private String msisdn;
    @JsonProperty("amount") private String amount;
    @JsonProperty("createtime") private String createtime;  // WAT, no offset — parse via OrangeTimeUtil

    public String getPayToken() { return payToken; }
    public String getNotifToken() { return notifToken; }
    public String getStatus() { return status; }
    public String getTxnid() { return txnid; }
    public String getMsisdn() { return msisdn; }
    public String getAmount() { return amount; }
    public String getCreatetime() { return createtime; }
}
