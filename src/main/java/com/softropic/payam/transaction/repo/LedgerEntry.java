package com.softropic.payam.transaction.repo;

import com.softropic.payam.transaction.contract.LedgerDirection;

import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;

import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Append-only double-entry ledger record.
 * Does NOT extend AbstractAuditingEntity — this is an immutable accounting record
 * with no status, audit, or soft-delete semantics.
 */
@Immutable
@Entity
@Table(name = "ledger_entry", schema = "main")
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class LedgerEntry {

    @Id @Tsid
    @Column(updatable = false, nullable = false)
    private Long id;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private String transactionId;

    @Column(name = "entry_group_id", nullable = false, updatable = false)
    private String entryGroupId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private LedgerDirection direction;

    @Column(name = "account_code", nullable = false, length = 50)
    private String accountCode;

    @Column(nullable = false, precision = 20, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_date", nullable = false, updatable = false)
    private Instant createdDate;
}
