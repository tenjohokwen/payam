package com.softropic.payam.platform.security.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AppEndpointsTest {

    @Test
    void publicEndpointsIncludesMtnDisbursement() {
        assertThat(AppEndpoints.PUBLIC_ENDPOINTS).contains("/v1/callbacks/mtn/disbursement/*");
    }

    @Test
    void publicEndpointsIncludesOrangeDisbursement() {
        assertThat(AppEndpoints.PUBLIC_ENDPOINTS).contains("/v1/callbacks/orange/disbursement");
    }

    @Test
    void publicEndpointsStillIncludesCollectionPaths() {
        assertThat(AppEndpoints.PUBLIC_ENDPOINTS).contains("/v1/callbacks/mtn");
        assertThat(AppEndpoints.PUBLIC_ENDPOINTS).contains("/v1/callbacks/orange");
    }
}
