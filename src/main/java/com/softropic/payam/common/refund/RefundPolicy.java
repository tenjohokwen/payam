package com.softropic.payam.common.refund;

import java.io.Serializable;
import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;


@Embeddable
public class RefundPolicy implements Serializable {
    @NotNull
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private RefundType refundType;

    @Positive
    private Long refundWindowInDays;

    @Enumerated(EnumType.STRING)
    private ChargeType chargeType;

    @DecimalMin("0.0")
    private BigDecimal chargeAmt;

    public RefundType getRefundType() {
        return refundType;
    }

    public void setRefundType(RefundType refundType) {
        this.refundType = refundType;
    }

    public Long getRefundWindowInDays() {
        return refundWindowInDays;
    }

    public void setRefundWindowInDays(Long refundWindowInDays) {
        this.refundWindowInDays = refundWindowInDays;
    }

    public ChargeType getChargeType() {
        return chargeType;
    }

    public void setChargeType(ChargeType chargeType) {
        this.chargeType = chargeType;
    }

    public BigDecimal getChargeAmt() {
        return chargeAmt;
    }

    public void setChargeAmt(BigDecimal chargeAmt) {
        this.chargeAmt = chargeAmt;
    }
}
