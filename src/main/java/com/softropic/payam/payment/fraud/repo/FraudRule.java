package com.softropic.payam.payment.fraud.repo;

import com.softropic.payam.infrastructure.persistence.AbstractAuditingEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;


/**
 * Fraud signal rule — stores weight, velocity threshold, and window for a named fraud signal.
 *
 * <p>Rules are loaded from the DB by {@link FraudRuleRepository} and hot-reloaded by
 * {@link com.softropic.payam.fraud.service.FraudRuleCache} on a scheduled interval.
 * No restart is needed when DB rows are updated.
 */
@Entity
@Table(name = "fraud_rule", schema = "main")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class FraudRule extends AbstractAuditingEntity {

    /** Signal name matching {@link com.softropic.payam.fraud.contract.FraudSignal#getSignalName()} or "BLOCK_THRESHOLD". */
    @Column(name = "signal_name", unique = true, nullable = false)
    private String signalName;

    /** Contribution to 0-100 risk score when this signal fires. */
    @Column(nullable = false)
    private int weight;

    /** Velocity window capacity — maximum allowed requests within windowSeconds. */
    @Column(nullable = false)
    private int threshold;

    /** Velocity window duration in seconds. */
    @Column(name = "window_seconds", nullable = false)
    private int windowSeconds;

    /** Whether this rule is active. Disabled rules are ignored by the cache and scoring engine. */
    @Column(nullable = false)
    private boolean enabled;

    /** Human-readable description of the rule's purpose. */
    @Column(length = 500)
    private String description;
}
