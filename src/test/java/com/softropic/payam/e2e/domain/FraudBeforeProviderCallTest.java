package com.softropic.payam.e2e.domain;

import com.softropic.payam.e2e.AbstractPayamE2ETest;
import com.softropic.payam.e2e.builder.PaymentRequestBuilder;
import com.softropic.payam.e2e.builder.TenantBuilder;
import com.softropic.payam.e2e.verify.InvariantVerifier;
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
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * INV-07-TEST: Fraud evaluation timestamp is recorded before the provider HTTP call.
 *
 * Proves that fraud evaluation completed (and allowed the payment) before the provider was called.
 * The proxy verification: PAYMENT_INITIATED event exists in the event log after the flow completes,
 * which is only reachable if fraud evaluation passed without blocking.
 */
public class FraudBeforeProviderCallTest extends AbstractPayamE2ETest {

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

    @Test
    void fraudEvaluationTimestamp_isBeforeProviderCallTimestamp() {
        // Seed fraud rules with BLOCK_THRESHOLD=70 — normal payment score < 70 is allowed
        transactionTemplate.execute(status -> {
            seedFraudRule(1L, "IP_VELOCITY",      40, 10,   60,   true);
            seedFraudRule(2L, "MSISDN_VELOCITY",  35, 5,    60,   true);
            seedFraudRule(3L, "APP_VELOCITY",     25, 20,   60,   true);
            seedFraudRule(4L, "MSISDN_HOUSEHOLD", 15, 8,    3600, true);
            seedFraudRule(5L, "BLOCK_THRESHOLD",   0, 70,   0,    true);
            return null;
        });
        fraudRuleCache.refreshRules();

        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{\"name\":\"Test User\"}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));

        TenantBuilder.CreatedTenant tenant = new TenantBuilder()
            .withName("FraudBeforeProvider-Test")
            .create(tenantService, tenantRepository);

        InvariantVerifier invariant = new InvariantVerifier(jdbcTemplate, redis, mtnServer, orangeServer);

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
            .as("Payment must be allowed (fraud score below BLOCK_THRESHOLD=70)")
            .isEqualTo(202);
        assertThat(response.getBody()).isNotNull();
        String transactionId = response.getBody().transactionId();
        assertThat(transactionId).isNotNull();

        // Assert fraud evaluated before provider: PAYMENT_INITIATED event proves fraud passed
        // and the provider was subsequently called in the correct order.
        invariant.assertFraudEvaluatedBeforeProviderCall(transactionId);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
