package com.softropic.payam.transaction.repo;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.common.persistence.AbstractAuditingEntity;
import com.softropic.payam.transaction.contract.TransactionStatus;

import org.hibernate.envers.Audited;

import java.math.BigDecimal;

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


@Audited
@Entity
@Table(name = "transaction", schema = "main")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class Transaction extends AbstractAuditingEntity {

    @Column(name = "transaction_id", unique = true, nullable = false, updatable = false)
    private String transactionId;

    @Column(name = "trace_id", nullable = false, updatable = false)
    private String traceId;

    @Column(name = "external_reference")
    private String externalReference;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "tx_status", nullable = false)
    private TransactionStatus txStatus = TransactionStatus.INITIATED;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false)
    private MobilePaymentProvider provider;

    @Column(nullable = false, precision = 20, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "provider_ref")
    private String providerRef;

    /**
     * Apply a state transition. Delegates to the state machine guard in TransactionStatus.
     * Throws IllegalStateTransitionException if the transition is not allowed.
     */
    public void applyTransition(TransactionStatus next) {
        this.txStatus = this.txStatus.transitionTo(next);
    }
}
