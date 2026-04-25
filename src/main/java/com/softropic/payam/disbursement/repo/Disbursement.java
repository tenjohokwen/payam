package com.softropic.payam.disbursement.repo;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.common.persistence.AbstractAuditingEntity;
import com.softropic.payam.disbursement.contract.DisbursementStatus;

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
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Audited
@Entity
@Table(name = "disbursement", schema = "main")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class Disbursement extends AbstractAuditingEntity {

    @Column(name = "disbursement_id", unique = true, nullable = false, updatable = false, length = 36)
    private String disbursementId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "recipient_msisdn", nullable = false, length = 50)
    private String recipientMsisdn;

    @Column(nullable = false, precision = 20, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 255)
    private String reference;

    @Column(length = 500)
    private String description;

    // Column name is disbursement_status — NOT status — because status is owned by AbstractAuditingEntity.
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "disbursement_status", nullable = false, length = 30)
    private DisbursementStatus disbursementStatus = DisbursementStatus.INITIATED;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private MobilePaymentProvider provider;

    @Column(name = "provider_ref", length = 255)
    private String providerRef;

    @Column(name = "idempotency_key", length = 255)
    private String idempotencyKey;

    @Column(name = "reserved_amount", precision = 20, scale = 2)
    private BigDecimal reservedAmount;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "poll_attempts", nullable = false)
    @Builder.Default
    private Integer pollAttempts = 0;

    /** Increment poll_attempts; null-safe (treats null as 0). Used by DisbursementStatusPollerJob. */
    public void incrementPollAttempts() {
        this.pollAttempts = (this.pollAttempts == null ? 0 : this.pollAttempts) + 1;
    }

    /** Apply a state transition via DisbursementStatus guard — throws IllegalStateTransitionException on illegal next. */
    public void applyTransition(DisbursementStatus next) {
        this.disbursementStatus = this.disbursementStatus.transitionTo(next);
    }
}
