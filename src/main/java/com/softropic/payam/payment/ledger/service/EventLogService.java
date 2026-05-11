package com.softropic.payam.payment.ledger.service;

import com.softropic.payam.payment.ledger.contract.TransactionEventType;
import com.softropic.payam.payment.ledger.contract.TransactionStatus;
import com.softropic.payam.payment.ledger.repo.PaymentEventLog;
import com.softropic.payam.payment.ledger.repo.PaymentEventLogRepository;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;


@Service
public class EventLogService {

    /** Number of distinct transaction IDs verified per database round-trip. */
    private static final int AUDIT_PAGE_SIZE = 200;

    /**
     * Result of verifying one page of transaction hash-chains.
     *
     * @param total      number of transactions examined in this page
     * @param valid      number whose chain is intact
     * @param violations transactionIds with a broken or unverifiable chain
     * @param hasMore    true when there are further pages to process
     */
    public record AuditPageResult(int total, int valid, List<String> violations, boolean hasMore) {}

    private final PaymentEventLogRepository paymentEventLogRepository;

    public EventLogService(PaymentEventLogRepository paymentEventLogRepository) {
        this.paymentEventLogRepository = paymentEventLogRepository;
    }

    /**
     * Appends an event to the hash chain for the given transaction.
     *
     * <p>The previous hash is fetched from the most recent event for this transaction.
     * If no prior event exists, "GENESIS" is used as the previous hash (establishing
     * the first link in the chain).
     *
     * <p>The event hash is computed inside {@link PaymentEventLog#create(String, String,
     * String, TransactionEventType, TransactionStatus, TransactionStatus, String, String, String)}
     * from a deterministic canonical string — never from timestamps or database IDs.
     */
    @Transactional
    public PaymentEventLog append(String transactionId,
                                  String traceId,
                                  String externalReference,
                                  TransactionEventType eventType,
                                  TransactionStatus statusFrom,
                                  TransactionStatus statusTo,
                                  String actor,
                                  String metadata) {

        String previousHash = paymentEventLogRepository
                .findLatestHashByTransactionId(transactionId)
                .orElse("GENESIS");

        PaymentEventLog entry = PaymentEventLog.create(
                transactionId, traceId, externalReference,
                eventType, statusFrom, statusTo, actor, metadata, previousHash);

        return paymentEventLogRepository.save(entry);
    }

    /**
     * Verifies one page of transaction hash-chains in a single, short-lived read transaction.
     *
     * <p>By opening and closing a dedicated transaction per page, the Hibernate L1 cache is
     * discarded at the end of each call — preventing unbounded memory growth when the event
     * log contains many transactions.  The caller loops over pages without holding any
     * transaction of its own.
     *
     * @param from optional lower bound on {@code created_date} (inclusive); null means unbounded
     * @param to   optional upper bound on {@code created_date} (inclusive); null means unbounded
     * @param page zero-based page index
     */
    @Transactional(readOnly = true)
    public AuditPageResult auditPage(Instant from, Instant to, int page) {
        Page<String> txPage = paymentEventLogRepository.findDistinctTransactionIdsPaged(
                from, to, PageRequest.of(page, AUDIT_PAGE_SIZE));

        int validCount = 0;
        List<String> violations = new ArrayList<>();

        for (String txId : txPage.getContent()) {
            try {
                if (verifyChain(txId)) {
                    validCount++;
                } else {
                    violations.add(txId);
                }
            } catch (Exception e) {
                violations.add(txId);
            }
        }

        return new AuditPageResult(txPage.getNumberOfElements(), validCount, violations, txPage.hasNext());
    }

    /**
     * Verifies the integrity of the hash chain for a given transaction.
     *
     * <p>Traverses all events in ascending creation order, re-computing the expected
     * hash from the canonical fields and confirming it matches the stored {@code event_hash}.
     * Also verifies that each event's {@code previous_hash} matches the hash of the
     * preceding event (or "GENESIS" for the first event).
     *
     * @return {@code true} if the chain is intact; {@code false} if any hash mismatches
     */
    public boolean verifyChain(String transactionId) {
        List<PaymentEventLog> events = paymentEventLogRepository
                .findByTransactionIdOrderByCreatedDateAsc(transactionId);

        String runningHash = "GENESIS";
        for (PaymentEventLog event : events) {
            if (!runningHash.equals(event.getPreviousHash())) return false;
            String canonical = event.getTransactionId() + "|"
                    + event.getEventType().name() + "|"
                    + (event.getStatusFrom() != null ? event.getStatusFrom().name() : "null") + "|"
                    + event.getStatusTo().name() + "|"
                    + event.getActor() + "|"
                    + event.getPreviousHash();
            String expected = DigestUtils.sha256Hex(canonical);
            if (!expected.equals(event.getEventHash())) return false;
            runningHash = event.getEventHash();
        }
        return true;
    }
}
