---
phase: 16-business-event-logging
plan: 02
subsystem: payments
tags: [structured-logging, logstash, loki, state-machine, transaction-lifecycle]

# Dependency graph
requires:
  - phase: 16-01
    provides: kv() import already present in PaymentOrchestrator; LOG-BUS-01 initiate_payment event pattern established
provides:
  - LOG-BUS-02 transaction_state_change structured log event at all 9 applyTransition() call sites
  - actor field identifying caller (ORCHESTRATOR, WEBHOOK_DOUBLE_CHECK, MTN_POLLER, ORANGE_POLLER)
  - fromState/toState fields enabling full state machine history reconstruction in Loki
affects: [phase-17-code-standards, future-observability-queries]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "LOG-BUS-02 pattern: log.info state change immediately after applyTransition(), before eventLogService.append()"
    - "actor field as string enum: ORCHESTRATOR | WEBHOOK_DOUBLE_CHECK | MTN_POLLER | ORANGE_POLLER"
    - "fromState hardcoded as TransactionStatus.X.name() at poller sites — avoids reading mutated status post-transition"

key-files:
  created: []
  modified:
    - src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java
    - src/main/java/com/softropic/payam/webhook/service/WebhookTransitionService.java
    - src/main/java/com/softropic/payam/mtn/service/MtnStatusPollerJob.java
    - src/main/java/com/softropic/payam/orange/service/OrangeStatusPollerJob.java

key-decisions:
  - "LOG-BUS-02 log placed after applyTransition() and before eventLogService.append() at every site — log and event log are co-located for correlation"
  - "fromState hardcoded as TransactionStatus.PROCESSING.name() at poller/webhook sites — post-transition status is already mutated; hardcoded constant is safe since pollers only ever transition from PROCESSING"
  - "kv import added to WebhookTransitionService, MtnStatusPollerJob, OrangeStatusPollerJob — PaymentOrchestrator already had it from 16-01"

patterns-established:
  - "State change log co-located with eventLogService.append(): both signals emitted at same call site"
  - "actor string enum matches eventLogService actor parameter for unified traceability across Loki and event log table"

# Metrics
duration: 5min
completed: 2026-03-27
---

# Phase 16 Plan 02: Business Event Logging — Transaction State Changes Summary

**Structured LOG-BUS-02 transaction_state_change events at all 9 applyTransition() sites across 4 files, enabling full payment state machine history reconstruction in Loki with fromState, toState, and actor**

## Performance

- **Duration:** 5 min
- **Started:** 2026-03-27T00:11:56Z
- **Completed:** 2026-03-27T00:16:36Z
- **Tasks:** 2
- **Files modified:** 4

## Accomplishments
- Added 4 state change log calls in PaymentOrchestrator: 3 inline (INITIATED→AUTH_PENDING, AUTH_PENDING→AUTHORIZED, AUTHORIZED→PROCESSING) and 1 in applyFailed() (from→FAILED), all with actor=ORCHESTRATOR
- Added 1 state change log call in WebhookTransitionService.applyFinalTransition() with actor=WEBHOOK_DOUBLE_CHECK, placed between applyTransition() and eventLogService.append()
- Added 2 state change log calls in MtnStatusPollerJob: timeout FAILED path and terminal status path, both with actor=MTN_POLLER
- Added 2 state change log calls in OrangeStatusPollerJob: timeout FAILED path and terminal status path, both with actor=ORANGE_POLLER
- All 9 sites verified by grep (exact count), compile clean, 135 tests passing

## Task Commits

Each task was committed atomically:

1. **Task 1: LOG-BUS-02 State change logs in PaymentOrchestrator** - `0702098` (feat)
2. **Task 2: LOG-BUS-02 State change logs in webhook and poller services** - `1e11f72` (feat)

**Plan metadata:** (docs commit — pending)

## Files Created/Modified
- `src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java` - 4 kv() state change log calls added (3 inline transitions + applyFailed); kv import was already present from 16-01
- `src/main/java/com/softropic/payam/webhook/service/WebhookTransitionService.java` - 1 kv() state change log call added; kv import added
- `src/main/java/com/softropic/payam/mtn/service/MtnStatusPollerJob.java` - 2 kv() state change log calls added (timeout + terminal); kv import added
- `src/main/java/com/softropic/payam/orange/service/OrangeStatusPollerJob.java` - 2 kv() state change log calls added (timeout + terminal); kv import added

## Decisions Made
- **fromState hardcoded at poller/webhook sites:** `TransactionStatus.PROCESSING.name()` used instead of `tx.getTxStatus().name()` — by the time the log runs, `locked.applyTransition(next)` has already mutated the status field. Hardcoded constant is safe since pollers and webhook double-check only ever transition from PROCESSING.
- **Log placement between applyTransition() and eventLogService.append():** Consistent with plan spec; co-locates the structured Loki event immediately adjacent to the event sourcing append, making the two signals easy to correlate in debugging.
- **kv import not duplicated in PaymentOrchestrator:** Plan noted it was already present from 16-01; confirmed and skipped.

## Deviations from Plan

None — plan executed exactly as written.

## Issues Encountered

None. Maven wrapper was missing but system `mvn` was available and worked identically.

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness
- LOG-BUS-02 complete. All 9 applyTransition() call sites across the payment state machine now emit structured Loki-queryable events.
- Phase 16 is now fully complete (all 5 plans: 16-01 through 16-05 done).
- Phase 17 (Code Standards) can proceed — no blockers from this phase.

---
*Phase: 16-business-event-logging*
*Completed: 2026-03-27*
