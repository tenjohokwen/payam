package com.softropic.payam.payment.contract;

import com.softropic.payam.common.exception.ErrorCode;

/**
 * Standardized error vocabulary for the payment orchestration layer.
 *
 * <p>Each entry maps to a specific failure mode encountered during payment initiation:
 * <ul>
 *   <li>{@link #PROVIDER_UNAVAILABLE} — Resilience4j circuit breaker is open (CallNotPermittedException)</li>
 *   <li>{@link #SUBSCRIBER_INACTIVE} — Subscriber's mobile money account is inactive or not found</li>
 *   <li>{@link #PROVIDER_ERROR} — Provider returned a 4xx/5xx HTTP error (HttpClientException)</li>
 *   <li>{@link #UNKNOWN_MSISDN_PREFIX} — MSISDN prefix not recognized as any Cameroonian operator</li>
 *   <li>{@link #PAYMENT_ALREADY_PROCESSING} — Duplicate in-flight request detected via idempotency key</li>
 *   <li>{@link #FRAUD_BLOCKED} — Payment blocked by fraud engine (velocity or score threshold). Maps to HTTP 422.</li>
 * </ul>
 */
public enum OrchestratorError implements ErrorCode {

    /** Resilience4j circuit is open — provider temporarily unavailable. Maps to HTTP 503. */
    PROVIDER_UNAVAILABLE,

    /** Subscriber's account is inactive (SubscriberInactiveException / MtnAccountInactiveException). Maps to HTTP 422. */
    SUBSCRIBER_INACTIVE,

    /** Provider returned a 4xx/5xx HTTP error (HttpClientException). Maps to HTTP 502. */
    PROVIDER_ERROR,

    /** MSISDN prefix not recognized — cannot route to Orange or MTN. Maps to HTTP 422. */
    UNKNOWN_MSISDN_PREFIX,

    /** Another request with the same idempotency key is still in flight (RESERVED sentinel). Maps to HTTP 202. */
    PAYMENT_ALREADY_PROCESSING,

    /** Payment blocked by fraud engine — velocity threshold exceeded or risk score too high. Maps to HTTP 422. */
    FRAUD_BLOCKED;

    @Override
    public String getErrorCode() {
        return this.name();
    }
}
