package com.softropic.payam.e2e.domain;

import com.softropic.payam.e2e.AbstractPayamE2ETest;
import com.softropic.payam.e2e.builder.PaymentRequestBuilder;
import com.softropic.payam.e2e.builder.TenantBuilder;
import com.softropic.payam.fraud.service.FraudRuleCache;
import com.softropic.payam.payment.contract.PaymentRequest;
import com.softropic.payam.payment.contract.PaymentResponse;
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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * INV-03-TEST: Idempotency no-double-charge invariant.
 *
 * Proves that:
 * 1. Same (tenantId, idempotencyKey) always maps to exactly one payment row and one provider call.
 * 2. Same idempotency key from different tenants creates two distinct rows (tenant-scoped idempotency).
 */
public class IdempotencyNoDoubleChargeTest extends AbstractPayamE2ETest {

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
    void sameKey_sameTenant_returnsOneRow() {
        seedFraudRules();

        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{\"name\":\"Test User\"}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));

        TenantBuilder.CreatedTenant tenant = new TenantBuilder()
            .withName("Idempotency-SameTenant-Test")
            .create(tenantService, tenantRepository);

        String idempotencyKey = UUID.randomUUID().toString();
        PaymentRequest req = new PaymentRequestBuilder()
            .withMsisdn("+237672000001")
            .withIdempotencyKey(idempotencyKey)
            .build();

        // POST twice with same key and same API key
        ResponseEntity<PaymentResponse> resp1 = postPayment(req, tenant.rawApiKey());
        ResponseEntity<PaymentResponse> resp2 = postPayment(req, tenant.rawApiKey());

        assertThat(resp1.getStatusCode().value()).isEqualTo(202);
        assertThat(resp2.getStatusCode().value()).isEqualTo(202);
        assertThat(resp1.getBody()).isNotNull();
        assertThat(resp2.getBody()).isNotNull();

        String txId1 = resp1.getBody().transactionId();
        String txId2 = resp2.getBody().transactionId();
        assertThat(txId1).isNotNull();
        assertThat(txId2)
            .as("Duplicate request must return the same transactionId (idempotency)")
            .isEqualTo(txId1);

        // Exactly 1 transaction row for this tenant + key
        Integer txCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM main.transaction WHERE transaction_id = ? AND tenant_id = ?",
            Integer.class, txId1, tenant.tenantId());
        assertThat(txCount)
            .as("Exactly one transaction row must exist for same tenant + idempotency key")
            .isEqualTo(1);

        // Provider called exactly once — second request hit idempotency cache
        mtnServer.verify(1, postRequestedFor(urlPathEqualTo("/v1_0/requesttopay")));
    }

    @Test
    void sameKey_differentTenant_returnsTwoRows() {
        seedFraudRules();

        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{\"name\":\"Test User\"}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));

        TenantBuilder.CreatedTenant tenantA = new TenantBuilder()
            .withName("Idempotency-TenantA-Test")
            .create(tenantService, tenantRepository);

        TenantBuilder.CreatedTenant tenantB = new TenantBuilder()
            .withName("Idempotency-TenantB-Test")
            .create(tenantService, tenantRepository);

        // Same idempotency key string, different tenant API keys
        String idempotencyKey = UUID.randomUUID().toString();
        PaymentRequest req = new PaymentRequestBuilder()
            .withMsisdn("+237672000001")
            .withIdempotencyKey(idempotencyKey)
            .build();

        ResponseEntity<PaymentResponse> respA = postPayment(req, tenantA.rawApiKey());
        ResponseEntity<PaymentResponse> respB = postPayment(req, tenantB.rawApiKey());

        assertThat(respA.getStatusCode().value()).isEqualTo(202);
        assertThat(respB.getStatusCode().value()).isEqualTo(202);
        assertThat(respA.getBody()).isNotNull();
        assertThat(respB.getBody()).isNotNull();

        String txIdA = respA.getBody().transactionId();
        String txIdB = respB.getBody().transactionId();
        assertThat(txIdA).isNotNull();
        assertThat(txIdB).isNotNull();
        assertThat(txIdB)
            .as("Cross-tenant same idempotency key must produce distinct transactionIds")
            .isNotEqualTo(txIdA);

        // Tenant B must NOT see Tenant A's transactionId in main.transaction.
        // Note: Both tenants legitimately have an idempotency_key row for the same key string
        // (tenant-scoped idempotency — that is the correct behavior). We assert cross-transaction
        // isolation only, not idempotency key isolation (which would be a false negative).
        Integer txLeakCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM main.transaction WHERE transaction_id = ? AND tenant_id = ?",
            Integer.class, txIdA, tenantB.tenantId());
        assertThat(txLeakCount)
            .as("Tenant B must not see Tenant A's transactionId=%s in main.transaction", txIdA)
            .isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ResponseEntity<PaymentResponse> postPayment(PaymentRequest req, String rawApiKey) {
        RestTemplate restTemplate = buildNoRetryRestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Api-Key", rawApiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        return restTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/payments",
            HttpMethod.POST,
            new HttpEntity<>(req, headers),
            PaymentResponse.class);
    }

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
