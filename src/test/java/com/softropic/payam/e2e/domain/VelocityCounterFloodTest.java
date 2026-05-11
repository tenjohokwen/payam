package com.softropic.payam.e2e.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.payam.e2e.AbstractPayamE2ETest;
import com.softropic.payam.e2e.builder.PaymentRequestBuilder;
import com.softropic.payam.e2e.builder.TenantBuilder;
import com.softropic.payam.payment.fraud.service.FraudRuleCache;
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
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.lessThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CONC-03: Velocity counter flood — 100 threads, same IP, at most threshold provider calls.
 *
 * <p>Proves that Bucket4j Redis token bucket velocity guard correctly limits concurrent requests
 * from the same source IP under flood conditions. 100 threads with unique idempotency keys all
 * fire simultaneously; at most THRESHOLD requests reach the provider.
 *
 * <p>NOTE: Does NOT assert Redis counter value directly — Bucket4j uses CAS state blobs, not
 * plain integer counters (RESEARCH.md Pitfall 4). Asserts effect: provider call count <= threshold.
 */
public class VelocityCounterFloodTest extends AbstractPayamE2ETest {

    // THRESHOLD must match the IP_VELOCITY threshold seeded in setUp().
    // Any change to this constant MUST be mirrored in the seedFraudRules() call below.
    private static final int THRESHOLD = 5;

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

    @BeforeEach
    void setUp() {
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));

        tenant = new TenantBuilder()
            .withName("CONC-03-Flood-Tenant")
            .create(tenantService, tenantRepository);

        // Seed fraud rules:
        // - IP_VELOCITY threshold = THRESHOLD (5) — the guard under test
        // - IP_VELOCITY weight = 40, BLOCK_THRESHOLD = 35
        //   → a single over-threshold IP request scores > 35 and gets FRAUD_BLOCKED
        // - Other signals given high thresholds so only IP_VELOCITY can trigger blocking
        transactionTemplate.execute(status -> {
            // IP_VELOCITY: weight 40, threshold THRESHOLD (5), window 60s
            seedFraudRule(1L, "IP_VELOCITY",      40, THRESHOLD, 60,   true);
            // MSISDN_VELOCITY: high threshold so MSISDN bucketing never triggers
            seedFraudRule(2L, "MSISDN_VELOCITY",  35, 1000,       60,   true);
            // APP_VELOCITY: high threshold so app-level bucketing never triggers
            seedFraudRule(3L, "APP_VELOCITY",     25, 1000,       60,   true);
            // MSISDN_HOUSEHOLD: high threshold so household bucketing never triggers
            seedFraudRule(4L, "MSISDN_HOUSEHOLD", 15, 1000,       3600, true);
            // BLOCK_THRESHOLD = 35: one IP over-threshold event (score 40) blocks the request
            seedFraudRule(5L, "BLOCK_THRESHOLD",   0, 35,          0,    true);
            return null;
        });
        fraudRuleCache.refreshRules();
    }

    @Test
    void hundredThreads_sameIp_atMostThresholdProviderCalls() throws Exception {
        int THREADS = 100;

        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        List<Future<ResponseEntity<PaymentResponse>>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            final String uniqueKey = UUID.randomUUID().toString();
            futures.add(pool.submit(() -> {
                try { barrier.await(15, TimeUnit.SECONDS); } catch (Exception ignored) {}
                return postPayment(uniqueKey, "+237672000001");
            }));
        }

        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS))
            .as("All 100 threads must complete within 120 seconds")
            .isTrue();

        // Collect outcomes — wrap checked exceptions in RuntimeException
        List<ResponseEntity<PaymentResponse>> responses = new ArrayList<>();
        for (Future<ResponseEntity<PaymentResponse>> f : futures) {
            responses.add(f.get());
        }

        long allowedCount = responses.stream()
            .filter(r -> r.getStatusCode().value() == 202)
            .count();
        long blockedCount = responses.stream()
            .filter(r -> r.getStatusCode().value() == 422)
            .count();

        // All 100 threads must produce either 202 (allowed) or 422 (fraud blocked)
        assertThat(allowedCount + blockedCount)
            .as("All %d threads must produce either 202 or 422 (no other status codes)", THREADS)
            .isEqualTo(THREADS);

        // Most requests must be blocked — at least THREADS - THRESHOLD are blocked
        assertThat(blockedCount)
            .as("At least %d/%d requests must be FRAUD_BLOCKED (IP_VELOCITY threshold=%d)",
                THREADS - THRESHOLD, THREADS, THRESHOLD)
            .isGreaterThanOrEqualTo(THREADS - THRESHOLD);

        // KEY ASSERTION: provider call count must be at most THRESHOLD.
        // This proves the Bucket4j Redis velocity guard correctly limits concurrent IP requests.
        // NOTE: Do NOT assert Redis key value directly — Bucket4j stores CAS state blobs, not
        // plain integer counters (RESEARCH.md Pitfall 4).
        mtnServer.verify(lessThanOrExactly(THRESHOLD), postRequestedFor(urlPathEqualTo("/v1_0/requesttopay")));
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
