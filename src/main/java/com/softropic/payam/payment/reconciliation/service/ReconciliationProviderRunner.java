package com.softropic.payam.payment.reconciliation.service;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.payment.reconciliation.contract.DiscrepancySeverity;
import com.softropic.payam.payment.reconciliation.contract.DiscrepancyType;
import com.softropic.payam.payment.reconciliation.port.ProviderReportPort;
import com.softropic.payam.payment.reconciliation.port.ProviderTransactionRecord;
import com.softropic.payam.payment.reconciliation.repo.ReconciliationDiscrepancy;
import com.softropic.payam.payment.reconciliation.repo.ReconciliationDiscrepancyRepository;
import com.softropic.payam.payment.reconciliation.repo.ReconciliationReport;
import com.softropic.payam.payment.reconciliation.repo.ReconciliationReportRepository;
import com.softropic.payam.transaction.repo.Transaction;
import com.softropic.payam.transaction.repo.TransactionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
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
 *  (a) each page of a provider's work runs in an isolated REQUIRES_NEW transaction — partial
 *      work is committed after every page, capping connection hold time per transaction;
 *  (b) FAILED status can be written in a fresh transaction after any page rolls back —
 *      writes inside a rollback-only transaction are silently discarded.
 *
 * Self-injection (@Lazy) is required so that processPage() and saveReport() are called
 * through the Spring AOP proxy, preserving REQUIRES_NEW semantics. Without it,
 * self.processPage() would bypass the proxy and no transaction would be created.
 */
@Service
public class ReconciliationProviderRunner {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationProviderRunner.class);
    private static final int PAGE_SIZE = 1000;

    /** Result of processing a single page — carried back to the orchestrator. */
    public record PageResult(int checked, int matched, int discrepancies) {}

    /** Self-reference through the proxy so REQUIRES_NEW is honoured on inner calls. */
    @Lazy
    @Autowired
    private ReconciliationProviderRunner self;

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
     * Orchestrates the page loop for one provider. NOT @Transactional — each page is
     * committed independently via {@link #processPage}. On exception this method lets it
     * propagate; caller (ReconciliationService) calls {@link #markFailed(Long)} in a
     * separate transaction so that already-committed pages are preserved.
     */
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
            // Each page commits in its own REQUIRES_NEW transaction — partial work survives
            // a later failure and the connection is released after every page.
            PageResult result = self.processPage(
                report.getId(), page.getContent(), reportDate, provider, port);
            totalChecked += result.checked();
            totalMatched += result.matched();
            totalDiscrepancies += result.discrepancies();
            pageNum++;
        } while (!page.isLast());

        report.setTotalChecked(totalChecked);
        report.setTotalMatched(totalMatched);
        report.setTotalDiscrepancies(totalDiscrepancies);
        report.setStatus("COMPLETE");
        self.saveReport(report);
    }

    /**
     * Processes one page of transactions and persists any discrepancies found.
     * Runs in its own REQUIRES_NEW transaction with a 30-minute timeout so that a stalled
     * provider call cannot hold a connection indefinitely.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 1800)
    public PageResult processPage(Long reportId,
                                  List<Transaction> transactions,
                                  LocalDate reportDate,
                                  MobilePaymentProvider provider,
                                  ProviderReportPort port) {
        List<ReconciliationDiscrepancy> pageDiscrepancies = new ArrayList<>();
        int matched = 0;
        for (Transaction tx : transactions) {
            ProviderTransactionRecord record = port.fetchProviderRecord(tx, reportDate);
            ReconciliationDiscrepancy d = compareTransaction(tx, record, reportId, reportDate, provider);
            if (d != null) {
                pageDiscrepancies.add(d);
            } else {
                matched++;
            }
        }
        discrepancyRepository.saveAll(pageDiscrepancies);
        return new PageResult(transactions.size(), matched, pageDiscrepancies.size());
    }

    /** Persists the final report state (COMPLETE + counts) in its own REQUIRES_NEW transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveReport(ReconciliationReport report) {
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
