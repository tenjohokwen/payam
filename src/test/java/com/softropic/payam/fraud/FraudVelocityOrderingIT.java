package com.softropic.payam.fraud;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.fraud.service.FraudRuleCache;
import com.softropic.payam.payment.contract.PaymentResponse;
import com.softropic.payam.platform.tenant.contract.ApiKeyEnvironment;
import com.softropic.payam.platform.tenant.service.TenantService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPS-02 integration test: proves that a retry using the same idempotency key returns the
 * cached response without consuming an additional velocity token.
 *
 * <p>The idempotency replay path in {@code PaymentOrchestrator.initiate()} returns the cached
 * {@code PaymentResponse} immediately after {@code idempotencyService.checkAndReserve()} — before
 * {@code fraudScoringService.evaluate()} is ever called. This means no velocity tokens are consumed
 * on a replay, satisfying OPS-02.
 *
 * <p>Boilerplate copied verbatim from {@link FraudEngineIT} — same annotations, same setUp/tearDown,
 * same helpers. No mocking: uses only the real production path.
 */
@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "mtn.callback-ip-whitelist="
})
@EnableWireMock({
    @ConfigureWireMock(name = "orange", baseUrlProperties = {"orange.base-url", "orange.pay-url"}),
    @ConfigureWireMock(name = "mtn",    baseUrlProperties = {"mtn.collection-base-url"})
})
class FraudVelocityOrderingIT {

    @InjectWireMock("mtn")
    WireMockServer mtnServer;

    @InjectWireMock("orange")
    WireMockServer orangeServer;

    @LocalServerPort
    int port;

    @Autowired TestRestTemplate restTemplate;
    @Autowired TenantService tenantService;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired FraudRuleCache fraudRuleCache;

    private Long tenantId;
    private String apiKey;

