---
phase: 55-transaction-validation-fee-removal
plan: "02"
subsystem: disbursement-service
tags: [disbursement, validation, transaction-claim, tdd, v11]
dependency_graph:
  requires: [55-01]
  provides: [TransactionClaimValidationService, validateAndClaim, orchestrator-claim-wiring, FEE-01-regression-guard]
  affects: [DisbursementOrchestrator, DisbursementOrchestratorTest, TransactionClaimValidationServiceTest]
tech_stack:
  added: []
  patterns: [pessimistic-write-multi-row-lock, pre-lock-ownership-check, DataIntegrityViolationException-mapping, BigDecimal-compareTo-pattern, TDD-red-green]
key_files:
  created:
    - src/main/java/com/softropic/payam/disbursement/service/TransactionClaimValidationService.java
    - src/test/java/com/softropic/payam/disbursement/service/TransactionClaimValidationServiceTest.java
  modified:
    - src/main/java/com/softropic/payam/disbursement/service/DisbursementOrchestrator.java
    - src/test/java/com/softropic/payam/disbursement/service/DisbursementOrchestratorTest.java
decisions:
  - "Pre-lock ownership check uses individual findByTransactionId() calls (not batch) — bounded to 500 ids by Bean Validation, and the locked re-read via findByTransactionIdsForUpdate is the authoritative read"
  - "Objects.requireNonNullElse used for null feeAmount coalesce — avoids conditional branching, always produces a valid BigDecimal operand for subtract()"
  - "DataIntegrityViolationException caught per-insert (not wrapping all inserts) — provides the specific conflicting transactionId in the error message"
  - "FEE-01 regression guard uses reflection on getDeclaredFields() — tests the absence of any *Fee*-typed field without depending on a specific class name string"
  - "Step 7.5 runs BEFORE the stepUp early-return — CLAIM-01 semantics require claims to exist when disbursement is accepted (includes PENDING_CONFIRMATION state)"
  - "Objects.equals() for tenantId Long comparison — correct idiom; BigDecimal.compareTo() for amount comparison — scale-insensitive"
metrics:
  duration: "~23 minutes"
  completed_date: "2026-05-04"
  tasks_completed: 2
  files_changed: 4
---

# Phase 55 Plan 02: Transaction Claim Validation Service Summary

Implemented `TransactionClaimValidationService` with full TXN-01..TXN-04 + TXN-06 logic and wired it into `DisbursementOrchestrator.initiate()` as Step 7.5 (between disbursement row creation and step-up early-return). Added FEE-01 regression guard via reflection assertion.

## Tasks Completed

| Task | Name | Commit | Key Files |
|------|------|--------|-----------|
| 1 (RED) | TransactionClaimValidationServiceTest — 13 failing tests | 1677615 | TransactionClaimValidationServiceTest.java |
| 1 (GREEN) | TransactionClaimValidationService implementation | f261ea9 | TransactionClaimValidationService.java |
| 2 (RED) | DisbursementOrchestratorTest — 6 new failing tests | ea6d752 | DisbursementOrchestratorTest.java |
| 2 (GREEN) | Wire TransactionClaimValidationService into orchestrator | 1a96bb2 | DisbursementOrchestrator.java |

## What Was Built

### TransactionClaimValidationService

New `@Service` class at `disbursement.service` layer with a single public method:

```java
public void validateAndClaim(Long tenantId, List<String> transactionIds,
                              BigDecimal requestedAmount, Long disbursementDbId)
```

Validation sequence:
1. **TXN-01 empty guard** — throws `InvalidTransactionException` with "empty" in message
2. **TXN-01 pre-lock ownership check** — loads via `findByTransactionId()` (non-locking), checks missing IDs and wrong tenant BEFORE acquiring any locks
3. **TXN-05 SELECT FOR UPDATE** — `findByTransactionIdsForUpdate(transactionIds)` ORDER BY transactionId ASC (deadlock prevention)
4. **TXN-02 re-verify under lock** — txStatus == SUCCESS AND effectiveFlow == COLLECTION; `getEffectiveFlow()` treats null as COLLECTION (TXN-06)
5. **TXN-03 app-layer probe** — `findClaimedTransactionIds` with PENDING+CLAIMED statuses
6. **TXN-04 amount check** — `BigDecimal.compareTo` (scale-insensitive); `Objects.requireNonNullElse(feeAmount, ZERO)` for TXN-06
7. **CLAIM-01 insert PENDING refs** — one `DisbursementTransactionRef` per transactionId; `DataIntegrityViolationException` caught per-insert and surfaced as `TransactionClaimedException`

