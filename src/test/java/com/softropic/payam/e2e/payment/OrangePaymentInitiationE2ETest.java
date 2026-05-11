package com.softropic.payam.e2e.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.payam.e2e.AbstractWebhookFlowTest;
import com.softropic.payam.e2e.PlatformConfigInitializer;
import com.softropic.payam.e2e.builder.DeterministicUuidFactory;
import com.softropic.payam.e2e.builder.PaymentRequestBuilder;
import com.softropic.payam.e2e.builder.TenantBuilder;
import com.softropic.payam.e2e.verify.CacheVerifier;
import com.softropic.payam.e2e.verify.InvariantVerifier;
import com.softropic.payam.payment.core.contract.PaymentRequest;
import com.softropic.payam.payment.core.contract.PaymentResponse;
import com.softropic.payam.platform.tenant.repo.TenantRepository;
import com.softropic.payam.platform.tenant.service.TenantService;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FLOWS-PAY-02: Orange webhook happy path with WAT timestamp verification.
 *
 * <p>Drives a full Orange payment lifecycle from POST /v1/payments → 202 → inbound POST callback
 * (correlated by payToken, not transactionId) → double-check → SUCCESS.
 *
 * <p>Key differences from MTN (pitfalls 7 and 8):
 * - Must call PaymentRequestBuilder.forOrange() to route via the Orange MSISDN prefix
 * - Callback correlation uses providerRef (payToken) from the 202 response, not transactionId
 * - Orange callback is POST, not PUT
 *
 * <p>Extends {@link AbstractWebhookFlowTest} — phase order:
 * setupPreconditions → executeFlow → dispatchInboundWebhook → verifyDoubleCheckTriggered
 * → verifyTransactionState.
 */
public class OrangePaymentInitiationE2ETest extends AbstractWebhookFlowTest {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformConfigInitializer platformConfigInitializer;

    private TenantBuilder.CreatedTenant tenant;
    private String transactionId;
    private String idempotencyKey;
    private String providerRef;
    private InvariantVerifier invariant;
    private DeterministicUuidFactory uuidFactory;

    @Override
    protected void setupPreconditions() {
        platformConfigInitializer.initOrange();

        // Stub Orange provider endpoints (token already stubbed by stubTokenEndpoints() in base)
        orangeServer.stubFor(get(urlPathEqualTo("/infos/subscriber"))
            .willReturn(okJson("{\"status\":\"ACTIF\",\"message\":\"OK\"}")));
        orangeServer.stubFor(post(urlPathEqualTo("/mp/init"))
            .willReturn(okJson("{\"data\":{\"payToken\":\"tok-orange-test-001\"},\"message\":\"OK\"}")));
        orangeServer.stubFor(post(urlPathMatching("/mp/pay"))
            .willReturn(okJson("{\"status\":\"SUCCESS\",\"message\":\"OK\"}")));
        // Double-check GET status — Orange spells SUCCESSFULL with double L
        orangeServer.stubFor(get(urlPathMatching("/mp/paymentstatus/.*"))
            .willReturn(okJson("{\"status\":\"SUCCESSFULL\",\"message\":\"OK\"}")));

        tenant = new TenantBuilder()
            .withName("Orange-E2E-Tenant")
            .create(tenantService, tenantRepository);

        uuidFactory = new DeterministicUuidFactory(0xBEEFEL);
        invariant = new InvariantVerifier(jdbcTemplate, redis, mtnServer, orangeServer);
    }

    @AfterEach
    void tearDown() {
        platformConfigInitializer.clear();
    }

    @Override
    protected void executeFlow() {
        // forOrange() switches to ORANGE_MSISDN = "237690000002" — routes to Orange adapter (pitfall 7)
        PaymentRequest req = new PaymentRequestBuilder()
            .forOrange()
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

        ResponseEntity<PaymentResponse> response = new org.springframework.web.client.RestTemplate()
            .exchange(
                "http://localhost:" + serverPort + "/v1/payments",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                PaymentResponse.class);

        assertThat(response.getStatusCode().value())
            .as("POST /v1/payments must return 202 Accepted")
            .isEqualTo(202);

        assertThat(response.getBody()).isNotNull();
        transactionId = response.getBody().transactionId();
        // providerRef for Orange = payToken — used for callback correlation (pitfall 8)
        providerRef = response.getBody().providerRef();
        assertThat(transactionId).as("transactionId must be non-null in 202 response").isNotNull();
        assertThat(providerRef).as("providerRef (payToken) must be non-null for Orange 202 response").isNotNull();
    }

    @Override
    protected void dispatchInboundWebhook() {
        // Orange callback correlation is by payToken (providerRef), NOT transactionId (pitfall 8)
        // Build raw JSON matching OrangeWebhookPayload @JsonProperty field names.
        // OrangeWebhookPayload has a computed getCreatetimeAsInstant() getter that breaks default
        // Jackson serialization (requires JavaTimeModule) — build JSON string directly instead.
        // OrangeCallbackController uses @JsonIgnoreProperties(ignoreUnknown=true) on the receiving side.
        // createtime format: OrangeTimeUtil.ORANGE_FMT = "yyyy-MM-dd'T'HH:mm:ss" (T separator).
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        String createtime = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        String body = String.format(
            "{\"payToken\":\"%s\",\"notif_token\":\"%s\",\"status\":\"SUCCESS\",\"txnid\":\"%s\"," +
            "\"msisdn\":\"237653000001\",\"amount\":\"1000\",\"createtime\":\"%s\"}",
            providerRef,
            java.util.UUID.randomUUID(),
            java.util.UUID.randomUUID(),
            createtime);

        // Orange uses POST (pitfall 4 inverse — MTN is PUT, Orange is POST)
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        new org.springframework.web.client.RestTemplate()
            .exchange(
                "http://localhost:" + serverPort + "/v1/callbacks/orange",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Void.class);
    }

    @Override
    protected void verifyDoubleCheckTriggered() {
        // PROVIDER_SUCCESS or PROVIDER_FAILED event signals the double-check fired
        invariant.assertWebhookDoubleCheckFired(transactionId);
    }

    @Override
    protected void verifyTransactionState() {
        // WebhookDoubleCheckHandler fires via @TransactionalEventListener(AFTER_COMMIT) —
        // the POST response returns before the commit+listener cycle completes (pitfall 1).
        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            invariant.assertAll(transactionId, tenant.tenantId(), idempotencyKey, "SUCCESS");
            invariant.provider().assertOrangeCallCount("/mp/pay", 1);
            invariant.events().assertEventPresent(transactionId, "PROVIDER_SUCCESS");
            invariant.assertFraudEvaluatedBeforeProviderCall(transactionId);
            new CacheVerifier(redis).assertIdempotencyKeyPresent(tenant.tenantId(), idempotencyKey);
        });
    }
}
