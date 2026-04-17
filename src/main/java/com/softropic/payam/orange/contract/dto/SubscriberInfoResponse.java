package com.softropic.payam.orange.contract.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SubscriberInfoResponse {

    @JsonProperty("data")    private Data data;
    @JsonProperty("message") private String message;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Data {
        @JsonProperty("firstname") private String firstname;
        @JsonProperty("lastname")  private String lastname;

        public String getFirstname() { return firstname; }
        public String getLastname()  { return lastname; }
    }

    public Data getData()      { return data; }
    public String getMessage() { return message; }

    /** Returns true when the response contains a non-null firstname — per Orange API guide Use Case 2 */
    public boolean isActive() {
        return data != null && data.getFirstname() != null;
    }
}
