---
phase: 08-admin-dashboard
plan: 02
subsystem: admin-metrics
tags: [micrometer, sse, counters, timers, spring-scheduling, payment-orchestrator]

dependency-graph:
  requires:
    - "08-01: admin package, countByTxStatus() on TransactionRepository"
    - "05-01: PaymentOrchestrator with constructor injection pattern"
    - "07-02: FraudScoringService wired into orchestrator (FRAUD_BLOCKED path)"
    - "config: ObservabilityConfig (MeterRegistry), AsyncConfig (@EnableScheduling)"
  provides:
    - "PaymentMetricsService: payment.success.total, payment.failed.total, payment.fraud.blocked.total Counters"
    - "PaymentMetricsService: payment.provider.latency Timer tagged with provider name"
    - "SSE emitter management with onCompletion/onTimeout/onError cleanup"
    - "@Scheduled pushMetrics() every 5s: MetricsSnapshotDto with providerLatencyMs map"
    - "AdminMetricsResource: GET /v1/admin/metrics/stream (text/event-stream, JWT+ROLE_ADMIN)"
    - "PaymentOrchestrator records metrics on all outcome paths and times provider dispatch"
  affects:
    - "08-03: /manage/prometheus exposes payment.* counters for Grafana dashboards"
    - "Quasar dashboard: SSE stream at /v1/admin/metrics/stream delivers live metric snapshots"

tech-stack:
  added: []
  patterns:
    - "Micrometer Counter.builder().register(registry) for payment domain counters"
    - "Micrometer Timer.builder().tag(provider).register(registry) for per-provider latency"
    - "CopyOnWriteArrayList<SseEmitter> for thread-safe emitter list"
    - "@Scheduled(fixedDelay) SSE push with dead-emitter batch removal"
    - "try/finally around provider dispatch for reliable latency recording"
    - "OrchestratorError branching in applyFailed() for fraud vs. general failure metrics"

key-files:
  created:
    - src/main/java/com/softropic/payam/admin/contract/MetricsSnapshotDto.java
    - src/main/java/com/softropic/payam/admin/service/PaymentMetricsService.java
    - src/main/java/com/softropic/payam/admin/api/AdminMetricsResource.java
  modified:
    - src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java
    - src/main/resources/application.yaml

decisions:
  - id: "08-02-A"
    decision: "@EnableScheduling already present in AsyncConfig — no new config class needed"
    rationale: "AsyncConfig in email.config package already has @EnableScheduling; no duplicate needed"
  - id: "08-02-B"
    decision: "providerStart declared before outer try block; inner try/finally wraps only port.initiateMerchantPayment()"
    rationale: "Ensures latency is recorded for any outcome (success or exception) from the provider call; outer catch blocks still handle errors correctly without duplicate recording"
  - id: "08-02-C"
    decision: "recordSuccess() called after idempotencyService.store() (not after state transitions)"
    rationale: "Plan spec locates recordSuccess after store — ensures payment is fully committed before counter increments"

metrics:
  duration: "~3 min"
  completed: "2026-03-24"
---

# Phase 8 Plan 02: Micrometer Metrics + SSE Stream Summary

**One-liner:** Micrometer counters/timer for payment outcomes and provider latency, @Scheduled SSE push to AdminMetricsResource, and PaymentOrchestrator wired to record all success/failed/fraud-blocked events with try/finally latency timing.

## What Was Built

### PaymentMetricsService (`com.softropic.payam.admin.service`)

Central metrics bean registered in Spring context as `@Service`:

- Three Counters registered at construction time: `payment.success.total`, `payment.failed.total`, `payment.fraud.blocked.total`
- Per-provider Timer registered on first call: `payment.provider.latency` tagged with `provider` (e.g. "ORANGE", "MTN")
- In-memory `ConcurrentHashMap<String, Long> latestProviderLatencyMs` updated on each `recordProviderLatency()` call
- `CopyOnWriteArrayList<SseEmitter> emitters` with per-emitter `onCompletion/onTimeout/onError` cleanup callbacks to prevent memory leaks
- `@Scheduled(fixedDelay=5000) pushMetrics()`: builds `MetricsSnapshotDto`, serializes to JSON, fans out to all connected emitters; dead emitters batch-removed after push loop; fast-path skip if emitter list is empty
- TPS calculation: delta between current success count and `lastSuccessSnapshot` AtomicLong, divided by push interval seconds

