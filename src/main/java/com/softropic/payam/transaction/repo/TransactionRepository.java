package com.softropic.payam.transaction.repo;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.transaction.contract.TransactionStatus;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByTransactionId(String transactionId);

    /**
     * Find transaction by Orange payToken — used by webhook handler (Pitfall 3).
     * payToken is stored in transaction.pay_token after OrangeMoneyPort.persistPayToken().
     */
    Optional<Transaction> findByPayToken(String payToken);

    List<Transaction> findByTenantIdOrderByCreatedDateDesc(Long tenantId);

    /**
     * Pessimistic-write lock query for state transitions (P1.2).
     * Use this before calling applyTransition() to prevent webhook+poller race.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transaction t WHERE t.transactionId = :transactionId")
    Optional<Transaction> findByTransactionIdForUpdate(@Param("transactionId") String transactionId);

    /**
     * Find PROCESSING transactions for a given provider that have not been modified
     * since {@code lastModifiedDate}. Used by the status poller jobs.
     * Pageable is required to cap the result set and avoid unbounded memory usage.
     */
    List<Transaction> findByTxStatusAndProviderAndLastModifiedDateBefore(
        TransactionStatus txStatus,
        MobilePaymentProvider provider,
        Instant lastModifiedDate,
        Pageable pageable);

    /**
     * Admin cross-tenant search. All parameters are optional (null = wildcard).
     * Results ordered by createdDate DESC for newest-first investigation view.
     * Used by AdminTransactionQueryService in Phase 8.
     */
    @Query("SELECT t FROM Transaction t WHERE " +
           "(:transactionId IS NULL OR t.transactionId = :transactionId) AND " +
           "(:traceId IS NULL OR t.traceId = :traceId) AND " +
           "(:externalReference IS NULL OR t.externalReference = :externalReference) AND " +
           "(:tenantId IS NULL OR t.tenantId = :tenantId) " +
           "ORDER BY t.createdDate DESC")
    Page<Transaction> adminSearch(
        @Param("transactionId") String transactionId,
        @Param("traceId") String traceId,
        @Param("externalReference") String externalReference,
        @Param("tenantId") Long tenantId,
        Pageable pageable);

    /**
     * Count transactions by status. Used by PaymentMetricsService gauge in Phase 8.
     */
    long countByTxStatus(TransactionStatus txStatus);

    /**
     * Find transactions eligible for daily reconciliation.
     *
     * Returns transactions for a given provider within the time window [from, to)
     * that have a non-null providerRef and are in reconcilable statuses:
     * SUCCESS, FAILED, or PROCESSING (INITIATED/AUTH_PENDING/AUTHORIZED are too early).
     *
     * Used by LedgerSnapshotService in Phase 9 reconciliation.
     */
    @Query("SELECT t FROM Transaction t WHERE t.provider = :provider " +
           "AND t.createdDate >= :from AND t.createdDate < :to " +
           "AND t.txStatus IN ('SUCCESS','FAILED','PROCESSING') " +
           "AND t.providerRef IS NOT NULL")
    List<Transaction> findForReconciliation(
        @Param("provider") MobilePaymentProvider provider,
        @Param("from") Instant from,
        @Param("to") Instant to);
}
