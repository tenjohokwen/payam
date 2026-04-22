package com.softropic.payam.transaction.contract;

import java.math.BigDecimal;

/**
 * Typed value object passed to LedgerService.postEntry to describe a money-moving event.
 *
 * Invariants (enforced by compact constructor):
 *  - flow and currency are non-null
 *  - principal is non-null and strictly positive (compareTo(ZERO) > 0)
 *  - fee is non-null and non-negative (compareTo(ZERO) >= 0)
 *
 * Use the static factories collection(...) or disbursement(...) rather than the
 * canonical constructor in call sites.
 */
public record LedgerPosting(
        LedgerFlow flow,
        BigDecimal principal,
        BigDecimal fee,
        String currency) {

    public LedgerPosting {
        if (flow == null) {
            throw new IllegalArgumentException("flow must not be null");
        }
        if (currency == null) {
            throw new IllegalArgumentException("currency must not be null");
        }
        if (principal == null || principal.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("principal must be > 0");
        }
        if (fee == null || fee.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("fee must be >= 0");
        }
    }

    /**
     * Factory for a COLLECTION posting (customer -> provider). fee is fixed to ZERO.
     */
    public static LedgerPosting collection(BigDecimal principal, String currency) {
        return new LedgerPosting(LedgerFlow.COLLECTION, principal, BigDecimal.ZERO, currency);
    }

    /**
     * Factory for a DISBURSEMENT posting (merchant -> customer + fee).
     * Accepts fee == BigDecimal.ZERO for zero-fee disbursements.
     */
    public static LedgerPosting disbursement(BigDecimal principal, BigDecimal fee, String currency) {
        return new LedgerPosting(LedgerFlow.DISBURSEMENT, principal, fee, currency);
    }
}
