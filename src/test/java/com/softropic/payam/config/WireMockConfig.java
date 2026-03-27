package com.softropic.payam.config;

public final class WireMockConfig {

    public static final String MTN_BASE_URL_PROPERTY    = "mtn.collection-base-url";
    public static final String ORANGE_BASE_URL_PROPERTY = "orange.base-url";
    public static final String ORANGE_PAY_URL_PROPERTY  = "orange.pay-url";

    public static final String MTN_TOKEN_RESPONSE =
        "{\"access_token\":\"mtn-test-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}";

    public static final String ORANGE_TOKEN_RESPONSE =
        "{\"access_token\":\"orange-test-token\",\"token_type\":\"Bearer\",\"expires_in\":7200}";

    private WireMockConfig() {}
}
