package com.softropic.payam.transaction.service;

import com.softropic.payam.transaction.contract.TransactionEventType;
import com.softropic.payam.transaction.contract.TransactionStatus;
import com.softropic.payam.transaction.repo.PaymentEventLog;
import com.softropic.payam.transaction.repo.PaymentEventLogRepository;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;


@Service
public class EventLogService {

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
