package com.softropic.payam.e2e.verify;

import com.github.tomakehurst.wiremock.WireMockServer;

import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

/**
 * VERIF-06: Asserts WireMock call counts for MTN and Orange provider endpoints.
 *
 * Common URL paths:
 *   MTN initiation:  /collection/v1_0/requesttopay
 *   Orange initiation: /web/pay
 */
public class ProviderCallVerifier {

    private final WireMockServer mtnServer;
    private final WireMockServer orangeServer;

    public ProviderCallVerifier(WireMockServer mtnServer, WireMockServer orangeServer) {
        this.mtnServer = mtnServer;
        this.orangeServer = orangeServer;
    }

    /**
     * Asserts MTN POST call count to the given URL path.
     * Example: assertMtnCallCount("/collection/v1_0/requesttopay", 1)
     */
    public void assertMtnCallCount(String urlPath, int expectedCount) {
        mtnServer.verify(expectedCount, postRequestedFor(urlPathEqualTo(urlPath)));
    }

    /**
     * Asserts MTN GET call count to URL paths matching the given regex pattern.
     * Used for double-check GET calls.
     * Example: assertMtnGetCallCount("/collection/v1_0/requesttopay/.*", 1)
     */
    public void assertMtnGetCallCount(String urlPathPattern, int expectedCount) {
        mtnServer.verify(expectedCount, getRequestedFor(urlPathMatching(urlPathPattern)));
    }

    /**
     * Asserts Orange POST call count to the given URL path.
     * Example: assertOrangeCallCount("/web/pay", 1)
     */
    public void assertOrangeCallCount(String urlPath, int expectedCount) {
        orangeServer.verify(expectedCount, postRequestedFor(urlPathEqualTo(urlPath)));
    }

    /**
     * Asserts that neither MTN nor Orange received any payment initiation calls.
     * Used for fraud-blocked flows.
     */
    public void assertNoProviderCalls() {
        mtnServer.verify(0, postRequestedFor(urlPathEqualTo("/collection/v1_0/requesttopay")));
        orangeServer.verify(0, postRequestedFor(urlPathEqualTo("/web/pay")));
    }
}
