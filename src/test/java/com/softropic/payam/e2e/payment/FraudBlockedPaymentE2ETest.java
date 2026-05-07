package com.softropic.payam.e2e.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.payam.e2e.AbstractFailureFlowTest;
import com.softropic.payam.e2e.builder.DeterministicUuidFactory;
import com.softropic.payam.e2e.builder.PaymentRequestBuilder;
import com.softropic.payam.e2e.builder.TenantBuilder;
import com.softropic.payam.fraud.service.FraudRuleCache;
import com.softropic.payam.payment.contract.PaymentRequest;
import com.softropic.payam.payment.contract.PaymentResponse;
import com.softropic.payam.platform.tenant.repo.TenantRepository;
import com.softropic.payam.platform.tenant.service.TenantService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FLOWS-PAY-06: Fraud-blocked payment path.
 *
 * <p>Verifies that when MSISDN_VELOCITY threshold = 1, the SECOND payment request to
 * the same MSISDN is blocked by fraud evaluation with a 422 FRAUD_BLOCKED response,
 * zero additional provider calls, and FAILED transaction status for the blocked request.
 *
 * <p>Extends {@link AbstractFailureFlowTest} — phase order:
 * setupPreconditions → injectFault → executeFlow → verifyFailureHandled.
 */
public class FraudBlockedPaymentE2ETest extends AbstractFailureFlowTest {

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
    private DeterministicUuidFactory uuidFactory;
    private ResponseEntity<PaymentResponse> response;

