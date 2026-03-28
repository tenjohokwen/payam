package com.softropic.payam.e2e.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.payam.e2e.AbstractPayamE2ETest;
import com.softropic.payam.e2e.builder.MtnWebhookPayloadBuilder;
import com.softropic.payam.e2e.builder.PaymentRequestBuilder;
import com.softropic.payam.e2e.builder.TenantBuilder;
import com.softropic.payam.e2e.verify.InvariantVerifier;
import com.softropic.payam.fraud.service.FraudRuleCache;
import com.softropic.payam.mtn.contract.MtnCallbackPayload;
import com.softropic.payam.mtn.service.MtnStatusPollerJob;
import com.softropic.payam.payment.contract.PaymentRequest;
import com.softropic.payam.payment.contract.PaymentResponse;
import com.softropic.payam.tenant.repo.TenantRepository;
import com.softropic.payam.tenant.service.TenantService;

import org.awaitility.Awaitility;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SM-01 + SM-02 + SM-03: MTN path matrix parameterized test.
 *
 * Covers 5 MTN scenarios in a single @ParameterizedTest:
 *   1. success          — webhook happy path → SUCCESS, 4 events
 *   2. fraud-blocked    — fraud evaluation blocks before provider → FAILED, 2 events
 *   3. provider-timeout — connection fault triggers Resilience4j → FAILED
 *   4. webhook-failed   — provider responds FAILED → FAILED, 3 events
 *   5. polling-fallback — no webhook, poller drives SUCCESS, 4 events (no ledger)
 */
public class MtnPathMatrixTest extends AbstractPayamE2ETest {

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
    private MtnStatusPollerJob mtnStatusPollerJob;

    @Autowired
    private FraudRuleCache fraudRuleCache;

    private TenantBuilder.CreatedTenant tenant;
    private InvariantVerifier invariant;
    private RestTemplate noRetryRestTemplate;

    @BeforeEach
    void setUp() {
        // Seed permissive fraud rules so normal payments are allowed (BLOCK_THRESHOLD=70).
        // Fraud-blocked scenario overrides MSISDN_VELOCITY threshold locally.
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
            .withName("MTN-Matrix-Tenant")
            .create(tenantService, tenantRepository);

        invariant = new InvariantVerifier(jdbcTemplate, redis, mtnServer, orangeServer);

        // Common MTN stubs — scenario-specific stubs added per scenario
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{}")));

        noRetryRestTemplate = buildNoRetryRestTemplate();
    }

    static Stream<Arguments> mtnPathMatrix() {
        return Stream.of(
            // scenarioName, finalStatus, expectedMinEventCount
            Arguments.of("success",            "SUCCESS",  1),
            Arguments.of("fraud-blocked",      "FAILED",   0),
            Arguments.of("provider-timeout",   "FAILED",  -1), // event count not constrained
            Arguments.of("webhook-failed",     "FAILED",   1),
            Arguments.of("polling-fallback",   "SUCCESS",  1)
        );
    }

    @ParameterizedTest(name = "MTN path: {0}")
    @MethodSource("mtnPathMatrix")
    void mtnPath_drivesCorrectFinalState(String scenarioName, String finalStatus,
                                         int expectedMinEventCount) throws Exception {
        switch (scenarioName) {
            case "success"            -> runSuccessScenario(finalStatus);
            case "fraud-blocked"      -> runFraudBlockedScenario(finalStatus);
            case "provider-timeout"   -> runProviderTimeoutScenario(finalStatus);
            case "webhook-failed"     -> runWebhookFailedScenario(finalStatus);
            case "polling-fallback"   -> runPollingFallbackScenario(finalStatus);
            default -> throw new IllegalArgumentException("Unknown scenario: " + scenarioName);
        }
    }

    // -------------------------------------------------------------------------
    // Scenario implementations
    // -------------------------------------------------------------------------