### DisbursementOrchestrator Wiring

Constructor extended from 9 to 10 params (adds `TransactionClaimValidationService`).

New Step 7.5 inserted between Step 7 (create row) and stepUp early-return:

```java
try {
    transactionTemplate.execute(status -> {
        transactionClaimValidationService.validateAndClaim(
                tenantId, request.transactionIds(), request.amount(), dsb.getId());
        return null;
    });
} catch (InvalidTransactionException e) {
    releaseAndFail(tenantId, totalAmount, disbursementId);
    return DisbursementResponse.failed(disbursementId,
            DisbursementOrchestratorError.INVALID_TRANSACTION.getErrorCode(), e.getMessage());
} catch (TransactionClaimedException e) { ... TRANSACTION_CLAIMED ... }
} catch (AmountMismatchException e) { ... AMOUNT_MISMATCH ... }
```

Position: BEFORE `if (stepUp)` so claims are created for both INITIATED and PENDING_CONFIRMATION disbursements (CLAIM-01).

### FEE-01 Regression Guard

New `@Test` in `DisbursementOrchestratorTest`:
```java
long feeFields = Arrays.stream(orchestrator.getClass().getDeclaredFields())
        .filter(f -> f.getType().getSimpleName().contains("Fee"))
        .count();
assertThat(feeFields).as("FEE-01: no FeeEvaluationService dependency").isZero();
```

This reflection assertion will fail if any future change re-introduces a `*Fee*`-typed field to `DisbursementOrchestrator`, pinning the FEE-01 property.

## Test Results

- **TransactionClaimValidationServiceTest**: 13/13 GREEN
  - emptyTransactionIdsThrowsInvalidTransaction
  - wrongTenantOwnershipThrowsInvalidTransactionBeforeLock (verifies `never().findByTransactionIdsForUpdate()`)
  - missingTransactionThrowsInvalidTransaction
  - nonSuccessStatusThrowsInvalidTransaction
  - nonCollectionFlowThrowsInvalidTransaction
  - nullFlowTreatedAsCollectionPasses (TXN-06)
  - nullFeeAmountTreatedAsZero (TXN-06)
  - amountMismatchThrowsAmountMismatch (TXN-04)
  - scaleDifferenceCompareToPasses
  - activeClaimThrowsTransactionClaimed (TXN-03)
  - successfulValidationInsertsPendingRefRows (3x save with ArgumentCaptor)
  - dataIntegrityViolationOnSaveSurfacesAsTransactionClaimed (DB-layer guard)
  - bigDecimalEqualsTrapAvoided (scale invariant behavioral test)

- **DisbursementOrchestratorTest**: 20/20 GREEN (14 existing + 6 new)
  - New: initiateRejectsEmptyTransactionIdsList
  - New: initiateRejectsMismatchedAmount
  - New: initiateRejectsClaimedTransaction
  - New: initiateNeverCallsFeeEvaluationService_FEE01_regression
  - New: initiateOnValidationFailureTransitionsDisbursementToFailed
  - New: initiateOnSuccessCallsValidateAndClaim

- **Testcontainers/Docker IT tests**: pre-existing `Ryuk` unavailability in this environment (identical to Phase 55-01 observation); no unit test failures

## Deviations from Plan

### Contextual Issues

**1. Worktree was behind main (Phase 55-01 changes absent)**
- **Found during:** Initial file discovery
- **Issue:** Same pattern as 55-01: worktree branch `worktree-agent-afc605fa033d0c10a` was at commit `67f11c3` (Phase 53 complete). All Phase 54 and 55-01 artifacts were absent.
- **Fix:** Fast-forward merged `main` before any implementation. Clean merge, no conflicts.
- **Impact:** No code changes required — merge brought all 55-01 contract artifacts.

### Auto-fixed Issues

None — plan executed exactly as written after the merge.

## Known Stubs

None. All functionality is fully implemented — no placeholder values or hardcoded empty returns in the new code.

## Self-Check: PASSED

Files verified:
- FOUND: src/main/java/com/softropic/payam/disbursement/service/TransactionClaimValidationService.java
- FOUND: src/test/java/com/softropic/payam/disbursement/service/TransactionClaimValidationServiceTest.java

Commits verified:
- 1677615: test(55-02): add failing TransactionClaimValidationServiceTest — RED phase
- f261ea9: feat(55-02): implement TransactionClaimValidationService (TXN-01..04, TXN-06)
- ea6d752: test(55-02): extend DisbursementOrchestratorTest with 6 new tests — RED phase
- 1a96bb2: feat(55-02): wire TransactionClaimValidationService into DisbursementOrchestrator
