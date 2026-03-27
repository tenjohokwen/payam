package com.softropic.payam.e2e.builder;

import io.hypersistence.tsid.TSID;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Builder for reconciliation test data via direct JdbcTemplate inserts (BUILD-07).
 *
 * <p>Does NOT use ReconciliationService — that service runs the full job. This builder
 * seeds pre-existing reconciliation state for E2E tests that verify reconciliation output.
 *
 * <p>Note: {@code main.reconciliation_report} has a UNIQUE(report_date, provider) constraint.
 * Use distinct dates per test or rely on {@code TestDataCleaner.wipeAll()} between tests.
 *
 * <p>Usage:
 * <pre>
 *     Long reportId = new ReconciliationReportBuilder()
 *         .forDate(LocalDate.of(2026, 1, 1))
 *         .forProvider("MTN")
 *         .withMissingTransaction(transactionId)
 *         .create(jdbc);
 * </pre>
 */
public class ReconciliationReportBuilder {

    private LocalDate reportDate = LocalDate.now();
    private String provider = "MTN";
    private int totalChecked = 0;
    private int totalMatched = 0;
    private int totalDiscrepancies = 0;
    private String status = "SUCCESS";
    private final List<DiscrepancyEntry> discrepancies = new ArrayList<>();

    public record DiscrepancyEntry(
        String payamTxId,
        String providerRef,
        String payamStatus,
        String providerStatus,
        String discrepancyType,
        String severity
    ) {}

    public ReconciliationReportBuilder forDate(LocalDate reportDate) {
        this.reportDate = reportDate;
        return this;
    }

    public ReconciliationReportBuilder forProvider(String provider) {
        this.provider = provider;
        return this;
    }

    public ReconciliationReportBuilder withStatus(String status) {
        this.status = status;
        return this;
    }

    /**
     * Records a matched transaction (increments totalChecked and totalMatched).
     */
    public ReconciliationReportBuilder withMatchedTransaction(String payamTxId, String providerRef) {
        totalChecked++;
        totalMatched++;
        return this;
    }

    /**
     * Records a missing-in-provider discrepancy (increments totalChecked and totalDiscrepancies).
     */
    public ReconciliationReportBuilder withMissingTransaction(String payamTxId) {
        totalChecked++;
        totalDiscrepancies++;
        discrepancies.add(new DiscrepancyEntry(payamTxId, null, null, null, "MISSING_IN_PROVIDER", "HIGH"));
        return this;
    }

    /**
     * Records a status-mismatch discrepancy (increments totalChecked and totalDiscrepancies).
     */
    public ReconciliationReportBuilder withMismatchedTransaction(
            String payamTxId, String providerRef, String payamStatus, String providerStatus) {
        totalChecked++;
        totalDiscrepancies++;
        discrepancies.add(new DiscrepancyEntry(payamTxId, providerRef, payamStatus, providerStatus, "STATUS_MISMATCH", "HIGH"));
        return this;
    }

    /**
     * Inserts the reconciliation report and all discrepancy rows via JdbcTemplate.
     * Uses TSID for primary key generation (consistent with BaseEntity @Tsid strategy).
     *
     * @return the generated reportId
     */
    public Long create(JdbcTemplate jdbc) {
        Long reportId = TSID.fast().toLong();

        jdbc.update(
            "INSERT INTO main.reconciliation_report " +
            "(id, report_date, provider, total_checked, total_matched, total_discrepancies, status) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            reportId, reportDate, provider, totalChecked, totalMatched, totalDiscrepancies, status);

        for (DiscrepancyEntry entry : discrepancies) {
            Long discrepancyId = TSID.fast().toLong();
            jdbc.update(
                "INSERT INTO main.reconciliation_discrepancy " +
                "(id, report_id, report_date, provider, payam_tx_id, provider_ref, " +
                " payam_status, provider_status, discrepancy_type, severity) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                discrepancyId, reportId, reportDate, provider,
                entry.payamTxId(), entry.providerRef(),
                entry.payamStatus(), entry.providerStatus(),
                entry.discrepancyType(), entry.severity());
        }

        return reportId;
    }
}
