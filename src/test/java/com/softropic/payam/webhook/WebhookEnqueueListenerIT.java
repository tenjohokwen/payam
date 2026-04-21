package com.softropic.payam.webhook;

import com.softropic.payam.config.TestConfig;
import com.softropic.payam.tenant.contract.ApiKeyEnvironment;
import com.softropic.payam.tenant.service.TenantService;
import com.softropic.payam.transaction.contract.TransactionStatus;
import com.softropic.payam.webhook.contract.WebhookEnqueueRequestedEvent;
import com.softropic.payam.webhook.repo.WebhookDeliveryLog;
import com.softropic.payam.webhook.repo.WebhookDeliveryLogRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * WEBHOOK-02 regression test: enqueue must fire only after the triggering transaction commits,
 * and must NOT fire if the transaction rolls back.
 *
 * Uses an unused webhookUrl so the first inline delivery attempt will fail fast, but the
 * WebhookDeliveryLog row is inserted BEFORE the attempt — we only assert existence/absence
 * of the row, not delivery success.
 */
@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
class WebhookEnqueueListenerIT {

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired WebhookDeliveryLogRepository deliveryLogRepo;
    @Autowired TenantService tenantService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;

    private Long tenantId;

    @BeforeEach
    void setUp() {
        var prov = tenantService.createTenant("wh-enq-listener-it", ApiKeyEnvironment.PROD);
        tenantId = prov.tenant().getId();
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("UPDATE main.tenant SET webhook_url = ? WHERE id = ?",
                "http://localhost:9/unused", tenantId);
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(s -> {
            jdbcTemplate.execute("DELETE FROM main.webhook_delivery_log");
            jdbcTemplate.execute("DELETE FROM main.tenant_api_key");
            jdbcTemplate.execute("DELETE FROM main.tenant");
            return null;
        });
    }

    @Test
    void enqueueFires_whenPublishingTransactionCommits() {
        String txId = "tx-enq-commit-" + System.nanoTime();

        transactionTemplate.execute(status -> {
            eventPublisher.publishEvent(new WebhookEnqueueRequestedEvent(
                txId, tenantId, "PROVIDER_SUCCESS",
                TransactionStatus.SUCCESS, "ext-commit", BigDecimal.ZERO));
            return null; // commit
        });

        // AFTER_COMMIT listener runs in REQUIRES_NEW transaction; poll for the row.
        await().atMost(5, SECONDS).until(() ->
            !deliveryLogRepo.findByTransactionIdOrderByCreatedDateAsc(txId).isEmpty());

        List<WebhookDeliveryLog> logs = deliveryLogRepo.findByTransactionIdOrderByCreatedDateAsc(txId);
        assertThat(logs).hasSizeGreaterThanOrEqualTo(1);
        assertThat(logs.get(0).getTenantId()).isEqualTo(tenantId);
    }

    @Test
    void enqueueDoesNotFire_whenPublishingTransactionRollsBack() throws InterruptedException {
        String txId = "tx-enq-rollback-" + System.nanoTime();

        transactionTemplate.execute(status -> {
            eventPublisher.publishEvent(new WebhookEnqueueRequestedEvent(
                txId, tenantId, "PROVIDER_SUCCESS",
                TransactionStatus.SUCCESS, "ext-rollback", BigDecimal.ZERO));
            status.setRollbackOnly();
            return null;
        });

        // Give any (incorrect) AFTER_COMMIT listener time to fire before asserting absence.
        Thread.sleep(1000L);

        List<WebhookDeliveryLog> logs = deliveryLogRepo.findByTransactionIdOrderByCreatedDateAsc(txId);
        assertThat(logs)
            .as("WEBHOOK-02: rollback of publishing transaction MUST NOT enqueue a delivery")
            .isEmpty();
    }
}
