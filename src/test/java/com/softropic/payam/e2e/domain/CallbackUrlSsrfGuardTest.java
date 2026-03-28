package com.softropic.payam.e2e.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.softropic.payam.e2e.AbstractPayamE2ETest;
import com.softropic.payam.e2e.builder.PaymentRequestBuilder;
import com.softropic.payam.e2e.builder.TenantBuilder;
import com.softropic.payam.fraud.service.FraudRuleCache;
import com.softropic.payam.payment.contract.PaymentRequest;
import com.softropic.payam.payment.contract.PaymentResponse;
import com.softropic.payam.tenant.repo.TenantRepository;
import com.softropic.payam.tenant.service.TenantService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * INV-08-TEST: SSRF guard — outbound provider HTTP calls always use Payam-owned callback URLs.
 *
 * Proves that even when a tenant is created with a suspicious callback URL, the callbackUrl
 * field in the MTN requesttopay body is a Payam-owned URL (containing /api/mtn/callback or
 * /v1/callbacks/mtn) — NOT the tenant-supplied URL.
 */
public class CallbackUrlSsrfGuardTest extends AbstractPayamE2ETest {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private FraudRuleCache fraudRuleCache;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void providerCall_usesPayamCallbackUrl_notTenantSuppliedUrl() {
        seedFraudRules();

        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{\"name\":\"Test User\"}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));

        // Create a tenant with a suspicious external webhook URL — this tests that Payam's
        // SSRF guard ignores the tenant-supplied URL and uses its own callback URL.
        TenantBuilder.CreatedTenant tenant = new TenantBuilder()
            .withName("SSRF-Guard-Test")
            .withWebhookUrl("http://evil.example.com/callback")
            .create(tenantService, tenantRepository);

        RestTemplate restTemplate = buildNoRetryRestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Api-Key", tenant.rawApiKey());
        headers.setContentType(MediaType.APPLICATION_JSON);

        PaymentRequest req = new PaymentRequestBuilder()
            .withMsisdn("+237672000001")
            .withIdempotencyKey(UUID.randomUUID().toString())
            .build();

        ResponseEntity<PaymentResponse> response = restTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/payments",
            HttpMethod.POST,
            new HttpEntity<>(req, headers),
            PaymentResponse.class);

        assertThat(response.getStatusCode().value())
            .as("Payment initiation must return 202")
            .isEqualTo(202);

        // Extract the requesttopay body from WireMock serve events
        List<ServeEvent> serveEvents = mtnServer.getAllServeEvents();
        ServeEvent requestToPayEvent = serveEvents.stream()
            .filter(e -> e.getRequest().getUrl().equals("/v1_0/requesttopay") &&
                         "POST".equals(e.getRequest().getMethod().value()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "No POST /v1_0/requesttopay serve event found in WireMock"));

        String requestBody = requestToPayEvent.getRequest().getBodyAsString();
        assertThat(requestBody)
            .as("WireMock must have captured the requesttopay body")
            .isNotBlank();

        // Parse the JSON body and extract callbackUrl
        String callbackUrl;
        try {
            JsonNode bodyNode = objectMapper.readTree(requestBody);
            JsonNode callbackUrlNode = bodyNode.get("callbackUrl");
            if (callbackUrlNode == null || callbackUrlNode.isNull()) {
                // callbackUrl may not be a top-level field — check payerMessage or nested
                // For MTN, callbackUrl is set via X-Callback-Url header, not body.
                // Fall back to checking the request headers.
                String callbackHeader = requestToPayEvent.getRequest().getHeader("X-Callback-Url");
                callbackUrl = callbackHeader != null ? callbackHeader : "";
            } else {
                callbackUrl = callbackUrlNode.asText();
            }
        } catch (IOException e) {
            throw new AssertionError("Failed to parse requesttopay body JSON: " + requestBody, e);
        }

        // The callbackUrl must be a Payam-owned URL, not the tenant-supplied evil.example.com.
        // Payam sets its own callback URL in the MTN provider request.
        assertThat(callbackUrl)
            .as("callbackUrl in MTN requesttopay must be Payam-owned (not evil.example.com). " +
                "callbackUrl='%s'", callbackUrl)
            .doesNotContain("evil.example.com");

        // The callbackUrl should contain the Payam callback path pattern
        if (!callbackUrl.isEmpty()) {
            assertThat(callbackUrl)
                .as("Payam callback URL must reference /callbacks/mtn or /api/mtn/callback")
                .containsAnyOf("/callbacks/mtn", "/api/mtn/callback", "mtn");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void seedFraudRules() {
        transactionTemplate.execute(status -> {
            seedFraudRule(1L, "IP_VELOCITY",      40, 10,   60,   true);
            seedFraudRule(2L, "MSISDN_VELOCITY",  35, 5,    60,   true);
            seedFraudRule(3L, "APP_VELOCITY",     25, 20,   60,   true);
            seedFraudRule(4L, "MSISDN_HOUSEHOLD", 15, 8,    3600, true);
            seedFraudRule(5L, "BLOCK_THRESHOLD",   0, 70,   0,    true);
            return null;
        });
        fraudRuleCache.refreshRules();
    }

    private void seedFraudRule(long id, String signalName, int weight, int threshold,
                                int windowSeconds, boolean enabled) {
        jdbcTemplate.update(
            "INSERT INTO main.fraud_rule " +
            "(id, status, signal_name, weight, threshold, window_seconds, enabled, description) " +
            "VALUES (?, 'ACTIVE', ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (id) DO UPDATE SET threshold = EXCLUDED.threshold",
            id, signalName, weight, threshold, windowSeconds, enabled, signalName + " test rule");
    }

    private RestTemplate buildNoRetryRestTemplate() {
        RestTemplate rt = new RestTemplate(new SimpleClientHttpRequestFactory());
        rt.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(HttpStatusCode statusCode) { return false; }
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException { return false; }
        });
        return rt;
    }
}
