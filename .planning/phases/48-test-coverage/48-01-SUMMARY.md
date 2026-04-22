---
phase: 48-test-coverage
plan: "01"
subsystem: transaction/service
tags: [ledger, unit-tests, pitest, mutation-coverage, tdd, disbursement]
dependency_graph:
  requires:
    - Phase 47 Plan 02 (LedgerService rewrite with switch-routed postEntry + LedgerPosting)
    - Phase 47 Plan 01 (LedgerFlow + LedgerPosting contract types)
  provides:
    - Unit tests for LedgerService DISBURSEMENT path (TEST-02, TEST-03)
    - PITest mutation coverage for LedgerService at 100% (TEST-05)
  affects:
    - PITest mutation profile (com.softropic.payam.domain.* targetTests)
tech_stack:
  added: []
  patterns:
    - Mockito ArgumentCaptor pattern for capturing saveAll() list
    - AssertJ isEqualByComparingTo + compareTo for scale-insensitive BigDecimal assertions
    - Unit tests in com.softropic.payam.domain package for PITest targetTests glob
key_files:
  created: []
  modified:
    - src/test/java/com/softropic/payam/domain/LedgerBalanceGuardTest.java
decisions:
  - "Tests added to existing LedgerBalanceGuardTest in com.softropic.payam.domain package — required for PITest targetTests glob"
  - "Used .compareTo(BigDecimal.ZERO) == 0 and isEqualByComparingTo() throughout — no .equals() on BigDecimal (scale-insensitivity pitfall)"
  - "Pre-existing PITest profile failure (31% overall) documented as out-of-scope — LedgerService alone achieves 100% when targeted directly"
metrics:
  duration: "~10 minutes"
  completed_date: "2026-04-22"
  tasks_completed: 3
  files_created: 0
  files_modified: 1
---

# Phase 48 Plan 01: LedgerService DISBURSEMENT Unit Tests Summary

**One-liner:** Two disbursement unit tests added to LedgerBalanceGuardTest (fee>0 and fee=0) in the PITest domain package, achieving 100% mutation kill rate for LedgerService.

## TEST-01 and TEST-04 Verification

### TEST-01 — COLLECTION unit test (pre-satisfied)

`mvn test -Dtest=LedgerBalanceGuardTest -pl . -q` (before Task 2):

```
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Existing `postEntry_createsBalancedDoubleEntry` confirms:
- `hasSize(2)` — exactly 2 entries
- `isEqualByComparingTo(credit.getAmount())` — balanced
- `debit.getAccountCode().isEqualTo("CUSTOMER_WALLET")` — correct DEBIT account
- `credit.getAccountCode().isEqualTo("PROVIDER_CLEARING")` — correct CREDIT account
- Shared `entryGroupId` verified

`grep -c '@Test' LedgerBalanceGuardTest.java` prints `1` (pre-Task 2 state confirmed).

**TEST-01 is ALREADY SATISFIED. No new code added.**

### TEST-04 — LedgerPosting rejection tests (pre-satisfied)

`mvn test -Dtest=LedgerPostingTest -pl . -q`:

```
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Four required rejection cases verified present:
- `constructor_rejectsNullFlow` — covers null flow
- `constructor_rejectsNullCurrency` — covers null currency
- `constructor_rejectsNegativePrincipal` — covers negative principal
- `constructor_rejectsNegativeFee` — covers negative fee

`grep -c` for all 4 method names prints `4`.

**TEST-04 is ALREADY SATISFIED by existing LedgerPostingTest. No new code added.**

## What Was Built

Two new `@Test` methods appended to `LedgerBalanceGuardTest` in package `com.softropic.payam.domain`:

### `postEntry_disbursement_createsThreeBalancedEntries` (TEST-02)

- principal=1000.00, fee=50.00, gross=1050.00
- Asserts `hasSize(3)` — exactly 3 entries
- Asserts DEBIT `MERCHANT_WALLET` amount = gross (1050.00)
- Asserts 2 CREDIT entries: `CUSTOMER_WALLET`=principal, `PROVIDER_FEE`=fee
- Asserts `creditSum.isEqualByComparingTo(gross)` — balance invariant
- Asserts shared `entryGroupId` across all 3 entries

### `postEntry_disbursementZeroFee_zeroProviderFeeEntry` (TEST-03)

- principal=1000.00, fee=BigDecimal.ZERO, gross=principal (1000.00)
- Asserts `hasSize(3)` — 3 entries even when fee=0
- Asserts DEBIT `MERCHANT_WALLET` amount = principal
- Asserts `PROVIDER_FEE` credit amount `compareTo(BigDecimal.ZERO) == 0` (scale-insensitive)
- Asserts `CUSTOMER_WALLET` credit = principal
- Asserts `creditSum.isEqualByComparingTo(principal)` — balance holds with zero fee
- Asserts shared `entryGroupId`

