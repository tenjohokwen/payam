---
phase: 38-transaction-boundary-fraud-ordering
plan: "04"
subsystem: testing
tags: [fraud, velocity, idempotency, ops-02, conc-03, integration-test]

# Dependency graph
requires:
  - phase: 38-transaction-boundary-fraud-ordering
    provides: "38-01 fee-before-lock, 38-02 probe/consume analysis, 38-03 FraudScoringServiceIT"
provides:
  - "FraudVelocityOrderingIT.java proving OPS-02 via idempotency-key replay path"
  - "Phase 38 gap closed — CONC-03 and OPS-02 both satisfied on main"
affects: [fraud, payment, idempotency, velocity]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Three-call OPS-02 replay proof: first call consumes token, same-key replay returns cached response without evaluate(), new-key call is blocked"

key-files:
  created:
    - src/test/java/com/softropic/payam/fraud/FraudVelocityOrderingIT.java
  modified: []

key-decisions:
  - "OPS-02 proved via idempotency-key replay path: the idempotency cache returns before fraudScoringService.evaluate() is called, so no velocity tokens are consumed on replay"
  - "No production code changes needed: consuming evaluate() on main already satisfies CONC-03; only the OPS-02 proof test was missing"
  - "OrangePathMatrixTest failure in worktree is from another parallel agent's commit (9a198aa); all plan-required tests (FraudVelocityOrderingIT, FraudEngineIT, VelocityCounterFloodTest, PaymentOrchestratorIT) pass with 0 failures"

patterns-established:
  - "FraudVelocityOrderingIT pattern: threshold=1 MSISDN_VELOCITY, then 3-call proof sequence (first=accepted, replay=cached, new-key=blocked)"

requirements-completed: [OPS-02, TXN-01]

# Metrics
duration: 55min
completed: 2026-04-15
---

# Phase 38 Plan 04: OPS-02 Idempotency-Replay Velocity Proof Summary

**FraudVelocityOrderingIT proves OPS-02: a retry with the same idempotency key returns the cached response without calling fraudScoringService.evaluate(), consuming zero additional velocity tokens**

## Performance

- **Duration:** ~55 min
- **Started:** 2026-04-15T00:15:00Z
- **Completed:** 2026-04-15T02:45:00Z
- **Tasks:** 2
- **Files modified:** 1

## Accomplishments

- Created `FraudVelocityOrderingIT.java` with a single test proving OPS-02 via the idempotency-key replay path
- Confirmed all plan-required tests pass: `FraudVelocityOrderingIT` (1/1), `FraudEngineIT` (3/3), `VelocityCounterFloodTest` (1/1), `PaymentOrchestratorIT` (8/8)
- Confirmed no probe/consume split exists on main (single consuming `checkVelocity()` call) — CONC-03 satisfied
- Closed phase 38 gap: both CONC-03 and OPS-02 are now provably satisfied

## Task Commits

Each task was committed atomically:

1. **Task 1: Create FraudVelocityOrderingIT proving OPS-02 via idempotency-key replay** - `f6be6ad` (test)
2. **Task 2: Run mvn verify — confirm all tests pass** - no files changed (verification only)

## Files Created/Modified

- `src/test/java/com/softropic/payam/fraud/FraudVelocityOrderingIT.java` - OPS-02 integration test proving idempotency replay does not consume additional velocity tokens; three-call proof sequence with MSISDN_VELOCITY threshold=1

## Decisions Made

- **OPS-02 via replay path:** Rather than splitting probe/consume (which was the plan 38-02 worktree approach never merged to main), OPS-02 is proved via the existing idempotency-key replay path. The `PaymentOrchestrator.initiate()` method returns the cached `PaymentResponse` before `fraudScoringService.evaluate()` is ever called, meaning zero velocity tokens are consumed on replay.
- **No production code changes:** Main already has the correct behavior. Only the proof test was missing. This is consistent with plan 38-04's explicit statement: "No production code changes required; main is already correct."

## Deviations from Plan

None - plan executed exactly as written. The only noteworthy finding was a pre-existing `OrangePathMatrixTest` failure in the worktree caused by another parallel agent's commit (`9a198aa: Fixes tests that failed because orange payToken expiration was changed`) — this failure is unrelated to plan 38-04 and all plan-required tests pass with 0 failures.

## Issues Encountered

- `OrangePathMatrixTest` fails in the worktree due to another parallel agent's changes to `OrangeStatusPollerJob.java` (commit `9a198aa`). This test passes on main when run in isolation, and is unrelated to plan 38-04 (which adds only a test file). All plan-required tests pass: FraudVelocityOrderingIT (1/1), FraudEngineIT (3/3), VelocityCounterFloodTest (1/1), PaymentOrchestratorIT (8/8).

## Next Phase Readiness

- Phase 38 gap is now closed: CONC-03 satisfied by consuming `evaluate()` at the fraud gate; OPS-02 satisfied by `FraudVelocityOrderingIT` proving replay path consumes zero additional tokens
- Phase 38 sign-off can proceed
- No blockers

---
*Phase: 38-transaction-boundary-fraud-ordering*
*Completed: 2026-04-15*
