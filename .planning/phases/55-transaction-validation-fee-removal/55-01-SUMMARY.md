---
phase: 55-transaction-validation-fee-removal
plan: "01"
subsystem: disbursement-contract
tags: [disbursement, contract, validation, repository, exception, v11]
dependency_graph:
  requires: [54-02, 54-03]
  provides: [DisbursementRequest-transactionIds, InvalidTransactionException, TransactionClaimedException, AmountMismatchException, findByTransactionIdsForUpdate, findClaimedTransactionIds, adminNote-retryCount-entity-fields]
  affects: [DisbursementOrchestrator, DisbursementResource, TransactionRepository, DisbursementTransactionRefRepository, DisbursementOrchestratorTest, DisbursementOrchestratorIT, DisbursementResourceIT]
tech_stack:
  added: []
  patterns: [pessimistic-write-multi-row-lock, ordered-lock-acquisition-deadlock-prevention, bean-validation-record-field, runtime-exception-subclass-pattern]
key_files:
  created:
    - src/main/java/com/softropic/payam/disbursement/contract/exception/InvalidTransactionException.java
    - src/main/java/com/softropic/payam/disbursement/contract/exception/TransactionClaimedException.java
    - src/main/java/com/softropic/payam/disbursement/contract/exception/AmountMismatchException.java
  modified:
    - src/main/java/com/softropic/payam/disbursement/repo/Disbursement.java
    - src/main/java/com/softropic/payam/disbursement/contract/DisbursementRequest.java
    - src/main/java/com/softropic/payam/disbursement/contract/DisbursementOrchestratorError.java
    - src/main/java/com/softropic/payam/disbursement/repo/DisbursementTransactionRefRepository.java
    - src/main/java/com/softropic/payam/transaction/repo/TransactionRepository.java
    - src/main/java/com/softropic/payam/disbursement/service/DisbursementOrchestrator.java
    - src/main/java/com/softropic/payam/disbursement/api/DisbursementResource.java
    - src/test/java/com/softropic/payam/disbursement/service/DisbursementOrchestratorTest.java
    - src/test/java/com/softropic/payam/disbursement/service/DisbursementOrchestratorIT.java
    - src/test/java/com/softropic/payam/disbursement/api/DisbursementResourceIT.java
decisions:
  - "Merged main into worktree branch before implementation — Phase 54 changes (V31 migration, reservedAmount removal) were not in the worktree, required fast-forward merge"
  - "DisbursementOrchestratorIT uses List.of(dummy-txn-id) placeholder — actual transaction row setup belongs in Plan 02 unit tests and Plan 03 integration tests"
  - "DisbursementResourceIT uses JSON transactionIds array — no DisbursementRequest constructor calls in that test file"
  - "E2E tests that fail with Ryuk/Docker unavailability are pre-existing environment-specific failures unrelated to Plan 01 changes"
metrics:
  duration: "~30 minutes"
  completed_date: "2026-05-04"
  tasks_completed: 3
  files_changed: 10
---

# Phase 55 Plan 01: Contract Foundation for Transaction-Backed Disbursements Summary

Established the complete contract foundation for Phase 55 transaction-backed disbursements: V31 entity fields anti-regression, 3 new error codes + exception classes, extended DisbursementRequest record with `transactionIds`, multi-row PESSIMISTIC_WRITE lock in TransactionRepository, and claim-probe query in DisbursementTransactionRefRepository — all construction sites updated, unit tests green, no behavior changes.

## Tasks Completed

| Task | Name | Commit | Key Files |
|------|------|--------|-----------|
| 1 | Extend Disbursement entity with adminNote + retryCount | 813c5c8 | Disbursement.java |
| 2 | Add error codes + 3 domain exceptions | 982acad | DisbursementOrchestratorError.java + 3 exception files |
| 3 | Extend DisbursementRequest + repository query methods + update construction sites | b9ffd91 | DisbursementRequest.java, TransactionRepository.java, DisbursementTransactionRefRepository.java, DisbursementOrchestrator.java, DisbursementResource.java, 3 test files |

## What Was Built

### Task 1 — Disbursement Entity V31 Anti-Regression Fields

Added two fields to `Disbursement.java` after `pollAttempts`:

- `adminNote` (TEXT, nullable): `@Column(name = "admin_note", columnDefinition = "TEXT")` — populated by Phase 56 ADMIN-02 approval flow
- `retryCount` (INT NOT NULL default 0): `@Builder.Default @Column(name = "retry_count", nullable = false)` — incremented by Phase 57 IDEM-02

No `@NotAudited` — V31 already added both columns to `disbursement_aud`. This prevents `spring.jpa.generate-ddl=true` from re-adding dropped columns (the same root cause fixed in Phase 54-02).

### Task 2 — Error Codes and Domain Exceptions

