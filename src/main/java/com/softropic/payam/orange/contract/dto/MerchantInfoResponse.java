package com.softropic.payam.orange.contract.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MerchantInfoResponse {
    @JsonProperty("payToken") private String payToken;
    @JsonProperty("message") private String message;

    public String getPayToken() { return payToken; }
    public String getMessage() { return message; }
}
