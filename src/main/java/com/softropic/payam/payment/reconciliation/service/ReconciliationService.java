package com.softropic.payam.payment.reconciliation.service;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.payment.reconciliation.port.ProviderReportPort;
import com.softropic.payam.payment.reconciliation.repo.ReconciliationReport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Core reconciliation orchestrator.
 *
 * Delegates per-provider execution to {@link ReconciliationProviderRunner}, which runs each
 * provider in its own REQUIRES_NEW transaction. This class is INTENTIONALLY NOT @Transactional —
 * wrapping runForDate in a single transaction would defeat the isolation that enables:
 *   1. RECON-01: bounded heap (page loop inside runner commits per page via the runner's transaction)
 *   2. RECON-02: FAILED state transition via markFailed() in a fresh transaction after rollback
 *
 * Provider failures are isolated — if one provider's runForProvider throws, this class catches
 * the exception, calls runner.markFailed(reportId) to persist FAILED status in a new transaction,
 * and continues with the next provider.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final ReconciliationProviderRunner runner;
    private final Map<MobilePaymentProvider, ProviderReportPort> providerPorts;

    public ReconciliationService(ReconciliationProviderRunner runner,
                                 List<ProviderReportPort> ports) {
        this.runner = runner;
        Map<MobilePaymentProvider, ProviderReportPort> map = new EnumMap<>(MobilePaymentProvider.class);
        for (ProviderReportPort port : ports) {
            map.put(port.provider(), port);
        }
        this.providerPorts = map;
    }

    /**
     * Run reconciliation for all known providers for the given date.
     * Each provider is processed in an independent transaction — a failure in one does not
     * affect the other, and a failed provider leaves its report at status=FAILED (not IN_PROGRESS).
     *
     * NOT @Transactional — each runner call manages its own REQUIRES_NEW transaction.
     */
    public void runForDate(LocalDate reportDate) {
        long start = System.currentTimeMillis();
        Instant from = reportDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = reportDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        int totalChecked = 0;
        int totalDiscrepancies = 0;

        for (MobilePaymentProvider provider : new MobilePaymentProvider[]{MobilePaymentProvider.MTN, MobilePaymentProvider.ORANGE}) {
            ProviderReportPort port = providerPorts.get(provider);
            if (port == null) {
                log.warn("No ProviderReportPort registered",
                    kv("operation", "reconciliation_run"),
                    kv("provider", provider),
                    kv("status", "NO_ADAPTER"));
                continue;
            }

            ReconciliationReport report = null;
            try {
                report = runner.createOrReset(reportDate, provider);
                runner.runForProvider(report, provider, reportDate, port, from, to);
                // After success, totals are on the report object but may not be refreshed post-commit.
                // Re-read not required — the runner saved counts inside runForProvider.
                totalChecked += report.getTotalChecked();
                totalDiscrepancies += report.getTotalDiscrepancies();
            } catch (Exception e) {
                log.error("Reconciliation unexpected error",
                    kv("operation", "reconciliation_run"),
                    kv("provider", provider),
                    kv("reportId", report != null ? report.getId() : null),
                    kv("status", "ERROR"),
                    e);
                if (report != null) {
                    try {
                        runner.markFailed(report.getId());
                    } catch (Exception inner) {
                        log.error("Failed to mark report FAILED",
                            kv("operation", "reconciliation_run"),
                            kv("reportId", report.getId()),
                            kv("status", "MARK_FAILED_ERROR"),
                            inner);
                    }
                }
            }
        }

        // LOG-BUS-07: structured reconciliation summary event
        log.info("Reconciliation run completed",
            kv("operation", "reconciliation_run"),
            kv("date", reportDate.toString()),
            kv("totalChecked", totalChecked),
            kv("discrepancyCount", totalDiscrepancies),
            kv("durationMs", System.currentTimeMillis() - start),
            kv("status", "SUCCESS"));
    }
}
