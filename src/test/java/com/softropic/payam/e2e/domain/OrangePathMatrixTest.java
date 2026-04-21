package com.softropic.payam.e2e.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.payam.e2e.AbstractPayamE2ETest;
import com.softropic.payam.e2e.PlatformConfigInitializer;
import com.softropic.payam.e2e.builder.PaymentRequestBuilder;
import com.softropic.payam.e2e.builder.TenantBuilder;
import com.softropic.payam.e2e.verify.InvariantVerifier;
import com.softropic.payam.fraud.service.FraudRuleCache;
import com.softropic.payam.orange.service.OrangeStatusPollerJob;
import com.softropic.payam.payment.contract.PaymentRequest;
import com.softropic.payam.payment.contract.PaymentResponse;
import com.softropic.payam.tenant.repo.TenantRepository;
import com.softropic.payam.tenant.service.TenantService;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SM-01 + SM-02 + SM-04: Orange path matrix parameterized test.
 *
 * Covers 4 Orange scenarios in a single @ParameterizedTest:
 *   1. success          — webhook happy path → SUCCESS
 *   2. payToken-expiry  — poller detects expired payToken → FAILED (Decision [20-01] reversed)
 *   3. init-failure     — POST /mp/pay returns 500 → FAILED
 *   4. polling-fallback — no webhook, OrangeStatusPollerJob drives SUCCESS
 */
public class OrangePathMatrixTest extends AbstractPayamE2ETest {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private OrangeStatusPollerJob orangeStatusPollerJob;

    @Autowired
    private FraudRuleCache fraudRuleCache;

    @Autowired
    private PlatformConfigInitializer platformConfigInitializer;

    private TenantBuilder.CreatedTenant tenant;
    private InvariantVerifier invariant;
    private RestTemplate noRetryRestTemplate;

    @BeforeEach
    void setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        platformConfigInitializer.initOrange();

        // Seed permissive fraud rules (BLOCK_THRESHOLD=70 allows normal payments)
        transactionTemplate.execute(status -> {
            seedFraudRule(1L, "IP_VELOCITY",      40, 200, 60,   true);
            seedFraudRule(2L, "MSISDN_VELOCITY",  35, 200, 60,   true);
            seedFraudRule(3L, "APP_VELOCITY",     25, 200, 60,   true);
            seedFraudRule(4L, "MSISDN_HOUSEHOLD", 15, 200, 3600, true);
            seedFraudRule(5L, "BLOCK_THRESHOLD",   0,  70,  0,   true);
            return null;
        });
        fraudRuleCache.refreshRules();

        tenant = new TenantBuilder()
            .withName("Orange-Matrix-Tenant")
            .create(tenantService, tenantRepository);

        invariant = new InvariantVerifier(jdbcTemplate, redis, mtnServer, orangeServer);

        // Common Orange subscriber/merchant stubs
        orangeServer.stubFor(get(urlPathEqualTo("/infos/subscriber"))
            .willReturn(okJson("{\"status\":\"ACTIF\",\"message\":\"OK\"}")));
        orangeServer.stubFor(post(urlPathEqualTo("/mp/init"))
            .willReturn(okJson("{\"data\":{\"payToken\":\"tok-matrix-001\"},\"message\":\"OK\"}")));

