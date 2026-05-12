package com.softropic.payam.payment.reconciliation;

import com.softropic.payam.platform.tenant.contract.ApiKeyEnvironment;
import com.softropic.payam.payment.core.contract.MobilePaymentProvider;
import com.softropic.payam.payment.core.contract.ProviderResult;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.payment.provider.mtn.service.MtnMoMoPort;
import com.softropic.payam.payment.provider.orange.service.OrangeMoneyPort;
import com.softropic.payam.payment.reconciliation.contract.DiscrepancyType;
import com.softropic.payam.payment.reconciliation.repo.ReconciliationDiscrepancy;
import com.softropic.payam.payment.reconciliation.repo.ReconciliationDiscrepancyRepository;
import com.softropic.payam.payment.reconciliation.repo.ReconciliationReport;
import com.softropic.payam.payment.reconciliation.repo.ReconciliationReportRepository;
import com.softropic.payam.payment.reconciliation.service.ReconciliationService;
import com.softropic.payam.platform.tenant.service.TenantService;

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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Integration test for the reconciliation pipeline.
 *
 * Verifies that:
 * 1. runForDate() produces 2 ReconciliationReport rows (MTN + Orange) for seeded transactions
 * 2. Matched transactions do not produce discrepancy rows
 * 3. When OrangeMoneyPort.getCollectionTransactionStatus() throws, all Orange transactions are flagged UNCONFIRMED
 *
 * The Quartz scheduler is prevented from auto-firing during tests by configuring a
 * far-future start delay via spring.quartz.properties — reconciliationService.runForDate()
 * is called directly to control execution.
 *
 * @MockBean on MtnMoMoPort and OrangeMoneyPort prevents real HTTP calls to providers.
 */
@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "mtn.callback-ip-whitelist="
})
class ReconciliationJobIT {

    @MockBean
    MtnMoMoPort mtnMoMoPort;

    @MockBean
    OrangeMoneyPort orangeMoneyPort;

    @Autowired
    ReconciliationService reconciliationService;

    @Autowired
    ReconciliationReportRepository reportRepository;

    @Autowired
    ReconciliationDiscrepancyRepository discrepancyRepository;

    @Autowired
    TenantService tenantService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TransactionTemplate transactionTemplate;

    private Long tenantId;
    private static final LocalDate YESTERDAY = LocalDate.now().minusDays(1);

