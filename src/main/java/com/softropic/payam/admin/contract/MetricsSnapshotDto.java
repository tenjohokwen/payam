package com.softropic.payam.admin.contract;

import java.time.Instant;
import java.util.Map;

/**
 * SSE snapshot DTO pushed every 5 seconds by PaymentMetricsService to connected admin clients.
 *
 * <p>providerLatencyMs carries the latest recorded wall-clock latency (in milliseconds) per
 * provider name (e.g. "ORANGE", "MTN"). Updated each time PaymentOrchestrator records a
 * completed provider call via recordProviderLatency().
 */
public record MetricsSnapshotDto(
        long paymentSuccessTotal,
        long paymentFailedTotal,
        long fraudBlockedTotal,
        double tpsLast5s,
        long processingCount,
        Map<String, Long> providerLatencyMs,
        Instant timestamp
) {}
