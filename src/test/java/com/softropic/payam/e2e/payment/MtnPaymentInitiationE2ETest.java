package com.softropic.payam.e2e.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.payam.e2e.AbstractWebhookFlowTest;
import com.softropic.payam.e2e.builder.DeterministicUuidFactory;
import com.softropic.payam.e2e.builder.MtnWebhookPayloadBuilder;
import com.softropic.payam.e2e.builder.PaymentRequestBuilder;
import com.softropic.payam.e2e.builder.TenantBuilder;
import com.softropic.payam.e2e.verify.CacheVerifier;
import com.softropic.payam.e2e.verify.InvariantVerifier;
import com.softropic.payam.payment.provider.mtn.contract.MtnCallbackPayload;
import com.softropic.payam.payment.core.contract.PaymentRequest;
import com.softropic.payam.payment.core.contract.PaymentResponse;
import com.softropic.payam.platform.tenant.repo.TenantRepository;
import com.softropic.payam.platform.tenant.service.TenantService;

import org.awaitility.Awaitility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FLOWS-PAY-01: MTN webhook happy path.
 *
 * <p>Drives a full payment lifecycle from POST /v1/payments → 202 → inbound PUT callback
 * → double-check → SUCCESS, asserting all domain invariants via InvariantVerifier.
 *
 * <p>Extends {@link AbstractWebhookFlowTest} — phase order:
 * setupPreconditions → executeFlow → dispatchInboundWebhook → verifyDoubleCheckTriggered
 * → verifyTransactionState.
 */
public class MtnPaymentInitiationE2ETest extends AbstractWebhookFlowTest {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TenantBuilder.CreatedTenant tenant;
    private String transactionId;
    private String idempotencyKey;
    private InvariantVerifier invariant;
    private DeterministicUuidFactory uuidFactory;

    @Override
    protected void setupPreconditions() {
        // Stub MTN provider endpoints (token already stubbed by stubTokenEndpoints() in base)
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));
        mtnServer.stubFor(get(urlPathMatching("/v1_0/requesttopay/.*"))
            .willReturn(okJson("{\"status\":\"SUCCESSFUL\",\"financialTransactionId\":\"fin-tx-001\"}")));

        tenant = new TenantBuilder()
            .withName("MTN-E2E-Tenant")
            .create(tenantService, tenantRepository);

        uuidFactory = new DeterministicUuidFactory(0xABCDEL);
        invariant = new InvariantVerifier(jdbcTemplate, redis, mtnServer, orangeServer);
    }

    @Override
    protected void executeFlow() {
        PaymentRequest req = new PaymentRequestBuilder()
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
        assertThat(transactionId).as("transactionId must be non-null in 202 response").isNotNull();
    }

    @Override
    protected void dispatchInboundWebhook() {
        MtnCallbackPayload payload = new MtnWebhookPayloadBuilder()
            .forTransaction(transactionId)
            .asSuccessful()
            .build();

        // MTN uses PUT — a POST returns 405 Method Not Allowed (pitfall 4)
        new org.springframework.web.client.RestTemplate()
            .exchange(
                "http://localhost:" + serverPort + "/v1/callbacks/mtn",
                HttpMethod.PUT,
                new HttpEntity<>(payload),
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
        // the PUT response returns before the commit+listener cycle completes (pitfall 1).
        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            invariant.assertAll(transactionId, tenant.tenantId(), idempotencyKey, "SUCCESS");
            invariant.provider().assertMtnCallCount("/v1_0/requesttopay", 1);
            invariant.events().assertEventPresent(transactionId, "PROVIDER_SUCCESS");
            invariant.assertFraudEvaluatedBeforeProviderCall(transactionId);
            new CacheVerifier(redis).assertIdempotencyKeyPresent(tenant.tenantId(), idempotencyKey);
            new CacheVerifier(redis).assertMtnTokenCached();
        });
    }
}