Added 3 enum entries to `DisbursementOrchestratorError`:
- `INVALID_TRANSACTION`: TXN-01/TXN-02 tenant ownership + status/flow validation failure
- `TRANSACTION_CLAIMED`: TXN-03 active-claim conflict (also enforced by `uq_dtr_txn_active_claim` DB index)
- `AMOUNT_MISMATCH`: TXN-04 sum(amount - feeAmount) != request.amount

Added 3 exception classes in `disbursement.contract.exception` package following the `DailyLimitExceededException` pattern (trivial RuntimeException subclass, single String constructor). `DisbursementResource.resolveHttpStatus` default-422 path covers all three without modification.

### Task 3 — DisbursementRequest Extension + Repository Methods + Construction Sites

**DisbursementRequest record** extended from 7 to 8 fields. New field position: slot 7 (before `idempotencyKey`):
```java
@NotEmpty
@Size(max = 500)
List<@NotBlank String> transactionIds
```

**TransactionRepository** gained `findByTransactionIdsForUpdate(List<String>)` — PESSIMISTIC_WRITE multi-row lock with `ORDER BY t.transactionId ASC` (TXN-05 deadlock prevention via canonical lock ordering).

**DisbursementTransactionRefRepository** now has `findClaimedTransactionIds(List<String>, Collection<DisbursementRefStatus>)` — TXN-03 active-claim probe returning the subset of transactionIds that have an existing PENDING or CLAIMED ref.

**Construction sites updated (8 total):**
- `DisbursementOrchestrator.confirm()`: passes `null` for transactionIds (claims created at initiate time; confirm() never re-validates)
- `DisbursementResource.initiate()`: passes `body.transactionIds()` through unchanged
- `DisbursementOrchestratorTest.validRequest()`: passes `List.of("txn-001")`
- `DisbursementOrchestratorIT` (6 sites): each passes `List.of("dummy-txn-id")` as placeholder
- `DisbursementResourceIT` JSON bodies: added `"transactionIds": ["dummy-txn-id"]` to all POST JSON bodies

## Test Results

- **DisbursementOrchestratorTest**: 14/14 GREEN (unit, no Docker required)
- **DisbursementResourceIT**: 6/6 GREEN when Docker is available (passed in full `mvn test` run)
- **DisbursementExpiryJobIT**: 1/1 GREEN
- **DisbursementIdempotencyIT**: 3/3 GREEN
- **MtnDisbursementCallbackControllerIT**: 4/4 GREEN
- **DisbursementConcurrencyRaceIT**: 1/1 GREEN (E2E)
- **LedgerConstraintIT**: 5/5 GREEN

## Deviations from Plan

### Contextual Issues

**1. Worktree was behind main (Phase 54 changes absent)**

- **Found during:** Initial setup (reading Disbursement.java)
- **Issue:** The worktree branch `worktree-agent-ab7745293240261ab` was at commit `67f11c3` (Phase 53 complete). Phase 54 changes (V31 migration, reservedAmount removal, DisbursementTransactionRef entity) were only on `main`. Without merging, Task 1 would have been redundant (reservedAmount still present) and Task 3 would have been missing the DisbursementTransactionRef entity.
- **Fix:** Fast-forward merged `main` into the worktree branch before any implementation. The merge was clean with no conflicts.
- **Impact:** No code changes needed — the merge brought everything up to date.

### Auto-fixed Issues

None — plan executed exactly as written after the merge.

## Known Stubs

The following placeholder values exist by design in this plan:
- `List.of("dummy-txn-id")` in DisbursementOrchestratorIT (6 sites): Plan 02 will replace with real transaction setup via `transactionTemplate.execute()` + seeded Transaction rows.
- `List.of("dummy-txn-id")` in DisbursementResourceIT JSON bodies: Plan 03 will wire real transaction data.

These stubs are intentional scaffolding — Plan 01 establishes contracts only. The `dummy-txn-id` value satisfies `@NotEmpty + @Size(max=500) + @NotBlank elements` Bean Validation, allowing the existing test assertions (status, errorCode, disbursementId) to remain unchanged.

## Self-Check: PASSED

All files verified:
- FOUND: Disbursement.java (adminNote + retryCount fields present)
- FOUND: InvalidTransactionException.java
- FOUND: TransactionClaimedException.java
- FOUND: AmountMismatchException.java

All commits verified:
- 813c5c8: feat(55-01): add adminNote + retryCount fields to Disbursement entity for V31 anti-regression
- 982acad: feat(55-01): add INVALID_TRANSACTION/TRANSACTION_CLAIMED/AMOUNT_MISMATCH error codes and 3 domain exceptions
- b9ffd91: feat(55-01): extend DisbursementRequest + add repository query methods + update all 8 construction sites
