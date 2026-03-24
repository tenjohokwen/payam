package com.softropic.payam.reconciliation.repo;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.common.persistence.BaseEntity;
import com.softropic.payam.reconciliation.contract.DiscrepancySeverity;
import com.softropic.payam.reconciliation.contract.DiscrepancyType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One discrepancy record produced by the daily reconciliation run.
 *
 * Extends BaseEntity only (not AbstractAuditingEntity) — no audit columns in DDL.
 * Append-only — built with @Builder and no mutation methods after creation.
 */
@Entity
@Table(name = "reconciliation_discrepancy", schema = "main")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class ReconciliationDiscrepancy extends BaseEntity {

    @Column(name = "report_id", nullable = false)
    private Long reportId;

    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private MobilePaymentProvider provider;

    @Column(name = "payam_tx_id", length = 100)
    private String payamTxId;

    @Column(name = "provider_ref", length = 100)
    private String providerRef;

    @Column(name = "payam_status", length = 30)
    private String payamStatus;

    @Column(name = "provider_status", length = 30)
    private String providerStatus;

    @Column(name = "payam_amount", precision = 20, scale = 2)
    private BigDecimal payamAmount;

    @Column(name = "provider_amount", precision = 20, scale = 2)
    private BigDecimal providerAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "discrepancy_type", nullable = false, length = 30)
    private DiscrepancyType discrepancyType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 10)
    private DiscrepancySeverity severity;
}
