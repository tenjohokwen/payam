package com.softropic.payam.e2e.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.payam.e2e.AbstractPayamE2ETest;
import com.softropic.payam.e2e.builder.DeterministicUuidFactory;
import com.softropic.payam.e2e.builder.PaymentRequestBuilder;
import com.softropic.payam.e2e.builder.TenantBuilder;
import com.softropic.payam.payment.contract.PaymentRequest;
import com.softropic.payam.payment.contract.PaymentResponse;
import com.softropic.payam.fraud.service.FraudRuleCache;
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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FLOWS-PAY-05: Three-round idempotency scenario.
 *
 * <p>Verifies the three critical idempotency invariants:
 * <ol>
 *   <li>Round 1 — new creation: 202 with fresh transactionId</li>
 *   <li>Round 2 — duplicate same tenant: 202 with SAME transactionId; provider NOT called again</li>
 *   <li>Round 3 — cross-tenant same idempotency key: 202 with DIFFERENT transactionId;
 *       TenantIsolationVerifier confirms no data leaks between tenants</li>
 * </ol>
 *
 * <p>This is NOT a template-method subclass. Extends {@link AbstractPayamE2ETest} directly
 * and implements a single {@link #idempotency_three_round_scenario()} @Test method.
 */
public class PaymentIdempotencyE2ETest extends AbstractPayamE2ETest {

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

    private DeterministicUuidFactory uuidFactory;

    // A non-retrying RestTemplate with no-op error handler — needed because TestRestTemplate
    // uses Apache HTTP Client which auto-retries on error responses, masking true first response.
    private RestTemplate noRetryRestTemplate;

    @Test
    void idempotency_three_round_scenario() {
        // --- Fraud rule seeding ---
        // FraudVelocityBlockE2ETest lowers MSISDN_VELOCITY threshold to 1 and does not restore it.
        // This test must seed/restore fraud rules so Round 3 (second distinct payment for same MSISDN
        // from a different tenant) is not blocked by an exhausted velocity bucket threshold.
        // Rule 1 fix: guard against stale FraudRuleCache state from previous test runs.
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.fraud_rule " +
                "(id, status, signal_name, weight, threshold, window_seconds, enabled, description) " +
                "VALUES (1, 'ACTIVE', 'IP_VELOCITY', 40, 10, 60, true, 'IP_VELOCITY test rule') " +
                "ON CONFLICT (id) DO UPDATE SET threshold = EXCLUDED.threshold");
            jdbcTemplate.update(
                "INSERT INTO main.fraud_rule " +
                "(id, status, signal_name, weight, threshold, window_seconds, enabled, description) " +
                "VALUES (2, 'ACTIVE', 'MSISDN_VELOCITY', 35, 5, 60, true, 'MSISDN_VELOCITY test rule') " +
                "ON CONFLICT (id) DO UPDATE SET threshold = EXCLUDED.threshold");
            jdbcTemplate.update(
                "INSERT INTO main.fraud_rule " +
                "(id, status, signal_name, weight, threshold, window_seconds, enabled, description) " +
                "VALUES (3, 'ACTIVE', 'APP_VELOCITY', 25, 20, 60, true, 'APP_VELOCITY test rule') " +
                "ON CONFLICT (id) DO UPDATE SET threshold = EXCLUDED.threshold");
            jdbcTemplate.update(
                "INSERT INTO main.fraud_rule " +
                "(id, status, signal_name, weight, threshold, window_seconds, enabled, description) " +
                "VALUES (4, 'ACTIVE', 'MSISDN_HOUSEHOLD', 15, 8, 3600, true, 'MSISDN_HOUSEHOLD test rule') " +
                "ON CONFLICT (id) DO UPDATE SET threshold = EXCLUDED.threshold");
            jdbcTemplate.update(
                "INSERT INTO main.fraud_rule " +
                "(id, status, signal_name, weight, threshold, window_seconds, enabled, description) " +
                "VALUES (5, 'ACTIVE', 'BLOCK_THRESHOLD', 0, 70, 0, true, 'BLOCK_THRESHOLD test rule') " +
                "ON CONFLICT (id) DO UPDATE SET threshold = EXCLUDED.threshold");
            return null;
        });
        fraudRuleCache.refreshRules();

        // --- Shared setup ---
        // Stub MTN initiation endpoints — shared across all three rounds.
        // Use prefix "67" (national: 672xxxxx) which routes to MTN via hardcoded fallback.
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));

        uuidFactory = new DeterministicUuidFactory(0xCAFEBL);

        noRetryRestTemplate = new RestTemplate(new SimpleClientHttpRequestFactory());
        noRetryRestTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(HttpStatusCode statusCode) { return false; }
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException { return false; }
        });

        // --- Round 1: new creation ---
        TenantBuilder.CreatedTenant tenantA = new TenantBuilder()
            .withName("Idempotency-A")
            .create(tenantService, tenantRepository);

        // Use +237672000001 (national prefix "67" = MTN via hardcoded fallback)
        PaymentRequest req = new PaymentRequestBuilder()
            .withMsisdn("+237672000001")
            .withDeterministicIdempotencyKey(uuidFactory)
            .build();
        String idempotencyKey = req.idempotencyKey();

        ResponseEntity<PaymentResponse> resp1 = postPayment(req, tenantA.rawApiKey());
        assertThat(resp1.getStatusCode().value())
            .as("Round 1: new payment must return 202")
            .isEqualTo(202);
        String transactionId1 = resp1.getBody().transactionId();
        assertThat(transactionId1)
            .as("Round 1: transactionId must not be null")
            .isNotNull();

        // --- Round 2: duplicate same tenant ---
        // POST with the SAME request body and SAME API key → must return same transactionId.
        ResponseEntity<PaymentResponse> resp2 = postPayment(req, tenantA.rawApiKey());
        assertThat(resp2.getStatusCode().value())
            .as("Round 2: duplicate request must return 202")
            .isEqualTo(202);
        String transactionId2 = resp2.getBody().transactionId();
        assertThat(transactionId2)
            .as("Round 2: duplicate request must return the same transactionId (idempotency)")
            .isEqualTo(transactionId1);

        // Provider called exactly ONCE across both rounds — second request hit the idempotency cache.
        mtnServer.verify(1, postRequestedFor(urlPathEqualTo("/v1_0/requesttopay")));

        // --- Round 3: cross-tenant, same idempotency key ---
        // POST with SAME request body (same idempotencyKey) but DIFFERENT tenant's API key.
        // Must return a DIFFERENT transactionId (idempotency is tenant-scoped).
        TenantBuilder.CreatedTenant tenantB = new TenantBuilder()
            .withName("Idempotency-B")
            .create(tenantService, tenantRepository);

        ResponseEntity<PaymentResponse> resp3 = postPayment(req, tenantB.rawApiKey());
        assertThat(resp3.getStatusCode().value())
            .as("Round 3: cross-tenant request must return 202")
            .isEqualTo(202);
        String transactionId3 = resp3.getBody().transactionId();
        assertThat(transactionId3)
            .as("Round 3: cross-tenant request must return a different transactionId")
            .isNotEqualTo(transactionId1);

        // Tenant isolation — tenant B must not see tenant A's transactionId.
        // We assert this directly rather than via TenantIsolationVerifier because both
        // tenants legitimately own an idempotency_key row for the same key string value
        // (that is exactly what tenant-scoped idempotency means: same key string, different
        // transactionId per tenant).  The TenantIsolationVerifier.assertNoDataLeaksToOtherTenant
        // check on main.idempotency_key would incorrectly fail here.
        Integer txLeakCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM main.transaction WHERE transaction_id = ? AND tenant_id = ?",
            Integer.class, transactionId1, tenantB.tenantId());
        assertThat(txLeakCount)
            .as("Tenant B must not see Tenant A's transactionId=%s in main.transaction", transactionId1)
            .isEqualTo(0);

        // Each tenant must have exactly ONE idempotency_key row for this key string
        // (they are isolated; the rows point to different transactionIds).
        Integer tenantAIdemCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM main.idempotency_key WHERE tenant_id = ? AND idempotency_key = ?",
            Integer.class, tenantA.tenantId(), idempotencyKey);
        assertThat(tenantAIdemCount)
            .as("Tenant A must have exactly one idempotency_key row for key=%s", idempotencyKey)
            .isEqualTo(1);

        Integer tenantBIdemCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM main.idempotency_key WHERE tenant_id = ? AND idempotency_key = ?",
            Integer.class, tenantB.tenantId(), idempotencyKey);
        assertThat(tenantBIdemCount)
            .as("Tenant B must have exactly one idempotency_key row for key=%s", idempotencyKey)
            .isEqualTo(1);

        // Provider called exactly TWICE total:
        // - Once for tenantA Round 1 (new payment)
        // - Once for tenantB Round 3 (new payment for different tenant)
        // Round 2 (tenantA duplicate) did NOT add a provider call.
        mtnServer.verify(2, postRequestedFor(urlPathEqualTo("/v1_0/requesttopay")));
    }

    /**
     * Post a payment request with the given API key and return the raw response.
     * Uses noRetryRestTemplate to avoid Apache HC retry masking on 5xx responses.
     */
    private ResponseEntity<PaymentResponse> postPayment(PaymentRequest req, String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", apiKey);

        String body;
        try {
            body = new ObjectMapper().writeValueAsString(req);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize PaymentRequest", e);
        }

        return noRetryRestTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/payments",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            PaymentResponse.class);
    }
}