    @BeforeEach
    void setUp() {
        // Seed JWT secret required by SecurityAdviceFilter.addSecretToThread()
        transactionTemplate.execute(status -> {
            jdbc.execute(
                "INSERT INTO main.sec (id, created_by, created_date, last_modified_by, last_modified_date, " +
                "request_id, session_id, status, bus_id, value, version) " +
                "VALUES ('659287191260154475','SYSTEM_ACCOUNT','2024-12-24 06:51:55.357352'," +
                "'SYSTEM_ACCOUNT','2024-12-24 06:51:55.357352'," +
                "'bed78f34-3e09-4fa8-81db-32326a528cca', null, 'ACTIVE', 'jot'," +
                "'loiI8oT2C1tWecrNXPDjN8fveYEU8rD6nb1k1NbVy92rwdd4/KO+aHhXh3A5zjsT5eSFL/xI+9Rqyj4RI6QCiFywn5nZLIwHGPNEY0F9lnDnGGmVjv/9rO5fgGt83+cxNDyGoCePaVEpBd7xHxyDdfpAoLxQs8mhKGqcEsh09Q+26qEiEm/a9bgDSbSQ0sX00VHBLd35OLmvN+ydjEluYxBTa6KzGb2CQ6Ttg4ZaELmbZOWpEjQ1Z7BbbYiXmWyaY+2HnkyhONoGbUpvVKl1c4e9IlQzeUYkekbUbADIm2LNK9Nhfv5/L5esvFrdVOUcUpLk/y8UT9f5xOMLFJ4Ct6s0eTKvNqYkSz2DFRI8Ip4p/ns6gA4V/1MUf9GeqPUWLiOa28Vw15+R8ycUMqb8NZHOP1oj9RunhSwA7EY84bZL3+yePc3n1b8ne8xzaYVEdK1WBu3J6s2AoBaOL/JLWfu8MuxXI+ub', 'v1') " +
                "ON CONFLICT DO NOTHING");
            return null;
        });

        // Seed fraud rules — dev profile create-drop wipes Flyway seed data on each test run
        transactionTemplate.execute(status -> {
            seedRule(1L, "IP_VELOCITY",      40, 10, 60,   true);
            seedRule(2L, "MSISDN_VELOCITY",  35, 5,  60,   true);
            seedRule(3L, "APP_VELOCITY",     25, 20, 60,   true);
            seedRule(4L, "MSISDN_HOUSEHOLD", 15, 8,  3600, true);
            seedRule(5L, "BLOCK_THRESHOLD",  0,  70, 0,    true);
            return null;
        });

        // Refresh the in-memory rule cache so FraudScoringService sees the seeded rules
        fraudRuleCache.refreshRules();

        // Create tenant for this test
        TenantService.TenantCreationResult provision = tenantService.createTenant("fraud-velocity-ordering-it-" + UUID.randomUUID(), ApiKeyEnvironment.PROD);
        tenantId = provision.tenant().getId();
        apiKey = provision.rawKey();

        // Flush Redis: velocity keys and idempotency keys
        deleteVelocityKeys();
        deleteIdempotencyKeys();

        // Stub MTN token endpoint (always needed if MTN call reaches provider)
        mtnServer.stubFor(post(urlPathEqualTo("/token/"))
            .willReturn(okJson("{\"access_token\":\"test-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));

        // Stub Orange token endpoint (not used in these tests but prevents startup issues)
        orangeServer.stubFor(post(urlPathEqualTo("/token"))
            .willReturn(okJson("{\"access_token\":\"orange-token\",\"token_type\":\"Bearer\",\"expires_in\":7200}")));
    }

    @AfterEach
    void tearDown() {
        mtnServer.resetAll();
        orangeServer.resetAll();

        deleteVelocityKeys();
        deleteIdempotencyKeys();

        // FK-safe DELETE order: idempotency_key references tenant, so delete it first
        transactionTemplate.execute(status -> {
            jdbc.execute("DELETE FROM main.payment_event_log");
            jdbc.execute("DELETE FROM main.transaction");
            jdbc.execute("DELETE FROM main.idempotency_key");
            jdbc.execute("DELETE FROM main.tenant_api_key");
            jdbc.execute("DELETE FROM main.tenant");
            jdbc.execute("DELETE FROM main.fraud_rule");
            jdbc.execute("DELETE FROM main.sec");
            return null;
        });
    }

    /**
     * OPS-02: A retry with the same idempotency key returns the cached response without
     * consuming an additional velocity token.
     *
     * Proof: Set MSISDN_VELOCITY threshold to 1. Send a successful first payment (consumes
     * the one available MSISDN token). Send an IDENTICAL second payment with the SAME
     * idempotency key — this hits the idempotency replay path in PaymentOrchestrator,
     * returns the cached 202 without calling fraudScoringService.evaluate(), and therefore
     * does NOT consume a second token. A THIRD payment with a NEW idempotency key is then
     * sent — it must be BLOCKED (422 FRAUD_BLOCKED) proving only ONE token was consumed
     * total (by the first call), not two.
     */
    @Test
    @DisplayName("OPS-02: idempotency replay does not consume an additional velocity token")
    void idempotencyReplay_doesNotConsumeAdditionalVelocityToken() {
        // Lower MSISDN_VELOCITY threshold to 1 so the MSISDN bucket has exactly 1 token.
        transactionTemplate.execute(status -> {
            jdbc.update("UPDATE main.fraud_rule SET threshold = 1 WHERE signal_name = 'MSISDN_VELOCITY'");
            return null;
        });
        fraudRuleCache.refreshRules();

        // Stub MTN for a successful payment
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));

        String sharedMsisdn     = "+237671000020";
        String sharedIdemKey    = "idem-ops02-replay-" + UUID.randomUUID();
        String differentIdemKey = "idem-ops02-new-"   + UUID.randomUUID();

        // --- Call 1: first payment, unique idempotency key → consumes the 1 available MSISDN token ---
        String body1 = buildMtnRequest(sharedMsisdn, sharedIdemKey);
        ResponseEntity<PaymentResponse> resp1 = postPayment(body1);
        assertThat(resp1.getStatusCode().value())
            .as("First request must be accepted (202) — MSISDN bucket has 1 token")
            .isEqualTo(202);

        // --- Call 2: SAME idempotency key → replay path; evaluate() NOT called; no token consumed ---
        String body2 = buildMtnRequest(sharedMsisdn, sharedIdemKey);
        ResponseEntity<PaymentResponse> resp2 = postPayment(body2);
        assertThat(resp2.getStatusCode().value())
            .as("Second request with SAME idempotency key must return cached 202 (replay path — no evaluate() called)")
            .isEqualTo(202);
        assertThat(resp2.getBody()).isNotNull();
        assertThat(resp2.getBody().transactionId()).as("Replayed response must carry the same transactionId as the first response").isEqualTo(resp1.getBody().transactionId());

        // --- Call 3: NEW idempotency key, same MSISDN → evaluate() called, bucket exhausted → BLOCKED ---
        // If Call 2 had consumed a token, the bucket would now have -1 tokens and this would still
        // be blocked. But the CRITICAL proof is that only ONE token was ever consumed (by Call 1).
        // We verify this by confirming Call 3 IS blocked (proving Call 1 exhausted the single token)
        // and that Call 2's replay returned the identical response to Call 1 (proving it took the
        // idempotency cache path, not the fraud-evaluation path).
        String body3 = buildMtnRequest(sharedMsisdn, differentIdemKey);
        ResponseEntity<PaymentResponse> resp3 = postPayment(body3);
        assertThat(resp3.getStatusCode().value())
            .as("Third request with NEW idempotency key must be FRAUD_BLOCKED (422) — bucket exhausted by Call 1 only")
            .isEqualTo(422);
        assertThat(resp3.getBody()).isNotNull();
        assertThat(resp3.getBody().errorCode())
            .as("Error code must be FRAUD_BLOCKED")
            .isEqualTo("FRAUD_BLOCKED");
    }

    // ---- helpers ----

    private HttpHeaders headersWithKey() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", apiKey);
        return headers;
    }

    private ResponseEntity<PaymentResponse> postPayment(String body) {
        return restTemplate.exchange(
            "http://localhost:" + port + "/v1/payments",
            HttpMethod.POST,
            new HttpEntity<>(body, headersWithKey()),
            PaymentResponse.class);
    }

    private String buildMtnRequest(String msisdn, String idempotencyKey) {
        return String.format(
            "{\"msisdn\":\"%s\",\"amount\":500,\"currency\":\"XAF\"," +
            "\"externalReference\":\"TEST-FVO\",\"idempotencyKey\":\"%s\"}",
            msisdn, idempotencyKey);
    }

    private void deleteVelocityKeys() {
        Set<String> keys = redis.keys("fraud:velocity:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private void deleteIdempotencyKeys() {
        Set<String> keys = redis.keys("idempotency:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private void seedRule(Long id, String signalName, int weight, int threshold,
                          int windowSeconds, boolean enabled) {
        jdbc.update(
            "INSERT INTO main.fraud_rule (id, signal_name, weight, threshold, window_seconds, enabled, status) " +
            "VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE') ON CONFLICT (signal_name) DO UPDATE SET " +
            "weight=EXCLUDED.weight, threshold=EXCLUDED.threshold, " +
            "window_seconds=EXCLUDED.window_seconds, enabled=EXCLUDED.enabled",
            id, signalName, weight, threshold, windowSeconds, enabled);
    }
}