        noRetryRestTemplate = buildNoRetryRestTemplate();
    }

    @AfterEach
    void tearDown() {
        platformConfigInitializer.clear();
    }

    static Stream<Arguments> orangePathMatrix() {
        return Stream.of(
            Arguments.of("success",           "SUCCESS",    2),
            Arguments.of("payToken-expiry",   "FAILED",     1), 
            Arguments.of("init-failure",      "FAILED",     1), 
            Arguments.of("polling-fallback",  "SUCCESS",    2)
        );
    }

    @ParameterizedTest(name = "Orange path: {0}")
    @MethodSource("orangePathMatrix")
    void orangePath_drivesCorrectFinalState(String scenarioName, String finalStatus,
                                            int expectedMinEventCount) throws Exception {
        switch (scenarioName) {
            case "success"          -> runSuccessScenario(finalStatus, expectedMinEventCount);
            case "payToken-expiry"  -> runPayTokenExpiryScenario(finalStatus, expectedMinEventCount);
            case "init-failure"     -> runInitFailureScenario(finalStatus, expectedMinEventCount);
            case "polling-fallback" -> runPollingFallbackScenario(finalStatus, expectedMinEventCount);
            default -> throw new IllegalArgumentException("Unknown scenario: " + scenarioName);
        }
    }

    // -------------------------------------------------------------------------
    // Scenario implementations
    // -------------------------------------------------------------------------

    private void runSuccessScenario(String finalStatus, int expectedMinEventCount) {
        orangeServer.stubFor(post(urlPathMatching("/mp/pay"))
            .willReturn(okJson("{\"status\":\"SUCCESS\",\"message\":\"OK\"}")));
        // Double-check GET status — Orange uses SUCCESSFULL (double-L) per decision [21-01]
        orangeServer.stubFor(get(urlPathMatching("/mp/paymentstatus/.*"))
            .willReturn(okJson("{\"status\":\"SUCCESSFULL\",\"message\":\"OK\"}")));

        OrangeInitResult init = postOrangePayment();
        assertThat(init.transactionId()).isNotNull();
        assertThat(init.providerRef()).isNotNull();

        // Send Orange POST webhook (correlated by payToken = providerRef)
        sendOrangeCallback(init.providerRef());

        Awaitility.await().atMost(8, TimeUnit.SECONDS).untilAsserted(() -> {
            invariant.assertLegalStateTransition(init.transactionId(), finalStatus);
            invariant.chain().assertChainValid(init.transactionId());
            assertEventCountAtLeast(init.transactionId(), expectedMinEventCount);
        });
    }

    private void runPayTokenExpiryScenario(String finalStatus, int expectedMinEventCount) {
        orangeServer.stubFor(post(urlPathMatching("/mp/pay"))
            .willReturn(okJson("{\"status\":\"SUCCESS\",\"message\":\"OK\"}")));
        // Do NOT stub paymentstatus — poller must NOT reach it once payToken expiry is detected

        OrangeInitResult init = postOrangePayment();
        assertThat(init.transactionId()).isNotNull();

        // Backdate pay_token_issued_at and last_modified_date with REQUIRES_NEW
        final Instant now = Instant.now();
        Instant backdated = now.minus(10, ChronoUnit.MINUTES);
        DefaultTransactionDefinition requiresNew = new DefaultTransactionDefinition();
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        new TransactionTemplate(transactionManager, requiresNew).execute(status -> {
            jdbcTemplate.update(
                "UPDATE main.transaction SET pay_token_issued_at = NOW() - INTERVAL '1 year', " +
                "last_modified_date = ? " +
                "WHERE transaction_id = ?", Timestamp.from(backdated), init.transactionId());
            return null;
        });

        // Invoke Orange poller via reflection — same pattern as OrangePayTokenExpiryE2ETest
        try {
            java.lang.reflect.Method exec = OrangeStatusPollerJob.class
                .getDeclaredMethod("executeInternal", org.quartz.JobExecutionContext.class);
            exec.setAccessible(true);
            transactionTemplate.execute(status -> {
                try {
                    exec.invoke(orangeStatusPollerJob, (Object) null);
                } catch (Exception e) {
                    throw new RuntimeException("OrangeStatusPollerJob.executeInternal failed", e);
                }
                return null;
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke OrangeStatusPollerJob.executeInternal", e);
        }

        // Transaction transitions to FAILED immediately on payToken expiry
        // Do NOT increment pollAttempts; the token expiry is not a poll outcome.
        String status = jdbcTemplate.queryForObject(
            "SELECT tx_status FROM main.transaction WHERE transaction_id = ?",
            String.class, init.transactionId());
        assertThat(status)
            .as("Transaction must transition to FAILED on payToken expiry")
            .isEqualTo(finalStatus);
        assertEventCountAtLeast(init.transactionId(), expectedMinEventCount);
    }

    private void runInitFailureScenario(String finalStatus, int expectedMinEventCount) {
        // POST /mp/pay returns 500 — orchestrator marks FAILED
        orangeServer.stubFor(post(urlPathMatching("/mp/pay"))
            .willReturn(aResponse().withStatus(500)));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", tenant.rawApiKey());

        PaymentRequest req = new PaymentRequestBuilder()
            .forOrange()
            .withIdempotencyKey(UUID.randomUUID().toString())
            .build();

        String body;
        try {
            body = new ObjectMapper().writeValueAsString(req);
        } catch (Exception e) {
            throw new RuntimeException("Serialization failed", e);
        }

        ResponseEntity<PaymentResponse> response = noRetryRestTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/payments",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            PaymentResponse.class);

        // Provider init failure should result in a non-202 response
        assertThat(response.getStatusCode().value())
            .as("Orange init failure must not return 202")
            .isNotEqualTo(202);

        String txId = response.getBody().transactionId();
        assertThat(txId).isNotNull();

        invariant.assertLegalStateTransition(txId, finalStatus);
        invariant.chain().assertChainValid(txId);
        assertEventCountAtLeast(txId, expectedMinEventCount);
    }

    private void runPollingFallbackScenario(String finalStatus, int expectedMinEventCount) throws Exception {
        orangeServer.stubFor(post(urlPathMatching("/mp/pay"))
            .willReturn(okJson("{\"status\":\"SUCCESS\",\"message\":\"OK\"}")));

        OrangeInitResult init = postOrangePayment();
        assertThat(init.transactionId()).isNotNull();

        // Backdate last_modified_date with REQUIRES_NEW
        Instant backdated = Instant.now().minus(10, ChronoUnit.MINUTES);
        DefaultTransactionDefinition requiresNew = new DefaultTransactionDefinition();
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        new TransactionTemplate(transactionManager, requiresNew).execute(status -> {
            jdbcTemplate.update(
                    "UPDATE main.transaction SET last_modified_date = ?" +
                "WHERE transaction_id = ?", Timestamp.from(backdated), init.transactionId());
            return null;
        });

        // Stub GET paymentstatus BEFORE invoking poller — Orange uses SUCCESSFULL (double-L)
        orangeServer.stubFor(get(urlPathMatching("/mp/paymentstatus/.*"))
            .willReturn(okJson("{\"status\":\"SUCCESSFULL\",\"message\":\"OK\"}")));

        // Invoke Orange poller via reflection (getDeclaredMethod pattern per plan spec)
        java.lang.reflect.Method exec = OrangeStatusPollerJob.class
            .getDeclaredMethod("executeInternal", org.quartz.JobExecutionContext.class);
        exec.setAccessible(true);
        transactionTemplate.execute(status -> {
            try {
                exec.invoke(orangeStatusPollerJob, (Object) null);
            } catch (Exception e) {
                throw new RuntimeException("OrangeStatusPollerJob.executeInternal failed", e);
            }
            return null;
        });

        // Polling path: skip assertLedgerBalanced (no ledger on polling path)
        invariant.assertLegalStateTransition(init.transactionId(), finalStatus);
        invariant.chain().assertChainValid(init.transactionId());
        assertEventCountAtLeast(init.transactionId(), expectedMinEventCount);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private OrangeInitResult postOrangePayment() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", tenant.rawApiKey());

        PaymentRequest req = new PaymentRequestBuilder()
            .forOrange()
            .withIdempotencyKey(UUID.randomUUID().toString())
            .build();

        String body;
        try {
            body = new ObjectMapper().writeValueAsString(req);
        } catch (Exception e) {
            throw new RuntimeException("Serialization failed", e);
        }

        ResponseEntity<PaymentResponse> response = noRetryRestTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/payments",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            PaymentResponse.class);

        assertThat(response.getStatusCode().value())
            .as("POST /v1/payments must return 202")
            .isEqualTo(202);
        assertThat(response.getBody()).isNotNull();

        return new OrangeInitResult(
            response.getBody().transactionId(),
            response.getBody().providerRef());
    }

    private void sendOrangeCallback(String payToken) {
        LocalDateTime now = LocalDateTime.now();
        String createtime = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        String body = String.format(
            "{\"payToken\":\"%s\",\"notif_token\":\"%s\",\"status\":\"SUCCESS\",\"txnid\":\"%s\"," +
            "\"msisdn\":\"237653000001\",\"amount\":\"1000\",\"createtime\":\"%s\"}",
            payToken, UUID.randomUUID(), UUID.randomUUID(), createtime);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        noRetryRestTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/callbacks/orange",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            Void.class);
    }

    private void assertEventCountAtLeast(String transactionId, int minCount) {
        if (minCount < 0) return;
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM main.payment_event_log WHERE transaction_id = ?",
            Integer.class, transactionId);
        assertThat(count)
            .as("Event count for transactionId=%s must be >= %d", transactionId, minCount)
            .isGreaterThanOrEqualTo(minCount);
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

    private record OrangeInitResult(String transactionId, String providerRef) {}
}
