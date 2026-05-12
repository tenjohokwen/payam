package com.softropic.payam.payment.ledger.repo;

import com.softropic.payam.payment.core.contract.MobilePaymentProvider;
import com.softropic.payam.payment.ledger.contract.TransactionStatus;

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
     * Tenant-scoped lookup by external (merchant-supplied) reference.
     */
    Optional<Transaction> findByTenantIdAndExternalReference(Long tenantId, String externalReference);

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
     * {@link #findByTransactionIdsForUpdate} — multi-row PESSIMISTIC_WRITE lock used by
     * Phase 55 transaction-claim validation (TXN-05). The ORDER BY t.transactionId ASC is
     * mandatory: it serializes lock acquisition across concurrent disbursement requests with
     * overlapping transaction sets, preventing deadlocks. Caller MUST be inside a
     * transactionTemplate.execute() block — locks are released on commit.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transaction t WHERE t.transactionId IN :transactionIds " +
           "ORDER BY t.transactionId ASC")
    List<Transaction> findByTransactionIdsForUpdate(
        @Param("transactionIds") List<String> transactionIds);

    /**
     * Find PROCESSING transactions for a given provider that have not been modified
     * since {@code lastModifiedDate}. Used by the status poller jobs.
     *
     * {@code txStatus} and {@code provider} must be passed as their enum {@code .name()} strings
     * because this is a native query and enums are not auto-converted.
     *
     * FOR UPDATE SKIP LOCKED: rows already claimed by another cluster node are silently skipped,
     * so two poller instances never process the same transaction concurrently.
     * ORDER BY id ASC: deterministic ordering; stable across pages; oldest-inserted rows first.
     * LIMIT :limit: caps heap usage and bounds worst-case execution time per tick.
     */
    @Query(value = "SELECT * FROM main.transaction" +
                   " WHERE tx_status = :txStatus" +
                   " AND provider = :provider" +
                   " AND last_modified_date < :lastModifiedDate" +
                   " ORDER BY id ASC" +
                   " LIMIT :limit" +
                   " FOR UPDATE SKIP LOCKED",
           nativeQuery = true)
    List<Transaction> findProcessingTransactionsSkipLocked(
        @Param("txStatus") String txStatus,
        @Param("provider") String provider,
        @Param("lastModifiedDate") Instant lastModifiedDate,
        @Param("limit") int limit);

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
     * Paged variant of {@link #findForReconciliation} for RECON-01.
     * MUST include ORDER BY t.id ASC — without a stable sort, offset pagination can skip or duplicate rows.
     * Page size is chosen by caller (ReconciliationProviderRunner uses 1000).
     */
    @Query("SELECT t FROM Transaction t WHERE t.provider = :provider " +
           "AND t.createdDate >= :from AND t.createdDate < :to " +
           "AND t.txStatus IN ('SUCCESS','FAILED','PROCESSING') " +
           "AND t.providerRef IS NOT NULL " +
           "ORDER BY t.id ASC")
    Page<Transaction> findForReconciliationPaged(
        @Param("provider") MobilePaymentProvider provider,
        @Param("from") Instant from,
        @Param("to") Instant to,
        Pageable pageable);
}