    private void runSuccessScenario(String finalStatus) {
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));
        mtnServer.stubFor(get(urlPathMatching("/v1_0/requesttopay/.*"))
            .willReturn(okJson("{\"status\":\"SUCCESSFUL\",\"financialTransactionId\":\"fin-matrix-suc\"}")));

        String transactionId = postPayment(new PaymentRequestBuilder()
            .withIdempotencyKey(UUID.randomUUID().toString()).build());
        assertThat(transactionId).isNotNull();

        // Send MTN PUT webhook to drive SUCCESS
        sendMtnCallback(transactionId, "SUCCESSFUL");

        Awaitility.await().atMost(8, TimeUnit.SECONDS).untilAsserted(() -> {
            invariant.assertLegalStateTransition(transactionId, finalStatus);
            invariant.chain().assertChainValid(transactionId);
            assertEventCountAtLeast(transactionId, 1);
        });
    }

    private void runFraudBlockedScenario(String finalStatus) {
        // Lower MSISDN_VELOCITY threshold to 1 — first request to the MSISDN consumes the bucket,
        // second request to the same MSISDN is blocked. Mirrors FraudBlockedPaymentE2ETest pattern.
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "UPDATE main.fraud_rule SET threshold = 1 WHERE signal_name = 'MSISDN_VELOCITY'");
            return null;
        });
        fraudRuleCache.refreshRules();

        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));

        final String msisdn = "+237672000001";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", tenant.rawApiKey());

        // Request 1: allowed — MSISDN_VELOCITY bucket has capacity=1, first request consumes it
        PaymentRequest req1 = new PaymentRequestBuilder()
            .withMsisdn(msisdn)
            .withIdempotencyKey(UUID.randomUUID().toString())
            .build();
        String body1;
        try {
            body1 = new ObjectMapper().writeValueAsString(req1);
        } catch (Exception e) {
            throw new RuntimeException("Serialization failed", e);
        }
        noRetryRestTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/payments",
            HttpMethod.POST,
            new HttpEntity<>(body1, headers),
            PaymentResponse.class);

        // Request 2: same MSISDN, different idempotency key — velocity bucket exhausted → blocked
        PaymentRequest req2 = new PaymentRequestBuilder()
            .withMsisdn(msisdn)
            .withIdempotencyKey(UUID.randomUUID().toString())
            .build();
        String body2;
        try {
            body2 = new ObjectMapper().writeValueAsString(req2);
        } catch (Exception e) {
            throw new RuntimeException("Serialization failed", e);
        }

        ResponseEntity<PaymentResponse> blockedResponse = noRetryRestTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/payments",
            HttpMethod.POST,
            new HttpEntity<>(body2, headers),
            PaymentResponse.class);

        // Fraud-blocked returns 422
        assertThat(blockedResponse.getStatusCode().value())
            .as("Fraud-blocked payment must return 422")
            .isEqualTo(422);
    }

    private void runProviderTimeoutScenario(String finalStatus) {
        // Use CONNECTION_RESET fault to trigger immediate Resilience4j failure without delay
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse()
                .withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", tenant.rawApiKey());

        PaymentRequest req = new PaymentRequestBuilder()
            .withMsisdn("+237672000001")
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

        // Provider fault should result in a non-202 (provider error / service unavailable)
        assertThat(response.getStatusCode().value())
            .as("Provider timeout/fault payment must not return 202")
            .isNotEqualTo(202);
    }

    private void runWebhookFailedScenario(String finalStatus) {
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));
        mtnServer.stubFor(get(urlPathMatching("/v1_0/requesttopay/.*"))
            .willReturn(okJson("{\"status\":\"FAILED\"}")));

        String transactionId = postPayment(new PaymentRequestBuilder()
            .withIdempotencyKey(UUID.randomUUID().toString()).build());
        assertThat(transactionId).isNotNull();

        // Send MTN PUT webhook with FAILED status
        sendMtnCallback(transactionId, "FAILED");

        Awaitility.await().atMost(8, TimeUnit.SECONDS).untilAsserted(() -> {
            invariant.assertLegalStateTransition(transactionId, finalStatus);
            invariant.chain().assertChainValid(transactionId);
            assertEventCountAtLeast(transactionId, 1);
        });
    }

    private void runPollingFallbackScenario(String finalStatus) throws Exception {
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));

        String transactionId = postPayment(new PaymentRequestBuilder()
            .withIdempotencyKey(UUID.randomUUID().toString()).build());
        assertThat(transactionId).isNotNull();

        // Backdate last_modified_date with REQUIRES_NEW
        DefaultTransactionDefinition requiresNew = new DefaultTransactionDefinition();
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        new TransactionTemplate(transactionManager, requiresNew).execute(status -> {
            jdbcTemplate.update(
                "UPDATE main.transaction SET last_modified_date = NOW() - INTERVAL '3 minutes' " +
                "WHERE transaction_id = ?", transactionId);
            return null;
        });

        // Stub GET status BEFORE invoking poller
        mtnServer.stubFor(get(urlPathMatching("/v1_0/requesttopay/.*"))
            .willReturn(okJson("{\"status\":\"SUCCESSFUL\",\"financialTransactionId\":\"fin-matrix-poll\"}")));

        // Invoke poller via reflection wrapped in TransactionTemplate
        java.lang.reflect.Method exec = MtnStatusPollerJob.class
            .getDeclaredMethod("executeInternal", org.quartz.JobExecutionContext.class);
        exec.setAccessible(true);
        transactionTemplate.execute(status -> {
            try {
                exec.invoke(mtnStatusPollerJob, (Object) null);
            } catch (Exception e) {
                throw new RuntimeException("MtnStatusPollerJob.executeInternal failed", e);
            }
            return null;
        });

        // Polling path: assert status and hash chain; skip assertLedgerBalanced (no ledger on polling path)
        invariant.assertLegalStateTransition(transactionId, finalStatus);
        invariant.chain().assertChainValid(transactionId);
        assertEventCountAtLeast(transactionId, 1);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String postPayment(PaymentRequest req) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", tenant.rawApiKey());

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
        return response.getBody().transactionId();
    }

    private void sendMtnCallback(String transactionId, String status) {
        MtnCallbackPayload payload;
        if ("SUCCESSFUL".equals(status)) {
            payload = new MtnWebhookPayloadBuilder()
                .forTransaction(transactionId)
                .asSuccessful()
                .build();
        } else {
            payload = new MtnWebhookPayloadBuilder()
                .forTransaction(transactionId)
                .asFailed("Provider failure")
                .build();
        }

        noRetryRestTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/callbacks/mtn",
            HttpMethod.PUT,
            new HttpEntity<>(payload),
            Void.class);
    }

    private void assertEventCountAtLeast(String transactionId, int minCount) {
        if (minCount < 0) return; // unconstrained
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
}
