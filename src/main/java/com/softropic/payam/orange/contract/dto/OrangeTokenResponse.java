package com.softropic.payam.orange.contract.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class OrangeTokenResponse {
    @JsonProperty("access_token") private String accessToken;
    @JsonProperty("token_type") private String tokenType;
    @JsonProperty("expires_in") private Integer expiresIn;

    public String getAccessToken() { return accessToken; }
    public String getTokenType() { return tokenType; }
    public Integer getExpiresIn() { return expiresIn; }
}
