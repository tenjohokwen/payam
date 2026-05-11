package com.softropic.payam.payment.fraud.contract;

/**
 * Enumeration of fraud velocity signals.
 *
 * <p>Each value maps to a {@code signal_name} in the {@code main.fraud_rule} DB table.
 * The {@link #getSignalName()} method returns the exact string stored in the DB — used by
 * {@link com.softropic.payam.fraud.service.FraudRuleCache#findBySignalName(String)} and
 * {@link com.softropic.payam.fraud.service.VelocityCheckService} for Redis key construction.
 */
public enum FraudSignal {

    /** Too many payment attempts from the same client IP within the configured window. */
    IP_VELOCITY("IP_VELOCITY"),

    /** Too many payment attempts to the same MSISDN within the configured window. */
    MSISDN_VELOCITY("MSISDN_VELOCITY"),

    /** Too many payment attempts from the same tenant application within the configured window. */
    APP_VELOCITY("APP_VELOCITY"),

    /**
     * SIM-sharing household pattern — multiple distinct payments from the same MSISDN prefix
     * within a longer window. Lower default weight to reduce false positives (Pitfall 6).
     */
    MSISDN_HOUSEHOLD("MSISDN_HOUSEHOLD");

    private final String signalName;

    FraudSignal(String signalName) {
        this.signalName = signalName;
    }

    /** Returns the signal_name value as stored in the fraud_rule DB table. */
    public String getSignalName() {
        return signalName;
    }
}
