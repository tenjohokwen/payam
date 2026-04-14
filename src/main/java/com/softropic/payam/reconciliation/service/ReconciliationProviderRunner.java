package com.softropic.payam.reconciliation.service;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.reconciliation.contract.DiscrepancySeverity;
import com.softropic.payam.reconciliation.contract.DiscrepancyType;
import com.softropic.payam.reconciliation.port.ProviderReportPort;
import com.softropic.payam.reconciliation.port.ProviderTransactionRecord;
import com.softropic.payam.reconciliation.repo.ReconciliationDiscrepancy;
import com.softropic.payam.reconciliation.repo.ReconciliationDiscrepancyRepository;
import com.softropic.payam.reconciliation.repo.ReconciliationReport;
import com.softropic.payam.reconciliation.repo.ReconciliationReportRepository;
import com.softropic.payam.transaction.repo.Transaction;
import com.softropic.payam.transaction.repo.TransactionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Per-provider reconciliation runner — RECON-01 (paged loop) + RECON-02 (FAILED state).
 *
 * Each public method runs in its own REQUIRES_NEW transaction. Callers (ReconciliationService)
 * MUST invoke via the injected Spring bean, not via self-invocation. Self-invocation bypasses
 * the AOP proxy and defeats REQUIRES_NEW semantics.
 *
 * Split out of ReconciliationService so that:
 *  (a) each provider's work runs in an isolated transaction — a failure rolls back only that
 *      provider's writes, not every provider's;
 *  (b) FAILED status can be written in a fresh transaction after the main run rolled back —
 *      writes inside a rollback-only transaction are silently discarded.
 */
