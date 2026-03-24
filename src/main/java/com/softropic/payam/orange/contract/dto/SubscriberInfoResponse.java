package com.softropic.payam.orange.contract.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SubscriberInfoResponse {
    @JsonProperty("status") private String status;
    @JsonProperty("message") private String message;

    public String getStatus() { return status; }
    public String getMessage() { return message; }

    /** Returns true when Orange status is "ACTIF" (case-insensitive) */
    public boolean isActive() { return "ACTIF".equalsIgnoreCase(status); }
}
