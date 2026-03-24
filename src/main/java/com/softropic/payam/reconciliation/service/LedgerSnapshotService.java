package com.softropic.payam.reconciliation.service;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.transaction.repo.Transaction;
import com.softropic.payam.transaction.repo.TransactionRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Read-only ledger query service for the reconciliation pipeline.
 *
 * Fetches transactions that are eligible for reconciliation:
 * - Created within the specified date window [date 00:00 UTC, date+1 00:00 UTC)
 * - For the specified provider
 * - Have a non-null providerRef (cannot reconcile without a provider reference)
 * - In terminal or near-terminal statuses: SUCCESS, FAILED, PROCESSING
 *   (PROCESSING is included so mismatches can be detected; INITIATED/AUTH_PENDING/AUTHORIZED are too early)
 */
@Service
public class LedgerSnapshotService {

    private final TransactionRepository transactionRepository;

    public LedgerSnapshotService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Find all transactions eligible for reconciliation on the given date for the given provider.
     *
     * @param date     The date being reconciled (in UTC)
     * @param provider The payment provider to reconcile
     * @return List of transactions with non-null providerRef and reconcilable status
     */
    @Transactional(readOnly = true)
    public List<Transaction> findTransactionsForDateAndProvider(LocalDate date, MobilePaymentProvider provider) {
        Instant from = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return transactionRepository.findForReconciliation(provider, from, to);
    }
}
