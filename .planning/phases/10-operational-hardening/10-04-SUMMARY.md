---
phase: 10-operational-hardening
plan: 04
status: complete
completed: 2026-03-25
commits:
  - 2cf9ef5  # feat(10-04): add callback counters to PaymentMetricsService and both callback controllers
  - ce0ca4d  # feat(10-04): implement CALLBACK_ANOMALY metric in AlertEvaluationService and add AlertRuleIT test 5/5
---

# Plan 10-04 Summary: CALLBACK_ANOMALY Gap Closure

## What Was Built

Closed the single gap identified in Phase 10 verification: `AlertEvaluationService.computeMetricValue("CALLBACK_ANOMALY")` unconditionally returned -1.0, causing any CALLBACK_ANOMALY alert rule to be silently skipped every evaluation cycle.

### Task 1 — Callback counters in PaymentMetricsService and controllers

- Added `callbackReceivedCounter` (`callback.received.total`) and `callbackFailedCounter` (`callback.failed.total`) Micrometer Counter fields to `PaymentMetricsService`
- Added `recordCallbackReceived()` and `recordCallbackFailed()` public methods following the existing `recordSuccess/recordFailed` style
- Injected `PaymentMetricsService` into `OrangeCallbackController` — calls `recordCallbackReceived()` before every `processWebhook()` call; `recordCallbackFailed()` on exception
- Injected `PaymentMetricsService` into `MtnCallbackController` — same pattern; added `Logger` field (was absent)
- Both controllers still return `ResponseEntity.ok().build()` on exception (Orange/MTN expect 200 regardless — Pitfall 5 guard preserved)

### Task 2 — CALLBACK_ANOMALY ratio in AlertEvaluationService + AlertRuleIT test 5

- Replaced `case "CALLBACK_ANOMALY" -> -1.0` with a real ratio block:
  - reads `callback.received.total` and `callback.failed.total` via `getCounter()`
  - if `received < MINIMUM_SAMPLE_SIZE` (10): yields -1.0 (Pitfall 8 guard preserved — no false alarms at startup)
  - otherwise: yields `failed / received`
- Updated `test_noAlertBeforeMinimumSampleSize` comment to reflect new guard mechanism (no received counters incremented → received=0 < 10 → -1.0; assertion unchanged)
- Added `test_callbackAnomalyAlertFires()`: seeds rule with threshold=0.30; increments 12 received + 5 failed (ratio ≈ 0.417); verifies `AlertFiredEvent` fires with `metricName=CALLBACK_ANOMALY` and `actualValue >= 0.30`
- **AlertRuleIT: 5/5 pass** (4 existing + 1 new)

## Decisions

- `recordCallbackFailed()` called only on exception from port, not on every callback — matches the semantic: a "failed" callback is one that could not be correlated to a known transaction, not a callback for a payment that happened to fail
- Orange `ResponseEntity.ok()` on exception path retained — Orange requires 200 ACK regardless of processing outcome; exception path already logged via `log.warn`
- `yield -1.0` when `received < MINIMUM_SAMPLE_SIZE` preserved — consistent with FAILURE_RATE and FRAUD_SPIKE_RATE guards; prevents alert storm at startup when counter values are low

## Verification

All must-haves satisfied:
- CALLBACK_ANOMALY `computeMetricValue` returns real ratio in [0.0, 1.0] when total >= 10
- AlertFiredEvent fires for CALLBACK_ANOMALY when threshold breached (AlertRuleIT test 5 passes)
- Both callback controllers increment `callback.received.total` on every accepted callback
- Phase 10 Truth #2 now fully satisfied: "Alert rules fire when failure rate, fraud spike rate, **or** callback anomaly thresholds are breached"
