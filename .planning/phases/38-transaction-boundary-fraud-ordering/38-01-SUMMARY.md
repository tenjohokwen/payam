---
phase: 38-transaction-boundary-fraud-ordering
plan: 01
subsystem: payments
tags: [spring, jpa, transactiontemplate, pessimistic-lock, fee-evaluation, mockito, inorder]

# Dependency graph
requires:
  - phase: 23-invariants-concurrency-sm-mutation
    provides: TXN boundary tests, transaction state machine tests
  - phase: 11-fee-exposure
    provides: FeeEvaluationService, FeeRuleCache (in-memory), FeeRule repo
provides:
  - PaymentOrchestrator.initiate() with fee evaluation hoisted above SELECT...FOR UPDATE lock
  - InOrder regression test pinning evaluateFee() before findByTransactionIdForUpdate()
affects: [38-02, payment-orchestration, fraud-ordering]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Pre-lock cache reads: hoist pure in-memory operations (FeeRuleCache) before transactionTemplate.execute() — lock covers writes only"
    - "FeeRule ID extraction via .map(r -> r.getId()).orElse(null) avoids importing FeeRule type into orchestrator"
    - "@SpyBean + Mockito InOrder for cross-bean ordering assertions in Spring IT tests"

key-files:
  created: []
  modified:
    - src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java
    - src/test/java/com/softropic/payam/payment/PaymentOrchestratorIT.java

key-decisions:
  - "feeRuleIdVal extracted as Optional<Long> via .map(r -> r.getId()) — FeeRule not imported into PaymentOrchestrator; cleaner dependency boundary"
  - "capturedFee/capturedFeeRuleId arrays initialized from pre-computed values before lambda rather than inside lock — correct since values are now available pre-lock"
  - "@SpyBean on FeeEvaluationService and TransactionRepository used for InOrder cross-bean call ordering — deterministic, no Thread.sleep"

patterns-established:
  - "Pattern: any pure cache read (no I/O, no DB) must be hoisted above transactionTemplate blocks that open row locks"
  - "Pattern: InOrder(spyA, spyB) verification proves cross-service call ordering in Spring IT tests"

requirements-completed: [TXN-01]

# Metrics
duration: 35min
completed: 2026-04-14
---

# Phase 38 Plan 01: TXN-01 Fee Evaluation Ordering Summary

**Fee evaluation hoisted out of SELECT...FOR UPDATE block in PaymentOrchestrator.initiate() — pure FeeRuleCache reads now execute before the row lock, with InOrder IT regression pinning the correct ordering.**

## Performance

- **Duration:** ~35 min
- **Started:** 2026-04-14T20:53:00Z
- **Completed:** 2026-04-14T21:28:11Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments

- TXN-01 satisfied: `feeEvaluationService.evaluateFee()` and `feeEvaluationService.findRuleForTenant()` moved before the `transactionTemplate.execute()` block — DB row lock now covers only state writes
- `capturedFee` / `capturedFeeRuleId` closure arrays initialized from pre-computed values, not from inside the locked lambda
- `feeRuleIdVal` extracted as `Optional<Long>` via `.map(r -> r.getId())` — `FeeRule` type not imported into `PaymentOrchestrator`
- New `feeEvaluationHappensBeforeLock()` test uses `@SpyBean` + `Mockito.inOrder()` to assert `evaluateFee()` precedes `findByTransactionIdForUpdate()`
- All 9 `PaymentOrchestratorIT` tests pass including `fee_rule_applied_returns_nonzero_fee_amount` regression

## Task Commits

Each task was committed atomically:

1. **Task 1: Hoist fee evaluation out of SELECT...FOR UPDATE block** - `8d532c8` (feat)
2. **Task 2: Add ordering assertion test to PaymentOrchestratorIT** - `39a97b3` (test)

## Files Created/Modified

- `src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java` - Fee evaluation hoisted pre-lock; transactionTemplate block contains writes only
- `src/test/java/com/softropic/payam/payment/PaymentOrchestratorIT.java` - Added @SpyBean fields + feeEvaluationHappensBeforeLock() InOrder test

## Decisions Made

- `feeRuleIdVal` extracted via `.map(r -> r.getId()).orElse(null)` — avoids importing `FeeRule` into `PaymentOrchestrator` (cleaner dependency boundary per plan constraint)
- `capturedFee/capturedFeeRuleId` arrays initialized from pre-computed values at declaration site (not inside lock lambda) — simpler and correct
- `@SpyBean` placed at class level so all tests share the spy instance; Mockito resets happen naturally per Spring context lifecycle

## Deviations from Plan

None — plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness

- TXN-01 verified and pinned with regression test
- Plan 38-02 (OPS-02: fraud velocity token consumed before idempotency store) can proceed independently
- `PaymentOrchestratorIT` fully green at 9 tests

---
*Phase: 38-transaction-boundary-fraud-ordering*
*Completed: 2026-04-14*
