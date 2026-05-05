---
phase: 58-integration-e2e-test-suite
plan: "01"
subsystem: disbursement-e2e
tags: [e2e, claim-lifecycle, txn-03, mtn]
dependency_graph:
  requires: [Phase 54 DisbursementTransactionRef DDL, Phase 56 DisbursementClaimTransitionService]
  provides: [CLAIM-01 assertion, CLAIM-02 assertion, TXN-03 guard in MtnDisbursementE2EIT]
  affects: [MtnDisbursementE2EIT]
tech_stack:
  added: []
  patterns:
    - raw JdbcTemplate query on disbursement_transaction_ref joined on BIGINT PK
    - Awaitility poll for AFTER_COMMIT claim transitions
    - parseErrorCode() helper for errorCode JSON field extraction
key_files:
  created: []
  modified:
    - src/test/java/com/softropic/payam/e2e/disbursement/MtnDisbursementE2EIT.java
decisions:
  - "reference field has @Size(max=50): test uses REF-CLAIM1-/REF-CLAIM2- prefixes (47 chars) instead of REF-MTN-CLAIMED-1/2- (54 chars) to pass Bean Validation"
  - "awaitClaimStatuses polls JdbcTemplate directly (not JPA repo) to bypass first-level cache"
metrics:
  duration_minutes: 49
  completed_date: "2026-05-05"
  tasks_completed: 2
  files_modified: 1
---

# Phase 58 Plan 01: MTN Disbursement E2E — Claim Lifecycle Assertions Summary

MTN E2E test suite extended with claim-state machine verification: PENDING at initiation (CLAIM-01), CLAIMED after SUCCESS callback (CLAIM-02), and 422 TRANSACTION_CLAIMED rejection on second attempt with same transactionIds (TXN-03).

## Tasks Completed

| Task | Description | Commit |
|------|-------------|--------|
| 1 | Add CLAIM-01 + CLAIM-02 assertions to mtnHappyPath_* test | 6cc7f46 |
| 2 | Add TXN-03 guard — mtnSecondAttemptWithSameTransactionIds_returns422TransactionClaimed | 97ffa45 |

## Changes Made

**MtnDisbursementE2EIT.java** (474 lines, 4 @Test methods):

- Added import `com.softropic.payam.disbursement.contract.DisbursementOrchestratorError`
- Added private helper `assertClaimStatuses(disbursementId, expectedStatus, expectedCount)`: raw JDBC query on `main.disbursement_transaction_ref` joined to `main.disbursement` via BIGINT PK — avoids JPA first-level cache
- Added private helper `awaitClaimStatuses(disbursementId, expectedStatus, expectedCount)`: Awaitility poll (10s max) for async AFTER_COMMIT claim transitions
- Added private helper `parseErrorCode(body)`: extracts `errorCode` field from JSON response
- Modified `mtnHappyPath_*` test: inserted CLAIM-01 assertion (PENDING immediately after 202) and CLAIM-02 await assertion (CLAIMED after SUCCESS callback completes)
- New `@Test` method `mtnSecondAttemptWithSameTransactionIds_returns422TransactionClaimed`: seeds 1 claim, first POST succeeds (PENDING claims created), second POST with same txnIds + different idempotency key asserts 422 TRANSACTION_CLAIMED; also verifies WireMock transfer call count does not increase (rejection before provider dispatch)

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Reference field exceeds @Size(max=50) constraint**
- **Found during:** Task 2 verification — first test run returned HTTP 400
- **Issue:** Reference format `"REF-MTN-CLAIMED-1-" + UUID.randomUUID()` = 54 characters, exceeds `@Size(max=50)` on `DisbursementRequest.reference`
- **Fix:** Changed to `"REF-CLAIM1-" + UUID.randomUUID()` (47 chars) and `"REF-CLAIM2-" + UUID.randomUUID()` (47 chars)
- **Files modified:** `MtnDisbursementE2EIT.java`
- **Commit:** 97ffa45

## Verification Results

```
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in MtnDisbursementE2EIT
```

All 4 tests pass:
1. `mtnHappyPath_initiateThenCallbackSuccess_transitionsToSuccessAndPostsLedger` — with CLAIM-01 + CLAIM-02
2. `mtnFailedCallback_transitionsToFailedAndReleasesWallet`
3. `mtnReplayedCallback_isDeduplicated`
4. `mtnSecondAttemptWithSameTransactionIds_returns422TransactionClaimed` (new, TXN-03)

## Requirements Covered

- CLAIM-01: DisbursementTransactionRef row created in PENDING state at initiation
- CLAIM-02: Claim transitions PENDING→CLAIMED inside the AFTER_COMMIT listener after SUCCESS callback
- TXN-03: Second POST with same transactionIds (different idempotency key) returns 422 TRANSACTION_CLAIMED without dispatching to provider

## Known Stubs

None — all assertions are wired to real claim rows in the database.

## Self-Check: PASSED
- `src/test/java/com/softropic/payam/e2e/disbursement/MtnDisbursementE2EIT.java` exists and contains 474 lines
- Commits 6cc7f46 and 97ffa45 both present in git log
- `mvn failsafe:integration-test -Dit.test=MtnDisbursementE2EIT` exits 0 with 4/4 tests passing
