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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

/**
 * FLOWS-HOOK-01: MTN inbound webhook double-check happy path.
 *
 * <p>Seeds a PROCESSING MTN transaction directly via JDBC (no payment API call),
 * dispatches an inbound PUT callback, waits for the double-check handler to fire
 * via {@code @TransactionalEventListener(AFTER_COMMIT)}, and asserts that the
 * transaction reaches SUCCESS with a balanced ledger and PROVIDER_SUCCESS event.
 *
 * <p>Extends {@link AbstractWebhookFlowTest} — phase order:
 * setupPreconditions → executeFlow → dispatchInboundWebhook → verifyDoubleCheckTriggered
 * → verifyTransactionState.
 */
public class MtnWebhookDoubleCheckE2ETest extends AbstractWebhookFlowTest {

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
        // CRITICAL: MTN uses "SUCCESSFUL" (single-L). "SUCCESSFULL" (double-L) is Orange spelling
        // and MtnStatusMapper.toInternal() will not recognise it.
        mtnServer.stubFor(get(urlPathMatching("/v1_0/requesttopay/.*"))
            .willReturn(okJson("{\"status\":\"SUCCESSFUL\",\"financialTransactionId\":\"fin-mtn-hook-001\"}")));

        referenceId = UUID.randomUUID().toString();
        tenant = new TenantBuilder()
            .withName("MTN-Hook-Tenant")
            .create(tenantService, tenantRepository);
        invariant = new InvariantVerifier(jdbcTemplate, redis, mtnServer, orangeServer);
    }

    @Override
    protected void executeFlow() {
        // Insert a PROCESSING MTN transaction directly via JDBC, bypassing the payment API.
        // provider_ref stores referenceId — the UUID the double-check handler passes to
        // GET /v1_0/requesttopay/{referenceId}, so the stub path and this value must match.
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
        // MtnWebhookPayloadBuilder.forTransaction(transactionId) sets externalId = transactionId.
        // MtnMoMoPort.processCallback() uses externalId for the dedup key:
        // "webhook:mtn:" + externalId + ":" + status
        MtnCallbackPayload payload = new MtnWebhookPayloadBuilder()
            .forTransaction(transactionId)
            .asSuccessful()
            .build();

        // CRITICAL: MTN callback controller is @PutMapping — a POST returns 405 Method Not Allowed.
        new RestTemplate().exchange(
            "http://localhost:" + serverPort + "/v1/callbacks/mtn",
            HttpMethod.PUT,
            new HttpEntity<>(payload),
            Void.class);
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
            mtnServer.verify(moreThanOrExactly(1), getRequestedFor(urlPathMatching("/v1_0/requesttopay/.*")));
        });
    }
}
