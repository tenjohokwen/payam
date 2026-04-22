---
phase: 48-test-coverage
verified: 2026-04-22T12:00:00Z
status: human_needed
score: 7/7 must-haves verified
human_verification:
  - test: "Run mvn verify and confirm LedgerConstraintIT.flowColumn_existsAndIsNullable passes"
    expected: "Failsafe reports 0 failures. All integration tests pass including the flow column assertion expecting character_maximum_length=20."
    why_human: "The 48-02-SUMMARY documents this test failed during the phase (expected 20, got 255). The REQUIREMENTS traceability table marks TEST-08 as Complete despite 1 Failsafe failure. Cannot determine whether the test was subsequently fixed or whether the 'pre-existing' classification is accurate without running the full suite against the current HEAD."
---

# Phase 48: Test Coverage Verification Report

**Phase Goal:** Every ledger flow variant is proven correct by unit tests, the PITest mutation threshold is maintained, and a real-database integration test confirms disbursement rows persist without constraint violation.
**Verified:** 2026-04-22T12:00:00Z
**Status:** human_needed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths (from ROADMAP.md Success Criteria)

| # | Truth | Status | Evidence |
|---|-------|--------|---------|
| 1 | COLLECTION posting unit test: 2 entries, correct account codes, balanced (TEST-01) | VERIFIED | `postEntry_createsBalancedDoubleEntry` at LedgerBalanceGuardTest.java:33; asserts hasSize(2), CUSTOMER_WALLET debit, PROVIDER_CLEARING credit, shared entryGroupId, isEqualByComparingTo |
| 2 | DISBURSEMENT fee>0 unit test: 3 entries, gross=principal+fee, balanced (TEST-02); fee=0 unit test: zero-amount PROVIDER_FEE, balanced (TEST-03) | VERIFIED | `postEntry_disbursement_createsThreeBalancedEntries` (lines 68-131) and `postEntry_disbursementZeroFee_zeroProviderFeeEntry` (lines 133-194) in LedgerBalanceGuardTest.java; both assert hasSize(3), correct account codes, compareTo-safe BigDecimal assertions, shared groupId, creditSum balance |
| 3 | LedgerPosting constructor rejection unit tests: 4 cases (TEST-04) | VERIFIED | LedgerPostingTest.java has 10 @Test methods; grep confirms all 4 rejection method names present: `constructor_rejectsNullFlow`, `constructor_rejectsNullCurrency`, `constructor_rejectsNegativePrincipal`, `constructor_rejectsNegativeFee` |
| 4 | LedgerBalanceGuardTest includes disbursement case; PITest kill rate >= 90% for LedgerService (TEST-05) | VERIFIED | 3 @Test methods present in `com.softropic.payam.domain` package (correct PITest targetTests glob); SUMMARY documents 4/4 mutations killed (100%) with `-DtargetClasses=...LedgerService -DtargetTests=com.softropic.payam.domain.*` |
| 5 | LedgerServiceIT: disbursement group of 3 rows persists in Testcontainers Postgres, no constraint violation, rows balanced (TEST-06) | VERIFIED | `postEntry_disbursement_persistsThreeBalancedRows` at LedgerServiceIT.java:166; no @Transactional on method (V25 trigger fires at commit); asserts hasSize(3), MERCHANT_WALLET debit=1050.00, 2 credits, shared groupId, sum(CREDIT)=sum(DEBIT), currency XAF; 3 @Test methods total, existing two preserved |
| 6 | LedgerVerifier.assertDisbursementLedgerBalanced(txId, principal, fee) exists; assertLedgerBalanced unchanged (TEST-07) | VERIFIED | New method at LedgerVerifier.java:79; queries `main.ledger_entry WHERE transaction_id = ?`; asserts hasSize(3), MERCHANT_WALLET debit=principal+fee, CUSTOMER_WALLET credit=principal, PROVIDER_FEE credit=fee, sum(CREDIT) balanced; existing assertLedgerBalanced, assertEntryCount, assertNoLedgerEntries, toBigDecimal unchanged; DEBIT_ACCOUNT and CREDIT_ACCOUNT constants preserved |
| 7 | mvn verify passes (TEST-08 cross-cutting gate) | UNCERTAIN | SUMMARY reports Surefire 317/0, Failsafe 223/1; the 1 failure is LedgerConstraintIT.flowColumn_existsAndIsNullable (expected character_maximum_length=20, got 255). SUMMARY classifies as pre-existing from Phase 46. REQUIREMENTS traceability marks TEST-08 Complete. Cannot verify programmatically whether this failure still exists at HEAD. |

**Score:** 6/7 truths fully verified; 1 requires human confirmation

---

## Required Artifacts