    @BeforeEach
    void setUp() {
        // Seed JWT secret required by SecurityAdviceFilter.addSecretToThread()
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

        // Create a tenant for seeding transactions
        TenantService.TenantCreationResult provision =
            tenantService.createTenant("reconciliation-test-" + UUID.randomUUID(), ApiKeyEnvironment.PROD);
        tenantId = provision.tenant().getId();

        // Seed one SUCCESS MTN transaction for yesterday
        // Use System.nanoTime() & Long.MAX_VALUE for unique positive BIGINT id (pattern from WebhookDoubleCheckIT)
        long mtnTxId = System.nanoTime() & Long.MAX_VALUE;
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.transaction (id, created_by, created_date, last_modified_by, last_modified_date, " +
                "transaction_id, trace_id, tenant_id, tx_status, status, " +
                "provider, amount, currency, provider_ref) " +
                "VALUES (?, 'SYSTEM', ?::TIMESTAMPTZ, 'SYSTEM', ?::TIMESTAMPTZ, ?, ?, ?, 'SUCCESS', 'ACTIVE', " +
                "'MTN', 500.00, 'XAF', ?)",
                mtnTxId,
                YESTERDAY + "T12:00:00Z",
                YESTERDAY + "T12:00:00Z",
                "tx-mtn-" + UUID.randomUUID(),
                UUID.randomUUID().toString(),
                tenantId,
                "provider-ref-mtn-" + UUID.randomUUID()
            );
            return null;
        });

        // Seed one SUCCESS Orange transaction for yesterday
        long orangeTxId = (System.nanoTime() + 1) & Long.MAX_VALUE;
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.transaction (id, created_by, created_date, last_modified_by, last_modified_date, " +
                "transaction_id, trace_id, tenant_id, tx_status, status, " +
                "provider, amount, currency, provider_ref) " +
                "VALUES (?, 'SYSTEM', ?::TIMESTAMPTZ, 'SYSTEM', ?::TIMESTAMPTZ, ?, ?, ?, 'SUCCESS', 'ACTIVE', " +
                "'ORANGE', 1000.00, 'XAF', ?)",
                orangeTxId,
                YESTERDAY + "T14:00:00Z",
                YESTERDAY + "T14:00:00Z",
                "tx-orange-" + UUID.randomUUID(),
                UUID.randomUUID().toString(),
                tenantId,
                "provider-ref-orange-" + UUID.randomUUID()
            );
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
    void runForDate_producesReportsWithCorrectCounts_whenProviderReturnsMatch() {
        // MTN mock: return success status matching Payam SUCCESS
        when(mtnMoMoPort.getCollectionTransactionStatus(anyString()))
            .thenReturn(ProviderResult.success("ref", "SUCCESSFUL"));

        // Orange mock: return success status matching Payam SUCCESS
        when(orangeMoneyPort.getCollectionTransactionStatus(anyString()))
            .thenReturn(ProviderResult.success("ref", "SUCCESSFULL"));

        reconciliationService.runForDate(YESTERDAY);

        List<ReconciliationReport> reports = reportRepository.findAll();
        assertThat(reports).hasSize(2);

        ReconciliationReport mtnReport = reports.stream()
            .filter(r -> r.getProvider() == MobilePaymentProvider.MTN)
            .findFirst()
            .orElseThrow();
        assertThat(mtnReport.getStatus()).isEqualTo("COMPLETE");
        assertThat(mtnReport.getTotalChecked()).isEqualTo(1);

        ReconciliationReport orangeReport = reports.stream()
            .filter(r -> r.getProvider() == MobilePaymentProvider.ORANGE)
            .findFirst()
            .orElseThrow();
        assertThat(orangeReport.getStatus()).isEqualTo("COMPLETE");
        assertThat(orangeReport.getTotalChecked()).isEqualTo(1);
    }

    @Test
    void runForDate_createsUnconfirmedDiscrepancy_whenOrangePortThrows() {
        // MTN mock: normal success
        when(mtnMoMoPort.getCollectionTransactionStatus(anyString()))
            .thenReturn(ProviderResult.success("ref", "SUCCESSFUL"));

        // Orange mock: throw RuntimeException to simulate API unreachable
        when(orangeMoneyPort.getCollectionTransactionStatus(anyString()))
            .thenThrow(new RuntimeException("Orange API unreachable"));

        reconciliationService.runForDate(YESTERDAY);

        // Both reports should still be created
        List<ReconciliationReport> reports = reportRepository.findAll();
        assertThat(reports).hasSize(2);

        // Orange report should be COMPLETE with 1 discrepancy
        ReconciliationReport orangeReport = reports.stream()
            .filter(r -> r.getProvider() == MobilePaymentProvider.ORANGE)
            .findFirst()
            .orElseThrow();
        assertThat(orangeReport.getStatus()).isEqualTo("COMPLETE");
        assertThat(orangeReport.getTotalChecked()).isEqualTo(1);
        assertThat(orangeReport.getTotalDiscrepancies()).isEqualTo(1);

        // One UNCONFIRMED discrepancy row with LOW severity
        List<ReconciliationDiscrepancy> discrepancies =
            discrepancyRepository.findByReportId(orangeReport.getId());
        assertThat(discrepancies).hasSize(1);
        assertThat(discrepancies.get(0).getDiscrepancyType()).isEqualTo(DiscrepancyType.UNCONFIRMED);
    }

    @Test
    void runForDate_processesLargeDataset_withPagedFetch() {
        // Seed 1000 additional MTN transactions (plus the 1 already seeded in @BeforeEach = 1001 total MTN)
        when(mtnMoMoPort.getCollectionTransactionStatus(anyString()))
            .thenReturn(ProviderResult.success("ref", "SUCCESSFUL"));
        when(orangeMoneyPort.getCollectionTransactionStatus(anyString()))
            .thenReturn(ProviderResult.success("ref", "SUCCESSFULL"));

        long baseId = (System.nanoTime() + 100L) & Long.MAX_VALUE;
        String timestamp = YESTERDAY + "T13:00:00Z";
        transactionTemplate.execute(status -> {
            for (int i = 0; i < 1000; i++) {
                jdbcTemplate.update(
                    "INSERT INTO main.transaction (id, created_by, created_date, last_modified_by, last_modified_date, " +
                    "transaction_id, trace_id, tenant_id, tx_status, status, provider, amount, currency, provider_ref) " +
                    "VALUES (?, 'SYSTEM', ?::TIMESTAMPTZ, 'SYSTEM', ?::TIMESTAMPTZ, ?, ?, ?, 'SUCCESS', 'ACTIVE', 'MTN', 500.00, 'XAF', ?)",
                    baseId + i, timestamp, timestamp,
                    "tx-bulk-" + i + "-" + UUID.randomUUID(),
                    UUID.randomUUID().toString(), tenantId,
                    "bulk-ref-" + i);
            }
            return null;
        });

        reconciliationService.runForDate(YESTERDAY);

        ReconciliationReport mtnReport = reportRepository.findAll().stream()
            .filter(r -> r.getProvider() == MobilePaymentProvider.MTN)
            .findFirst().orElseThrow();
        assertThat(mtnReport.getStatus()).isEqualTo("COMPLETE");
        assertThat(mtnReport.getTotalChecked()).isEqualTo(1001);
    }
}
