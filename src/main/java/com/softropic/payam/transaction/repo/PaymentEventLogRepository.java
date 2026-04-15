package com.softropic.payam.transaction.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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
     * Returns a page of distinct transactionIds present in the event log, optionally bounded
     * by a creation-date window.  Null values for {@code from}/{@code to} are treated as
     * unbounded on that side, so the same query handles windowed and full-table scans.
     *
     * <p>Callers should process one page per transaction to avoid unbounded L1-cache growth.
     */
    @Query(value = "SELECT DISTINCT e.transactionId FROM PaymentEventLog e " +
                   "WHERE (:from IS NULL OR e.createdDate >= :from) " +
                   "AND   (:to   IS NULL OR e.createdDate <= :to)",
           countQuery = "SELECT COUNT(DISTINCT e.transactionId) FROM PaymentEventLog e " +
                        "WHERE (:from IS NULL OR e.createdDate >= :from) " +
                        "AND   (:to   IS NULL OR e.createdDate <= :to)")
    Page<String> findDistinctTransactionIdsPaged(
            @Param("from") Instant from,
            @Param("to")   Instant to,
            Pageable pageable);
}
