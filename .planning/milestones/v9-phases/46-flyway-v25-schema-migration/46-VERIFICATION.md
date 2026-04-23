---
phase: 46-flyway-v25-schema-migration
verified: 2026-04-21T22:45:00Z
status: human_needed
score: 5/6 must-haves verified automatically
human_verification:
  - test: "Run mvn verify and confirm BUILD SUCCESS with LedgerConstraintIT showing 5 tests passing"
    expected: "Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 for LedgerConstraintIT; overall BUILD SUCCESS with 222 tests"
    why_human: "Full integration test suite requires a running Testcontainers PostgreSQL instance and ~8 minutes to execute; cannot be triggered from verification tooling. SUMMARY documents this as achieved (222 tests, 0 failures) backed by two git commits. Manual spot-check or CI run needed to independently confirm."
---

# Phase 46: Flyway V25 Schema Migration Verification Report

**Phase Goal:** Enable disbursement ledger writes by migrating schema — drop incompatible unique constraint, add deferrable balance trigger, relax amount check, add flow column
**Verified:** 2026-04-21T22:45:00Z
**Status:** human_needed (all automated checks pass; mvn verify result deferred to human/CI confirmation)
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | V25 migration runs cleanly on an existing DB that already has V23 applied and ledger_entry rows present | ✓ VERIFIED | File exists at correct path; preflight DO block (line 11) precedes DROP CONSTRAINT (line 32); no BEGIN/COMMIT present (Flyway anti-pattern); two git commits confirmed (89c98eb, 0ea2450) |
| 2 | After V25, an unbalanced DEBIT-only group is rejected at commit by the balance-check trigger | ✓ VERIFIED | `trg_ledger_balance_check AFTER INSERT DEFERRABLE INITIALLY DEFERRED` present in migration (lines 64–67); `LedgerConstraintIT.unbalancedInsert_isRejectedByConstraint` asserts "Ledger balance violation" and includes JpaSystemException in exception type assertion |
| 3 | After V25, a 3-entry disbursement group (DEBIT gross + CREDIT principal + CREDIT fee) commits without unique constraint violation | ✓ VERIFIED | `uq_ledger_entry_group_direction` dropped in Step 2; `threeEntryDisbursementGroup_succeeds` test with DEBIT 110 + CREDIT 100 + CREDIT 10 for same entry_group_id exists and is substantive |
| 4 | After V25, a ledger_entry row with amount = 0.00 inserts without violating the amount CHECK | ✓ VERIFIED | Step 4 drops V4 auto-named CHECK and adds `chk_ledger_amount_non_negative CHECK (amount >= 0)` (line 90); `zeroAmountEntry_succeeds` test validates this |
| 5 | After V25, main.transaction and main.transaction_aud both have a nullable flow VARCHAR(20) column | ✓ VERIFIED | Two `ADD COLUMN IF NOT EXISTS flow VARCHAR(20)` statements (lines 96 and 132); `CREATE TABLE IF NOT EXISTS main.transaction_aud` present (line 103); `flowColumn_existsAndIsNullable` test queries information_schema and asserts is_nullable=YES, data_type=character varying, length=20 on both tables |
| 6 | mvn verify passes after the migration + test updates land in the same commit | ? UNCERTAIN | SUMMARY documents "Tests run: 222, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS" confirmed by commit 0ea2450; independent programmatic verification requires running Testcontainers (~8 min) — deferred to human/CI |

**Score:** 5/6 truths verified automatically; 1 requires human/CI confirmation

---

## Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/resources/db/migration/V25__ledger_disbursement_schema.sql` | Complete V25 migration: preflight DO block, constraint drop, trigger function, CONSTRAINT TRIGGER, CHECK relaxation, flow column on transaction + transaction_aud | ✓ VERIFIED | 136-line file; all 6 required content strings confirmed by grep; correct Flyway naming convention (double underscore); no BEGIN/COMMIT |
| `src/test/java/com/softropic/payam/transaction/LedgerConstraintIT.java` | Integration tests covering trigger behavior, 3-entry disbursement group, zero-amount entries, and flow column existence | ✓ VERIFIED | 189-line file; 5 @Test methods present; correct assertion updated from constraint name to trigger message; all new test methods substantive and non-trivial |

---

## Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `V25__ledger_disbursement_schema.sql` trigger function | `main.ledger_entry` rows | `CREATE CONSTRAINT TRIGGER trg_ledger_balance_check DEFERRABLE INITIALLY DEFERRED AFTER INSERT` | ✓ WIRED | Lines 64–67 confirmed by grep; AFTER INSERT scope is correct for immutable ledger_entry |
| `LedgerConstraintIT.unbalancedInsert_isRejectedByConstraint` | trigger exception message | assertion on exception cause chain | ✓ WIRED | `.contains("Ledger balance violation")` assertion present (line 90 of test file); JpaSystemException added to isInstanceOfAny to handle Spring's wrapping of deferred trigger exceptions |
| V25 flow column | `main.transaction_aud` (Envers parity) | `CREATE TABLE IF NOT EXISTS` + `ADD COLUMN IF NOT EXISTS` | ✓ WIRED | `CREATE TABLE IF NOT EXISTS main.transaction_aud` (line 103); `ALTER TABLE main.transaction_aud ADD COLUMN IF NOT EXISTS flow VARCHAR(20)` (line 132); `main.transaction_aud` referenced 3 times in migration |

---

