package com.softropic.payam.e2e.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.payam.e2e.AbstractPayamE2ETest;
import com.softropic.payam.e2e.builder.PaymentRequestBuilder;
import com.softropic.payam.e2e.builder.TenantBuilder;
import com.softropic.payam.fraud.service.FraudRuleCache;
import com.softropic.payam.payment.contract.PaymentRequest;
import com.softropic.payam.payment.contract.PaymentResponse;
import com.softropic.payam.platform.tenant.repo.TenantRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CONC-01: Concurrent idempotency race — 20 threads, same idempotency key, exactly 1 row.
 *
 * <p>Proves that the Redis NX+EX idempotency guard prevents double-charging under concurrent load.
 * 20 threads are released simultaneously via CyclicBarrier all using the same idempotency key.
 * Asserts exactly 1 payment row and 1 provider call; all non-null transactionIds are the same.
 */
public class ConcurrentIdempotencyRaceTest extends AbstractPayamE2ETest {

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

    private TenantBuilder.CreatedTenant tenant;
    private RestTemplate noRetryRestTemplate;

    @BeforeEach
    void setUp() {
        // Seed tenant and WireMock stubs
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));

        tenant = new TenantBuilder()
            .withName("CONC-01-Tenant")
            .create(tenantService, tenantRepository);

        // Seed fraud rules with permissive thresholds so none of the 20 threads are fraud-blocked
        transactionTemplate.execute(status -> {
            seedFraudRule(1L, "IP_VELOCITY",      40, 200,  60,   true);
            seedFraudRule(2L, "MSISDN_VELOCITY",  35, 200,  60,   true);
            seedFraudRule(3L, "APP_VELOCITY",     25, 200,  60,   true);
            seedFraudRule(4L, "MSISDN_HOUSEHOLD", 15, 200,  3600, true);
            seedFraudRule(5L, "BLOCK_THRESHOLD",   0, 70,   0,    true);
            return null;
        });
        fraudRuleCache.refreshRules();

        noRetryRestTemplate = buildNoRetryRestTemplate();
    }

    @Test
    void twentyThreads_sameIdempotencyKey_producesExactlyOneRow() throws Exception {
        int THREADS = 20;
        String sharedKey = UUID.randomUUID().toString();
        String msisdn = "+237672000001"; // MTN prefix

        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        List<Future<ResponseEntity<PaymentResponse>>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                try { barrier.await(10, TimeUnit.SECONDS); } catch (Exception ignored) {}
                return postPayment(sharedKey, msisdn);
            }));
        }

        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS))
            .as("All 20 threads must complete within 60 seconds")
            .isTrue();

        // Collect all non-null transactionIds — RESERVED in-flight threads return null transactionId
        List<String> txIds = futures.stream()
            .map(f -> {
                try { return f.get(); }
                catch (Exception e) { throw new RuntimeException(e); }
            })
            .map(r -> r.getBody() != null ? r.getBody().transactionId() : null)
            .filter(Objects::nonNull)
            .distinct()
            .collect(Collectors.toList());

        // At least 1 non-null transactionId must exist
        assertThat(txIds)
            .as("At least one thread must have received a non-null transactionId")
            .isNotEmpty();

        // All non-null transactionIds must be the same (exactly 1 unique value)
        assertThat(txIds)
            .as("All 20 threads must converge on exactly 1 unique transactionId — idempotency guard")
            .hasSize(1);

        String uniqueTxId = txIds.get(0);

        // Exactly 1 transaction row in DB for this tenant + idempotency key
        Integer rowCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM main.transaction WHERE transaction_id = ? AND tenant_id = ?",
            Integer.class, uniqueTxId, tenant.tenantId());
        assertThat(rowCount)
            .as("Exactly 1 transaction row must exist for transactionId=%s, tenantId=%s",
                uniqueTxId, tenant.tenantId())
            .isEqualTo(1);

        // Exactly 1 provider call — idempotency guard blocked all duplicates
        mtnServer.verify(1, postRequestedFor(urlPathEqualTo("/v1_0/requesttopay")));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ResponseEntity<PaymentResponse> postPayment(String idempotencyKey, String msisdn) {
        PaymentRequest req = new PaymentRequestBuilder()
            .withMsisdn(msisdn)
            .withIdempotencyKey(idempotencyKey)
            .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", tenant.rawApiKey());

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
