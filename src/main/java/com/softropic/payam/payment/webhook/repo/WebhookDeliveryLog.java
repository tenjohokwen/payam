package com.softropic.payam.payment.webhook.repo;

import com.softropic.payam.infrastructure.persistence.AbstractAuditingEntity;
import com.softropic.payam.transaction.contract.TransactionStatus;

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

/**
 * Mutable JPA entity tracking outbound tenant webhook delivery attempts.
 *
 * NOT @Immutable — delivery state (http_status, attempt_count, next_retry_at, delivered, last_attempt_at)
 * is updated on each delivery attempt.
 *
 * externalReference is set at creation time via enqueue() and is not updated after INSERT.
 */
@Entity
@Table(name = "webhook_delivery_log", schema = "main")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class WebhookDeliveryLog extends AbstractAuditingEntity {

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private String transactionId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "webhook_url", length = 2048, nullable = false, updatable = false)
    private String webhookUrl;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(name = "external_reference", length = 255)
    private String externalReference;

    @Column(name = "fee_amount", precision = 20, scale = 2)
    private BigDecimal feeAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_status", length = 20)
    private TransactionStatus transactionStatus;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Builder.Default
    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 0;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Builder.Default
    @Column(name = "delivered", nullable = false)
    private Boolean delivered = false;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    // Public setters for mutable fields — updated on each delivery attempt

    public void setTransactionStatus(TransactionStatus transactionStatus) {
        this.transactionStatus = transactionStatus;
    }

    public void setFeeAmount(BigDecimal feeAmount) {
        this.feeAmount = feeAmount;
    }

    public void setHttpStatus(Integer httpStatus) {
        this.httpStatus = httpStatus;
    }

    public void setAttemptCount(Integer attemptCount) {
        this.attemptCount = attemptCount;
    }

    public void setNextRetryAt(Instant nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public void setDelivered(Boolean delivered) {
        this.delivered = delivered;
    }

    public void setLastAttemptAt(Instant lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }
}
