package com.softropic.payam.platform.admin.contract;

/**
 * Snapshot of a single provider's circuit breaker state.
 *
 * @param state        Resilience4j circuit breaker state name (CLOSED, OPEN, HALF_OPEN, …)
 * @param failureRate  current failure-rate percentage (-1 if insufficient calls recorded)
 * @param bufferedCalls number of calls in the current sliding window
 * @param failedCalls  number of failed calls in the current sliding window
 */
public record ProviderStatusDto(
        String state,
        float failureRate,
        int bufferedCalls,
        int failedCalls) {
}
