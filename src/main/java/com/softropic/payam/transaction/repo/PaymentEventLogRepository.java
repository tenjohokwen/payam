package com.softropic.payam.transaction.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;


public interface PaymentEventLogRepository extends JpaRepository<PaymentEventLog, Long> {

    /**
     * Returns the event_hash of the most recently inserted event for the given transaction.
     * Used by EventLogService to establish the previous hash before appending a new event.
     */
    @Query("SELECT e.eventHash FROM PaymentEventLog e " +
           "WHERE e.transactionId = :txId " +
           "ORDER BY e.createdDate DESC LIMIT 1")
    Optional<String> findLatestHashByTransactionId(@Param("txId") String txId);

    /**
     * Returns all events for a transaction in insertion order (ascending created_date).
     * Used for hash chain verification traversal.
     */
    List<PaymentEventLog> findByTransactionIdOrderByCreatedDateAsc(String transactionId);

    /**
     * Returns all distinct transactionIds present in the event log.
     * Used by {@link com.softropic.payam.admin.api.AuditResource} for full hash-chain audit.
     *
     * <p>WARNING: may be slow on large tables — caller should apply date windowing in production.
     */
    @Query("SELECT DISTINCT e.transactionId FROM PaymentEventLog e")
    List<String> findAllDistinctTransactionIds();
}
