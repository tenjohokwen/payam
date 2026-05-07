package com.softropic.payam.e2e.reconciliation;

import com.softropic.payam.common.payment.ProviderResult;
import com.softropic.payam.e2e.AbstractPayamE2ETest;
import com.softropic.payam.e2e.builder.TenantBuilder;
import com.softropic.payam.mtn.service.MtnMoMoPort;
import com.softropic.payam.orange.service.OrangeMoneyPort;
import com.softropic.payam.reconciliation.service.ReconciliationService;
import com.softropic.payam.platform.tenant.repo.TenantRepository;
import com.softropic.payam.platform.tenant.service.TenantService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * E2E tests for the daily reconciliation pipeline (FLOWS-RECON-01 through FLOWS-RECON-04).
 *
 * <p>Uses {@code @MockBean} on provider ports (same pattern as {@code ReconciliationJobIT})
 * to inject controlled provider responses without real HTTP calls. Transactions are seeded
 * directly via JDBC. {@code ReconciliationService.runForDate()} is called directly to
 * control execution.
 *
 * <p>{@code TestDataCleaner.wipeAll()} runs in {@code @AfterEach} (inherited from
 * {@link AbstractPayamE2ETest}) and deletes {@code reconciliation_discrepancy} before
 * {@code reconciliation_report}, satisfying the FK constraint. This allows all four
 * {@code @Test} methods to reuse {@code LocalDate.now(ZoneOffset.UTC).minusDays(1)} safely.
 */
public class DailyReconciliationE2ETest extends AbstractPayamE2ETest {

    @MockBean
    private MtnMoMoPort mtnMoMoPort;

    @MockBean
    private OrangeMoneyPort orangeMoneyPort;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantRepository tenantRepository;

    // -------------------------------------------------------------------------
    // FLOWS-RECON-01: Matched transaction → 0 discrepancies
    // -------------------------------------------------------------------------

