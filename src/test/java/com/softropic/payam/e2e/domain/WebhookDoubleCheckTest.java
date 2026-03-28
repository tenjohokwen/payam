package com.softropic.payam.e2e.domain;

import com.softropic.payam.e2e.AbstractPayamE2ETest;
import com.softropic.payam.e2e.builder.MtnWebhookPayloadBuilder;
import com.softropic.payam.e2e.builder.TenantBuilder;
import com.softropic.payam.e2e.verify.InvariantVerifier;
import com.softropic.payam.mtn.contract.MtnCallbackPayload;
import com.softropic.payam.tenant.repo.TenantRepository;
import com.softropic.payam.tenant.service.TenantService;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

/**
 * INV-06-TEST: Webhook double-check invariant.
 *
 * Proves that inbound webhook processing always re-queries the provider before any state change.
 * Seeds a PROCESSING transaction directly via JDBC (same pattern as MtnWebhookDoubleCheckE2ETest),
 * dispatches an inbound PUT callback, and asserts a PROVIDER_SUCCESS event was appended to the
 * event log — proving the double-check re-queried the provider.
 */
public class WebhookDoubleCheckTest extends AbstractPayamE2ETest {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void mtnWebhook_requeriesProviderBeforeStateChange() {
        // Stub MTN double-check status endpoint — MTN uses "SUCCESSFUL" (single-L)
        mtnServer.stubFor(get(urlPathMatching("/v1_0/requesttopay/.*"))
            .willReturn(okJson("{\"status\":\"SUCCESSFUL\",\"financialTransactionId\":\"fin-hook-inv-001\"}")));

        TenantBuilder.CreatedTenant tenant = new TenantBuilder()
            .withName("WebhookDoubleCheck-Test")
            .create(tenantService, tenantRepository);

        InvariantVerifier invariant = new InvariantVerifier(jdbcTemplate, redis, mtnServer, orangeServer);

        // Insert a PROCESSING MTN transaction directly via JDBC, bypassing the payment API.
        // provider_ref stores referenceId — used by the double-check handler for GET /v1_0/requesttopay/{referenceId}
        String transactionId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        String referenceId = UUID.randomUUID().toString();
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

        // Dispatch inbound MTN webhook callback
        MtnCallbackPayload payload = new MtnWebhookPayloadBuilder()
            .forTransaction(transactionId)
            .asSuccessful()
            .build();

        // MTN callback controller is @PutMapping
        new RestTemplate().exchange(
            "http://localhost:" + serverPort + "/v1/callbacks/mtn",
            HttpMethod.PUT,
            new HttpEntity<>(payload),
            Void.class);

        // Wait for async @TransactionalEventListener(AFTER_COMMIT) processing
        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            // Assert PROVIDER_SUCCESS or PROVIDER_FAILED event exists, proving double-check fired
            invariant.assertWebhookDoubleCheckFired(transactionId);

            // Assert the provider GET double-check endpoint was called at least once
            invariant.provider().assertMtnGetCallCount("/v1_0/requesttopay/.*", 1);
        });
    }
}
