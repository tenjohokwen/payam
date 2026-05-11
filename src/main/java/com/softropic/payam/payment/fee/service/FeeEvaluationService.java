package com.softropic.payam.payment.fee.service;

import com.softropic.payam.payment.fee.contract.FeeType;
import com.softropic.payam.payment.fee.repo.FeeRule;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;


/**
 * Evaluates the applicable fee for a payment given the tenant and amount.
 *
 * <p>Delegates rule lookup to {@link FeeRuleCache} (hot-reloaded from DB).
 * Supports FEE_FIXED and FEE_PERCENTAGE calculation types.
 *
 * <p>Returns {@link BigDecimal#ZERO} when no enabled rule applies — fail-open for fee calculation.
 */
@Service
public class FeeEvaluationService {

    private final FeeRuleCache feeRuleCache;

    public FeeEvaluationService(FeeRuleCache feeRuleCache) {
        this.feeRuleCache = feeRuleCache;
    }

    /**
     * Compute the fee for a payment.
     *
     * @param tenantId tenant initiating the payment
     * @param amount   payment amount (before fee)
     * @return computed fee; {@link BigDecimal#ZERO} if no rule applies
     */
    public BigDecimal evaluateFee(Long tenantId, BigDecimal amount) {
        Optional<FeeRule> ruleOpt = feeRuleCache.findForTenant(tenantId);
        if (ruleOpt.isEmpty()) {
            return BigDecimal.ZERO;
        }
        FeeRule rule = ruleOpt.get();
        if (rule.getFeeType() == FeeType.FEE_FIXED) {
            return rule.getFixedAmount() != null ? rule.getFixedAmount() : BigDecimal.ZERO;
        }
        if (rule.getFeeType() == FeeType.FEE_PERCENTAGE) {
            if (rule.getPercentageRate() == null || amount == null) {
                return BigDecimal.ZERO;
            }
            return amount.multiply(rule.getPercentageRate())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }

    /**
     * Return the applicable fee rule for a tenant — used to store fee_rule_id on the transaction.
     *
     * @param tenantId tenant to look up
     * @return the applicable rule, or empty if none applies
     */
    public Optional<FeeRule> findRuleForTenant(Long tenantId) {
        return feeRuleCache.findForTenant(tenantId);
    }
}