### MetricsSnapshotDto (`com.softropic.payam.admin.contract`)

Java record with fields: `paymentSuccessTotal`, `paymentFailedTotal`, `fraudBlockedTotal`, `tpsLast5s`, `processingCount`, `Map<String, Long> providerLatencyMs`, `timestamp (Instant)`.

### AdminMetricsResource (`com.softropic.payam.admin.api`)

`GET /v1/admin/metrics/stream` — produces `text/event-stream`, protected by `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)`. Returns `SseEmitter(Long.MAX_VALUE)` registered with `PaymentMetricsService`; no `send()` on the request thread (push is @Scheduled).

### PaymentOrchestrator wiring

- Constructor now takes `PaymentMetricsService metricsService` as last argument (follows existing pattern)
- `long providerStart = System.currentTimeMillis()` captured before provider dispatch
- Inner `try/finally` around `port.initiateMerchantPayment(cmd)` records `recordProviderLatency(provider.name(), elapsed)`
- `recordSuccess(provider.name())` called after `idempotencyService.store()` on the success path
- `applyFailed()` now branches: `FRAUD_BLOCKED` → `recordFraudBlocked()`; all other errors → `recordFailed(null)`

### application.yaml

Added `spring.mvc.async.request-timeout: -1` under the `spring:` section to prevent Tomcat's default 10-second async timeout from closing SSE connections.

## Decisions Made

| Decision | What | Why |
|----------|------|-----|
| 08-02-A | @EnableScheduling already in AsyncConfig | No new config needed; email.config.AsyncConfig already covers it |
| 08-02-B | providerStart before outer try; inner try/finally around port call only | Reliable latency recording on success and exception; outer catches still work cleanly |
| 08-02-C | recordSuccess after idempotencyService.store() | Plan spec placement; ensures full commit before increment |

## Files Created/Modified

| File | Action | Purpose |
|------|--------|---------|
| MetricsSnapshotDto.java | Created | SSE push payload record with providerLatencyMs map |
| PaymentMetricsService.java | Created | Counter/Timer registration, emitter management, @Scheduled push |
| AdminMetricsResource.java | Created | GET /v1/admin/metrics/stream SSE endpoint |
| PaymentOrchestrator.java | Modified | metricsService injection + record calls on all outcome paths |
| application.yaml | Modified | spring.mvc.async.request-timeout=-1 |

## Deviations from Plan

None — plan executed exactly as written.

## Verification Results

All success criteria met:

- `payment.success.total`, `payment.failed.total`, `payment.fraud.blocked.total` Counters registered at construction
- `payment.provider.latency` Timer with `provider` tag registered via `recordProviderLatency()`
- `MetricsSnapshotDto` has `Map<String, Long> providerLatencyMs` field
- `AdminMetricsResource` at `/v1/admin/metrics/stream`: `produces = TEXT_EVENT_STREAM_VALUE`, `@PreAuthorize(HAS_ADMIN_ROLE)`
- `PaymentOrchestrator`: try/finally around provider dispatch, recordSuccess after store, applyFailed branches on FRAUD_BLOCKED
- SSE cleanup: `onCompletion/onTimeout/onError` callbacks registered in `addEmitter()`
- `application.yaml`: `spring.mvc.async.request-timeout: -1` added
- `mvn compiler:compile`: BUILD SUCCESS (~11s, no errors)

## Next Phase Readiness

Phase 8 Plan 03 can proceed:

- `/manage/prometheus` already exposed via `management.endpoints.web.exposure.include: "*"` — counters and timer will appear automatically
- `AdminMetricsResource` security is in place; `/v1/admin/**` routes to JWT chain via existing NegatedRequestMatcher
- `PaymentMetricsService` is a Spring-managed `@Service` bean available for injection in future admin services