## Data-Flow Trace (Level 4)

Not applicable — this phase produces a SQL migration file and integration tests, not components that render dynamic data.

---

## Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Migration file has correct Flyway filename | `test -f src/main/resources/db/migration/V25__ledger_disbursement_schema.sql` | File exists at exact path | ✓ PASS |
| Preflight precedes DROP CONSTRAINT | Line numbers: preflight DO=11, DROP CONSTRAINT=32 | Correct ordering | ✓ PASS |
| No BEGIN/COMMIT anti-pattern | `grep "BEGIN;\|COMMIT;"` returns no matches | Exit 1 (no matches) | ✓ PASS |
| @NotAudited columns absent from DDL | `grep -c "risk_score\|device_fingerprint\|fee_amount\|fee_rule_id"` returns 1 | Match is comment line 100 only; DDL excludes all 4 columns | ✓ PASS |
| Old constraint name absent from test assertions | `grep "uq_ledger_entry_group_direction" LedgerConstraintIT.java` | Exit 1 (no matches) | ✓ PASS |
| Both flow ADD COLUMN statements present | Count of `ADD COLUMN IF NOT EXISTS flow VARCHAR(20)` = 2 | Count = 2 | ✓ PASS |
| Full mvn verify | `mvn verify` | Requires Testcontainers — not run | ? SKIP (human needed) |

---

## Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| SCHEMA-01 | 46-01-PLAN.md | Drop `uq_ledger_entry_group_direction`; replace with deferrable SUM(DEBIT)==SUM(CREDIT) trigger | ✓ SATISFIED | `DROP CONSTRAINT IF EXISTS uq_ledger_entry_group_direction` (line 32); `CREATE CONSTRAINT TRIGGER trg_ledger_balance_check … DEFERRABLE INITIALLY DEFERRED` (lines 64–67); trigger function uses COALESCE SUM FILTER pattern (lines 49–50) |
| SCHEMA-02 | 46-01-PLAN.md | Pre-flight DO block verifies no unbalanced groups before constraint drop | ✓ SATISFIED | DO block (lines 11–26) queries ledger_entry grouped by entry_group_id, RAISES EXCEPTION if bad_count > 0; positioned before DROP CONSTRAINT at line 32 |
| SCHEMA-03 | 46-01-PLAN.md | Relax `CHECK (amount > 0)` to `CHECK (amount >= 0)` for zero-fee entries | ✓ SATISFIED | Step 4 DO block discovers auto-named V4 CHECK via pg_constraint, drops it dynamically; `ADD CONSTRAINT chk_ledger_amount_non_negative CHECK (amount >= 0)` (line 90) |
| SCHEMA-04 | 46-01-PLAN.md | Add nullable `flow VARCHAR(20)` to `main.transaction` and `main.transaction_aud` | ✓ SATISFIED | `ALTER TABLE main.transaction ADD COLUMN IF NOT EXISTS flow VARCHAR(20)` (line 96); `CREATE TABLE IF NOT EXISTS main.transaction_aud` with flow column (line 121); `ALTER TABLE main.transaction_aud ADD COLUMN IF NOT EXISTS flow VARCHAR(20)` (line 132) |

**Orphaned requirements check:** REQUIREMENTS.md maps SCHEMA-01 through SCHEMA-04 exclusively to Phase 46 (traceability table lines 72–75), all marked Complete. No requirements assigned to Phase 46 that were not claimed in the plan. No orphaned requirements.

---

## Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `V25__ledger_disbursement_schema.sql` | 100 | `risk_score, device_fingerprint, fee_amount, fee_rule_id` appears in a SQL comment | Info | Comment documents the exclusion of @NotAudited columns from transaction_aud DDL. Correct behavior — these columns are absent from the CREATE TABLE DDL. The SUMMARY notes this as a known deviation from one acceptance criterion that used `grep -c` to check count. No behavioral impact. |

No blocking anti-patterns found.

---

## Human Verification Required

### 1. mvn verify Integration Test Suite

**Test:** From the project root, run `mvn verify` (requires Docker for Testcontainers)
**Expected:** BUILD SUCCESS; `LedgerConstraintIT` reports `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`; `LedgerServiceIT` reports `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`; overall test count ~222, 0 failures
**Why human:** Full test suite requires a running Docker daemon for Testcontainers PostgreSQL, takes approximately 8 minutes, and cannot be invoked from static verification tooling. SUMMARY.md documents the result as achieved (commit 0ea2450 message explicitly states "mvn verify BUILD SUCCESS") but independent confirmation via CI or a local run is needed.

---

## Deviations from Plan

One deviation is documented in SUMMARY.md and is correctly handled:

**JpaSystemException in exception type assertion:** When the CONSTRAINT TRIGGER fires at commit via `TransactionTemplate`, Spring wraps the PostgreSQL trigger exception as `JpaSystemException: Unable to commit against JDBC Connection` rather than `DataIntegrityViolationException` (which the V23 unique constraint previously threw directly). The test was updated to add `JpaSystemException.class` to `isInstanceOfAny(...)` in commit 0ea2450. This is a correct fix, not a regression or stub.

---

## Gaps Summary

No gaps. All automated checks pass. The phase goal is achieved at the artifact and wiring level. The sole open item is independent confirmation of the `mvn verify` green build, which requires a Docker environment and is noted for human or CI verification.

---

_Verified: 2026-04-21T22:45:00Z_
_Verifier: Claude (gsd-verifier)_
