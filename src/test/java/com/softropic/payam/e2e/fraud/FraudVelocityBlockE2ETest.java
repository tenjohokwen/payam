package com.softropic.payam.e2e.fraud;

import com.softropic.payam.e2e.AbstractFailureFlowTest;
import com.softropic.payam.e2e.builder.PaymentRequestBuilder;
import com.softropic.payam.e2e.builder.TenantBuilder;
import com.softropic.payam.e2e.verify.InvariantVerifier;
import com.softropic.payam.fraud.service.FraudRuleCache;
import com.softropic.payam.payment.contract.PaymentRequest;
import com.softropic.payam.payment.contract.PaymentResponse;
import com.softropic.payam.tenant.repo.TenantRepository;
import com.softropic.payam.tenant.service.TenantService;

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
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FLOWS-FRAUD-01, FLOWS-FRAUD-02, FLOWS-FRAUD-03: Fraud velocity block E2E test.
 *
 * <p>Proves the fraud engine blocks payments before any provider HTTP call is made when the
 * MSISDN velocity threshold is exceeded, and that the allowed path correctly records
 * PAYMENT_INITIATED before provider communication.
 *
 * <ul>
 *   <li>FLOWS-FRAUD-01: Second MTN request from the same MSISDN returns 422 FRAUD_BLOCKED;
 *       WireMock POST count to /v1_0/requesttopay = 1 (blocked path generates zero provider calls)
 *   <li>FLOWS-FRAUD-02: First (allowed) MTN request returns 202; PAYMENT_INITIATED event present
 *       in payment_event_log for allowedTransactionId
 *   <li>FLOWS-FRAUD-03: invariantVerifier.assertFraudEvaluatedBeforeProviderCall(allowedTransactionId)
 *       passes without AssertionError
 * </ul>
 *
 * <p>Phase order (AbstractFailureFlowTest contract):
 * setupPreconditions → injectFault → executeFlow → verifyFailureHandled
 */
public class FraudVelocityBlockE2ETest extends AbstractFailureFlowTest {

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
    private InvariantVerifier invariant;

    /** transactionId from the FIRST (allowed) request. */
    private String allowedTransactionId;

    /** Full response from the SECOND (blocked) request for assertion in verifyFailureHandled(). */
    private ResponseEntity<PaymentResponse> blockedResponse;

    // -------------------------------------------------------------------------
    // AbstractFailureFlowTest phases
    // -------------------------------------------------------------------------

