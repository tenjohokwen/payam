package com.softropic.payam.fee.contract;

/**
 * Fee calculation type for a {@link com.softropic.payam.fee.repo.FeeRule}.
 *
 * <ul>
 *   <li>{@link #FEE_FIXED} — a flat amount stored in {@code fixed_amount} regardless of payment amount</li>
 *   <li>{@link #FEE_PERCENTAGE} — a percentage of the payment amount stored in {@code percentage_rate}</li>
 * </ul>
 */
public enum FeeType {

    /** Fixed monetary fee (e.g. 50 XAF per transaction). */
    FEE_FIXED,

    /** Percentage of transaction amount (e.g. 1.5% = 0.0150 in percentage_rate column). */
    FEE_PERCENTAGE
}
