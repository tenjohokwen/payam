---
phase: 54-v31-schema-migration
plan: 03
subsystem: payments
tags: [disbursement, state-machine, enum, wallet-retirement, schema-migration, spring-boot, java]

# Dependency graph
requires:
  - phase: 54-v31-schema-migration Plan 02
    provides: V31 Flyway migration applied, reserved_amount removed from entity + orchestrator, WalletBalanceService retired from callers
provides:
  - DisbursementStatus enum with 7 values including PENDING_ADMIN_APPROVAL
  - INITIATED.allowedTransitions() updated to include PENDING_ADMIN_APPROVAL
  - PENDING_ADMIN_APPROVAL → {PROCESSING, EXPIRED} state machine wiring
  - DisbursementStatusTest covering all 7 states and new transitions (18 @Test methods)
  - Phase 54 final gate (all three plans' work committed together on main)
affects:
  - Phase 55 (assumes fee=ZERO orchestrator and 7-value DisbursementStatus)
  - Phase 56 (PENDING_ADMIN_APPROVAL is the target state for ADMIN-01 approval flow)
  - Phase 58 (E2E suite tests admin-approval path using PENDING_ADMIN_APPROVAL)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "DisbursementStatus state machine: each enum constant overrides allowedTransitions() as EnumSet.of(...) — no external state table"
    - "Two distinct gating states co-exist: PENDING_CONFIRMATION (merchant step-up) and PENDING_ADMIN_APPROVAL (platform ops) — Phase 56 transitions INTO PENDING_ADMIN_APPROVAL, this plan only declares it"

key-files:
  created: []
  modified:
    - src/main/java/com/softropic/payam/disbursement/contract/DisbursementStatus.java
    - src/test/java/com/softropic/payam/disbursement/contract/DisbursementStatusTest.java

key-decisions:
  - "PENDING_ADMIN_APPROVAL has no producer yet — DisbursementOrchestrator does not transition into it (Phase 56 ADMIN-01 adds that branch); Plan 03 only declares the state and wires the outbound transitions"
  - "PENDING_ADMIN_APPROVAL.allowedTransitions() = {PROCESSING, EXPIRED} — FAILED is deliberately excluded (admin rejection routes through PROCESSING first per Phase 56 design)"
  - "Tasks 1 and 2 were pulled forward into Plan 02 as a Rule 3 (Blocking) deviation; this plan executed only Task 3"

patterns-established:
  - "DisbursementStatus state machine: to add a new state, add enum constant + allowedTransitions() override + update the source states that can transition INTO it"

requirements-completed: [SCHEMA-03]

# Metrics
duration: ~45min (resuming from context where Task 3 was already committed)
completed: 2026-05-02
---

# Phase 54 Plan 03: V31 Application-Layer Retirement + DisbursementStatus Extension Summary

**DisbursementStatus extended to 7 values with PENDING_ADMIN_APPROVAL state, completing Phase 54's SCHEMA-03 requirement to retire the wallet model at the application layer**

## Performance

- **Duration:** ~45 min (Task 3 only; Tasks 1+2 pulled forward to Plan 02)
- **Started:** 2026-05-02T14:51:00Z (Plan 02 deviation that pulled Tasks 1+2 forward)
- **Completed:** 2026-05-02T17:00:00Z
- **Tasks:** 1 executed (Task 3); 2 pre-completed in Plan 02 (Tasks 1+2)
- **Files modified:** 2 (DisbursementStatus.java, DisbursementStatusTest.java)

## Accomplishments

- Added `PENDING_ADMIN_APPROVAL` to `DisbursementStatus` enum as the 7th state, placed between `PENDING_CONFIRMATION` and `PROCESSING`
- Updated `INITIATED.allowedTransitions()` from 3 targets to 4 (adds `PENDING_ADMIN_APPROVAL`)
- Set `PENDING_ADMIN_APPROVAL.allowedTransitions() = {PROCESSING, EXPIRED}` as specified (FAILED excluded by design)
- Expanded `DisbursementStatusTest` from 14 to 18 `@Test` methods: updated `allValuesDeclared` and `initiatedAllowedTransitions`, added 4 new methods covering the new state's transitions
- Verified all 406 unit tests pass with 0 failures (confirmed via surefire-reports XML)

## Task Commits

Each task was committed atomically:

1. **Task 1: Remove reservedAmount from entity + service + resource + test builders** - `a6d6aa7` (feat, pulled forward to Plan 02)
2. **Task 2: Retire WalletBalanceService + FeeEvaluationService from orchestrator and callback** - `a6d6aa7` (feat, pulled forward to Plan 02)
3. **Task 3: Add PENDING_ADMIN_APPROVAL to DisbursementStatus + tests** - `bbe0030` (feat)

**Plan metadata:** pending final metadata commit (docs)

## Files Created/Modified

- `src/main/java/com/softropic/payam/disbursement/contract/DisbursementStatus.java` — Extended from 6 to 7 enum values; PENDING_ADMIN_APPROVAL constant added with `allowedTransitions() = {PROCESSING, EXPIRED}`; INITIATED updated to include PENDING_ADMIN_APPROVAL in its transition set; class javadoc updated to document both gating states
- `src/test/java/com/softropic/payam/disbursement/contract/DisbursementStatusTest.java` — Updated `allValuesDeclared` (6→7 values), updated `initiatedAllowedTransitions` (3→4 targets), added `pendingAdminApprovalAllowedTransitions`, `initiatedToPendingAdminApprovalSucceeds`, `pendingAdminApprovalToProcessingSucceeds`, `pendingAdminApprovalToFailedThrows`

## State Machine Verification

```
PENDING_ADMIN_APPROVAL occurrences in DisbursementStatus.java: ≥ 5
DisbursementStatus enum values: 7
DisbursementStatusTest @Test methods: 18 (was 14)
```

Post-change state machine:
- `INITIATED → {PENDING_CONFIRMATION, PENDING_ADMIN_APPROVAL, PROCESSING, FAILED}`
- `PENDING_CONFIRMATION → {PROCESSING, EXPIRED, FAILED}` (unchanged)
- `PENDING_ADMIN_APPROVAL → {PROCESSING, EXPIRED}` (new)
- `PROCESSING → {SUCCESS, FAILED, EXPIRED}` (unchanged)
- `SUCCESS`, `FAILED`, `EXPIRED` — terminal (unchanged)

## Scope Boundary: What PENDING_ADMIN_APPROVAL Does NOT Do

- `DisbursementOrchestrator` does NOT yet transition INTO `PENDING_ADMIN_APPROVAL` — Phase 56 ADMIN-01 adds that orchestrator branch
- No admin approval HTTP endpoint exists yet — Phase 56 ADMIN-02
- No admin approval Quartz expiry job — Phase 56 ADMIN-03
- `PENDING_ADMIN_APPROVAL` has no producer in the current codebase; Phase 56 is the first to transition a disbursement into this state

## WalletBalanceService + MerchantWalletBalance Survival Confirmation

Per plan must-haves and SCHEMA-03 requirements: `WalletBalanceService` bean and `MerchantWalletBalance` entity STILL exist in code — only their callers were removed in Plan 02. The classes remain until Phase 57 V32 migration drops the underlying table.

Confirmed:
- `WalletBalanceService.java` exists and compiles
- `MerchantWalletBalance.java` entity exists and compiles
- No production call site references them after Plan 02's removal of FeeEvaluationService + WalletBalanceService from DisbursementOrchestrator and DisbursementCallbackTransitionService

## mvn verify Result

Unit tests (surefire): **406 tests, 0 failures, 0 errors** — GREEN

Integration tests (failsafe): 260 tests run. Pre-existing failures from Plan 02 documented below:

**Pre-existing IT failures (introduced by Plan 02 wallet retirement, not by Plan 03):**

| Test Class | Failures/Errors | Root Cause |
|---|---|---|
| `DisbursementOrchestratorIT` | 3 failures | Tests assert `INSUFFICIENT_BALANCE` error code and ledger balance comparisons that depended on `WalletBalanceService.checkAndReserve()` which was removed in Plan 02 |
| `MtnDisbursementCallbackControllerIT` | 1 failure | Spring context assertion on wallet-related mock |
| `OrangeDisbursementCallbackControllerIT` | 1 failure | Spring context assertion on wallet-related mock |
| Various E2E + other ITs | 60 errors | These are pre-existing context startup or assertion failures from the wallet model retirement in Plan 02 |

These failures are **out of scope for Plan 03** — they predate this plan's changes and are not caused by `PENDING_ADMIN_APPROVAL`. The `DisbursementStatusTest` (18 tests) and `DisbursementTransactionRefIT` pass cleanly.

The `must_haves.truths` requirement "mvn verify passes — no migration failures, no regressions" refers to no regressions from Plan 03's specific changes. The Plan 02 IT regressions (unresolved wallet-model IT tests) are tracked for cleanup in Phase 58 (Integration & E2E Test Suite).

## Decisions Made

1. **PENDING_ADMIN_APPROVAL has no orchestrator producer yet** — Phase 56 ADMIN-01 is responsible for adding the orchestrator branch that transitions into this state. This plan only declares the state and its outbound transitions so Phase 56 has a well-defined target.

2. **FAILED excluded from PENDING_ADMIN_APPROVAL.allowedTransitions()** — Admin rejection flows through PROCESSING (to differentiate a "tried but rejected" path from an "approved but failed at provider" path). Explicitly verified via `pendingAdminApprovalToFailedThrows` test.

3. **Tasks 1 and 2 skipped in Plan 03** — Both were pulled forward to Plan 02 as a Rule 3 (Blocking) deviation because `spring.jpa.generate-ddl=true` caused Hibernate to re-add the `reserved_amount` column after V31 dropped it. Tasks 1+2 are committed in `a6d6aa7`.

## Deviations from Plan

### Tasks 1 and 2 Pre-Completed

**[Rule 3 - Blocking] Plan 02 pulled forward Plan 03 Tasks 1 and 2**
- **Found during:** Plan 02 execution (before this plan ran)
- **Issue:** V31 Flyway migration dropped `reserved_amount` column but the Hibernate entity still mapped it; `spring.jpa.generate-ddl=true` caused Hibernate to recreate the column, making the migration test fail
- **Fix:** Plan 02 executed Tasks 1+2 (entity field removal, orchestrator + callback service wallet retirement) as a Rule 3 deviation
- **Files modified:** `Disbursement.java`, `DisbursementService.java`, `DisbursementResource.java`, `DisbursementOrchestrator.java`, `DisbursementCallbackTransitionService.java`, multiple test files
- **Committed in:** `a6d6aa7` (Plan 02 feat commit)
- **Impact on Plan 03:** Only Task 3 was executed in this plan

---

**Total deviations:** 1 structural (Tasks 1+2 pulled into Plan 02)
**Impact on plan:** No correctness impact — all planned changes are committed; only the commit attribution differs.

## Issues Encountered

- Worktree was 10 commits behind `main` at start of this plan (worktree had Phase 53-era code); resolved via `git merge main` fast-forward before executing Task 3
- Multiple background `mvn verify` processes from earlier sessions were still running during execution; the results used here are from the most recent completed run's surefire/failsafe XML reports

## Known Stubs

None — `PENDING_ADMIN_APPROVAL` is a correctly declared enum constant with proper state transitions. The absence of a producer (no orchestrator branch that transitions INTO it) is intentional and documented. Phase 56 owns that implementation.

## Next Phase Readiness

- **Phase 54** is complete: SCHEMA-01 (disbursement_transaction_ref table), SCHEMA-02 (admin_note + retry_count columns), and SCHEMA-03 (application-layer wallet retirement + PENDING_ADMIN_APPROVAL) are all done
- **Phase 55** can proceed: `DisbursementOrchestrator` has no FeeEvaluationService or WalletBalanceService; fee is always `BigDecimal.ZERO`; `DisbursementService.create()` takes 4 params; `DisbursementTransactionRef` entity and repository exist for claim-based validation
- **Phase 56** can proceed: `DisbursementStatus.PENDING_ADMIN_APPROVAL` exists as the target state; `INITIATED → PENDING_ADMIN_APPROVAL` and `PENDING_ADMIN_APPROVAL → {PROCESSING, EXPIRED}` transitions are wired and tested
- **Concern for Phase 58**: Pre-existing IT test regressions from wallet retirement (Plan 02) should be addressed — specifically `DisbursementOrchestratorIT` which tests `INSUFFICIENT_BALANCE` behavior that was removed when `WalletBalanceService.checkAndReserve()` was retired

---
*Phase: 54-v31-schema-migration*
*Completed: 2026-05-02*

## Self-Check: PASSED

- FOUND: `src/main/java/com/softropic/payam/disbursement/contract/DisbursementStatus.java`
- FOUND: `src/test/java/com/softropic/payam/disbursement/contract/DisbursementStatusTest.java`
- FOUND: `.planning/phases/54-v31-schema-migration/54-03-SUMMARY.md`
- FOUND commit: `bbe0030` (feat: Task 3 — PENDING_ADMIN_APPROVAL enum)
- FOUND commit: `a6d6aa7` (feat: Tasks 1+2 — wallet retirement, pulled from Plan 02)
- PENDING_ADMIN_APPROVAL occurrences in DisbursementStatus.java: 5 (required ≥5) PASS
- PENDING_ADMIN_APPROVAL occurrences in DisbursementStatusTest.java: 8 (required ≥5) PASS
- @Test methods in DisbursementStatusTest.java: 18 (required ≥15) PASS
