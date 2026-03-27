---
phase: 16-business-event-logging
plan: "05"
subsystem: reconciliation
tags: [logstash, structured-logging, reconciliation, kv, LOG-BUS-07]

requires:
  - phase: 16-01-business-event-logging
    provides: "Established LOG-BUS-01/05 kv() structured event pattern using StructuredArguments.kv"

provides:
  - "LOG-BUS-07: ReconciliationService.runForDate() emits kv('operation', 'reconciliation_run') with date, totalChecked, discrepancyCount, durationMs, status"
  - "Cross-provider total accumulation: runForProviderAndDate() returns int[] so both MTN and Orange totals are aggregated before emitting the summary event"

affects: [17-code-standards, observability, loki-queries]

tech-stack:
  added: []
  patterns:
    - "cross-provider accumulation pattern: private helper returns int[] {totalChecked, discrepancyCount} so runForDate() aggregates before emitting a single summary event"
    - "start timer declared before provider loop: long start = System.currentTimeMillis() as first statement in runForDate() so durationMs covers full execution including both providers"

key-files:
  created: []
  modified:
    - src/main/java/com/softropic/payam/reconciliation/service/ReconciliationService.java

key-decisions:
  - "runForProviderAndDate() return type changed from void to int[]{totalChecked, discrepancyCount} — only structural change needed; no behavioral changes to comparison logic"
  - "status='SUCCESS' is emitted unconditionally after the provider loop — provider exceptions are caught and logged inside the try/catch so runForDate() always completes normally; discrepancyCount communicates the financial outcome"
  - "Early-exit path (port == null) returns int[]{0,0} so the accumulator in runForDate() is never disrupted by a missing port"

patterns-established:
  - "LOG-BUS-07 reconciliation_run: single structured summary event after full cross-provider loop with totalChecked, discrepancyCount, durationMs aggregated across all providers"

duration: 5min
completed: 2026-03-27
---

# Phase 16 Plan 05: Business Event Logging — Reconciliation Summary Log

**`reconciliation_run` structured event in ReconciliationService with cross-provider totalChecked/discrepancyCount aggregation and full-method durationMs coverage**

## Performance

- **Duration:** ~5 min
- **Started:** 2026-03-27T00:00:47Z
- **Completed:** 2026-03-27T00:05:00Z
- **Tasks:** 1
- **Files modified:** 1

## Accomplishments

- Added `kv("operation", "reconciliation_run")` structured event to `runForDate()` covering all required fields: date, totalChecked, discrepancyCount, durationMs, status
- Refactored `runForProviderAndDate()` return type from `void` to `int[]` so cross-provider totals (MTN + Orange) can be accumulated before emitting the single summary event
- Timer declared as first statement in `runForDate()` so durationMs covers the full provider loop including all 4 Redis velocity lookups per provider

## Task Commits

Each task was committed atomically:

1. **Task 1: LOG-BUS-07 — Reconciliation summary log with cross-provider total accumulation** - `a01ee81` (feat)

## Files Created/Modified

- `src/main/java/com/softropic/payam/reconciliation/service/ReconciliationService.java` - Added static import for `kv`, changed `runForProviderAndDate()` return type to `int[]`, added accumulator variables and start timer to `runForDate()`, emits LOG-BUS-07 event after provider loop

## Decisions Made

- **`status="SUCCESS"` unconditional:** `runForDate()` always completes normally because provider exceptions are caught inside the try/catch. `discrepancyCount` communicates whether discrepancies were found — `status` reflects execution completion, not financial outcome.
- **`int[]` return for `runForProviderAndDate()`:** The minimal structural change that avoids re-querying totals or duplicating state. No changes to `compareTransaction()`, `buildDiscrepancy()`, `isTerminal()`, or `statusesMatch()`.
- **Early-exit returns `int[]{0, 0}`:** The missing-port case contributes zero to the aggregate — consistent with the port simply not existing, not an error in accumulation.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None. The `./mvnw` wrapper was missing its properties file, so system `mvn` was used instead. This is a pre-existing tooling configuration issue unrelated to the plan.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- LOG-BUS-07 complete. ReconciliationService emits a Loki-queryable `reconciliation_run` event after every daily run.
- All `reconciliation_run` events carry `discrepancyCount` — ops teams can alert on `discrepancyCount > 0` directly from Loki without querying the database.
- Ready for Phase 17 (code standards) or any remaining Phase 16 plans.

---
*Phase: 16-business-event-logging*
*Completed: 2026-03-27*
