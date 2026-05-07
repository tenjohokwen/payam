package com.softropic.payam.e2e.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.payam.e2e.AbstractPayamE2ETest;
import com.softropic.payam.e2e.builder.PaymentRequestBuilder;
import com.softropic.payam.e2e.builder.TenantBuilder;
import com.softropic.payam.fraud.service.FraudRuleCache;
import com.softropic.payam.payment.contract.PaymentRequest;
import com.softropic.payam.payment.contract.PaymentResponse;
import com.softropic.payam.platform.tenant.repo.TenantApiKey;
import com.softropic.payam.platform.tenant.repo.TenantApiKeyRepository;
import com.softropic.payam.platform.tenant.repo.TenantRepository;
import com.softropic.payam.platform.tenant.service.ApiKeyService;
import com.softropic.payam.platform.tenant.service.TenantService;

import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CONC-04: API key rotation grace period — old and new key both authenticate during 24h window.
 *
 * <p>Proves that after key rotation, both the old ROTATED key and the new ACTIVE key are
 * accepted during the 24-hour grace period. This is implemented by
 * {@code ApiKeyService.authenticate()} which queries for keys with status ACTIVE or
 * (ROTATED AND rotatedAt > now - 24h).
 *
 * <p>Both threads fire simultaneously via CyclicBarrier; both must receive 202 responses
 * with distinct transactionIds belonging to the same tenant.
 */
public class ApiKeyRotationGracePeriodTest extends AbstractPayamE2ETest {

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private TenantApiKeyRepository tenantApiKeyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private FraudRuleCache fraudRuleCache;

    @BeforeEach
    void setUp() {
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));

        // Seed fraud rules with permissive thresholds so neither thread is fraud-blocked
        transactionTemplate.execute(status -> {
            seedFraudRule(1L, "IP_VELOCITY",      40, 200,  60,   true);
            seedFraudRule(2L, "MSISDN_VELOCITY",  35, 200,  60,   true);
            seedFraudRule(3L, "APP_VELOCITY",     25, 200,  60,   true);
            seedFraudRule(4L, "MSISDN_HOUSEHOLD", 15, 200,  3600, true);
            seedFraudRule(5L, "BLOCK_THRESHOLD",   0, 70,   0,    true);
            return null;
        });
        fraudRuleCache.refreshRules();
    }

    @Test
    void oldAndNewKey_bothAuthenticate_duringGracePeriod() throws Exception {
        // Step 1: Create tenant with initial API key
        TenantBuilder.CreatedTenant tenant = new TenantBuilder()
            .withName("CONC-04-Grace-Tenant")
            .create(tenantService, tenantRepository);

        String oldApiKey = tenant.rawApiKey();

        // Step 2: Find the key entity to get the key ID for rotation.
        // TenantApiKeyRepository.findAllByTenantId returns all keys for this tenant.
        List<TenantApiKey> keys = tenantApiKeyRepository.findAllByTenantId(tenant.tenantId());
        assertThat(keys)
            .as("Tenant must have exactly 1 initial API key after creation")
            .hasSize(1);
        Long oldKeyId = keys.get(0).getId();

        // Step 3: Rotate the key — old key transitions to ROTATED, new ACTIVE key created.
        // Do NOT backdate rotatedAt — the rotation just happened, so both keys are valid
        // within the 24-hour grace window (ApiKeyService.GRACE_PERIOD = Duration.ofHours(24)).
        ApiKeyService.ApiKeyAndRawKey rotationResult = apiKeyService.rotate(oldKeyId);
        String newApiKey = rotationResult.rawKey();

        // Verify the keys are distinct
        assertThat(newApiKey)
            .as("New API key must be different from old API key")
            .isNotEqualTo(oldApiKey);

        // Step 4: Launch both threads simultaneously — one uses the old (ROTATED) key,
        // the other uses the new (ACTIVE) key. Both must be accepted during the grace period.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);

        String uniqueKeyA = UUID.randomUUID().toString();
        String uniqueKeyB = UUID.randomUUID().toString();
        String msisdn = "+237672000001";

        Future<ResponseEntity<PaymentResponse>> futureA = pool.submit(() -> {
            try { barrier.await(10, TimeUnit.SECONDS); } catch (Exception ignored) {}
            return postPayment(uniqueKeyA, msisdn, oldApiKey);
        });

        Future<ResponseEntity<PaymentResponse>> futureB = pool.submit(() -> {
            try { barrier.await(10, TimeUnit.SECONDS); } catch (Exception ignored) {}
            return postPayment(uniqueKeyB, msisdn, newApiKey);
        });

        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS))
            .as("Both threads must complete within 30 seconds")
            .isTrue();

        ResponseEntity<PaymentResponse> respA = futureA.get();
        ResponseEntity<PaymentResponse> respB = futureB.get();

        // Step 5: Both responses must return 202 — both keys authenticate successfully
        assertThat(respA.getStatusCode().value())
            .as("Old (ROTATED) key must authenticate and return 202 during 24h grace period")
            .isEqualTo(202);
        assertThat(respB.getStatusCode().value())
            .as("New (ACTIVE) key must authenticate and return 202")
            .isEqualTo(202);

        // Step 6: Both transactionIds must be non-null and distinct (two separate payments)
        assertThat(respA.getBody()).isNotNull();
        assertThat(respB.getBody()).isNotNull();

        String txIdA = respA.getBody().transactionId();
        String txIdB = respB.getBody().transactionId();

        assertThat(txIdA)
            .as("Old-key payment must return a non-null transactionId")
            .isNotNull();
        assertThat(txIdB)
            .as("New-key payment must return a non-null transactionId")
            .isNotNull();
        assertThat(txIdB)
            .as("Two payments with different idempotency keys must produce distinct transactionIds")
            .isNotEqualTo(txIdA);

        // Step 7: Both payments must belong to the same tenant
        Long tenantIdA = jdbcTemplate.queryForObject(
            "SELECT tenant_id FROM main.transaction WHERE transaction_id = ?",
            Long.class, txIdA);
        Long tenantIdB = jdbcTemplate.queryForObject(
            "SELECT tenant_id FROM main.transaction WHERE transaction_id = ?",
            Long.class, txIdB);

        assertThat(tenantIdA)
            .as("Old-key payment must belong to the correct tenant")
            .isEqualTo(tenant.tenantId());
        assertThat(tenantIdB)
            .as("New-key payment must belong to the same tenant")
            .isEqualTo(tenantIdA);

        // Step 8: Exactly 2 provider calls — both payments reached the provider
        mtnServer.verify(2, postRequestedFor(urlPathEqualTo("/v1_0/requesttopay")));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ResponseEntity<PaymentResponse> postPayment(String idempotencyKey, String msisdn,
                                                         String rawApiKey) {
        PaymentRequest req = new PaymentRequestBuilder()
            .withMsisdn(msisdn)
            .withIdempotencyKey(idempotencyKey)
            .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", rawApiKey);

        String body;
        try {
            body = new ObjectMapper().writeValueAsString(req);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize PaymentRequest", e);
        }

        RestTemplate rt = buildNoRetryRestTemplate();
        return rt.exchange(
            "http://localhost:" + serverPort + "/v1/payments",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            PaymentResponse.class);
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
