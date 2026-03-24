package com.softropic.payam.fraud.contract;

/**
 * Result of a fraud evaluation for a payment attempt.
 *
 * <p>Use the static factories {@link #allow(int)} and {@link #block(int, String)} to construct.
 *
 * @param blocked   true if the payment should be blocked; false if it may proceed
 * @param riskScore weighted risk score 0–100 computed from active signal rule weights
 * @param reason    human-readable reason for blocking; null when blocked=false
 */
public record FraudDecision(boolean blocked, int riskScore, String reason) {

    /** Payment is allowed — risk score recorded but no block applied. */
    public static FraudDecision allow(int riskScore) {
        return new FraudDecision(false, riskScore, null);
    }

    /** Payment is blocked — reason identifies the triggering signal. */
    public static FraudDecision block(int riskScore, String reason) {
        return new FraudDecision(true, riskScore, reason);
    }
}
