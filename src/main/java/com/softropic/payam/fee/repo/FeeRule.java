package com.softropic.payam.fee.repo;

import com.softropic.payam.common.persistence.AbstractAuditingEntity;
import com.softropic.payam.fee.contract.FeeType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;


/**
 * Fee rule entity — stores fee configuration for a tenant or globally (when tenantId is null).
 *
 * <p>Rules are hot-reloaded by {@link com.softropic.payam.fee.service.FeeRuleCache} without
 * requiring a JVM restart. A {@code null} tenantId means the rule applies globally to all
 * tenants that do not have a more specific tenant-level rule.
 *
 * <p>Modelled after {@link com.softropic.payam.fraud.repo.FraudRule} — same
 * {@link AbstractAuditingEntity} base and immutable-entity pattern.
 */
@Entity
@Table(name = "fee_rule", schema = "main")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class FeeRule extends AbstractAuditingEntity {

    /**
     * Owning tenant. {@code null} means this is a global rule applied to all tenants
     * unless overridden by a tenant-specific rule.
     */
    @Column(name = "tenant_id")
    private Long tenantId;

    /** Calculation type — fixed amount or percentage of payment amount. */
    @Enumerated(EnumType.STRING)
    @Column(name = "fee_type", nullable = false)
    private FeeType feeType;

    /** Flat fee amount in the rule's currency. Used only when feeType is FEE_FIXED. */
    @Column(name = "fixed_amount", precision = 20, scale = 2)
    private BigDecimal fixedAmount;

    /**
     * Percentage rate as a decimal fraction (e.g. 1.5% stored as 0.0150).
     * Used only when feeType is FEE_PERCENTAGE.
     */
    @Column(name = "percentage_rate", precision = 5, scale = 4)
    private BigDecimal percentageRate;

    /** ISO 4217 currency code (e.g. XAF, USD). */
    @Column(length = 3)
    private String currency;

    /** Human-readable rule name for admin UI display. */
    @Column(name = "rule_name", nullable = false)
    private String ruleName;

    /** Whether this rule is active and should be loaded by the cache. */
    @Column(nullable = false)
    private boolean enabled;

    /** Optional description of the rule's purpose. */
    @Column(length = 500)
    private String description;
}
