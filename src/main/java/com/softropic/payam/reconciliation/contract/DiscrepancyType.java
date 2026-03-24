package com.softropic.payam.reconciliation.contract;

/**
 * Types of reconciliation discrepancies that can be detected.
 *
 * NOTE: MISSING_IN_PAYAM is intentionally excluded — neither Orange nor MTN expose
 * a batch listing API, making provider-side orphan detection impossible.
 */
public enum DiscrepancyType {

    /**
     * A Payam transaction has a providerRef but the provider returned null/not-found
     * for that reference. The payment may have been lost on the provider side.
     */
    MISSING_IN_PROVIDER,

    /**
     * Both Payam and the provider have a record but the amounts differ.
     * High severity — potential fraud or provider billing error.
     */
    AMOUNT_MISMATCH,

    /**
     * Both Payam and the provider have a record but the statuses are inconsistent
     * (e.g. Payam shows SUCCESS, provider shows FAILED).
     */
    STATUS_MISMATCH,

    /**
     * The provider API was unreachable during the reconciliation run.
     * The transaction cannot be confirmed. Low severity — retry on next run.
     */
    UNCONFIRMED
}
