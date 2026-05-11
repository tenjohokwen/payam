package com.softropic.payam.e2e.domain;

import com.github.tomakehurst.wiremock.http.RequestListener;
import com.softropic.payam.e2e.AbstractPayamE2ETest;
import com.softropic.payam.e2e.builder.PaymentRequestBuilder;
import com.softropic.payam.e2e.builder.TenantBuilder;
import com.softropic.payam.payment.fraud.service.FraudRuleCache;
import com.softropic.payam.payment.core.contract.PaymentRequest;
import com.softropic.payam.payment.core.contract.PaymentResponse;
import com.softropic.payam.platform.tenant.repo.TenantRepository;
import com.softropic.payam.platform.tenant.service.TenantService;

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
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * INV-09-TEST: INIT row exists in DB before provider HTTP call arrives.
 *
 * Uses a WireMock RequestListener to capture DB state at the exact moment the provider
 * receives the POST /v1_0/requesttopay request. The listener fires in WireMock's request
 * handling thread and sets an AtomicInteger with the count of INITIATED rows.
 * Proves that PaymentOrchestrator persists the transaction before dispatching to the provider.
 */
public class InitBeforeProviderCallTest extends AbstractPayamE2ETest {

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
    void initRow_existsInDb_whenProviderCallArrives() {
        seedFraudRules();

        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{\"name\":\"Test User\"}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));

        TenantBuilder.CreatedTenant tenant = new TenantBuilder()
            .withName("InitBeforeProvider-Test")
            .create(tenantService, tenantRepository);

        // Capture DB row count at the moment WireMock receives the provider call.
        // Uses AtomicInteger for thread safety (listener fires in WireMock request thread).
        AtomicInteger rowCountAtProviderCall = new AtomicInteger(-1);

        RequestListener listener = (request, response) -> {
            if (request.getUrl().contains("/v1_0/requesttopay") &&
                "POST".equals(request.getMethod().value())) {
                Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM main.transaction WHERE tx_status = 'INITIATED'",
                    Integer.class);
                rowCountAtProviderCall.set(count != null ? count : 0);
            }
        };

        // Note: WireMock 3.9.1 WireMockServer does not expose removeMockServiceRequestListener().
        // The listener is scoped to this test's AtomicInteger; baseTearDown() resets the server state.
        mtnServer.addMockServiceRequestListener(listener);

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

        // The listener must have captured the count (> -1 means it fired)
        assertThat(rowCountAtProviderCall.get())
            .as("RequestListener must have fired during POST /v1_0/requesttopay")
            .isGreaterThan(-1);

        // INIT row must exist in DB when provider call arrives
        assertThat(rowCountAtProviderCall.get())
            .as("INIT row must exist in main.transaction when provider HTTP call arrives")
            .isGreaterThanOrEqualTo(1);
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
