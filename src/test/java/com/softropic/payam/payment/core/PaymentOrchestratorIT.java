package com.softropic.payam.payment.core;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.payment.fee.service.FeeRuleCache;
import com.softropic.payam.payment.core.contract.PaymentResponse;
import com.softropic.payam.platform.admin.service.PlatformConfigService;
import com.softropic.payam.platform.tenant.contract.ApiKeyEnvironment;
import com.softropic.payam.platform.tenant.service.TenantService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "mtn.callback-ip-whitelist=",
    // Override CB config so Test 7 can trip the circuit with 10 failures (50% of 10)
    "resilience4j.circuitbreaker.instances.mtn.slidingWindowSize=10",
    "resilience4j.circuitbreaker.instances.mtn.failureRateThreshold=50",
    "resilience4j.circuitbreaker.instances.mtn.minimumNumberOfCalls=10",
    "resilience4j.circuitbreaker.instances.orange.slidingWindowSize=10",
    "resilience4j.circuitbreaker.instances.orange.failureRateThreshold=50",
    "resilience4j.circuitbreaker.instances.orange.minimumNumberOfCalls=10"
})
@EnableWireMock({
    @ConfigureWireMock(name = "orange", baseUrlProperties = {"orange.base-url", "orange.pay-url"}),
    @ConfigureWireMock(name = "mtn",    baseUrlProperties = {"mtn.collection-base-url"})
})
class PaymentOrchestratorIT {

    @InjectWireMock("orange")
    WireMockServer orangeServer;

    @InjectWireMock("mtn")
    WireMockServer mtnServer;

    TestRestTemplate testRestTemplate = new TestRestTemplate();
    @Autowired TenantService tenantService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired StringRedisTemplate redis;
    @Autowired CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired FeeRuleCache          feeRuleCache;
    @Autowired PlatformConfigService platformConfigService;


    @MockitoSpyBean
    com.softropic.payam.payment.fee.service.FeeEvaluationService feeSpy;

    @MockitoSpyBean
    com.softropic.payam.payment.ledger.repo.TransactionRepository txRepoSpy;

    @LocalServerPort
    int serverPort;

    // A non-retrying RestTemplate for circuit-breaker tests where TestRestTemplate
    // would otherwise auto-retry on 503 (Apache HTTP Client retry behaviour) — causing
    // the idempotency key to be found RESERVED on the second attempt and masking the 503.
    private RestTemplate noRetryRestTemplate;

    private Long tenantId;
    private String apiKey;
    private String baseUrl = "http://localhost:";

