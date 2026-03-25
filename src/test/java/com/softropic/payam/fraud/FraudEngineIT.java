package com.softropic.payam.fraud;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.fraud.service.FraudRuleCache;
import com.softropic.payam.payment.contract.PaymentResponse;
import com.softropic.payam.tenant.service.TenantService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.http.HttpStatus;
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
 * End-to-end integration test for fraud engine wired into POST /v1/payments.
 *
 * <p>Verifies two FRAUD-01 requirements end-to-end:
 * <ol>
 *   <li>A payment from an IP exceeding the velocity threshold returns HTTP 422 with FRAUD_BLOCKED.
 *       The block fires before any provider call — WireMock receives zero requests for the blocked payment.</li>
 *   <li>A non-blocked payment results in a Transaction row with non-null risk_score in the DB.</li>
 * </ol>
 */
@ActiveProfiles("dev")
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
class FraudEngineIT {

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
        TenantService.TenantCreationResult provision = tenantService.createTenant("fraud-engine-it-" + UUID.randomUUID(), "LIVE");
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

        // FK-safe DELETE order (follows PaymentOrchestratorIT pattern)
        transactionTemplate.execute(status -> {
            jdbc.execute("DELETE FROM main.payment_event_log");
            jdbc.execute("DELETE FROM main.transaction");
            jdbc.execute("DELETE FROM main.tenant_api_key");
            jdbc.execute("DELETE FROM main.tenant");
            jdbc.execute("DELETE FROM main.fraud_rule");
            jdbc.execute("DELETE FROM main.sec");
            return null;
        });
    }

    // ---- Test 1 ----

    /**
     * Velocity block — lower the MSISDN_VELOCITY threshold to 1 so that the 2nd request to the
     * same MSISDN is blocked. Choosing MSISDN_VELOCITY avoids IP-header injection complexities
     * (ForwardedHeaderFilter may strip or rewrite the Forwarded header). The block fires before
     * any provider call. Asserts HTTP 422 with errorCode=FRAUD_BLOCKED.
     */
    @Test
    void velocityBlockReturns422() {
        // Lower MSISDN_VELOCITY threshold to 1 so the 2nd request to the same MSISDN is blocked.
        // Wrap in transactionTemplate to ensure the JDBC update commits before refreshRules() reads it.
        transactionTemplate.execute(status -> {
            jdbc.update("UPDATE main.fraud_rule SET threshold = 1 WHERE signal_name = 'MSISDN_VELOCITY'");
            return null;
        });
        fraudRuleCache.refreshRules();

        // Stub MTN for the first (allowed) request
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));

        String msisdn = "+237671000001";

        // First request — should pass (MSISDN_VELOCITY bucket has capacity=1, first request consumes it)
        String body1 = buildMtnRequest(msisdn, "idem-fraud-001-" + UUID.randomUUID());
        ResponseEntity<PaymentResponse> resp1 = postPayment(body1);
        assertThat(resp1.getStatusCode().value())
                .as("First request should be accepted (202)")
                .isEqualTo(202);

        // Reset WireMock request tracking but keep stubs — second request must NOT reach provider
        mtnServer.resetRequests();

        // Second request to same MSISDN — bucket exhausted, should be blocked before provider call
        String body2 = buildMtnRequest(msisdn, "idem-fraud-002-" + UUID.randomUUID());
        ResponseEntity<PaymentResponse> resp2 = postPayment(body2);

        assertThat(resp2.getStatusCode())
                .as("Second request to same MSISDN should be blocked with 422")
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(resp2.getBody()).isNotNull();
        assertThat(resp2.getBody().errorCode())
                .as("Error code must be FRAUD_BLOCKED")
                .isEqualTo("FRAUD_BLOCKED");

        // Verify no provider call was made for the blocked request (fraud fires before dispatch)
        mtnServer.verify(0, postRequestedFor(urlPathEqualTo("/v1_0/requesttopay")));

        // Reset threshold back to default after test
        transactionTemplate.execute(status -> {
            jdbc.update("UPDATE main.fraud_rule SET threshold = 5 WHERE signal_name = 'MSISDN_VELOCITY'");
            return null;
        });
        fraudRuleCache.refreshRules();
    }

    // ---- Test 2 ----

    /**
     * Normal payment — asserts that a non-blocked payment produces a Transaction row with
     * non-null risk_score in the DB (FRAUD-01 score persistence requirement).
     */
    @Test
    void normalPaymentHasRiskScoreInDb() {
        // Stub MTN endpoints for a successful payment
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));

        // Use a MSISDN not shared with velocityBlockReturns422 test to avoid cross-test velocity state
        String body = buildMtnRequest("+237671000003", "idem-fraud-score-" + UUID.randomUUID());

        ResponseEntity<PaymentResponse> response = postPayment(body);

        assertThat(response.getStatusCode().value())
                .as("Payment should be accepted (202)")
                .isEqualTo(202);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().transactionId()).isNotNull();

        // Query DB to confirm risk_score is persisted and within valid range
        Integer score = jdbc.queryForObject(
            "SELECT risk_score FROM main.transaction WHERE tenant_id = ? ORDER BY created_date DESC LIMIT 1",
            Integer.class, tenantId);

        assertThat(score)
                .as("risk_score must not be null — fraud evaluation must run before provider dispatch")
                .isNotNull();
        assertThat(score)
                .as("risk_score must be within valid range 0-100")
                .isBetween(0, 100);
    }

    // ---- Test 3 ----

    /**
     * Device fingerprint persistence — asserts that a payment submitted with a non-null
     * deviceFingerprint is stored in the device_fingerprint column of main.transaction (FRAUD-01).
     */
    @Test
    void deviceFingerprintIsPersistedInDb() {
        // Stub MTN endpoints for a successful payment
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));

        // Use a MSISDN not shared with other tests to avoid cross-test velocity state
        String body = buildMtnRequestWithFingerprint("+237671000005", "idem-fraud-fp-" + UUID.randomUUID(), "fp-test-abc123");

        ResponseEntity<PaymentResponse> response = postPayment(body);

        assertThat(response.getStatusCode().value())
                .as("Payment should be accepted (202)")
                .isEqualTo(202);
        assertThat(response.getBody()).isNotNull();

        // Query DB to confirm device_fingerprint is persisted with the submitted value
        String deviceFingerprint = jdbc.queryForObject(
            "SELECT device_fingerprint FROM main.transaction WHERE tenant_id = ? ORDER BY created_date DESC LIMIT 1",
            String.class, tenantId);

        assertThat(deviceFingerprint)
                .as("device_fingerprint must not be null and must equal the submitted value")
                .isEqualTo("fp-test-abc123");
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
            "\"externalReference\":\"TEST-FE\",\"idempotencyKey\":\"%s\"}",
            msisdn, idempotencyKey);
    }

    private String buildMtnRequestWithFingerprint(String msisdn, String idempotencyKey, String fingerprint) {
        return String.format(
            "{\"msisdn\":\"%s\",\"amount\":500,\"currency\":\"XAF\"," +
            "\"externalReference\":\"TEST-FE\",\"idempotencyKey\":\"%s\"," +
            "\"deviceFingerprint\":\"%s\"}",
            msisdn, idempotencyKey, fingerprint);
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
