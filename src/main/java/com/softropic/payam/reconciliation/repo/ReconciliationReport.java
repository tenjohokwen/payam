package com.softropic.payam.reconciliation.repo;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.common.persistence.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Stores the summary result of one daily reconciliation run for a given provider+date combination.
 *
 * Extends BaseEntity only (not AbstractAuditingEntity) — the DDL has no audit columns.
 * One row per (report_date, provider) pair — enforced by unique constraint.
 */
@Entity
@Table(name = "reconciliation_report", schema = "main")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class ReconciliationReport extends BaseEntity {

    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private MobilePaymentProvider provider;

    @Builder.Default
    @Column(name = "run_at", nullable = false)
    private Instant runAt = Instant.now();

    @Setter
    @Column(name = "total_checked", nullable = false)
    private int totalChecked;

    @Setter
    @Column(name = "total_matched", nullable = false)
    private int totalMatched;

    @Setter
    @Column(name = "total_discrepancies", nullable = false)
    private int totalDiscrepancies;

    @Setter
    @Column(name = "status", nullable = false, length = 20)
    private String status;
}
