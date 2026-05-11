package com.softropic.payam.platform.admin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.payam.platform.admin.contract.MetricsSnapshotDto;
import com.softropic.payam.payment.ledger.contract.TransactionStatus;
import com.softropic.payam.payment.ledger.repo.TransactionRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central metrics service for payment domain counters, timers, and SSE live-stream management.
 *
 * <p>Registers five Micrometer Counters (payment.success.total, payment.failed.total,
 * payment.fraud.blocked.total, callback.received.total, callback.failed.total) and a per-provider
 * Timer (payment.provider.latency) with the MeterRegistry. These are exposed via /manage/prometheus
 * for Grafana.
 *
 * <p>Also manages a list of SseEmitter connections (via addEmitter) and pushes a
 * MetricsSnapshotDto to all connected clients every 5 seconds via @Scheduled(pushMetrics).
 * Dead emitters (timed-out or disconnected) are removed eagerly via onCompletion/onTimeout/onError
 * callbacks and lazily during each push cycle.
 */
@Service
public class PaymentMetricsService {

    private final Counter successCounter;
    private final Counter failedCounter;
    private final Counter fraudBlockedCounter;
    private final Counter callbackReceivedCounter;
    private final Counter callbackFailedCounter;
    private final MeterRegistry registry;
    private final TransactionRepository transactionRepository;
    private final ObjectMapper objectMapper;
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /** For TPS computation: track count at last push interval */
    private final AtomicLong lastSuccessSnapshot = new AtomicLong(0);
    private static final int PUSH_INTERVAL_SECONDS = 5;

    /** Latest recorded latency per provider name, updated on each provider call */
    private final ConcurrentHashMap<String, Long> latestProviderLatencyMs = new ConcurrentHashMap<>();

    public PaymentMetricsService(MeterRegistry registry,
                                 TransactionRepository transactionRepository,
                                 ObjectMapper objectMapper) {
        this.registry = registry;
        this.transactionRepository = transactionRepository;
        this.objectMapper = objectMapper;
        this.successCounter = Counter.builder("payment.success.total")
                .description("Total successful payments")
                .register(registry);
        this.failedCounter = Counter.builder("payment.failed.total")
                .description("Total failed payments")
                .register(registry);
        this.fraudBlockedCounter = Counter.builder("payment.fraud.blocked.total")
                .description("Total fraud-blocked payments")
                .register(registry);
        this.callbackReceivedCounter = Counter.builder("callback.received.total")
                .description("Total inbound provider callbacks received")
                .register(registry);
        this.callbackFailedCounter = Counter.builder("callback.failed.total")
                .description("Total inbound provider callbacks that failed to process (no matching transaction)")
                .register(registry);
    }

    /**
     * Increment the success counter. Called by PaymentOrchestrator on final SUCCESS path
     * (after idempotencyService.store confirms the payment reached PROCESSING).
     *
     * @param provider provider name (currently unused in counter; reserved for future tagging)
     */
    public void recordSuccess(String provider) {
        successCounter.increment();
    }

    /**
     * Increment the failed counter. Called by PaymentOrchestrator on non-fraud failure paths.
     *
     * @param provider provider name (may be null for routing failures)
     */
    public void recordFailed(String provider) {
        failedCounter.increment();
    }

    /**
     * Increment the fraud-blocked counter. Called by PaymentOrchestrator when the fraud engine
     * returns a blocked decision.
     */
    public void recordFraudBlocked() {
        fraudBlockedCounter.increment();
    }

    /**
     * Increment the callback received counter. Called by OrangeCallbackController and
     * MtnCallbackController on every accepted inbound callback (after dedup and HMAC checks pass).
     */
    public void recordCallbackReceived() {
        callbackReceivedCounter.increment();
    }

    /**
     * Increment the callback failed counter. Called when a callback cannot be correlated to a
     * known transaction (e.g., OrangeMoneyPort or MtnMoMoPort throws on lookup).
     */
    public void recordCallbackFailed() {
        callbackFailedCounter.increment();
    }

    /**
     * Records the wall-clock duration of a provider gateway call.
     * Registers a Micrometer Timer tagged with the provider name and updates
     * the in-memory latency map used by the SSE snapshot.
     *
     * @param provider provider name (e.g. "ORANGE", "MTN")
     * @param millis   elapsed time in milliseconds
     */
    public void recordProviderLatency(String provider, long millis) {
        io.micrometer.core.instrument.Timer.builder("payment.provider.latency")
                .description("Provider gateway call duration")
                .tag("provider", provider)
                .register(registry)
                .record(millis, TimeUnit.MILLISECONDS);
        latestProviderLatencyMs.put(provider, millis);
    }

    /**
     * Register a new SSE emitter for live metrics streaming.
     * Cleanup callbacks are registered to remove the emitter on disconnect, timeout, or error,
     * preventing memory leaks from dead connections.
     *
     * @param emitter SSE emitter created by AdminMetricsResource.stream()
     */
    public void addEmitter(SseEmitter emitter) {
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(ex -> emitters.remove(emitter));
    }

    /**
     * Push a MetricsSnapshotDto to all connected SSE clients.
     * Runs every 5 seconds. Fast-path returns immediately if no clients are connected.
     * Dead emitters (failed send) are collected and batch-removed after the push loop
     * to avoid ConcurrentModificationException on CopyOnWriteArrayList.
     */
    @Observed(name = "scheduler.metrics-push")
    @Scheduled(fixedDelay = PUSH_INTERVAL_SECONDS * 1000)
    public void pushMetrics() {
        if (emitters.isEmpty()) return;

        long currentSuccess = (long) successCounter.count();
        long delta = currentSuccess - lastSuccessSnapshot.getAndSet(currentSuccess);
        double tps = (double) delta / PUSH_INTERVAL_SECONDS;
        long processingCount = transactionRepository.countByTxStatus(TransactionStatus.PROCESSING);

        MetricsSnapshotDto snapshot = new MetricsSnapshotDto(
                currentSuccess,
                (long) failedCounter.count(),
                (long) fraudBlockedCounter.count(),
                tps,
                processingCount,
                Map.copyOf(latestProviderLatencyMs),
                Instant.now()
        );

        String json;
        try {
            json = objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            return; // skip this push cycle on serialization error
        }

        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().data(json));
            } catch (Exception e) {
                dead.add(emitter);
            }
        }
        emitters.removeAll(dead);
    }
}