    @BeforeEach
    void setUp() {
        // Seed JWT secret required by SecurityAdviceFilter.addSecretToThread()
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute(
                "INSERT INTO main.sec (id, created_by, created_date, last_modified_by, last_modified_date, " +
                "request_id, session_id, status, bus_id, value, version) " +
                "VALUES ('659287191260154475','SYSTEM_ACCOUNT','2024-12-24 06:51:55.357352'," +
                "'SYSTEM_ACCOUNT','2024-12-24 06:51:55.357352'," +
                "'bed78f34-3e09-4fa8-81db-32326a528cca', null, 'ACTIVE', 'jot'," +
                "'loiI8oT2C1tWecrNXPDjN8fveYEU8rD6nb1k1NbVy92rwdd4/KO+aHhXh3A5zjsT5eSFL/xI+9Rqyj4RI6QCiFywn5nZLIwHGPNEY0F9lnDnGGmVjv/9rO5fgGt83+cxNDyGoCePaVEpBd7xHxyDdfpAoLxQs8mhKGqcEsh09Q+26qEiEm/a9bgDSbSQ0sX00VHBLd35OLmvN+ydjEluYxBTa6KzGb2CQ6Ttg4ZaELmbZOWpEjQ1Z7BbbYiXmWyaY+2HnkyhONoGbUpvVKl1c4e9IlQzeUYkekbUbADIm2LNK9Nhfv5/L5esvFrdVOUcUpLk/y8UT9f5xOMLFJ4Ct6s0eTKvNqYkSz2DFRI8Ip4p/ns6gA4V/1MUf9GeqPUWLiOa28Vw15+R8ycUMqb8NZHOP1oj9RunhSwA7EY84bZL3+yePc3n1b8ne8xzaYVEdK1WBu3J6s2AoBaOL/JLWfu8MuxXI+ub', 'v1') " +
                "ON CONFLICT DO NOTHING");
            return null;
        });

        var provision = tenantService.createTenant("payment-orch-test", ApiKeyEnvironment.PROD);
        tenantId = provision.tenant().getId();
        apiKey = provision.rawKey();

        // Stub MTN collection token endpoint (path is /token/ per test application.properties)
        mtnServer.stubFor(post(urlPathEqualTo("/token/"))
            .willReturn(okJson("{\"access_token\":\"test-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));

        // Stub Orange token endpoint (path is /token per test application.properties)
        orangeServer.stubFor(post(urlPathEqualTo("/token"))
            .willReturn(okJson("{\"access_token\":\"orange-token\",\"token_type\":\"Bearer\",\"expires_in\":7200}")));

        // Build a non-retrying RestTemplate for tests that need to observe the raw first response.
        // TestRestTemplate uses Apache HTTP Client which auto-retries on 503 — this bypasses that.
        // Also set a no-op error handler so 5xx responses are returned as ResponseEntity, not thrown.
        noRetryRestTemplate = new RestTemplate(new SimpleClientHttpRequestFactory());
        noRetryRestTemplate.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse response) throws java.io.IOException {
                return false; // never throw; let assertions handle status codes
            }
        });

        // Flush all Redis keys to ensure idempotency cache is clean before each test
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();

        // Also clear provider token caches that may survive across tests
        redis.delete("mtn:token:cm");
        redis.delete("orange:token");

        // Ensure fee_rule table is empty so no global Flyway-seeded rule (id=1) interferes.
        // With hibernate.ddl-auto: none, Flyway seed data persists between context startups.
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM main.fee_rule");
            return null;
        });
        feeRuleCache.refresh();

        // Reconfigure the mtn and orange CBs with minimumNumberOfCalls=5 so the
        // circuit_breaker test can auto-trip with 10 failures without relying on
        // @TestPropertySource property binding.
        CircuitBreakerConfig smallWindowConfig = CircuitBreakerConfig.custom()
            .slidingWindowSize(5)
            .failureRateThreshold(50)
            .minimumNumberOfCalls(5)
            .waitDurationInOpenState(java.time.Duration.ofSeconds(30))
            .permittedNumberOfCallsInHalfOpenState(3)
            .ignoreExceptions(com.softropic.payam.mtn.contract.exception.MtnAccountInactiveException.class)
            .build();
        circuitBreakerRegistry.remove("mtn");
        circuitBreakerRegistry.circuitBreaker("mtn", smallWindowConfig);

        circuitBreakerRegistry.remove("orange");
        circuitBreakerRegistry.circuitBreaker("orange",
            CircuitBreakerConfig.custom()
                .slidingWindowSize(5)
                .failureRateThreshold(50)
                .minimumNumberOfCalls(5)
                .waitDurationInOpenState(java.time.Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .ignoreExceptions(
                    com.softropic.payam.orange.contract.exception.SubscriberInactiveException.class,
                    com.softropic.payam.orange.contract.exception.PayTokenExpiredException.class)
                .build());
    }

    @AfterEach
    void tearDown() {
        mtnServer.resetAll();
        orangeServer.resetAll();

        // Flush Redis idempotency keys
        var keys = redis.keys("idempotency:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        // Also clear provider token caches
        redis.delete("mtn:token:cm");
        redis.delete("orange:token");

        // FK-safe DELETE order: transaction → fee_rule; idempotency_key → tenant
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM main.payment_event_log");
            jdbcTemplate.execute("DELETE FROM main.transaction");
            jdbcTemplate.execute("DELETE FROM main.fee_rule");
            jdbcTemplate.execute("DELETE FROM main.idempotency_key");
            jdbcTemplate.execute("DELETE FROM main.tenant_api_key");
            jdbcTemplate.execute("DELETE FROM main.tenant");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
        feeRuleCache.refresh();
    }

    // ---- helper methods ----

    private HttpHeaders headersWithKey() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", apiKey);
        return headers;
    }

    /**
     * Make a POST /v1/payments call using a non-retrying RestTemplate.
     * Use this when you need to observe the raw first response (e.g., 503) without
     * Apache HTTP Client's automatic retry behavior masking it.
     */
    private ResponseEntity<PaymentResponse> postPaymentNoRetry(String body) {
        String url = "http://localhost:" + serverPort + "/v1/payments";
        return noRetryRestTemplate.exchange(
            url, HttpMethod.POST,
            new HttpEntity<>(body, headersWithKey()),
            PaymentResponse.class);
    }

    private String buildMtnRequest(String msisdn, String idempotencyKey) {
        return String.format(
            "{\"msisdn\":\"%s\",\"amount\":100,\"currency\":\"XAF\",\"externalReference\":\"ext-cb\",\"idempotencyKey\":\"%s\"}",
            msisdn, idempotencyKey);
    }

    // ---- tests ----

    @Test
    void orange_msisdn_routes_to_orange_and_returns_202() {
        // Seed platform config with PIN
        platformConfigService.update("ORANGE", "691301143", "2222");

        // Stub Orange subscriber info endpoint
        orangeServer.stubFor(get(urlPathEqualTo("/infos/subscriber"))
            .willReturn(okJson("{\"status\":\"ACTIF\",\"message\":\"OK\"}")));

        //Stub Orange access token endpoint
        orangeServer.stubFor(post(urlPathEqualTo("/token"))
                                     .withHeader("Authorization", containing("Basic"))
                                     .withRequestBody(equalTo("grant_type=client_credentials"))
                                     .willReturn(okJson("{\"access_token\":\"test-bearer-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));

        // Stub Orange merchant info endpoint — returns payToken
        orangeServer.stubFor(post(urlPathEqualTo("/mp/init"))
            .willReturn(okJson("{\"data\":{\"payToken\":\"tok-orange-001\"},\"message\":\"OK\"}")));

        // Stub Orange pay endpoint (pay-url is overridden to same WireMock server in tests)
        orangeServer.stubFor(post(urlPathMatching("/mp/pay"))
            .willReturn(okJson("{\"status\":\"SUCCESS\",\"message\":\"OK\"}")));

        String body = "{\"msisdn\":\"+237653000001\",\"amount\":1000,\"currency\":\"XAF\"," +
                      "\"externalReference\":\"ext-001\",\"idempotencyKey\":\"idem-orange-001\"}";

        ResponseEntity<PaymentResponse> response = testRestTemplate.exchange(
            baseUrl + serverPort + "/v1/payments", HttpMethod.POST,
            new HttpEntity<>(body, headersWithKey()),
            PaymentResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().transactionId()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("PROCESSING");
        assertThat(response.getBody().errorCode()).isNull();
        // Fee fields — no fee rule seeded for this tenant so feeAmount must be 0 (never null)
        assertThat(response.getBody().feeAmount()).isNotNull();
        assertThat(response.getBody().feeAmount()).isEqualByComparingTo(java.math.BigDecimal.ZERO);
        assertThat(response.getBody().feeRuleId()).isNull(); // no rule seeded
    }

    @Test
    void mtn_msisdn_routes_to_mtn_and_returns_202() {
        // Stub MTN validate account holder
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{}")));

        // Stub MTN requestToPay — returns 202 Accepted with no body
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));

        String body = "{\"msisdn\":\"+237672000001\",\"amount\":500,\"currency\":\"XAF\"," +
                      "\"externalReference\":\"ext-002\",\"idempotencyKey\":\"idem-mtn-001\"}";

        ResponseEntity<PaymentResponse> response = testRestTemplate.exchange(
                baseUrl + serverPort + "/v1/payments", HttpMethod.POST,
            new HttpEntity<>(body, headersWithKey()),
            PaymentResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().transactionId()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("PROCESSING");
        assertThat(response.getBody().errorCode()).isNull();
        // Fee fields — no fee rule seeded for this tenant so feeAmount must be 0 (never null)
        assertThat(response.getBody().feeAmount()).isNotNull();
        assertThat(response.getBody().feeAmount()).isEqualByComparingTo(java.math.BigDecimal.ZERO);
        assertThat(response.getBody().feeRuleId()).isNull(); // no rule seeded
    }

    @Test
    void unknown_msisdn_prefix_returns_422() {
        // No WireMock stubs needed — router rejects before any provider call
        String body = "{\"msisdn\":\"+237900000000\",\"amount\":500,\"currency\":\"XAF\"," +
                      "\"externalReference\":\"ext-003\",\"idempotencyKey\":\"idem-unk-001\"}";

        ResponseEntity<PaymentResponse> response = testRestTemplate.exchange(
                baseUrl + serverPort + "/v1/payments", HttpMethod.POST,
            new HttpEntity<>(body, headersWithKey()),
            PaymentResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("UNKNOWN_MSISDN_PREFIX");
    }

    @Test
    void fee_rule_applied_returns_nonzero_fee_amount() {
        // Seed a FEE_FIXED rule for this tenant via JDBC (dev create-drop wipes Flyway seed data)
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.fee_rule (id, created_by, created_date, last_modified_by, last_modified_date, " +
                "status, tenant_id, fee_type, fixed_amount, percentage_rate, enabled, rule_name) " +
                "VALUES (?, 'TEST', NOW(), 'TEST', NOW(), 'ACTIVE', ?, 'FEE_FIXED', 50.00, NULL, true, 'TEST-FIXED-50')",
                System.nanoTime() & Long.MAX_VALUE, tenantId);
            return null;
        });
        // Force cache reload so FeeEvaluationService sees the new rule
        feeRuleCache.refresh();

        // Stub MTN endpoints
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));

        String body = "{\"msisdn\":\"+237672000001\",\"amount\":1000,\"currency\":\"XAF\"," +
                      "\"externalReference\":\"ext-fee-01\",\"idempotencyKey\":\"idem-fee-01\"}";

        ResponseEntity<PaymentResponse> response = testRestTemplate.exchange(
                baseUrl + serverPort + "/v1/payments", HttpMethod.POST,
            new HttpEntity<>(body, headersWithKey()),
            PaymentResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isNotNull();
        // Fee rule was seeded — feeAmount must be 50 XAF and feeRuleId must be set
        assertThat(response.getBody().feeAmount()).isNotNull();
        assertThat(response.getBody().feeAmount()).isGreaterThan(java.math.BigDecimal.ZERO);
        assertThat(response.getBody().feeRuleId()).isNotNull();
    }

    @Test
    void duplicate_idempotency_key_returns_202_without_second_provider_call() {
        // Stub MTN endpoints for first call
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));

        String body = "{\"msisdn\":\"+237672000001\",\"amount\":500,\"currency\":\"XAF\"," +
                      "\"externalReference\":\"ext-004\",\"idempotencyKey\":\"idem-idem-001\"}";

        // First request
        ResponseEntity<PaymentResponse> response1 = testRestTemplate.exchange(
                baseUrl + serverPort + "/v1/payments", HttpMethod.POST,
            new HttpEntity<>(body, headersWithKey()),
            PaymentResponse.class);

        assertThat(response1.getStatusCode().value()).isEqualTo(202);

        // Reset WireMock request tracking (not stubs) to check second request's provider calls
        mtnServer.resetRequests();

        // Second request with same idempotency key
        ResponseEntity<PaymentResponse> response2 = testRestTemplate.exchange(
                baseUrl + serverPort + "/v1/payments", HttpMethod.POST,
            new HttpEntity<>(body, headersWithKey()),
            PaymentResponse.class);

        // Should return 202 (cached or PAYMENT_ALREADY_PROCESSING)
        assertThat(response2.getStatusCode().value()).isEqualTo(202);

        // Provider's requestToPay must NOT have been called again
        mtnServer.verify(0, postRequestedFor(urlPathEqualTo("/v1_0/requesttopay")));
    }

    @Test
    void missing_api_key_returns_401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // No X-Api-Key header

        String body = "{\"msisdn\":\"+237672000001\",\"amount\":500,\"currency\":\"XAF\"," +
                      "\"externalReference\":\"ext-005\",\"idempotencyKey\":\"idem-nokey-001\"}";

        // Use String.class because Spring Security returns 401 as HTML/text, not JSON
        ResponseEntity<String> response = testRestTemplate.exchange(
                baseUrl + serverPort + "/v1/payments", HttpMethod.POST,
            new HttpEntity<>(body, headers),
            String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void subscriber_inactive_returns_422() {
        // Stub MTN validateAccountHolder to return 404 (inactive account)
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(aResponse().withStatus(404)));

        String body = "{\"msisdn\":\"+237672000001\",\"amount\":500,\"currency\":\"XAF\"," +
                      "\"externalReference\":\"ext-006\",\"idempotencyKey\":\"idem-inactive-001\"}";

        ResponseEntity<PaymentResponse> response = testRestTemplate.exchange(
                baseUrl + serverPort + "/v1/payments", HttpMethod.POST,
            new HttpEntity<>(body, headersWithKey()),
            PaymentResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("SUBSCRIBER_INACTIVE");
    }

    @Test
    void circuit_breaker_trips_after_repeated_failures_returns_503() {
        // Stub MTN validateAccountHolder to return 500 (server error — counts as circuit failure)
        // NOTE: 404 is in ignoreExceptions (MtnAccountInactiveException) and does NOT count.
        // 500 causes MtnApiException which DOES count toward the sliding window.
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(aResponse().withStatus(500)));

        // Send 10 requests — each with unique idempotencyKey to bypass idempotency cache
        // Circuit breaker: slidingWindowSize=10, failureRateThreshold=50 (50% of 10 = 5 failures needed)
        for (int i = 0; i < 10; i++) {
            String body = buildMtnRequest("+237672000001", "idem-cb-" + i);
            testRestTemplate.exchange(
                    baseUrl + serverPort + "/v1/payments", HttpMethod.POST,
                new HttpEntity<>(body, headersWithKey()),
                PaymentResponse.class);
            // Responses will be 502/422 — circuit not yet open until all 10 slots in sliding window filled
        }

        // After 10 failures at 100% rate (> 50% threshold), circuit MUST be OPEN
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("mtn");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Explicitly force the circuit to OPEN state before the verification request.
        // This ensures the circuit is in OPEN state even if the CB state machine behaves
        // differently in test context (e.g., due to waitDurationInOpenState transitions).
        cb.transitionToOpenState();

        // Reset WireMock request tracking for clarity
        mtnServer.resetRequests();

        // Also delete any idempotency keys from the 10 failure iterations to avoid stale RESERVED state
        // that could cause checkAndReserve to find them unexpectedly
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        // Re-stub the token endpoint since we flushed the token cache too
        mtnServer.stubFor(post(urlPathEqualTo("/token/"))
            .willReturn(okJson("{\"access_token\":\"test-token-2\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));

        // Send the circuit-open verification request with a fresh unique key.
        // IMPORTANT: Use noRetryRestTemplate — TestRestTemplate auto-retries on 503 via
        // Apache HTTP Client, which causes the 2nd attempt to find the idempotency key
        // RESERVED and return 202 instead of the expected 503.
        String circuitOpenKey = "idem-cb-verify-" + java.util.UUID.randomUUID();
        String body = buildMtnRequest("+237672000001", circuitOpenKey);
        ResponseEntity<PaymentResponse> resp = postPaymentNoRetry(body);

        assertThat(resp.getStatusCode().value()).isEqualTo(503);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().errorCode()).isEqualTo("PROVIDER_UNAVAILABLE");

        // WireMock received NO validateAccountHolder calls — circuit intercepted before provider call
        mtnServer.verify(0, getRequestedFor(urlPathMatching(".*accountholder.*")));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("TXN-01: fee evaluation completes before SELECT...FOR UPDATE row lock is acquired")
    void feeEvaluationHappensBeforeLock() {
        // Seed a fee rule so evaluateFee is observable (non-trivial call)
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.fee_rule (id, created_by, created_date, last_modified_by, last_modified_date, " +
                "status, tenant_id, fee_type, fixed_amount, percentage_rate, enabled, rule_name) " +
                "VALUES (?, 'TEST', NOW(), 'TEST', NOW(), 'ACTIVE', ?, 'FEE_FIXED', 25.00, NULL, true, 'TXN01-TEST')",
                System.nanoTime() & Long.MAX_VALUE, tenantId);
            return null;
        });
        feeRuleCache.refresh();

        // Stub MTN endpoints for a successful payment
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));

        String body = "{\"msisdn\":\"+237672000001\",\"amount\":1000,\"currency\":\"XAF\"," +
                      "\"externalReference\":\"ext-txn01\",\"idempotencyKey\":\"idem-txn01-order\"}";

        ResponseEntity<PaymentResponse> response = testRestTemplate.exchange(
                baseUrl + serverPort + "/v1/payments", HttpMethod.POST,
            new HttpEntity<>(body, headersWithKey()),
            PaymentResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(202);

        // TXN-01: evaluateFee must have been called BEFORE findByTransactionIdForUpdate
        InOrder inOrder = Mockito.inOrder(feeSpy, txRepoSpy);
        inOrder.verify(feeSpy).evaluateFee(eq(tenantId), any(java.math.BigDecimal.class));
        inOrder.verify(txRepoSpy, Mockito.atLeastOnce()).findByTransactionIdForUpdate(anyString());
    }
}