    @Override
    protected void setupPreconditions() {
        // Stub MTN account holder validation and requestToPay for the FIRST (allowed) request.
        // The blocked (second) request must never reach these stubs.
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{\"name\":\"Test User\"}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));

        tenant = new TenantBuilder()
            .withName("Fraud-Velocity-E2E")
            .create(tenantService, tenantRepository);

        invariant = new InvariantVerifier(jdbcTemplate, redis, mtnServer, orangeServer);

        // Seed all 5 fraud rules — dev profile create-drop wipes Flyway seed data.
        // Without these rows fraudRuleCache loads empty and all payments are allowed (fail-open).
        // Use ON CONFLICT (id) DO UPDATE so re-runs are idempotent.
        transactionTemplate.execute(status -> {
            seedFraudRule(1L, "IP_VELOCITY",      40, 10,   60,   true);
            seedFraudRule(2L, "MSISDN_VELOCITY",  35, 5,    60,   true);
            seedFraudRule(3L, "APP_VELOCITY",     25, 20,   60,   true);
            seedFraudRule(4L, "MSISDN_HOUSEHOLD", 15, 8,    3600, true);
            seedFraudRule(5L, "BLOCK_THRESHOLD",   0, 70,   0,    true);
            return null;
        });
        // MUST call refreshRules() so FraudScoringService picks up the seeded rows from cache.
        fraudRuleCache.refreshRules();
    }

    @Override
    protected void injectFault() {
        // Lower MSISDN_VELOCITY threshold from 5 to 1 so the first request consumes the token
        // bucket and the second request from the same MSISDN is blocked by fraud evaluation.
        // Wrap in transactionTemplate.execute() to commit the update before refreshRules() reads it.
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "UPDATE main.fraud_rule SET threshold = 1 WHERE signal_name = 'MSISDN_VELOCITY'");
            return null;
        });
        // MUST be outside the lambda — refreshRules() must read the committed row.
        fraudRuleCache.refreshRules();
    }

    @Override
    protected void executeFlow() {
        // Use +237672000001 (national prefix "67" = MTN via V16 migration msisdn_prefix_route seed).
        final String msisdn = "+237672000001";

        // Construct no-op error handler so 4xx/5xx are returned as ResponseEntity instead of
        // being thrown as HttpClientErrorException. Required to assert exact status + body.
        RestTemplate noRetryRestTemplate = new RestTemplate(new SimpleClientHttpRequestFactory());
        noRetryRestTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(HttpStatusCode statusCode) { return false; }
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException { return false; }
        });

        HttpHeaders apiKeyHeaders = new HttpHeaders();
        apiKeyHeaders.set("X-Api-Key", tenant.rawApiKey());
        apiKeyHeaders.setContentType(MediaType.APPLICATION_JSON);

        // Request 1 (ALLOWED): MSISDN_VELOCITY bucket starts full (threshold=1 after injectFault);
        // first request consumes the single token and proceeds to the provider.
        PaymentRequest allowedRequest = new PaymentRequestBuilder()
            .withMsisdn(msisdn)
            .withIdempotencyKey(UUID.randomUUID().toString())
            .build();

        ResponseEntity<PaymentResponse> allowedResponse = noRetryRestTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/payments",
            HttpMethod.POST,
            new HttpEntity<>(allowedRequest, apiKeyHeaders),
            PaymentResponse.class);

        // First request must succeed — if it doesn't, the test setup is broken.
        if (allowedResponse.getStatusCode().value() != 202) {
            throw new AssertionError(
                "Expected first (allowed) request to return 202 but got "
                + allowedResponse.getStatusCode().value()
                + " — check MSISDN_VELOCITY threshold and fraudRuleCache.refreshRules() call");
        }

        assertThat(allowedResponse.getBody()).isNotNull();
        allowedTransactionId = allowedResponse.getBody().transactionId();
        assertThat(allowedTransactionId)
            .as("Allowed (first) request must return a non-null transactionId")
            .isNotNull();

        // Request 2 (BLOCKED): same MSISDN, different idempotency key — velocity bucket is now
        // exhausted (threshold=1 consumed by request 1); fraud evaluation blocks before provider.
        PaymentRequest blockedRequest = new PaymentRequestBuilder()
            .withMsisdn(msisdn)
            .withIdempotencyKey(UUID.randomUUID().toString())
            .build();

        blockedResponse = noRetryRestTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/payments",
            HttpMethod.POST,
            new HttpEntity<>(blockedRequest, apiKeyHeaders),
            PaymentResponse.class);
    }

    @Override
    protected void verifyFailureHandled() {
        // FLOWS-FRAUD-01: Blocked request must return 422 with FRAUD_BLOCKED error code.
        assertThat(blockedResponse.getStatusCode().value())
            .as("Fraud-blocked (second) request must return HTTP 422")
            .isEqualTo(422);

        assertThat(blockedResponse.getBody()).isNotNull();
        assertThat(blockedResponse.getBody().errorCode())
            .as("Error code must be FRAUD_BLOCKED")
            .isEqualTo("FRAUD_BLOCKED");

        // FLOWS-FRAUD-01: Exactly 1 provider POST — the allowed request only; blocked adds none.
        mtnServer.verify(exactly(1), postRequestedFor(urlEqualTo("/v1_0/requesttopay")));

        // FLOWS-FRAUD-02: PAYMENT_INITIATED event must exist for the allowed (first) transaction.
        // This event is written by MtnMoMoPort/OrangeMoneyPort after the provider accepted the
        // request — only reachable if fraud evaluation completed without blocking.
        Integer initiatedEventCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.payment_event_log "
            + "WHERE transaction_id = ? AND event_type = 'PAYMENT_INITIATED'",
            Integer.class, allowedTransactionId);
        assertThat(initiatedEventCount)
            .as("PAYMENT_INITIATED event must exist for allowedTransactionId=%s (FLOWS-FRAUD-02)",
                allowedTransactionId)
            .isEqualTo(1);

        // FLOWS-FRAUD-03: assertFraudEvaluatedBeforeProviderCall passes on the allowed transaction.
        invariant.assertFraudEvaluatedBeforeProviderCall(allowedTransactionId);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void seedFraudRule(long id, String signalName, int weight, int threshold,
                               int windowSeconds, boolean enabled) {
        jdbcTemplate.update(
            "INSERT INTO main.fraud_rule "
            + "(id, status, signal_name, weight, threshold, window_seconds, enabled, description) "
            + "VALUES (?, 'ACTIVE', ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (id) DO UPDATE SET threshold = EXCLUDED.threshold",
            id, signalName, weight, threshold, windowSeconds, enabled, signalName + " test rule");
    }
}
