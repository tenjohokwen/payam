package com.softropic.payam.reconciliation;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.common.payment.ProviderResult;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.mtn.service.MtnMoMoPort;
import com.softropic.payam.orange.service.OrangeMoneyPort;
import com.softropic.payam.reconciliation.repo.ReconciliationDiscrepancyRepository;
import com.softropic.payam.reconciliation.repo.ReconciliationReport;
import com.softropic.payam.reconciliation.repo.ReconciliationReportRepository;
import com.softropic.payam.reconciliation.service.ReconciliationService;
import com.softropic.payam.tenant.contract.ApiKeyEnvironment;
import com.softropic.payam.tenant.service.TenantService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "mtn.callback-ip-whitelist="
})
class ReconciliationFailedStateIT {
    @MockBean MtnMoMoPort mtnMoMoPort;
    @MockBean OrangeMoneyPort orangeMoneyPort;
    @MockBean ReconciliationDiscrepancyRepository discrepancyRepository;

    @Autowired ReconciliationService reconciliationService;
    @Autowired ReconciliationReportRepository reportRepository;
    @Autowired TenantService tenantService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;

    private Long tenantId;
    private static final LocalDate YESTERDAY = LocalDate.now().minusDays(1);

    @BeforeEach
    void setUp() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute(
                "INSERT INTO main.sec (id, created_by, created_date, last_modified_by, last_modified_date, " +
                "request_id, session_id, status, bus_id, value, version) " +
                "VALUES ('659287191260154475','SYSTEM_ACCOUNT','2024-12-24 06:51:55.357352'," +
                "'SYSTEM_ACCOUNT','2024-12-24 06:51:55.357352'," +
                "'bed78f34-3e09-4fa8-81db-32326a528cca', null, 'ACTIVE', 'jot'," +
                "'loiI8oT2C1tWecrNXPDjN8fveYEU8rD6nb1k1NbVy92rwdd4/KO+aHhXh3A5zjsT5eSFL/xI+9Rqyj4RI6QCiFywn5nZLIwHGPNEY0F9lnDnGGmVjv/9rO5fgGt83+cxNDyGoCePaVEpBd7xHxyDdfpAoLxQs8mhKGqcEsh09Q+26qEiEm/a9bgDSbSQ0sX00VHBLd35OLmvN+ydjEluYxBTa6KzGb2CQ6Ttg4ZaELmbZOWpEjQ1Z7BbbYiXmWyaY+2HnkyhONoGbUpvVKl1c4e9IlQzeUYkekbUbADIm2LNK9Nhfv5/L5esvFrdVOUcUpLk/y8UT9f5xOMLFJ4Ct6s0eTKvNqYkSz2DFRI8Ip4p/ns6gA4V/1MUf9GeqPUWLiOa28Vw15+R8ycUMqb8NZHOP1oj9RunhSwA7EY84bZL3+yePc3n1b8ne8xzaYVEdK1WBu3J6s2AoBaOL/JLWfu8MuxXI+ub', 'v1') " +
                "ON CONFLICT DO NOTHING");
            return null;
        });
        TenantService.TenantCreationResult provision =
            tenantService.createTenant("recon-fail-" + UUID.randomUUID(), ApiKeyEnvironment.PROD);
        tenantId = provision.tenant().getId();

        // Seed 1 MTN + 1 Orange transaction so the runner hits the port and produces a discrepancy
        long mtnId = System.nanoTime() & Long.MAX_VALUE;
        transactionTemplate.execute(s -> {
            jdbcTemplate.update(
                "INSERT INTO main.transaction (id, created_by, created_date, last_modified_by, last_modified_date, " +
                "transaction_id, trace_id, tenant_id, tx_status, status, provider, amount, currency, provider_ref) " +
                "VALUES (?, 'SYSTEM', ?::TIMESTAMPTZ, 'SYSTEM', ?::TIMESTAMPTZ, ?, ?, ?, 'SUCCESS', 'ACTIVE', 'MTN', 500.00, 'XAF', ?)",
                mtnId, YESTERDAY + "T12:00:00Z", YESTERDAY + "T12:00:00Z",
                "tx-mtn-" + UUID.randomUUID(), UUID.randomUUID().toString(), tenantId,
                "provider-ref-mtn-" + UUID.randomUUID());
            return null;
        });
        long orangeId = (System.nanoTime() + 1) & Long.MAX_VALUE;
        transactionTemplate.execute(s -> {
            jdbcTemplate.update(
                "INSERT INTO main.transaction (id, created_by, created_date, last_modified_by, last_modified_date, " +
                "transaction_id, trace_id, tenant_id, tx_status, status, provider, amount, currency, provider_ref) " +
                "VALUES (?, 'SYSTEM', ?::TIMESTAMPTZ, 'SYSTEM', ?::TIMESTAMPTZ, ?, ?, ?, 'SUCCESS', 'ACTIVE', 'ORANGE', 1000.00, 'XAF', ?)",
                orangeId, YESTERDAY + "T14:00:00Z", YESTERDAY + "T14:00:00Z",
                "tx-orange-" + UUID.randomUUID(), UUID.randomUUID().toString(), tenantId,
                "provider-ref-orange-" + UUID.randomUUID());
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM main.reconciliation_discrepancy");
            jdbcTemplate.execute("DELETE FROM main.reconciliation_report");
            jdbcTemplate.execute("DELETE FROM main.payment_event_log");
            jdbcTemplate.execute("DELETE FROM main.transaction");
            jdbcTemplate.execute("DELETE FROM main.tenant_api_key");
            jdbcTemplate.execute("DELETE FROM main.tenant");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void runForDate_transitionsReportToFailed_whenDiscrepancyPersistenceThrows() {
        // Provider returns FAILED status -> comparing against tx SUCCESS produces a STATUS_MISMATCH discrepancy
        // which then triggers saveAll -> mock throws -> runner propagates -> runForDate catches -> markFailed commits.
        when(mtnMoMoPort.getCollectionTransactionStatus(anyString()))
            .thenReturn(ProviderResult.success("ref", "FAILED"));
        when(orangeMoneyPort.getCollectionTransactionStatus(anyString()))
            .thenReturn(ProviderResult.success("ref", "FAILED_DELIVERY"));
        when(discrepancyRepository.saveAll(anyIterable()))
            .thenThrow(new RuntimeException("forced discrepancy persistence failure"));

        reconciliationService.runForDate(YESTERDAY);

        ReconciliationReport mtn = reportRepository
            .findByReportDateAndProvider(YESTERDAY, MobilePaymentProvider.MTN).orElseThrow();
        ReconciliationReport orange = reportRepository
            .findByReportDateAndProvider(YESTERDAY, MobilePaymentProvider.ORANGE).orElseThrow();

        assertThat(mtn.getStatus()).isEqualTo("FAILED");
        assertThat(orange.getStatus()).isEqualTo("FAILED");
    }
}
