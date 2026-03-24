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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Core reconciliation engine.
 *
 * Orchestrates one reconciliation run for a given date: for each known provider,
 * fetches the ledger snapshot, compares each transaction against the provider's record,
 * detects discrepancies, and persists the results.
 *
 * Provider failures are isolated — if one provider's loop throws, the other still runs.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    /** Provider statuses that are considered "terminal" for status-mismatch comparison. */
    private static final java.util.Set<String> TERMINAL_STATUSES = java.util.Set.of(
        "SUCCESS", "SUCCESSFULL", // Orange uses double-L
        "FAILED", "FAILED_DELIVERY"
    );

    private final LedgerSnapshotService ledgerSnapshotService;
    private final ReconciliationReportRepository reportRepository;
    private final ReconciliationDiscrepancyRepository discrepancyRepository;
    private final Map<MobilePaymentProvider, ProviderReportPort> providerPorts;

    public ReconciliationService(LedgerSnapshotService ledgerSnapshotService,
                                 ReconciliationReportRepository reportRepository,
                                 ReconciliationDiscrepancyRepository discrepancyRepository,
                                 List<ProviderReportPort> ports) {
        this.ledgerSnapshotService = ledgerSnapshotService;
        this.reportRepository = reportRepository;
        this.discrepancyRepository = discrepancyRepository;

        // Build provider -> port map from the injected list
        Map<MobilePaymentProvider, ProviderReportPort> map = new EnumMap<>(MobilePaymentProvider.class);
        for (ProviderReportPort port : ports) {
            map.put(port.provider(), port);
        }
        this.providerPorts = map;
    }

    /**
     * Run reconciliation for all known providers for the given date.
     * Each provider is processed independently — a failure in one does not abort the other.
     *
     * @param reportDate The date to reconcile (typically yesterday)
     */
    @Transactional
    public void runForDate(LocalDate reportDate) {
        log.info("ReconciliationService: starting reconciliation for date={}", reportDate);

        for (MobilePaymentProvider provider : new MobilePaymentProvider[]{MobilePaymentProvider.MTN, MobilePaymentProvider.ORANGE}) {
            try {
                runForProviderAndDate(provider, reportDate);
            } catch (Exception e) {
                log.error("ReconciliationService: unexpected error reconciling provider={} date={}: {}",
                    provider, reportDate, e.getMessage(), e);
            }
        }

        log.info("ReconciliationService: completed reconciliation for date={}", reportDate);
    }

    private void runForProviderAndDate(MobilePaymentProvider provider, LocalDate reportDate) {
        ProviderReportPort port = providerPorts.get(provider);
        if (port == null) {
            log.warn("ReconciliationService: no ProviderReportPort registered for provider={}", provider);
            return;
        }

        // Step 1: create or reset the report for this provider+date
        ReconciliationReport report = reportRepository.findByReportDateAndProvider(reportDate, provider)
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
        report = reportRepository.save(report);

        // Step 2: fetch eligible ledger transactions
        List<Transaction> ledgerTxs = ledgerSnapshotService.findTransactionsForDateAndProvider(reportDate, provider);
        log.info("ReconciliationService: provider={} date={} — found {} transactions to reconcile",
            provider, reportDate, ledgerTxs.size());

        // Step 3: compare each transaction against the provider record
        List<ReconciliationDiscrepancy> discrepancies = new ArrayList<>();
        int matched = 0;

        for (Transaction tx : ledgerTxs) {
            ProviderTransactionRecord record = port.fetchProviderRecord(tx.getProviderRef(), reportDate);
            ReconciliationDiscrepancy discrepancy = compareTransaction(tx, record, report.getId(), reportDate, provider);
            if (discrepancy != null) {
                discrepancies.add(discrepancy);
            } else {
                matched++;
            }
        }

        // Step 4: persist discrepancies and update report
        discrepancyRepository.saveAll(discrepancies);

        report.setTotalChecked(ledgerTxs.size());
        report.setTotalMatched(matched);
        report.setTotalDiscrepancies(discrepancies.size());
        report.setStatus("COMPLETE");
        reportRepository.save(report);

        log.info("ReconciliationService: provider={} date={} — checked={}, matched={}, discrepancies={}",
            provider, reportDate, ledgerTxs.size(), matched, discrepancies.size());
    }

    /**
     * Compare one Payam transaction against the provider record.
     *
     * @return A discrepancy if one is detected, or null if the transaction matched.
     */
    private ReconciliationDiscrepancy compareTransaction(Transaction tx,
                                                          ProviderTransactionRecord record,
                                                          Long reportId,
                                                          LocalDate reportDate,
                                                          MobilePaymentProvider provider) {
        String payamStatus = tx.getTxStatus().name();

        // Case 1: provider API was unreachable
        if (record.unconfirmed()) {
            return buildDiscrepancy(reportId, reportDate, provider, tx, record,
                DiscrepancyType.UNCONFIRMED, DiscrepancySeverity.LOW);
        }

        // Case 2: provider returned null/not-found
        if (record.providerStatus() == null) {
            return buildDiscrepancy(reportId, reportDate, provider, tx, record,
                DiscrepancyType.MISSING_IN_PROVIDER, DiscrepancySeverity.HIGH);
        }

        // Case 3: amount mismatch (only when both sides have amounts)
        if (tx.getAmount() != null && record.providerAmount() != null
            && tx.getAmount().compareTo(record.providerAmount()) != 0) {
            return buildDiscrepancy(reportId, reportDate, provider, tx, record,
                DiscrepancyType.AMOUNT_MISMATCH, DiscrepancySeverity.HIGH);
        }

        // Case 4: status mismatch between terminal statuses
        String providerStatus = record.providerStatus();
        boolean payamTerminal = isTerminal(payamStatus);
        boolean providerTerminal = isTerminal(providerStatus);
        if (payamTerminal && providerTerminal && !statusesMatch(payamStatus, providerStatus)) {
            return buildDiscrepancy(reportId, reportDate, provider, tx, record,
                DiscrepancyType.STATUS_MISMATCH, DiscrepancySeverity.MEDIUM);
        }

        // No discrepancy — transaction matched
        return null;
    }

    private boolean isTerminal(String status) {
        if (status == null) return false;
        return TERMINAL_STATUSES.contains(status.toUpperCase());
    }

    private boolean statusesMatch(String payamStatus, String providerStatus) {
        if (payamStatus == null || providerStatus == null) return false;
        // Normalize: treat "SUCCESSFULL" (Orange) as equivalent to "SUCCESS"
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
