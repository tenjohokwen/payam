---
phase: 03-orange-money-adapter
plan: "03"
subsystem: payments
tags: [orange-money, java, junit5, jackson, timezone, WAT, UTC]

# Dependency graph
requires:
  - phase: 03-orange-money-adapter
    provides: OrangeTimeUtil.parseOrangeTimestamp() correctly implemented but dead code

provides:
  - OrangeWebhookPayload.getCreatetimeAsInstant() — wires OrangeTimeUtil into production call site
  - OrangeTimeUtilTest — 3-case unit test suite documenting WAT→UTC conversion

affects:
  - 05-payment-orchestrator (consumes OrangeWebhookPayload createtime as Instant for event log timestamps)
  - 06-webhook-controller (calls getCreatetimeAsInstant() when processing push notifications)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "WAT timestamp consumption pattern: OrangeWebhookPayload.getCreatetimeAsInstant() is the designated parse entry point — callers must NOT call OrangeTimeUtil directly or use LocalDateTime.parse() (P5.1)"
    - "Null-safe derived getter: returns null for null/blank raw string, never throws NPE on absent JSON field"

key-files:
  created:
    - src/test/java/com/softropic/payam/orange/OrangeTimeUtilTest.java
  modified:
    - src/main/java/com/softropic/payam/orange/contract/OrangeWebhookPayload.java

key-decisions:
  - "getCreatetimeAsInstant() is the sole designated call site for OrangeTimeUtil.parseOrangeTimestamp() — Phase 5/6 consumers of OrangeWebhookPayload.createtime must use this method, not the raw String getter"
  - "No @JsonIgnore needed on getCreatetimeAsInstant() — Jackson ignores derived getters with no matching JSON field when @JsonIgnoreProperties(ignoreUnknown=true) is present"

patterns-established:
  - "WAT parse entry point: all Orange createtime consumers must call getCreatetimeAsInstant() — one canonical call site prevents 1-hour drift bug"

# Metrics
duration: 4min
completed: 2026-03-24
---

# Phase 3 Plan 03: WAT Timestamp Wiring Summary

**OrangeWebhookPayload.getCreatetimeAsInstant() wires the dead OrangeTimeUtil.parseOrangeTimestamp() into its production call site, closing the P5.1 WAT timezone drift gap with a 3-test unit suite**

## Performance

- **Duration:** 4 min
- **Started:** 2026-03-24T02:24:28Z
- **Completed:** 2026-03-24T02:28:00Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments

- Added `getCreatetimeAsInstant()` to `OrangeWebhookPayload` — `OrangeTimeUtil.parseOrangeTimestamp()` now has one confirmed production call site
- Added null/blank guard returning `null` so callers never get an NPE on absent `createtime` JSON field
- Created `OrangeTimeUtilTest` with 3 passing unit tests (no Spring context, no WireMock, no Testcontainers) documenting the WAT→UTC 1-hour subtraction
- All 8 `OrangeMoneyPortIT` tests continue to pass — zero regressions

## Task Commits

Each task was committed atomically:

1. **Task 1: Add getCreatetimeAsInstant() to OrangeWebhookPayload** - `a64ecfd` (feat)
2. **Task 2: Add OrangeTimeUtilTest unit test** - `5d3d709` (test)

**Plan metadata:** (see final commit below)

## Files Created/Modified

- `src/main/java/com/softropic/payam/orange/contract/OrangeWebhookPayload.java` — Added OrangeTimeUtil import, java.time.Instant import, and `getCreatetimeAsInstant()` method
- `src/test/java/com/softropic/payam/orange/OrangeTimeUtilTest.java` — New unit test class with 3 tests covering WAT→UTC conversion, null createtime, and webhook payload wiring

## Decisions Made

- **getCreatetimeAsInstant() is the sole call site:** Phase 5/6 consumers of `OrangeWebhookPayload.createtime` must call `getCreatetimeAsInstant()`, not the raw `getCreatetime()` String getter. This enforces the single-point-of-truth for WAT interpretation.
- **No @JsonIgnore needed:** `getCreatetimeAsInstant()` is a derived getter with no matching JSON field. Since `@JsonIgnoreProperties(ignoreUnknown=true)` is already present, Jackson correctly ignores it during deserialization without annotation.

## Deviations from Plan

None — plan executed exactly as written.

## Issues Encountered

- First `mvn surefire:test` call failed with "No tests matching pattern" because test classes had not been compiled. Added `mvn compiler:testCompile` step before running surefire directly (consistent with existing project patterns documented in STATE.md Pending Todos).

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness

- Phase 3 Gap B (must-have #5) is now closed: `OrangeTimeUtil.parseOrangeTimestamp()` has one confirmed production call site.
- Phase 3 is fully complete. All deferred gaps are classified and documented: Gap A (processWebhook state transition) deferred to Phase 6 SC-1/SC-2; Gap C (cashout/C2C) accepted as SC-3 ROADMAP deviation.
- Phase 4 (MTN Money Adapter) is unblocked.
- Phase 5/6 consumers: use `OrangeWebhookPayload.getCreatetimeAsInstant()` for all createtime consumption — do not call `OrangeTimeUtil.parseOrangeTimestamp()` directly or use the raw String getter for timestamp arithmetic.

---
*Phase: 03-orange-money-adapter*
*Completed: 2026-03-24*
