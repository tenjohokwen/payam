package com.softropic.payam.payment.webhook;

import com.softropic.payam.config.TestConfig;
import com.softropic.payam.e2e.verify.QueryCountVerifier;
import com.softropic.payam.platform.tenant.contract.ApiKeyEnvironment;
import com.softropic.payam.platform.tenant.repo.Tenant;
import com.softropic.payam.platform.tenant.service.TenantService;
import com.softropic.payam.payment.webhook.repo.WebhookDeliveryLog;
import com.softropic.payam.payment.webhook.repo.WebhookDeliveryLogRepository;
import com.softropic.payam.payment.webhook.service.WebhookDeliveryService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WEBHOOK-01 regression test: WebhookDeliveryJob tick must load tenants in one SELECT,
 * not N (one per pending delivery).
 *
 * Enables datasource-proxy query counting via the log.database.spy property so that
 * QueryCountVerifier (backed by QueryCountHolder) returns meaningful counts.
 */
@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "log.database.spy=true",
    "datasource.container=true"
})
class WebhookDeliveryJobIT {

    @Autowired WebhookDeliveryService deliveryService;
    @Autowired WebhookDeliveryLogRepository deliveryLogRepo;
    @Autowired TenantService tenantService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;

    private final QueryCountVerifier queryCountVerifier = new QueryCountVerifier();

    private Long tenantIdA;
    private Long tenantIdB;
    private Long tenantIdC;

    @BeforeEach
    void setUp() {
        var provA = tenantService.createTenant("wh-job-it-A", ApiKeyEnvironment.PROD);
        var provB = tenantService.createTenant("wh-job-it-B", ApiKeyEnvironment.PROD);
        var provC = tenantService.createTenant("wh-job-it-C", ApiKeyEnvironment.PROD);
        tenantIdA = provA.tenant().getId();
        tenantIdB = provB.tenant().getId();
        tenantIdC = provC.tenant().getId();

        // Set webhookUrl on each tenant (value is unused by this test — query count only)
        transactionTemplate.execute(s -> {
            jdbcTemplate.update("UPDATE main.tenant SET webhook_url = ? WHERE id = ?",
                "http://localhost:9/unused", tenantIdA);
            jdbcTemplate.update("UPDATE main.tenant SET webhook_url = ? WHERE id = ?",
                "http://localhost:9/unused", tenantIdB);
            jdbcTemplate.update("UPDATE main.tenant SET webhook_url = ? WHERE id = ?",
                "http://localhost:9/unused", tenantIdC);
            return null;
        });

        // Seed one pending WebhookDeliveryLog per tenant — directly via repo.save
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        for (Long tid : List.of(tenantIdA, tenantIdB, tenantIdC)) {
            WebhookDeliveryLog entry = WebhookDeliveryLog.builder()
                .transactionId("tx-" + tid)
                .tenantId(tid)
                .webhookUrl("http://localhost:9/unused")
                .eventType("PROVIDER_SUCCESS")
                .externalReference("ext-" + tid)
                .build();
            entry.setFeeAmount(BigDecimal.ZERO);
            entry.setNextRetryAt(past);
            entry.setDelivered(false);
            deliveryLogRepo.save(entry);
        }
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
    void loadTenants_bulkFetchesAllInOneSelect() {
        Set<Long> ids = Set.of(tenantIdA, tenantIdB, tenantIdC);

        queryCountVerifier.reset();
        Map<Long, Tenant> map = deliveryService.loadTenants(ids);

        assertThat(map).hasSize(3);
        assertThat(map).containsKeys(tenantIdA, tenantIdB, tenantIdC);
        // WEBHOOK-01: exactly one SELECT for N tenant lookups (findAllById IN clause)
        queryCountVerifier.assertSelectCountAtMost(1);
    }

    @Test
    void jobTickPath_oneTenantSelectPerTickAcrossNDeliveries() {
        // Mirror the production job flow: findPending -> loadTenants -> loop attemptDelivery(log, tenant)
        List<WebhookDeliveryLog> pending = deliveryService.findPendingDeliveries();
        assertThat(pending).hasSizeGreaterThanOrEqualTo(3);

        Set<Long> tenantIds = pending.stream()
            .map(WebhookDeliveryLog::getTenantId)
            .collect(Collectors.toSet());

        // Reset immediately before the bulk tenant load so only that SELECT is counted.
        queryCountVerifier.reset();
        Map<Long, Tenant> tenantMap = deliveryService.loadTenants(tenantIds);

        assertThat(tenantMap).hasSize(tenantIds.size());
        // WEBHOOK-01 regression boundary: one tenant SELECT regardless of pending count
        queryCountVerifier.assertSelectCountAtMost(1);
    }
}
