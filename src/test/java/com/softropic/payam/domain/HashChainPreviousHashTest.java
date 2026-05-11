package com.softropic.payam.domain;

import com.softropic.payam.payment.ledger.contract.TransactionEventType;
import com.softropic.payam.payment.ledger.contract.TransactionStatus;
import com.softropic.payam.payment.ledger.repo.PaymentEventLog;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MUT-02: Hash chain previousHash inclusion mutation kill.
 *
 * Pure unit test — no @SpringBootTest, no Testcontainers, starts in milliseconds.
 * PITest targetTests points to com.softropic.payam.domain.* — this test is a PITest target.
 *
 * PaymentEventLog.create() computes:
 *   canonical = txId + "|" + eventType + "|" + statusFrom + "|" + statusTo + "|" + actor + "|" + previousHash
 *   eventHash = DigestUtils.sha256Hex(canonical)
 *
 * This test kills the mutation that omits previousHash from the canonical string,
 * proving that two events identical except for previousHash produce different hashes.
 */
class HashChainPreviousHashTest {

    @Test
    void hashValue_includesPreviousHash_inCanonicalString() {
        String transactionId = "txn-hash-test-001";
        String traceId = "trace-001";
        TransactionEventType eventType = TransactionEventType.PAYMENT_INITIATED;
        TransactionStatus statusFrom = null; // genesis event has null statusFrom
        TransactionStatus statusTo = TransactionStatus.INITIATED;
        String actor = "ORCHESTRATOR";
        String metadata = null;

        // Create two events identical except for previousHash
        PaymentEventLog eventA = PaymentEventLog.create(
            transactionId, traceId, null,
            eventType, statusFrom, statusTo,
            actor, metadata,
            "GENESIS");

        PaymentEventLog eventB = PaymentEventLog.create(
            transactionId, traceId, null,
            eventType, statusFrom, statusTo,
            actor, metadata,
            "different-previous-hash");

        // The event hashes must differ — previousHash is part of the canonical string
        assertThat(eventA.getEventHash())
            .as("Events with different previousHash must produce different eventHash values")
            .isNotEqualTo(eventB.getEventHash());

        // Both hashes must be non-null and non-empty (SHA-256 is always 64 hex chars)
        assertThat(eventA.getEventHash())
            .as("eventHash must be a 64-character hex string (SHA-256)")
            .hasSize(64);
        assertThat(eventB.getEventHash())
            .as("eventHash must be a 64-character hex string (SHA-256)")
            .hasSize(64);
    }

    @Test
    void hashValue_sameInputs_produceSameHash() {
        // Determinism check — same canonical string always produces the same hash
        String transactionId = "txn-hash-test-002";
        String traceId = "trace-002";
        String previousHash = "GENESIS";

        PaymentEventLog event1 = PaymentEventLog.create(
            transactionId, traceId, null,
            TransactionEventType.PROVIDER_SUCCESS,
            TransactionStatus.PROCESSING, TransactionStatus.SUCCESS,
            "MTN_WEBHOOK", null, previousHash);

        PaymentEventLog event2 = PaymentEventLog.create(
            transactionId, traceId, null,
            TransactionEventType.PROVIDER_SUCCESS,
            TransactionStatus.PROCESSING, TransactionStatus.SUCCESS,
            "MTN_WEBHOOK", null, previousHash);

        assertThat(event1.getEventHash())
            .as("Same canonical inputs must produce the same SHA-256 hash (determinism)")
            .isEqualTo(event2.getEventHash());
    }

    @Test
    void hashValue_nullStatusFrom_differFromNonNullStatusFrom() {
        // Kills the mutation "replaced equality check with false" in statusFrom != null ternary.
        // When statusFrom is null, canonical uses "null". When statusFrom is non-null, uses name().
        // Mutation: replace != null with false → always "null" even when statusFrom is non-null
        //           → these two events would produce the SAME hash (mutation survives if not tested)
        String transactionId = "txn-hash-test-003";
        String traceId = "trace-003";
        String previousHash = "GENESIS";
        String actor = "ORCHESTRATOR";

        // Event with null statusFrom (genesis event)
        PaymentEventLog eventNullFrom = PaymentEventLog.create(
            transactionId, traceId, null,
            TransactionEventType.PAYMENT_INITIATED,
            null, TransactionStatus.INITIATED,
            actor, null, previousHash);

        // Event with non-null statusFrom (state transition event)
        PaymentEventLog eventNonNullFrom = PaymentEventLog.create(
            transactionId, traceId, null,
            TransactionEventType.PAYMENT_INITIATED,
            TransactionStatus.PROCESSING, TransactionStatus.INITIATED,
            actor, null, previousHash);

        // Hashes must differ — statusFrom=null vs statusFrom=PROCESSING changes the canonical string
        assertThat(eventNullFrom.getEventHash())
            .as("Event with null statusFrom must have different hash from event with non-null statusFrom")
            .isNotEqualTo(eventNonNullFrom.getEventHash());
    }
}
