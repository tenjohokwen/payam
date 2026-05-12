package com.softropic.payam.payment.ledger.repo;

import com.softropic.payam.payment.core.contract.MobilePaymentProvider;
import com.softropic.payam.infrastructure.persistence.AbstractAuditingEntity;
import com.softropic.payam.payment.ledger.contract.LedgerFlow;
import com.softropic.payam.payment.ledger.contract.TransactionStatus;

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
     * Fee amount computed at payment initiation by FeeEvaluationService. Nullable — null for
     * transactions created before Phase 10. Both columns use @NotAudited because V14 adds columns
     * to main.transaction only; Envers _AUD table lacks them.
     */
    @NotAudited
    @Column(name = "fee_amount", precision = 20, scale = 2)
    private java.math.BigDecimal feeAmount;

    /**
     * ID of the fee rule applied at payment initiation time. Nullable — null for
     * transactions created before Phase 10.
     */
    @NotAudited
    @Column(name = "fee_rule_id")
    private Long feeRuleId;

    /**
     * Ledger flow classification for this transaction.
     *
     * Nullable by design — rows created before Phase 47 / V25 have NULL flow.
     * Use {@link #getEffectiveFlow()} to read; it treats null as {@link LedgerFlow#COLLECTION}.
     *
     * NOT @NotAudited: V25 added the `flow` column to both main.transaction AND
     * main.transaction_aud, so Envers must audit it naturally.
     * NOT @Builder.Default: null must be preserved for pre-v9 rows; the COLLECTION
     * fallback belongs in the accessor, not in the builder default.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "flow", length = 20)
    private LedgerFlow flow;

    /**
     * Apply a state transition. Delegates to the state machine guard in TransactionStatus.
     * Throws IllegalStateTransitionException if the transition is not allowed.
     */
    public void applyTransition(TransactionStatus next) {
        this.txStatus = this.txStatus.transitionTo(next);
    }

    /**
     * Returns the effective ledger flow.
     * For legacy rows (flow == null), returns {@link LedgerFlow#COLLECTION} — the
     * pre-v9 behavior was collection-only, so that is the correct interpretation.
     */
    public LedgerFlow getEffectiveFlow() {
        return flow != null ? flow : LedgerFlow.COLLECTION;
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

    public void setFeeAmount(java.math.BigDecimal feeAmount) {
        this.feeAmount = feeAmount;
    }

    public void setFeeRuleId(Long feeRuleId) {
        this.feeRuleId = feeRuleId;
    }
}
