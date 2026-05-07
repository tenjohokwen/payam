package com.softropic.payam.e2e.webhook;

import com.softropic.payam.e2e.AbstractWebhookFlowTest;
import com.softropic.payam.e2e.builder.TenantBuilder;
import com.softropic.payam.e2e.verify.InvariantVerifier;
import com.softropic.payam.platform.tenant.repo.TenantRepository;
import com.softropic.payam.platform.tenant.service.TenantService;

import org.awaitility.Awaitility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

/**
 * FLOWS-HOOK-02: Orange inbound webhook double-check happy path.
 *
 * <p>Seeds a PROCESSING Orange transaction directly via JDBC (no payment API call),
 * dispatches an inbound POST callback correlated by {@code payToken} (NOT transactionId),
 * waits for the double-check handler to fire via {@code @TransactionalEventListener(AFTER_COMMIT)},
 * and asserts that the transaction reaches SUCCESS with a balanced ledger and PROVIDER_SUCCESS event.
 *
 * <p>Orange correlation uses {@code pay_token} — {@code OrangeMoneyPort.processWebhook()} calls
 * {@code transactionRepository.findByPayToken(payToken)}. The JDBC-inserted row must have
 * {@code pay_token} set, otherwise no event is published and the double-check never fires.
 *
 * <p>Extends {@link AbstractWebhookFlowTest} — phase order:
 * setupPreconditions → executeFlow → dispatchInboundWebhook → verifyDoubleCheckTriggered
 * → verifyTransactionState.
 */
public class OrangeWebhookDoubleCheckE2ETest extends AbstractWebhookFlowTest {

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
    private String payToken;
    private InvariantVerifier invariant;

    @Override
    protected void setupPreconditions() {
        // Stub the Orange double-check status endpoint.
        // CRITICAL: Orange uses "SUCCESSFULL" (double-L). "SUCCESSFUL" (single-L) is MTN spelling
        // and OrangeStatusMapper.toInternal() will not recognise the single-L variant.
        orangeServer.stubFor(get(urlPathMatching("/mp/paymentstatus/.*"))
            .willReturn(okJson("{\"status\":\"SUCCESSFULL\",\"message\":\"OK\"}")));

        payToken = UUID.randomUUID().toString();
        tenant = new TenantBuilder()
            .withName("Orange-Hook-Tenant")
            .create(tenantService, tenantRepository);
        invariant = new InvariantVerifier(jdbcTemplate, redis, mtnServer, orangeServer);
    }

    @Override
    protected void executeFlow() {
        // Insert a PROCESSING Orange transaction directly via JDBC, bypassing the payment API.
        // Orange correlation is by pay_token — OrangeMoneyPort.processWebhook() calls
        // transactionRepository.findByPayToken(payToken). Both provider_ref and pay_token
        // columns are set to payToken.
        transactionId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        long id = System.nanoTime() & Long.MAX_VALUE;
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.transaction " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, " +
                "transaction_id, trace_id, tenant_id, tx_status, status, provider, amount, currency, provider_ref, pay_token) " +
                "VALUES (?, 'TEST', NOW(), 'TEST', NOW(), ?, ?, ?, 'PROCESSING', 'ACTIVE', 'ORANGE', 100, 'XAF', ?, ?)",
                id, transactionId, traceId, tenant.tenantId(), payToken, payToken);
            return null;
        });
    }

    @Override
    protected void dispatchInboundWebhook() {
        // createtime must be in OrangeTimeUtil.ORANGE_FMT = "yyyy-MM-dd'T'HH:mm:ss" (T separator,
        // no timezone offset). A space separator breaks OrangeTimeUtil.parseOrangeTimestamp().
        String createtime = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

        // Orange correlation is by payToken — OrangeCallbackController routes on payToken,
        // not transactionId. The dedup key is "webhook:orange:" + payToken + ":" + createtime.
        String body = String.format(
            "{\"payToken\":\"%s\",\"notif_token\":\"%s\",\"status\":\"SUCCESS\",\"txnid\":\"%s\"," +
            "\"msisdn\":\"237653000001\",\"amount\":\"1000\",\"createtime\":\"%s\"}",
            payToken, UUID.randomUUID(), UUID.randomUUID(), createtime);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Orange callback is POST (not PUT — PUT would silently fail with 405).
        new RestTemplate().exchange(
            "http://localhost:" + serverPort + "/v1/callbacks/orange",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
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
        // wrap assertions in Awaitility because the POST response returns before the
        // commit+listener cycle completes.
        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            invariant.assertLegalStateTransition(transactionId, "SUCCESS");
            invariant.assertLedgerBalanced(transactionId);
            invariant.events().assertEventPresent(transactionId, "PROVIDER_SUCCESS");
            orangeServer.verify(moreThanOrExactly(1), getRequestedFor(urlPathMatching("/mp/paymentstatus/.*")));
        });
    }
}
