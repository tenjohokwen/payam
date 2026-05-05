---
plan: 60-01
phase: 60-claim-05-e2e-coverage
status: complete
completed: 2026-05-05
tasks_total: 2
tasks_completed: 2
self_check: PASSED
---

# Plan 60-01 Summary — CLAIM-05 E2E Coverage

## What Was Built

Added 1 new `@Test` method and 3 private helpers to `DisbursementExpiryE2EIT.java`.
No production code was modified. No new files created.

**New test method:** `processingToExpired_claimsRemainUnchanged_neverReleased`
**File:** `src/test/java/com/softropic/payam/e2e/disbursement/DisbursementExpiryE2EIT.java`
**Lines added:** 178 insertions

## Assertion Strategy

The test proves CLAIM-05 against the real database:

1. Seeds 1 backing transaction and POSTs a sub-threshold (5,000 XAF) disbursement — reaches `PROCESSING` state directly (below step-up and admin-approval thresholds).
2. Asserts CLAIM-01 precondition: 1 `disbursement_transaction_ref` row in `PENDING` state at initiation time.
3. Forces `PROCESSING → EXPIRED` via direct SQL UPDATE inside a `TransactionTemplate` — `DisbursementStatusPollerJob` does not yet exist in this codebase; the SQL simulates the future automated trigger. `PROCESSING.allowedTransitions()` includes `EXPIRED` (DisbursementStatus line 48).
4. **CLAIM-05 core assertion:** `assertClaimStatuses(disbursementId, "PENDING", 1)` — all rows remain PENDING, none released.
5. **Negative control:** explicit `SELECT ref_status = 'RELEASED'` query must return empty list — guards against future regression if someone adds an EXPIRED branch to claim transition logic.
6. HTTP-visible state check: `GET /v1/disbursements/{id}` returns 200 with `status=EXPIRED`.

## Why This Matches Finding G-1

Finding G-1 from `v11-MILESTONE-AUDIT.md`: CLAIM-05 production invariant is enforced by omission — `DisbursementCallbackTransitionService` only calls `transitionClaims` for SUCCESS/FAILED, and `DisbursementExpiryJob` only handles `PENDING_CONFIRMATION`. No code path reaches `transitionClaims` for an EXPIRED target. The invariant was correct but **unproven by any E2E test**. This plan adds that machine-verified proof.

## New Helpers Added

- `stubMtnAccountAndTransferForProcessing()` — stubs MTN account-holder GET and transfer POST for PROCESSING path. Not in `setUp` because existing tests assert zero `/v1_0/transfer` calls.
- `postDisbursementForProcessing(amount, reference, idempotencyKey, transactionIds)` — POSTs disbursement and asserts `status=PROCESSING` in response. Adapted from existing `postDisbursementAndAssertPending`.
- `assertClaimStatuses(disbursementId, expectedStatus, expectedCount)` — copied verbatim from `MtnDisbursementE2EIT` (lines 452–459). Uses raw JDBC to avoid JPA first-level cache.

## Production Code Changes

None. `git diff --name-only HEAD -- src/main/` returns empty.

## Test Results

**Focused run:** `mvn test -Dtest=DisbursementExpiryE2EIT -DfailIfNoTests=false`
→ `Tests run: 3, Failures: 0, Errors: 0` — BUILD SUCCESS

**Phase quality gate:** `mvn verify`
→ `Tests run: 301, Failures: 0, Errors: 0, Skipped: 3` — BUILD SUCCESS
→ Baseline was 300 ITs; +1 confirms the new test is included and passing.

## Closing Note

This closes Finding G-1 from `v11-MILESTONE-AUDIT.md`. CLAIM-05 now has both production correctness (enforced since Phase 56) and E2E proof (this phase). Phase 60 complete.