| Artifact | Status | Evidence |
|----------|--------|---------|
| `src/test/java/com/softropic/payam/domain/LedgerBalanceGuardTest.java` | VERIFIED | Exists, 195 lines, 3 @Test methods in `com.softropic.payam.domain` package; substantive assertions throughout; wired to `LedgerService` via constructor injection + `mock(LedgerEntryRepository.class)` |
| `src/test/java/com/softropic/payam/e2e/verify/LedgerVerifier.java` | VERIFIED | Exists, 156 lines, 4 public methods (assertLedgerBalanced, assertDisbursementLedgerBalanced, assertEntryCount, assertNoLedgerEntries) + 1 private helper; full SQL + AssertJ assertion logic; no stubs |
| `src/test/java/com/softropic/payam/e2e/verify/LedgerVerifierTest.java` | VERIFIED | Exists, 131 lines, 5 @Test methods in `com.softropic.payam.e2e.verify` package; uses `mock(JdbcTemplate.class)`; no Spring context; tests both happy paths and failure paths |
| `src/test/java/com/softropic/payam/transaction/LedgerServiceIT.java` | VERIFIED | Exists, 235 lines, 3 @Test methods; @BeforeEach/@AfterEach intact; no @Transactional on disbursement test method; uses `@SpringBootTest` + Testcontainers via `TestConfig` |

---

## Key Link Verification

| From | To | Via | Status | Detail |
|------|----|-----|--------|--------|
| LedgerBalanceGuardTest disbursement tests | LedgerService.buildDisbursementEntries | `LedgerPosting.disbursement(principal, fee, "XAF")` + ArgumentCaptor on `repo.saveAll()` | WIRED | `LedgerPosting.disbursement(` appears 2 times in file; `verify(repo).saveAll(captor.capture())` pattern present in both new tests |
| LedgerBalanceGuardTest | PITest targetTests glob `com.softropic.payam.domain.*` | Package declaration `com.softropic.payam.domain` | WIRED | `package com.softropic.payam.domain;` confirmed at line 1 |
| LedgerServiceIT.postEntry_disbursement_persistsThreeBalancedRows | LedgerService + V25 balance-check trigger | `ledgerService.postEntry(..., LedgerPosting.disbursement(...))` + `ledgerEntryRepository.findByTransactionId(transactionId)` | WIRED | call at lines 171-173; readback at line 174; no @Transactional on test method (trigger fires at commit) |
| LedgerVerifier.assertDisbursementLedgerBalanced | main.ledger_entry (3-row disbursement group) | JdbcTemplate.queryForList on `SELECT direction, account_code, amount FROM main.ledger_entry WHERE transaction_id = ?` | WIRED | SQL query at lines 83-85; MERCHANT_WALLET, CUSTOMER_WALLET, PROVIDER_FEE assertions at lines 107-127 |

---

## Data-Flow Trace (Level 4)

Not applicable — all artifacts are test files. Test files assert against outputs of production code; they do not render dynamic data for users. The relevant data flow is:

- LedgerBalanceGuardTest: ArgumentCaptor captures `saveAll()` argument → assertions on captured list (in-process, no I/O)
- LedgerServiceIT: `ledgerService.postEntry()` writes to real Testcontainers DB → `ledgerEntryRepository.findByTransactionId()` reads back → assertions (real DB flow, no hollow props)
- LedgerVerifierTest: `mock(JdbcTemplate.class)` returns hand-built rows → verifier logic assertions (assertion logic verification, no rendering)

---

## Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| LedgerBalanceGuardTest has 3 @Test methods | `grep -c '@Test' LedgerBalanceGuardTest.java` | 3 | PASS |
| All 3 expected method names present | grep for each name | 1, 1, 1 | PASS |
| LedgerVerifierTest has 5 @Test methods | `grep -c '@Test' LedgerVerifierTest.java` | 5 | PASS |
| All 5 LedgerVerifierTest method names present | grep for all 5 | 5 | PASS |
| LedgerServiceIT has 3 @Test methods (not counting @TestPropertySource) | grep lines 112, 138, 165 | 3 confirmed | PASS |
| Disbursement test method present in LedgerServiceIT | grep count | 1 | PASS |
| No @Transactional on LedgerServiceIT disbursement test | grep before method | not found | PASS |
| No BigDecimal .equals() anti-pattern | grep on all files | no matches | PASS |
| No TODO/FIXME/placeholder comments | grep on all files | no matches | PASS |
| Commits documented in SUMMARYs exist in git log | `git log --oneline` | 47b1a8e, 36df236, f33b03c found | PASS |
| LedgerBalanceGuardTest in com.softropic.payam.domain package | grep package line | match | PASS |
| assertLedgerBalanced (original) unchanged | grep constants DEBIT_ACCOUNT/CREDIT_ACCOUNT; grep method signature | both 1 | PASS |
| No production source modified by phase 48 | `git diff HEAD~3..HEAD -- src/main/` | no output | PASS |