    @Test
    void matchedTransaction() {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        TenantBuilder.CreatedTenant tenant =
            new TenantBuilder().withName("Recon-Match").create(tenantService, tenantRepository);

        insertTransaction(tenant.tenantId(), "MTN", "SUCCESS", yesterday + "T12:00:00Z");

        when(mtnMoMoPort.getCollectionTransactionStatus(anyString()))
            .thenReturn(ProviderResult.success("fin-matched-001", "SUCCESSFUL"));
        when(orangeMoneyPort.getCollectionTransactionStatus(anyString()))
            .thenReturn(ProviderResult.success("fin-orange-001", "SUCCESSFULL")); // Orange double-L

        reconciliationService.runForDate(yesterday);

        Integer discrepancyCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.reconciliation_discrepancy WHERE report_id IN " +
            "(SELECT id FROM main.reconciliation_report WHERE report_date = ?)",
            Integer.class, yesterday);
        assertThat(discrepancyCount).isZero();
    }

    // -------------------------------------------------------------------------
    // FLOWS-RECON-02: Missing transaction → 1 MISSING_IN_PROVIDER discrepancy
    // -------------------------------------------------------------------------

    @Test
    void missingTransaction() {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        TenantBuilder.CreatedTenant tenant =
            new TenantBuilder().withName("Recon-Missing").create(tenantService, tenantRepository);

        insertTransaction(tenant.tenantId(), "MTN", "SUCCESS", yesterday + "T10:00:00Z");

        // Return rawStatus=null — MtnReportAdapter passes result.rawStatus() as providerStatus.
        // When providerStatus == null, ReconciliationService.compareTransaction() creates MISSING_IN_PROVIDER.
        when(mtnMoMoPort.getCollectionTransactionStatus(anyString()))
            .thenReturn(new ProviderResult(null, null, false, null, null));
        when(orangeMoneyPort.getCollectionTransactionStatus(anyString()))
            .thenReturn(ProviderResult.success("fin-orange-001", "SUCCESSFULL"));

        reconciliationService.runForDate(yesterday);

        Integer discrepancyCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.reconciliation_discrepancy WHERE " +
            "report_id IN (SELECT id FROM main.reconciliation_report WHERE report_date = ?) " +
            "AND discrepancy_type = 'MISSING_IN_PROVIDER'",
            Integer.class, yesterday);
        assertThat(discrepancyCount).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // FLOWS-RECON-03: Mismatched transaction → 1 STATUS_MISMATCH discrepancy
    // -------------------------------------------------------------------------

    @Test
    void mismatchedTransaction() {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        TenantBuilder.CreatedTenant tenant =
            new TenantBuilder().withName("Recon-Mismatch").create(tenantService, tenantRepository);

        insertTransaction(tenant.tenantId(), "MTN", "SUCCESS", yesterday + "T14:00:00Z");

        // rawStatus="FAILED" → providerStatus="FAILED". Payam SUCCESS vs provider FAILED
        // are both terminal and do not match → STATUS_MISMATCH.
        when(mtnMoMoPort.getCollectionTransactionStatus(anyString()))
            .thenReturn(ProviderResult.success("fin-mismatch-001", "FAILED"));
        when(orangeMoneyPort.getCollectionTransactionStatus(anyString()))
            .thenReturn(ProviderResult.success("fin-orange-001", "SUCCESSFULL"));

        reconciliationService.runForDate(yesterday);

        Integer discrepancyCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.reconciliation_discrepancy WHERE " +
            "report_id IN (SELECT id FROM main.reconciliation_report WHERE report_date = ?) " +
            "AND discrepancy_type = 'STATUS_MISMATCH'",
            Integer.class, yesterday);
        assertThat(discrepancyCount).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // FLOWS-RECON-04: WAT timestamp boundary
    // -------------------------------------------------------------------------

    /**
     * Verifies that {@code ReconciliationProviderRunner} uses UTC boundaries for the reconciliation
     * date window: {@code [YESTERDAY 00:00Z, TODAY 00:00Z)}.
     *
     * <p>A transaction at {@code YESTERDAY T23:30:00Z} corresponds to {@code TODAY T00:30:00 WAT}
     * (UTC+1). If the window used WAT instead of UTC, this transaction would be placed in today's
     * bucket and missed from yesterday's report. This test asserts it is included in yesterday's
     * reconciliation window — confirming UTC boundary correctness.
     *
     * <p>The Orange WAT parsing concern (OrangeTimeUtil.parseOrangeTimestamp interprets createtime
     * as Africa/Douala / UTC+1) is separate and is exercised by OrangeTimeUtilTest unit tests.
     * OrangeReportAdapter does not call OrangeTimeUtil at reconciliation time because
     * PayResponse has no createtime field.
     */
    @Test
    void watTimestampBoundary() {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        TenantBuilder.CreatedTenant tenant =
            new TenantBuilder().withName("Recon-WAT").create(tenantService, tenantRepository);

        // Seed at 23:30 UTC = 00:30 WAT next day. Must fall in YESTERDAY's window.
        insertTransaction(tenant.tenantId(), "MTN", "SUCCESS", yesterday + "T23:30:00Z");

        when(mtnMoMoPort.getCollectionTransactionStatus(anyString()))
            .thenReturn(ProviderResult.success("fin-wat-001", "SUCCESSFUL"));
        when(orangeMoneyPort.getCollectionTransactionStatus(anyString()))
            .thenReturn(ProviderResult.success("fin-orange-001", "SUCCESSFULL"));

        reconciliationService.runForDate(yesterday);

        // Assert the reconciliation_report row was created for YESTERDAY + MTN
        Integer reportCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.reconciliation_report WHERE report_date = ? AND provider = 'MTN'",
            Integer.class, yesterday);
        assertThat(reportCount).isEqualTo(1);

        // Assert the transaction WAS included (total_checked >= 1 on the MTN report for YESTERDAY)
        Integer totalChecked = jdbcTemplate.queryForObject(
            "SELECT total_checked FROM main.reconciliation_report WHERE report_date = ? AND provider = 'MTN'",
            Integer.class, yesterday);
        assertThat(totalChecked).isGreaterThanOrEqualTo(1);

        // Assert no discrepancy (provider returned SUCCESSFUL = match with Payam SUCCESS)
        Integer discrepancyCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.reconciliation_discrepancy WHERE " +
            "report_id IN (SELECT id FROM main.reconciliation_report WHERE report_date = ? AND provider = 'MTN')",
            Integer.class, yesterday);
        assertThat(discrepancyCount).isZero();

        // Assert the transaction is NOT included in TODAY's run
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        reconciliationService.runForDate(today);

        Integer todayTotal = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(total_checked), 0) FROM main.reconciliation_report WHERE report_date = ? AND provider = 'MTN'",
            Integer.class, today);
        assertThat(todayTotal).isZero(); // the 23:30 UTC tx was yesterday's, not today's
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Inserts a transaction row directly via JDBC inside a TransactionTemplate.
     * Returns the generated transactionId for debugging (reconciliation queries by provider_ref).
     */
    private String insertTransaction(long tenantId, String provider, String txStatus, String createdDate) {
        String transactionId = UUID.randomUUID().toString();
        String providerRef = "pref-" + UUID.randomUUID();
        long id = System.nanoTime() & Long.MAX_VALUE;
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.transaction " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, " +
                "transaction_id, trace_id, tenant_id, tx_status, status, provider, amount, currency, provider_ref) " +
                "VALUES (?, 'TEST', (?::TIMESTAMPTZ) AT TIME ZONE 'UTC', 'TEST', (?::TIMESTAMPTZ) AT TIME ZONE 'UTC', ?, ?, ?, ?, 'ACTIVE', ?, 500.00, 'XAF', ?)",
                id, createdDate, createdDate,
                transactionId, UUID.randomUUID().toString(), tenantId,
                txStatus, provider, providerRef);
            return null;
        });
        return transactionId;
    }
}
