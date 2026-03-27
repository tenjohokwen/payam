package com.softropic.payam.e2e.webhook;

import com.softropic.payam.e2e.AbstractPayamE2ETest;
import com.softropic.payam.e2e.builder.MtnWebhookPayloadBuilder;
import com.softropic.payam.e2e.builder.TenantBuilder;
import com.softropic.payam.mtn.contract.MtnCallbackPayload;
import com.softropic.payam.tenant.repo.TenantRepository;
import com.softropic.payam.tenant.service.TenantService;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
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
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FLOWS-HOOK-03: Redis dedup prevents duplicate outbox event for both providers.
 *
 * <p>Extends {@link AbstractPayamE2ETest} directly (not AbstractWebhookFlowTest) because the
 * replay test does not follow the 4-phase template — it sends two callbacks in a single
 * {@code @Test} method without any Redis flush between them. Both calls must be in the same
 * test body: {@code @BeforeEach baseSetUp()} in AbstractPayamE2ETest flushes Redis once before
 * the test starts, but must NOT flush between the two callback dispatches.
 *
 * <p>MTN dedup key: {@code "webhook:mtn:" + externalId + ":" + status}
 * <br>Orange dedup key: {@code "webhook:orange:" + payToken + ":" + createtime}
 */
public class WebhookReplayProtectionE2ETest extends AbstractPayamE2ETest {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void mtnWebhookReplayIsRejected() {
        // Stub the MTN double-check endpoint.
        // CRITICAL: MTN uses "SUCCESSFUL" (single-L), not "SUCCESSFULL".
        mtnServer.stubFor(get(urlPathMatching("/v1_0/requesttopay/.*"))
            .willReturn(okJson("{\"status\":\"SUCCESSFUL\",\"financialTransactionId\":\"fin-replay-001\"}")));

        // Create tenant and seed PROCESSING MTN transaction.
        TenantBuilder.CreatedTenant tenant =
            new TenantBuilder().withName("MTN-Replay-Tenant").create(tenantService, tenantRepository);

        String referenceId = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
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

        // First webhook call — must process and fire double-check.
        MtnCallbackPayload payload = new MtnWebhookPayloadBuilder()
            .forTransaction(transactionId)
            .asSuccessful()
            .build();
        new RestTemplate().exchange(
            "http://localhost:" + serverPort + "/v1/callbacks/mtn",
            HttpMethod.PUT, new HttpEntity<>(payload), Void.class);

        // Wait for the first double-check to complete and Redis dedup key to be written.
        // MTN dedup key: "webhook:mtn:" + externalId + ":" + status
        String dedupKey = "webhook:mtn:" + transactionId + ":SUCCESSFUL";
        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> redis.hasKey(dedupKey));
        assertThat(redis.hasKey(dedupKey)).isTrue();

        // Second webhook call — identical payload. Must be silently accepted (200) but suppressed.
        // DO NOT flush Redis between the two calls — the dedup key must remain set.
        new RestTemplate().exchange(
            "http://localhost:" + serverPort + "/v1/callbacks/mtn",
            HttpMethod.PUT, new HttpEntity<>(payload), Void.class);

        // Verify the PROVIDER_SUCCESS/PROVIDER_FAILED event count has NOT increased.
        // Only one double-check should have fired — the second call was suppressed by the dedup key.
        Integer eventCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.payment_event_log WHERE transaction_id = ? " +
            "AND event_type IN ('PROVIDER_SUCCESS','PROVIDER_FAILED')",
            Integer.class, transactionId);
        assertThat(eventCount)
            .as("Replay protection must suppress duplicate event; expected 1 PROVIDER_* event for transactionId=%s",
                transactionId)
            .isEqualTo(1);
    }

    @Test
    void orangeWebhookReplayIsRejected() {
        // Stub the Orange double-check endpoint.
        // CRITICAL: Orange uses "SUCCESSFULL" (double-L), not "SUCCESSFUL".
        orangeServer.stubFor(get(urlPathMatching("/mp/paymentstatus/.*"))
            .willReturn(okJson("{\"status\":\"SUCCESSFULL\",\"message\":\"OK\"}")));

        // Create tenant and seed PROCESSING Orange transaction.
        TenantBuilder.CreatedTenant tenant =
            new TenantBuilder().withName("Orange-Replay-Tenant").create(tenantService, tenantRepository);

        String payToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
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

        // createtime must be in OrangeTimeUtil.ORANGE_FMT = "yyyy-MM-dd'T'HH:mm:ss" (T separator).
        // Captured for use in dedup key assertion: "webhook:orange:" + payToken + ":" + createtime
        String createtime = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        String body = String.format(
            "{\"payToken\":\"%s\",\"notif_token\":\"%s\",\"status\":\"SUCCESS\",\"txnid\":\"%s\"," +
            "\"msisdn\":\"237653000001\",\"amount\":\"1000\",\"createtime\":\"%s\"}",
            payToken, UUID.randomUUID(), UUID.randomUUID(), createtime);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // First webhook call — must process and fire double-check.
        new RestTemplate().exchange(
            "http://localhost:" + serverPort + "/v1/callbacks/orange",
            HttpMethod.POST, new HttpEntity<>(body, headers), Void.class);

        // Wait for the first double-check to complete and Orange dedup key to be written.
        // Orange dedup key: "webhook:orange:" + payToken + ":" + createtime
        String dedupKey = "webhook:orange:" + payToken + ":" + createtime;
        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> redis.hasKey(dedupKey));
        assertThat(redis.hasKey(dedupKey)).isTrue();

        // Second webhook call — identical body. Must be silently accepted (200) but suppressed.
        // DO NOT flush Redis between the two calls.
        new RestTemplate().exchange(
            "http://localhost:" + serverPort + "/v1/callbacks/orange",
            HttpMethod.POST, new HttpEntity<>(body, headers), Void.class);

        // Verify the PROVIDER_SUCCESS/PROVIDER_FAILED event count has NOT increased.
        Integer eventCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.payment_event_log WHERE transaction_id = ? " +
            "AND event_type IN ('PROVIDER_SUCCESS','PROVIDER_FAILED')",
            Integer.class, transactionId);
        assertThat(eventCount)
            .as("Replay protection must suppress duplicate event; expected 1 PROVIDER_* event for transactionId=%s",
                transactionId)
            .isEqualTo(1);
    }
}
