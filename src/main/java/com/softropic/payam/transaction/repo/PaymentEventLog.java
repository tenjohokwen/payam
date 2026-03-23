package com.softropic.payam.transaction.repo;

import com.softropic.payam.common.persistence.BaseEntity;
import com.softropic.payam.transaction.contract.TransactionEventType;
import com.softropic.payam.transaction.contract.TransactionStatus;

import org.apache.commons.codec.digest.DigestUtils;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;


@Immutable
@Entity
@Table(name = "payment_event_log", schema = "main")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class PaymentEventLog extends BaseEntity {

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private String transactionId;

    @Column(name = "trace_id", nullable = false, updatable = false)
    private String traceId;

    @Column(name = "external_reference", updatable = false)
    private String externalReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false)
    private TransactionEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_from", updatable = false)
    private TransactionStatus statusFrom;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_to", nullable = false, updatable = false)
    private TransactionStatus statusTo;

    @Column(nullable = false, updatable = false)
    private String actor;

    @Column(columnDefinition = "jsonb", updatable = false)
    private String metadata;

    @Column(name = "previous_hash", nullable = false, updatable = false)
    private String previousHash;

    @Column(name = "event_hash", nullable = false, updatable = false)
    private String eventHash;

    @Column(name = "created_date", nullable = false, updatable = false)
    private Instant createdDate;

    /**
     * Factory method. Computes the SHA-256 hash from a deterministic pipe-delimited
     * canonical string before constructing the object. The hash does NOT include
     * timestamps or database IDs — only stable domain fields.
     */
    public static PaymentEventLog create(
            String transactionId,
            String traceId,
            String externalReference,
            TransactionEventType eventType,
            TransactionStatus statusFrom,
            TransactionStatus statusTo,
            String actor,
            String metadata,
            String previousHash) {

        String canonical = transactionId + "|"
                + eventType.name() + "|"
                + (statusFrom != null ? statusFrom.name() : "null") + "|"
                + statusTo.name() + "|"
                + actor + "|"
                + previousHash;

        String eventHash = DigestUtils.sha256Hex(canonical);

        return PaymentEventLog.builder()
                .transactionId(transactionId)
                .traceId(traceId)
                .externalReference(externalReference)
                .eventType(eventType)
                .statusFrom(statusFrom)
                .statusTo(statusTo)
                .actor(actor)
                .metadata(metadata)
                .previousHash(previousHash)
                .eventHash(eventHash)
                .createdDate(Instant.now())
                .build();
    }
}
