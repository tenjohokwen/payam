package com.softropic.payam.reconciliation.contract;

/**
 * Severity levels for reconciliation discrepancies.
 * Used for prioritizing investigation and alerting.
 */
public enum DiscrepancySeverity {

    /** Requires immediate attention — potential money loss or fraud. */
    HIGH,

    /** Should be investigated but not immediately critical. */
    MEDIUM,

    /** Informational — typically transient (API unreachable, timing). */
    LOW;

    /**
     * Derive severity from discrepancy type.
     * - HIGH: MISSING_IN_PROVIDER, AMOUNT_MISMATCH
     * - MEDIUM: STATUS_MISMATCH
     * - LOW: UNCONFIRMED
     */
    public static DiscrepancySeverity forType(DiscrepancyType type) {
        return switch (type) {
            case MISSING_IN_PROVIDER, AMOUNT_MISMATCH -> HIGH;
            case STATUS_MISMATCH -> MEDIUM;
            case UNCONFIRMED -> LOW;
        };
    }
}
