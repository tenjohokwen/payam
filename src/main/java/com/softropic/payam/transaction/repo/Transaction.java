package com.softropic.payam.transaction.repo;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.common.persistence.AbstractAuditingEntity;
import com.softropic.payam.transaction.contract.TransactionStatus;

import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

import java.math.BigDecimal;
import java.time.Instant;

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
     * MTN financialTransactionId from callback/status response — null until MTN confirms;
     * absent for non-MTN transactions.
     */
    @Column(name = "mtn_financial_tx_id")
    private String mtnFinancialTxId;

    /** Orange payToken — stored after merchant info call, used for status polling. */
    @Column(name = "pay_token")
    private String payToken;

    /** Timestamp when payToken was issued — used for expiry check (P1.3). */
    @Column(name = "pay_token_issued_at")
    private Instant payTokenIssuedAt;

    /** Number of times this transaction has been polled by OrangeStatusPollerJob. */
    @Column(name = "poll_attempts")
    private Integer pollAttempts;

    /**
     * Fraud risk score 0–100 computed by FraudScoringService at payment initiation.
     * Null until the fraud evaluation step in PaymentOrchestrator runs (non-null on all
     * transactions created after Phase 7).
     */
    @NotAudited
    @Column(name = "risk_score")
    private Integer riskScore;

    /**
     * Client device fingerprint token (e.g. from FingerprintJS). Optional — provided
     * by the client via PaymentRequest.deviceFingerprint(). Stored for fraud pattern analysis.
     */
    @NotAudited
    @Column(name = "device_fingerprint", columnDefinition = "TEXT")
    private String deviceFingerprint;

    /**
     * Apply a state transition. Delegates to the state machine guard in TransactionStatus.
     * Throws IllegalStateTransitionException if the transition is not allowed.
     */
    public void applyTransition(TransactionStatus next) {
        this.txStatus = this.txStatus.transitionTo(next);
    }

    public void setProviderRef(String providerRef) {
        this.providerRef = providerRef;
    }

    public void setMtnFinancialTxId(String mtnFinancialTxId) {
        this.mtnFinancialTxId = mtnFinancialTxId;
    }

    public void setPayToken(String payToken) {
        this.payToken = payToken;
    }

    public void setPayTokenIssuedAt(Instant payTokenIssuedAt) {
        this.payTokenIssuedAt = payTokenIssuedAt;
    }

    /**
     * Increment poll attempt counter by 1. Null is treated as 0 (first increment -> 1).
     */
    public void incrementPollAttempts() {
        this.pollAttempts = (this.pollAttempts == null ? 0 : this.pollAttempts) + 1;
    }

    public void setRiskScore(Integer riskScore) {
        this.riskScore = riskScore;
    }

    public void setDeviceFingerprint(String deviceFingerprint) {
        this.deviceFingerprint = deviceFingerprint;
    }
}
