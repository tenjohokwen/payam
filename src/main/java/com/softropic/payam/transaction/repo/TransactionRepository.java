package com.softropic.payam.transaction.repo;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.transaction.contract.TransactionStatus;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByTransactionId(String transactionId);

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
     * since {@code lastModifiedDate}. Used by OrangeStatusPollerJob to discover
     * stuck transactions eligible for status polling.
     */
    List<Transaction> findByTxStatusAndProviderAndLastModifiedDateBefore(
        TransactionStatus txStatus,
        MobilePaymentProvider provider,
        Instant lastModifiedDate);
}