---

## Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|---------|
| TEST-01 | 48-01 | COLLECTION unit test: 2 entries, balanced, correct account codes | SATISFIED | `postEntry_createsBalancedDoubleEntry` — pre-existing, verified in plan 01 |
| TEST-02 | 48-01 | DISBURSEMENT fee>0 unit test: 3 entries, gross DEBIT = principal + fee | SATISFIED | `postEntry_disbursement_createsThreeBalancedEntries` added in commit 47b1a8e |
| TEST-03 | 48-01 | DISBURSEMENT fee=0 unit test: zero-amount PROVIDER_FEE credit, balanced | SATISFIED | `postEntry_disbursementZeroFee_zeroProviderFeeEntry` added in commit 47b1a8e |
| TEST-04 | 48-01 | LedgerPosting constructor rejection tests (4 cases) | SATISFIED | Pre-existing in LedgerPostingTest (10 tests), 4 rejection methods confirmed |
| TEST-05 | 48-01 | PITest LedgerService mutation kill rate >= 90% | SATISFIED | 100% (4/4 killed) with targeted run; note: full profile run shows 31% overall due to pre-existing issue in other target classes unrelated to LedgerService |
| TEST-06 | 48-02 | LedgerServiceIT: 3 rows persisted in Testcontainers Postgres, no constraint violation, balanced | SATISFIED | `postEntry_disbursement_persistsThreeBalancedRows` added in commit f33b03c |
| TEST-07 | 48-02 | LedgerVerifier.assertDisbursementLedgerBalanced + existing assertLedgerBalanced unchanged | SATISFIED | New method at LedgerVerifier.java:79; 5 unit tests in LedgerVerifierTest; added in commit 36df236 |
| TEST-08 | Cross-cutting (both plans) | mvn verify passes (all unit + integration tests) | UNCERTAIN — human needed | SUMMARY reports 1 pre-existing failure: LedgerConstraintIT.flowColumn_existsAndIsNullable. Traceability table marks it Complete. Human must confirm current HEAD. |

**Note on TEST-08 and the full PITest profile:** TEST-08 is a cross-cutting gate not claimed in either plan's `requirements:` field, but the ROADMAP explicitly assigns it to Phase 48 as the "final quality gate." The SUMMARY documents `mvn verify` Surefire 317/0 + Failsafe 222 passes and 1 pre-existing failure. TEST-05's 31% overall PITest rate is also flagged — this is a pre-existing issue affecting non-LedgerService targets; the requirement as written is LedgerService-specific (4/4 killed, 100%).

---

## Anti-Patterns Found

None detected in any of the four key test files.

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| — | — | No issues found | — | — |

---

## Human Verification Required

### 1. mvn verify clean run at current HEAD (TEST-08)

**Test:** Run `mvn verify -pl . -q` from the repository root on the current main HEAD and confirm the build exits 0 with Failsafe showing 0 failures.

**Expected:** `BUILD SUCCESS`. Failsafe output shows no failures. Specifically, `LedgerConstraintIT.flowColumn_existsAndIsNullable` must pass — it asserts that `character_maximum_length` for the `flow` column is `20` (matching the V25 Flyway migration `flow VARCHAR(20)`).

**Why human:** The 48-02-SUMMARY documents 1 Failsafe failure for this test during the phase run (got 255, expected 20). The REQUIREMENTS.md traceability table marks TEST-08 as Complete despite this. The SUMMARY argues it is "pre-existing from Phase 46" — but this cannot be verified by static code analysis. Only running the full suite against HEAD can confirm whether: (a) the test was subsequently fixed, (b) it remains a known-pre-existing failure that the team accepts, or (c) it is a genuine gate failure. If it fails, the root cause (Hibernate DDL override vs. V25 Flyway column size) should be filed for Phase 49 or a dedicated fix.

---

## Gaps Summary

No blocking gaps. All 7 required test artifacts exist, are substantive, and are correctly wired. The only open item is human confirmation of the full `mvn verify` clean run — specifically whether `LedgerConstraintIT.flowColumn_existsAndIsNullable` passes or is an accepted pre-existing failure at HEAD.

The 31% overall PITest profile result is documented above but does not constitute a gap against TEST-05 as written, since TEST-05 specifies the LedgerService mutation target explicitly and that target achieves 100%.

---

_Verified: 2026-04-22T12:00:00Z_
_Verifier: Claude (gsd-verifier)_