## Test Run Output

`mvn test -Dtest=LedgerBalanceGuardTest -pl . -q` after Task 2:

```
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Plan-level verification `mvn test -Dtest=LedgerBalanceGuardTest,LedgerPostingTest -pl . -q`:

```
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

(3 in LedgerBalanceGuardTest + 10 in LedgerPostingTest)

## PITest Output (TEST-05)

### LedgerService-specific run (TEST-05 proof):

```
mvn pitest:mutationCoverage -Pmutation -pl . \
  -DtargetClasses=com.softropic.payam.transaction.service.LedgerService \
  -DtargetTests='com.softropic.payam.domain.*' \
  -DmutationThreshold=90
```

```
>> Generated 4 mutations Killed 4 (100%)
>> Mutations with no coverage 0. Test strength 100%
BUILD SUCCESS
```

`LedgerService` mutation kill rate: **100% (4/4 killed) — TEST-05 SATISFIED**.

### Note on full profile run:

`mvn pitest:mutationCoverage -Pmutation -pl .` reports 31% overall (22/71 mutations killed). This is a **pre-existing issue** unrelated to this plan:

- The `mutation` profile targets 6 classes: `OrangeTimeUtil`, `TransactionStatus`, `PaymentEventLog`, `FraudScoringService`, `LedgerService`, `IdempotencyService`
- `targetTests=com.softropic.payam.domain.*` filters tests to those 6 domain guard classes
- Classes other than `LedgerService` have surviving mutations in the domain tests — this was already failing at 27% before this plan ran (confirmed by checking main HEAD)
- `LedgerService` alone: **100%** (target/pit-reports HTML confirms 4/4 killed)
- `IdempotencyService`: 21% (4/19) — pre-existing survivor accumulation not in scope for this plan
- Pre-existing failure on `main` before Phase 48: 27% overall

This failure predates this plan and is out of scope per scope boundary rules. The `LedgerService`-specific criterion (TEST-05) is met.

## Commits

| Task | Commit | Files |
|------|--------|-------|
| Task 1: Verify TEST-01 and TEST-04 (no code) | — | — |
| Task 2: Add DISBURSEMENT tests to LedgerBalanceGuardTest | 47b1a8e | LedgerBalanceGuardTest.java |

## Traceability

| Requirement | Status | Evidence |
|-------------|--------|---------|
| TEST-01 | Satisfied (pre-existing) | `postEntry_createsBalancedDoubleEntry` — 1 test passing |
| TEST-02 | Satisfied | `postEntry_disbursement_createsThreeBalancedEntries` — passing |
| TEST-03 | Satisfied | `postEntry_disbursementZeroFee_zeroProviderFeeEntry` — passing |
| TEST-04 | Satisfied (pre-existing) | LedgerPostingTest 4 rejection cases — 10 tests passing |
| TEST-05 | Satisfied | LedgerService PITest 100% (4/4 killed) |

## Deviations from Plan

### Pre-existing PITest profile failure (out of scope)

- **Found during:** Task 3
- **Issue:** `mvn pitest:mutationCoverage -Pmutation -pl .` exits with non-zero (31% overall < 90% threshold). This was already failing at 27% before this plan.
- **Root cause:** The `mutation` profile targets 6 classes but only domain tests (com.softropic.payam.domain.*) are used. Classes other than `LedgerService` have surviving mutations in their domain tests (pre-Phase 48 issue).
- **Per scope boundary rules:** Pre-existing failures in unrelated targets are out of scope. `LedgerService` specifically achieves 100% kill rate — TEST-05 is satisfied.
- **Action:** Documented in SUMMARY; not fixed (would require strengthening 5 other domain guard tests, not in this plan's scope).

## Known Stubs

None.

## Self-Check: PASSED

- [x] `src/test/java/com/softropic/payam/domain/LedgerBalanceGuardTest.java` exists and has 3 @Test methods
- [x] `grep -c '@Test' LedgerBalanceGuardTest.java` prints `3`
- [x] `grep -c 'void postEntry_disbursement_createsThreeBalancedEntries\b'` prints `1`
- [x] `grep -c 'void postEntry_disbursementZeroFee_zeroProviderFeeEntry\b'` prints `1`
- [x] `grep -c 'void postEntry_createsBalancedDoubleEntry\b'` prints `1` (preserved)
- [x] Commit 47b1a8e exists
- [x] LedgerService PITest 100% (4/4) confirmed
- [x] No production source files modified (`git diff --name-only src/main/` prints nothing)
