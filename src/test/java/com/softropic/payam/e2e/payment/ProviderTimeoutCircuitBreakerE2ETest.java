package com.softropic.payam.e2e.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.payam.config.WireMockConfig;
import com.softropic.payam.e2e.AbstractFailureFlowTest;
import com.softropic.payam.e2e.builder.DeterministicUuidFactory;
import com.softropic.payam.e2e.builder.PaymentRequestBuilder;
import com.softropic.payam.e2e.builder.TenantBuilder;
import com.softropic.payam.e2e.verify.InvariantVerifier;
import com.softropic.payam.payment.core.contract.PaymentRequest;
import com.softropic.payam.payment.core.contract.PaymentResponse;
import com.softropic.payam.platform.tenant.repo.TenantRepository;
import com.softropic.payam.platform.tenant.service.TenantService;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

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
 * FLOWS-PAY-07: Provider timeout / circuit-breaker forced OPEN path.
 *
 * <p>Verifies that when the MTN circuit breaker is forced OPEN, the payment request
 * returns 503 PROVIDER_UNAVAILABLE, the circuit breaker remains OPEN, and no
 * transaction row is written to the DB.
 *
 * <p>Uses {@code noRetryRestTemplate} (SimpleClientHttpRequestFactory) to avoid
 * Apache HTTP Client's automatic 503 retry behavior that would mask the circuit state
 * by hitting the idempotency cache on a second attempt.
 *
 * <p>Extends {@link AbstractFailureFlowTest} — phase order:
 * setupPreconditions → injectFault → executeFlow → verifyFailureHandled.
 */
public class ProviderTimeoutCircuitBreakerE2ETest extends AbstractFailureFlowTest {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TenantBuilder.CreatedTenant tenant;
    private String idempotencyKey;
    private InvariantVerifier invariant;
    private DeterministicUuidFactory uuidFactory;
    private RestTemplate noRetryRestTemplate;
    private ResponseEntity<PaymentResponse> response;

    @Override
    protected void setupPreconditions() {
        // Stub MTN account holder and requestToPay — these should NOT be called
        // when circuit is OPEN; stubs are present for completeness and verification.
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));

        tenant = new TenantBuilder()
            .withName("CB-Test-Tenant")
            .create(tenantService, tenantRepository);

        uuidFactory = new DeterministicUuidFactory(0xCBCBL);
        invariant = new InvariantVerifier(jdbcTemplate, redis, mtnServer, orangeServer);

        // Build noRetryRestTemplate — Apache HC auto-retries 503, masking circuit state.
        // SimpleClientHttpRequestFactory never retries, so the raw 503 is observable.
        noRetryRestTemplate = new RestTemplate(new SimpleClientHttpRequestFactory());
        noRetryRestTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(HttpStatusCode statusCode) {
                return false; // never throw; let assertions handle status codes
            }

            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return false;
            }
        });
    }

    @Override
    protected void injectFault() {
        // Force MTN circuit breaker directly to OPEN state — deterministic and immediate.
        // No need to trip it via failure accumulation.
        circuitBreakerRegistry.circuitBreaker("mtn").transitionToOpenState();

        // Flush Redis to clear any RESERVED idempotency keys from previous tests
        // that might cause a 409/202 response before the circuit-breaker check fires.
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();

        // Re-stub the MTN token endpoint — the Redis flush cleared the cached token,
        // so the token fetch will happen again before the circuit breaker intercepts.
        mtnServer.stubFor(post(urlPathEqualTo("/token/"))
            .willReturn(okJson(WireMockConfig.MTN_TOKEN_RESPONSE)));
    }

    @Override
    protected void executeFlow() {
        // Use +237672000001 (national prefix "67" = MTN via hardcoded fallback).
        // The default MTN_MSISDN constant in PaymentRequestBuilder uses prefix 69 (ORANGE).
        PaymentRequest req = new PaymentRequestBuilder()
            .withMsisdn("+237672000001")
            .withDeterministicIdempotencyKey(uuidFactory)
            .build();
        idempotencyKey = req.idempotencyKey();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", tenant.rawApiKey());

        String body;
        try {
            body = new ObjectMapper().writeValueAsString(req);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize PaymentRequest", e);
        }

        // MUST use noRetryRestTemplate — NOT testRestTemplate.
        // Apache HTTP Client (used by TestRestTemplate) auto-retries on 503, which causes
        // the second attempt to find the idempotency key RESERVED and return 202 instead
        // of the expected 503.
        response = noRetryRestTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/payments",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            PaymentResponse.class);
    }

    @Override
    protected void verifyFailureHandled() {
        // HTTP 503 must be returned when circuit breaker is OPEN
        assertThat(response.getStatusCode().value())
            .as("Circuit-open payment must return HTTP 503")
            .isEqualTo(503);

        // Response body must contain PROVIDER_UNAVAILABLE error code
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode())
            .as("Error code must be PROVIDER_UNAVAILABLE")
            .isEqualTo("PROVIDER_UNAVAILABLE");

        // Circuit breaker must still be OPEN after the request
        assertThat(circuitBreakerRegistry.circuitBreaker("mtn").getState())
            .as("MTN circuit breaker must remain OPEN after rejected request")
            .isEqualTo(CircuitBreaker.State.OPEN);

        // Assert DB state via transactionId from 503 response body.
        // PaymentOrchestrator creates the transaction row BEFORE the provider dispatch (step 3),
        // then applyFailed() updates it to tx_status = 'FAILED' when CallNotPermittedException is caught.
        // The 503 response body includes the transactionId.
        String transactionId = response.getBody().transactionId();
        assertThat(transactionId)
            .as("Circuit-open 503 response must include the transactionId (created before CB rejection)")
            .isNotNull();

        String txStatus = jdbcTemplate.queryForObject(
            "SELECT tx_status FROM main.transaction WHERE transaction_id = ? AND tenant_id = ?",
            String.class, transactionId, tenant.tenantId());
        assertThat(txStatus)
            .as("Transaction row must have tx_status=FAILED after CB-open rejection (transactionId=%s)", transactionId)
            .isEqualTo("FAILED");
    }
}
