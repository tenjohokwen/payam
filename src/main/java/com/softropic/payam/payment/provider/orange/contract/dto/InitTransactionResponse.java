package com.softropic.payam.payment.provider.orange.contract.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response DTO for POST /mp/init.
 *
 * Orange response shape:
 * <pre>
 * {
 *   "data": { "payToken": "MP-XXXXXXXXXXXXXXXX" },
 *   "message": "OK"
 * }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class InitTransactionResponse {

    @JsonProperty("data")    private Data data;
    @JsonProperty("message") private String message;

    /** Returns the payToken from the nested data object, or null if data is absent. */
    public String getPayToken() {
        return data != null ? data.payToken : null;
    }

    public String getMessage() { return message; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Data {
        @JsonProperty("payToken") private String payToken;
    }
}