@Service
public class ReconciliationProviderRunner {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationProviderRunner.class);
    private static final int PAGE_SIZE = 1000;

    /** Provider statuses that are considered "terminal" for status-mismatch comparison. */
    private static final Set<String> TERMINAL_STATUSES = Set.of(
        "SUCCESS", "SUCCESSFULL", // Orange uses double-L
        "FAILED", "FAILED_DELIVERY"
    );

    private final TransactionRepository transactionRepository;
    private final ReconciliationReportRepository reportRepository;
    private final ReconciliationDiscrepancyRepository discrepancyRepository;

    public ReconciliationProviderRunner(TransactionRepository transactionRepository,
                                        ReconciliationReportRepository reportRepository,
                                        ReconciliationDiscrepancyRepository discrepancyRepository) {
        this.transactionRepository = transactionRepository;
        this.reportRepository = reportRepository;
        this.discrepancyRepository = discrepancyRepository;
    }

    /** Creates or resets the report row to IN_PROGRESS in its own transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReconciliationReport createOrReset(LocalDate reportDate, MobilePaymentProvider provider) {
        ReconciliationReport report = reportRepository
            .findByReportDateAndProvider(reportDate, provider)
            .orElseGet(() -> ReconciliationReport.builder()
                .reportDate(reportDate)
                .provider(provider)
                .runAt(Instant.now())
                .totalChecked(0)
                .totalMatched(0)
                .totalDiscrepancies(0)
                .status("IN_PROGRESS")
                .build());
        report.setStatus("IN_PROGRESS");
        report.setTotalChecked(0);
        report.setTotalMatched(0);
        report.setTotalDiscrepancies(0);
        return reportRepository.save(report);
    }

    /**
     * Runs the full page loop for one provider. Own REQUIRES_NEW transaction.
     * On exception, this method lets it propagate — caller (ReconciliationService) is
     * responsible for invoking {@link #markFailed(Long)} in a separate transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void runForProvider(ReconciliationReport report,
                               MobilePaymentProvider provider,
                               LocalDate reportDate,
                               ProviderReportPort port,
                               Instant from,
                               Instant to) {
        int totalChecked = 0;
        int totalMatched = 0;
        int totalDiscrepancies = 0;
        int pageNum = 0;
        Page<Transaction> page;
        do {
            page = transactionRepository.findForReconciliationPaged(
                provider, from, to, PageRequest.of(pageNum, PAGE_SIZE));
            List<ReconciliationDiscrepancy> pageDiscrepancies = new ArrayList<>();
            for (Transaction tx : page.getContent()) {
                ProviderTransactionRecord record = port.fetchProviderRecord(tx.getProviderRef(), reportDate);
                ReconciliationDiscrepancy d = compareTransaction(tx, record, report.getId(), reportDate, provider);
                if (d != null) {
                    pageDiscrepancies.add(d);
                } else {
                    totalMatched++;
                }
            }
            // Incremental persistence — RECON-01 requires saving each page before fetching the next
            discrepancyRepository.saveAll(pageDiscrepancies);
            totalChecked += page.getNumberOfElements();
            totalDiscrepancies += pageDiscrepancies.size();
            pageNum++;
        } while (!page.isLast());

        report.setTotalChecked(totalChecked);
        report.setTotalMatched(totalMatched);
        report.setTotalDiscrepancies(totalDiscrepancies);
        report.setStatus("COMPLETE");
        reportRepository.save(report);
    }

    /** Writes FAILED status in its own independent transaction. RECON-02. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long reportId) {
        reportRepository.findById(reportId).ifPresent(r -> {
            r.setStatus("FAILED");
            reportRepository.save(r);
            log.warn("Reconciliation report marked FAILED",
                kv("operation", "reconciliation_run"),
                kv("reportId", reportId),
                kv("status", "FAILED"));
        });
    }

    // --- comparison helpers (duplicated from ReconciliationService; Task 3 deletes them from service) ---

    private ReconciliationDiscrepancy compareTransaction(Transaction tx,
                                                          ProviderTransactionRecord record,
                                                          Long reportId,
                                                          LocalDate reportDate,
                                                          MobilePaymentProvider provider) {
        String payamStatus = tx.getTxStatus().name();
        if (record.unconfirmed()) {
            return buildDiscrepancy(reportId, reportDate, provider, tx, record,
                DiscrepancyType.UNCONFIRMED, DiscrepancySeverity.LOW);
        }
        if (record.providerStatus() == null) {
            return buildDiscrepancy(reportId, reportDate, provider, tx, record,
                DiscrepancyType.MISSING_IN_PROVIDER, DiscrepancySeverity.HIGH);
        }
        if (tx.getAmount() != null && record.providerAmount() != null
            && tx.getAmount().compareTo(record.providerAmount()) != 0) {
            return buildDiscrepancy(reportId, reportDate, provider, tx, record,
                DiscrepancyType.AMOUNT_MISMATCH, DiscrepancySeverity.HIGH);
        }
        String providerStatus = record.providerStatus();
        if (isTerminal(payamStatus) && isTerminal(providerStatus)
            && !statusesMatch(payamStatus, providerStatus)) {
            return buildDiscrepancy(reportId, reportDate, provider, tx, record,
                DiscrepancyType.STATUS_MISMATCH, DiscrepancySeverity.MEDIUM);
        }
        return null;
    }

    private boolean isTerminal(String status) {
        if (status == null) return false;
        return TERMINAL_STATUSES.contains(status.toUpperCase());
    }

    private boolean statusesMatch(String payamStatus, String providerStatus) {
        if (payamStatus == null || providerStatus == null) return false;
        String normalizedPayam = "SUCCESSFULL".equalsIgnoreCase(payamStatus) ? "SUCCESS" : payamStatus.toUpperCase();
        String normalizedProvider = "SUCCESSFULL".equalsIgnoreCase(providerStatus) ? "SUCCESS" : providerStatus.toUpperCase();
        return normalizedPayam.equals(normalizedProvider);
    }

    private ReconciliationDiscrepancy buildDiscrepancy(Long reportId,
                                                       LocalDate reportDate,
                                                       MobilePaymentProvider provider,
                                                       Transaction tx,
                                                       ProviderTransactionRecord record,
                                                       DiscrepancyType type,
                                                       DiscrepancySeverity severity) {
        return ReconciliationDiscrepancy.builder()
            .reportId(reportId)
            .reportDate(reportDate)
            .provider(provider)
            .payamTxId(tx.getTransactionId())
            .providerRef(tx.getProviderRef())
            .payamStatus(tx.getTxStatus().name())
            .providerStatus(record.providerStatus())
            .payamAmount(tx.getAmount())
            .providerAmount(record.providerAmount())
            .discrepancyType(type)
            .severity(severity)
            .build();
    }
}
