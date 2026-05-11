package com.softropic.payam.e2e.webhook;

import com.softropic.payam.e2e.AbstractWebhookFlowTest;
import com.softropic.payam.e2e.builder.MtnWebhookPayloadBuilder;
import com.softropic.payam.e2e.builder.TenantBuilder;
import com.softropic.payam.e2e.verify.InvariantVerifier;
import com.softropic.payam.payment.provider.mtn.contract.MtnCallbackPayload;
import com.softropic.payam.platform.tenant.repo.TenantRepository;
import com.softropic.payam.platform.tenant.service.TenantService;

import org.awaitility.Awaitility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FLOWS-HOOK-06: MTN PUT accepted and processed; POST returns 405 Method Not Allowed.
 *
 * <p>Verifies that {@code MtnCallbackController} accepts PUT (processes to SUCCESS) and
 * rejects POST (returns 405). Both assertions are in the same test execution since they
 * operate on the same transaction — the POST is sent first as a negative assertion,
 * then the PUT is sent to drive the actual transition.
 *
 * <p>Extends {@link AbstractWebhookFlowTest} — phase order:
 * setupPreconditions → executeFlow → dispatchInboundWebhook → verifyDoubleCheckTriggered
 * → verifyTransactionState.
 */
public class MtnPutCallbackAcceptanceE2ETest extends AbstractWebhookFlowTest {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private TenantBuilder.CreatedTenant tenant;
    private String transactionId;
    private String referenceId;
    private InvariantVerifier invariant;

    @Override
    protected void setupPreconditions() {
        // Stub the MTN double-check status endpoint.
        // CRITICAL: MTN uses "SUCCESSFUL" (single-L), not "SUCCESSFULL".
        mtnServer.stubFor(get(urlPathMatching("/v1_0/requesttopay/.*"))
            .willReturn(okJson("{\"status\":\"SUCCESSFUL\",\"financialTransactionId\":\"fin-mtn-put-001\"}")));

        referenceId = UUID.randomUUID().toString();
        tenant = new TenantBuilder()
            .withName("MTN-Put-Tenant")
            .create(tenantService, tenantRepository);
        invariant = new InvariantVerifier(jdbcTemplate, redis, mtnServer, orangeServer);
    }

    @Override
    protected void executeFlow() {
        // Insert a PROCESSING MTN transaction directly via JDBC.
        transactionId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        long id = System.nanoTime() & Long.MAX_VALUE;
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.transaction " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, " +
                "transaction_id, trace_id, tenant_id, tx_status, status, provider, amount, currency, provider_ref) " +
                "VALUES (?, 'TEST', NOW(), 'TEST', NOW(), ?, ?, ?, 'PROCESSING', 'ACTIVE', 'MTN', 100, 'XAF', ?)",
                id, transactionId, traceId, tenant.tenantId(), referenceId);
            return null;
        });
    }

    @Override
    protected void dispatchInboundWebhook() {
        MtnCallbackPayload payload = new MtnWebhookPayloadBuilder()
            .forTransaction(transactionId)
            .asSuccessful()
            .build();

        // Use a no-error-handler RestTemplate so we can assert the 405 status directly
        // without Spring throwing an exception on non-2xx responses.
        RestTemplate noErrorRestTemplate = new RestTemplate();
        noErrorRestTemplate.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response)
                    throws java.io.IOException {
                return false; // never throw — let the test assert the status
            }
        });

        // Negative assertion first: POST to the MTN callback URL returns 405.
        // MtnCallbackController is @PutMapping — no @PostMapping exists.
        // ApiAdvice.methodNotSupportedHandler maps HttpRequestMethodNotSupportedException to 405.
        ResponseEntity<Void> postResponse = noErrorRestTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/callbacks/mtn",
            HttpMethod.POST,
            new HttpEntity<>(payload),
            Void.class);
        assertThat(postResponse.getStatusCode().value())
            .as("POST to MTN callback URL must return 405 Method Not Allowed")
            .isEqualTo(405);

        // Positive assertion: PUT returns 200 and triggers the double-check handler.
        ResponseEntity<Void> putResponse = new RestTemplate().exchange(
            "http://localhost:" + serverPort + "/v1/callbacks/mtn",
            HttpMethod.PUT,
            new HttpEntity<>(payload),
            Void.class);
        assertThat(putResponse.getStatusCode().value())
            .as("PUT to MTN callback URL must return 200 OK")
            .isEqualTo(200);
    }

    @Override
    protected void verifyDoubleCheckTriggered() {
        // Asserts PROVIDER_SUCCESS or PROVIDER_FAILED event was appended by the double-check handler.
        invariant.assertWebhookDoubleCheckFired(transactionId);
    }

    @Override
    protected void verifyTransactionState() {
        // WebhookDoubleCheckHandler fires via @TransactionalEventListener(AFTER_COMMIT) —
        // wrap assertions in Awaitility because the PUT response returns before the
        // commit+listener cycle completes.
        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            invariant.assertLegalStateTransition(transactionId, "SUCCESS");
            invariant.assertLedgerBalanced(transactionId);
            invariant.events().assertEventPresent(transactionId, "PROVIDER_SUCCESS");
        });
    }
}
