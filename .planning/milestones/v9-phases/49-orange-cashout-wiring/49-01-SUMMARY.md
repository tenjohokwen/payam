---
phase: 49-orange-cashout-wiring
plan: 01
subsystem: payments
tags: [java, spring-boot, payment-command, fee-evaluation, cashout, ledger]

# Dependency graph
requires:
  - phase: 38-transaction-boundary-fraud-ordering
    provides: FeeEvaluationService injected into PaymentOrchestrator; TXN-01 fee evaluation before DB lock established
  - phase: 11-fee-exposure
    provides: FeeEvaluationService with evaluateFee(tenantId, amount) API
provides:
  - PaymentCommand record with nullable feeAmount as 14th component (CASHOUT-01)
  - Backward-compat 13-arg constructor delegates to canonical with feeAmount=null
  - withFeeAmount(BigDecimal) instance method returning enriched copy with all other fields preserved
  - PaymentOrchestrator.initiate() enriches cmd with FeeEvaluationService-evaluated fee via cmd = cmd.withFeeAmount(fee)
affects: [49-orange-cashout-wiring/49-02, orange-cashout-ledger, OrangeMoneyPort.initiateCashout]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Record wither pattern: add withFeeAmount() to immutable record to carry fee across service boundary without breaking 13-arg call sites"
    - "Pre-lambda capture: extract cmd.deviceFingerprint() to local var before rebinding cmd to satisfy Java effectively-final lambda constraint"

key-files:
  created: []
  modified:
    - src/main/java/com/softropic/payam/common/payment/PaymentCommand.java
    - src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java

key-decisions:
  - "13-arg compat constructor delegates to 14-arg canonical with feeAmount=null — preserves backward compat for all existing call sites without requiring any external changes"
  - "deviceFingerprint captured to local var before cmd rebind — required by Java lambda effectively-final rule; semantically equivalent, fingerprint value unchanged"
  - "withFeeAmount inserts BEFORE the transactionTemplate.execute block — ensures any future port reading cmd.feeAmount() sees the evaluated fee"

patterns-established:
  - "Wither pattern for PaymentCommand: new fields get withX() method returning enriched copy; orchestrator rebinds local var"
  - "Pre-lambda capture: any cmd accessor used in lambda must be extracted to final local before rebinding cmd"

requirements-completed: [CASHOUT-01]

# Metrics
duration: 25min
completed: 2026-04-22
---

# Phase 49 Plan 01: Orange Cashout Wiring — PaymentCommand Fee Propagation Summary

**Nullable `feeAmount` added to `PaymentCommand` as 14th record component with 13-arg backward-compat constructor and `withFeeAmount` wither; `PaymentOrchestrator` enriches the in-flight command with `FeeEvaluationService`-computed fee before any port dispatch**

## Performance

- **Duration:** ~25 min
- **Started:** 2026-04-22T22:00:00Z
- **Completed:** 2026-04-22T22:15:00Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments

- Extended `PaymentCommand` Java record with nullable `BigDecimal feeAmount` as the 14th component, maintaining all 13 existing components in original order
- Added backward-compatible 13-arg constructor that delegates to the 14-arg canonical form with `feeAmount=null` — all 7 test construction sites and 1 production site compile unchanged
- Added `withFeeAmount(BigDecimal)` instance method for orchestrator-side enrichment returning a fresh immutable copy
- Wired `PaymentOrchestrator.initiate()` to call `cmd = cmd.withFeeAmount(fee)` after `FeeEvaluationService.evaluateFee` (line 202) and before the fee-persistence `transactionTemplate.execute` block (line 222)
- All 26 tests across 5 reference test classes pass without modification: `OrangeMoneyPortIT` (7), `FraudScoringServiceIT` (3), `MtnMoMoPortIT` (5), `FraudThresholdGuardTest` (2), `PaymentOrchestratorIT` (9)

## Task Commits

Each task was committed atomically:

1. **Task 1: Extend PaymentCommand with feeAmount + backward-compat constructor + withFeeAmount helper** - `1d0f02d` (feat)
2. **Task 2: Wire PaymentOrchestrator to enrich PaymentCommand with FeeEvaluationService fee** - `dd2dbed` (feat)

## Files Created/Modified

- `src/main/java/com/softropic/payam/common/payment/PaymentCommand.java` — Added `BigDecimal feeAmount` as 14th record component, 13-arg compat constructor, `withFeeAmount(BigDecimal)` wither method
- `src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java` — Inserted `cmd = cmd.withFeeAmount(fee)` at line 211; extracted `deviceFingerprint` local to satisfy lambda constraint

## Decisions Made

- 13-arg compat constructor delegates to 14-arg canonical with `feeAmount=null` — preserves backward compat for all existing call sites without requiring any external changes
- `withFeeAmount` insert position (between evaluateFee line 202 and transactionTemplate.execute line 222) — ensures any future port reading `cmd.feeAmount()` sees the evaluated fee before port dispatch
- `deviceFingerprint` extracted to local var before `cmd` rebind — required by Java lambda effectively-final rule; semantically equivalent, fingerprint value unchanged

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Extracted deviceFingerprint local before cmd rebind for lambda compliance**
- **Found during:** Task 2 (Wire PaymentOrchestrator)
- **Issue:** Java requires variables captured in lambdas to be effectively final. After `cmd = cmd.withFeeAmount(fee)` rebind, `cmd` is no longer effectively final, so `cmd.deviceFingerprint()` inside the `transactionTemplate.execute(...)` lambda produced a compilation error: "local variables referenced from a lambda expression must be final or effectively final"
- **Fix:** Extracted `String deviceFingerprint = cmd.deviceFingerprint();` before the `cmd` rebind, then used `deviceFingerprint` inside the lambda instead of `cmd.deviceFingerprint()`
- **Files modified:** `src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java`
- **Verification:** `mvn test` with all 5 test classes compiled and passed (26 tests green)
- **Committed in:** `dd2dbed` (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (Rule 1 - Bug)
**Impact on plan:** Fix necessary for compilation. Semantically equivalent — fingerprint value unchanged, no behavior difference. No scope creep.

## Issues Encountered

- Lambda effectively-final constraint required `deviceFingerprint` extraction before `cmd` rebind — resolved via Rule 1 auto-fix inline with Task 2 commit.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- `PaymentCommand.feeAmount()` is now populated with the FeeEvaluationService-evaluated fee for every payment initiated through `PaymentOrchestrator`
- Plan 02 (`OrangeMoneyPort.initiateCashout` wiring) can now read `cmd.feeAmount()` at the port boundary to pass fee to the disbursement ledger writer
- No blockers — CASHOUT-01 fully satisfied

---
*Phase: 49-orange-cashout-wiring*
*Completed: 2026-04-22*
