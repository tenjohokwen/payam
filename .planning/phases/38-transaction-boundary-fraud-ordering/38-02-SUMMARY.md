---
phase: 38-transaction-boundary-fraud-ordering
plan: 02
subsystem: payments
tags: [spring, fraud, bucket4j, redis, velocity, ops-02, idempotency, testcontainers, spybean]

# Dependency graph
requires:
  - phase: 38-01
    provides: TXN-01 fee evaluation ordering fix, PaymentOrchestrator post-38-01 state
provides:
  - VelocityCheckService.probeVelocity (non-consuming Bucket4j estimateAbilityToConsume probe)
  - FraudScoringService.probe() (score without token consumption)
  - FraudScoringService.consumeTokens() (deduct tokens on success path only)
  - PaymentOrchestrator.initiate() with probe-before-provider, consume-after-store ordering
  - FraudVelocityOrderingIT proving OPS-02 ordering invariant
affects: [payment-orchestration, fraud-engine, velocity-checks]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "OPS-02 split pattern: probe() checks velocity without consuming (Bucket4j estimateAbilityToConsume) — consumeTokens() deducts only after successful idempotency store"
    - "Bucket4j 8.x non-consuming probe: bucket.estimateAbilityToConsume(1).canBeConsumed() — read-only, no Redis mutation"
    - "consumeTokens() is fail-open: RuntimeException caught and logged; rate-limit accounting must not break payment success path"
    - "@SpyBean IdempotencyService + doThrow + doCallRealMethod for ordered-behavior integration tests"

key-files:
  created:
    - src/test/java/com/softropic/payam/fraud/FraudVelocityOrderingIT.java
  modified:
    - src/main/java/com/softropic/payam/fraud/service/VelocityCheckService.java
    - src/main/java/com/softropic/payam/fraud/service/FraudScoringService.java
    - src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java

key-decisions:
  - "probe() duplicates evaluate() body but substitutes probeVelocity for checkVelocity — avoids shared code path that could accidentally consume tokens"
  - "consumeTokens() discards boolean return values from checkVelocity — the block decision was already made by probe(); consumption is a side-effect-only call"
  - "consumeTokens() placed BEFORE metricsService.recordSuccess() on the success path — both are post-store, neither is in a catch block"
  - "TXN-01 fix also applied in this worktree: fee evaluation hoisted above SELECT...FOR UPDATE lock (worktree branched before 38-01 was merged)"

patterns-established:
  - "Pattern: split consuming side effects (velocity token deduction) from decision logic — probe/consume duality for any Redis rate-limit operation that precedes a fallible downstream write"
  - "Pattern: @SpyBean + doThrow + doCallRealMethod for simulating single-call failures in Spring IT tests without mocking the entire bean"

requirements-completed: [OPS-02]

# Metrics
duration: 45min
completed: 2026-04-14
---

# Phase 38 Plan 02: OPS-02 Velocity Token Ordering Summary

**Velocity token consumption split into probe (non-consuming, before provider) and consumeTokens (consuming, after idempotency store success) — a failed store() no longer depletes a rate-limit slot.**

## Performance

- **Duration:** ~45 min
- **Completed:** 2026-04-14
- **Tasks:** 3
- **Files created:** 1
- **Files modified:** 3

## Accomplishments

- OPS-02 satisfied: `fraudScoringService.probe(cmd)` called at fraud step (non-consuming) — `fraudScoringService.consumeTokens(cmd)` called only after `idempotencyService.store()` succeeds
- `VelocityCheckService.probeVelocity()` added — uses Bucket4j 8.x `estimateAbilityToConsume(1).canBeConsumed()` (read-only, no Redis token deducted)
- `FraudScoringService.probe()` added — identical logic to `evaluate()` but delegates to `probeVelocity`; no side effects
- `FraudScoringService.consumeTokens()` added — calls `checkVelocity` for all 4 signals, swallows RuntimeException (fail-open rate accounting)
- Existing `evaluate()` and `checkVelocity()` unchanged — `FraudScoringServiceIT` regression unaffected
- `FraudVelocityOrderingIT.velocityTokenNotConsumedOnStoreFailure()` proves OPS-02: MSISDN_VELOCITY threshold=1, first call has store() forced to throw, second call from same MSISDN succeeds (202) proving no token was consumed
- TXN-01 fix also included in this worktree (fee evaluation hoisted above SELECT...FOR UPDATE lock — worktree branched before 38-01 was applied to main)

## Task Commits

Each task was committed atomically:

1. **Task 1: FraudVelocityOrderingIT scaffold (RED test)** - `5501378` (test)
2. **Task 2: probeVelocity + probe/consumeTokens** - `85a1799` (feat)
3. **Task 3: Rewire PaymentOrchestrator** - `4c3ae6d` (feat)

## Files Created/Modified

- `src/test/java/com/softropic/payam/fraud/FraudVelocityOrderingIT.java` - New IT proving OPS-02 ordering invariant
- `src/main/java/com/softropic/payam/fraud/service/VelocityCheckService.java` - Added probeVelocity (non-consuming)
- `src/main/java/com/softropic/payam/fraud/service/FraudScoringService.java` - Added probe() and consumeTokens()
- `src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java` - evaluate() replaced with probe(); consumeTokens() after store()

## Decisions Made

- `probe()` duplicates `evaluate()` body substituting `probeVelocity` for `checkVelocity` — no shared code path that could accidentally consume tokens
- `consumeTokens()` placed before `metricsService.recordSuccess()` — both are post-store, neither in a catch block
- TXN-01 fee hoist also applied since this worktree branched from a state before 38-01 was merged

## Deviations from Plan

### Auto-applied: TXN-01 fix in worktree

**Found during:** Task 3 (reading PaymentOrchestrator in worktree)
**Issue:** Worktree branched from `17eb5ae` which predates Plan 38-01 (TXN-01 fee evaluation hoist). The fee evaluation inside lock bug was still present in this worktree.
**Fix:** Applied TXN-01 fix (fee eval before lock) as part of Task 3's PaymentOrchestrator edit — correct since both fixes target the same method.
**Files modified:** `PaymentOrchestrator.java` (included in Task 3 commit)
**Rule:** Rule 1 (Bug fix) — the TXN-01 bug was present in the worktree and required fixing for correct behavior.

## Issues Encountered

None.

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness

- OPS-02 verified and pinned with FraudVelocityOrderingIT
- FraudEngineIT.velocityBlockReturns422 regression passes
- FraudScoringServiceIT regression passes
- PaymentOrchestratorIT regression passes (9 tests)
- Plan 38-03 can proceed

## Known Stubs

None.

---
*Phase: 38-transaction-boundary-fraud-ordering*
*Completed: 2026-04-14*