    @Override
    protected void setupPreconditions() {
        // Stub MTN account holder and requestToPay.
        // The FIRST request must pass fraud evaluation and reach the provider.
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));

        tenant = new TenantBuilder()
            .withName("Fraud-Test-Tenant")
            .create(tenantService, tenantRepository);

        uuidFactory = new DeterministicUuidFactory(0xDEADBL);

        // Seed fraud rules — dev profile create-drop wipes Flyway seed data at context startup.
        // Without these rows, fraudRuleCache loads empty and all payments are allowed (fail-open).
        transactionTemplate.execute(status -> {
            seedFraudRule(1L, "IP_VELOCITY",      40, 10, 60,   true);
            seedFraudRule(2L, "MSISDN_VELOCITY",  35, 5,  60,   true);
            seedFraudRule(3L, "APP_VELOCITY",      25, 20, 60,  true);
            seedFraudRule(4L, "MSISDN_HOUSEHOLD", 15, 8,  3600, true);
            seedFraudRule(5L, "BLOCK_THRESHOLD",   0, 70, 0,    true);
            return null;
        });
        fraudRuleCache.refreshRules();
    }

    private void seedFraudRule(long id, String signalName, int weight, int threshold,
                               int windowSeconds, boolean enabled) {
        // status column is NOT NULL (AbstractAuditingEntity); use 'ACTIVE' as the standard value.
        // Hibernate create-drop recreates fraud_rule without the Flyway DEFAULT or version column.
        jdbcTemplate.update(
            "INSERT INTO main.fraud_rule "
            + "(id, status, signal_name, weight, threshold, window_seconds, enabled, description) "
            + "VALUES (?, 'ACTIVE', ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (id) DO UPDATE SET threshold = EXCLUDED.threshold",
            id, signalName, weight, threshold, windowSeconds, enabled, signalName + " test rule");
    }

    @Override
    protected void injectFault() {
        // Lower MSISDN_VELOCITY threshold to 1 so the first request to an MSISDN consumes the
        // token and the second request to the SAME MSISDN is blocked by fraud evaluation.
        // This matches the approach used in FraudEngineIT.velocityBlockReturns422 — known to work.
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "UPDATE main.fraud_rule SET threshold = 1 WHERE signal_name = 'MSISDN_VELOCITY'");
            return null;
        });
        // MUST call refreshRules() — the DB update alone has no effect on FraudScoringService
        // which reads from the in-memory cache, not the DB.
        fraudRuleCache.refreshRules();
    }

    @Override
    protected void executeFlow() {
        // Use +237672000001 (national prefix "67" = MTN via hardcoded fallback).
        // The default MTN_MSISDN constant in PaymentRequestBuilder uses prefix 69 (ORANGE).
        final String msisdn = "+237672000001";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", tenant.rawApiKey());

        // Use a no-op error handler so 4xx/5xx responses are returned as ResponseEntity
        // instead of being thrown as exceptions. This lets verifyFailureHandled() assert
        // the exact status code and error body.
        RestTemplate noThrowRestTemplate = new RestTemplate(new SimpleClientHttpRequestFactory());
        noThrowRestTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(HttpStatusCode statusCode) { return false; }
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException { return false; }
        });

        // Request 1: allowed — MSISDN_VELOCITY bucket has capacity=1, first request consumes it.
        // Use a unique idempotency key so it's not deduplicated.
        PaymentRequest req1 = new PaymentRequestBuilder()
            .withMsisdn(msisdn)
            .withDeterministicIdempotencyKey(uuidFactory)
            .build();
        String body1;
        try {
            body1 = new ObjectMapper().writeValueAsString(req1);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize PaymentRequest (request 1)", e);
        }
        ResponseEntity<PaymentResponse> firstResponse = noThrowRestTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/payments",
            HttpMethod.POST,
            new HttpEntity<>(body1, headers),
            PaymentResponse.class);
        // First request must succeed — if it doesn't, the test setup is wrong
        if (firstResponse.getStatusCode().value() != 202) {
            throw new AssertionError("Expected first request to return 202 but got "
                + firstResponse.getStatusCode().value()
                + " — MSISDN_VELOCITY threshold may not be applied correctly");
        }

        // Request 2 (same MSISDN, different idempotency key): blocked — velocity bucket is exhausted.
        PaymentRequest req2 = new PaymentRequestBuilder()
            .withMsisdn(msisdn)
            .withDeterministicIdempotencyKey(uuidFactory)
            .build();
        String body2;
        try {
            body2 = new ObjectMapper().writeValueAsString(req2);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize PaymentRequest (request 2)", e);
        }
        response = noThrowRestTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/payments",
            HttpMethod.POST,
            new HttpEntity<>(body2, headers),
            PaymentResponse.class);
    }

    @Override
    protected void verifyFailureHandled() {
        // HTTP 422 must be returned for fraud-blocked payment
        assertThat(response.getStatusCode().value())
            .as("Fraud-blocked payment must return HTTP 422")
            .isEqualTo(422);

        // Response body must contain FRAUD_BLOCKED error code
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode())
            .as("Error code must be FRAUD_BLOCKED")
            .isEqualTo("FRAUD_BLOCKED");

        // Provider call count — exactly ONE provider call (from the first, allowed request).
        // The second (blocked) request must NOT trigger another provider call.
        // Fraud evaluation fires before provider dispatch; the blocked request adds zero provider calls.
        mtnServer.verify(1, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
            com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo("/v1_0/requesttopay")));

        // Transaction status check — PaymentOrchestrator creates the transaction row BEFORE
        // fraud evaluation (initiate → then fraud check). When fraud blocks, applyFailed() is
        // called and the row is updated to tx_status = 'FAILED'. Assert this via the transactionId
        // from the 422 response body (fraud-blocked responses DO include a transactionId).
        String transactionId = response.getBody().transactionId();
        assertThat(transactionId)
            .as("Fraud-blocked 422 response must include the transactionId created before fraud check")
            .isNotNull();

        String txStatus = jdbcTemplate.queryForObject(
            "SELECT tx_status FROM main.transaction WHERE transaction_id = ? AND tenant_id = ?",
            String.class, transactionId, tenant.tenantId());
        assertThat(txStatus)
            .as("Transaction row must have tx_status=FAILED after fraud block (transactionId=%s)", transactionId)
            .isEqualTo("FAILED");

        // Fraud evaluation evidence: the 422 FRAUD_BLOCKED response itself is the evidence
        // that fraud evaluation occurred. Event log will have a FRAUD_CHECK_BLOCKED entry but
        // there is no PAYMENT_INITIATED event (provider was not reached).
    }
}
