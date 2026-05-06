package com.softropic.payam.disbursement.repo;

import com.softropic.payam.infrastructure.persistence.AbstractAuditingEntity;

import org.hibernate.envers.Audited;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Audited
@Entity
@Table(name = "merchant_wallet_balance", schema = "main")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class MerchantWalletBalance extends AbstractAuditingEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false, unique = true)
    private Long tenantId;

    /** Spendable (available) balance. Decremented by checkAndReserve, incremented by release. BAL-01/BAL-02. */
    @Builder.Default
    @Column(nullable = false, precision = 20, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    /** Currently earmarked amount across open disbursements. */
    @Builder.Default
    @Column(name = "reserved_amount", nullable = false, precision = 20, scale = 2)
    private BigDecimal reservedAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(nullable = false, length = 3)
    private String currency = "XAF";

    /** JPA @Version — safety net only. The primary concurrency strategy is PESSIMISTIC_WRITE via repository lock. */
    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;
}
