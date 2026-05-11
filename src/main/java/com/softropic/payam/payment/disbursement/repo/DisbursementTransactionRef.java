package com.softropic.payam.payment.disbursement.repo;

import com.softropic.payam.infrastructure.persistence.AbstractAuditingEntity;
import com.softropic.payam.payment.disbursement.contract.DisbursementRefStatus;

import org.hibernate.envers.Audited;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Per-collection-transaction claim that backs a {@link Disbursement} (v11 TXN-03, CLAIM-01..05).
 *
 * <p>One row per (disbursement, collection-transaction) pair. The partial unique index on
 * {@code (transaction_id) WHERE ref_status IN ('PENDING','CLAIMED')} (created by V31) ensures
 * a collection transaction is bound to at most one ACTIVE claim at any time — RELEASED rows
 * do not participate in the index and may co-exist (preserving audit trail across retries
 * per IDEM-02).
 *
 * <p>Lifecycle states: see {@link DisbursementRefStatus}.
 *
 * <p>FK shape: {@code disbursement_id} is BIGINT referencing {@code main.disbursement(id)}
 * (TSID-generated). {@code transaction_id} is VARCHAR(36) — the logical UUID on
 * {@code main.transaction.transaction_id}, NOT the BIGINT primary key. This matches the
 * existing {@code ledger_entry.transaction_id} convention to avoid cross-table FKs on
 * non-primary keys (see RESEARCH Section "Entity ID Pattern" + "Anti-Patterns to Avoid").
 */
@Audited
@Entity
@Table(name = "disbursement_transaction_ref", schema = "main")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class DisbursementTransactionRef extends AbstractAuditingEntity {

    @Column(name = "disbursement_id", nullable = false, updatable = false)
    private Long disbursementId;

    @Column(name = "transaction_id", nullable = false, updatable = false, length = 36)
    private String transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "ref_status", nullable = false, length = 30)
    private DisbursementRefStatus refStatus;
}
