# Pitfalls — v9 Ledger Disbursement Support

**Domain:** Double-entry ledger extension — disbursement/cashout flow
**Project:** Payam — Spring Boot 3.5, Spring Data JPA, PostgreSQL/Flyway, Hibernate Envers
**Researched:** 2026-04-21
**Overall confidence:** HIGH — derived from direct codebase inspection and cross-validated against STACK/FEATURES/ARCHITECTURE research.

---

## Pitfall 1: V23 Constraint Blocks Disbursement CREDIT Rows (CRITICAL)

**Phase:** V25 migration (Phase 46)

**What goes wrong:** `V23__ledger_group_constraint.sql` adds `UNIQUE (entry_group_id, direction) DEFERRABLE INITIALLY DEFERRED`. A disbursement group has one DEBIT and **two** CREDITs (`CUSTOMER_WALLET` + `PROVIDER_FEE`). PostgreSQL defers the uniqueness check to commit — but at commit it still rejects two rows with the same `(entry_group_id, 'CREDIT')`. Every disbursement write fails at the DB layer with a unique constraint violation.

**Why it's easy to miss:** The constraint is deferred, so inserts succeed individually. The failure surfaces only at commit, which may appear as a generic transaction rollback rather than a constraint error.

**Prevention:** V25 must drop `uq_ledger_entry_group_direction` **before** any disbursement write path is exercised. Replace with a balance-check trigger (`DEFERRABLE INITIALLY DEFERRED`) that asserts `SUM(DEBIT) == SUM(CREDIT)` per group at commit — the correct double-entry invariant. Include a pre-flight DO block verifying no unbalanced groups exist before dropping the constraint.

---

## Pitfall 2: `CHECK (amount > 0)` Rejects Zero-Fee PROVIDER_FEE Entries

**Phase:** V25 migration (Phase 46)

**What goes wrong:** `V4__ledger_schema.sql` has `CHECK (amount > 0)` on `ledger_entry.amount`. A zero-fee disbursement must emit a `PROVIDER_FEE` CREDIT with amount = 0. This is rejected at the DB layer.

**Why it's easy to miss:** Unit tests may use fee > 0. The constraint only surfaces when a real cashout has no provider fee.

**Prevention:** V25 must relax the check to `CHECK (amount >= 0)`. All existing rows satisfy the new constraint; this is a non-breaking change. Include this in the same migration as the constraint drop.

---

## Pitfall 3: `Transaction.flow` on `@Audited` Entity Without Envers AUD Column

**Phase:** V25 migration (Phase 46) + Transaction entity update

**What goes wrong:** `Transaction` is `@Audited`. Adding `flow` without either `@NotAudited` or a matching column in `transaction_aud` causes Hibernate to throw a schema validation error on startup. The v8 cycle had the exact same issue with `platform_config_aud` (fixed in V24 via `ADD COLUMN IF NOT EXISTS`).

**Two valid paths:**
1. Add `flow` to `transaction_aud` in V25 — Envers will audit changes to the field.
2. Annotate the field `@NotAudited` — matches `feeAmount` / `feeRuleId` precedent.

**Recommendation (ARCHITECTURE.md):** Add `flow` column to `transaction_aud` in V25 (audit trail for flow type is valuable for reconciliation). This is the path the ARCHITECTURE.md migration script takes.

**Prevention:** V25 must include `ALTER TABLE main.transaction_aud ADD COLUMN IF NOT EXISTS flow VARCHAR(20)`. Run `mvn verify` before committing to confirm startup succeeds against Testcontainers.

---

## Pitfall 4: Balance-Check Trigger Fires Mid-Transaction on Partial Groups

**Phase:** V25 migration

**What goes wrong:** A deferrable trigger that fires `FOR EACH ROW` (not `FOR EACH STATEMENT`) will run after every INSERT. If both sides are checked immediately (before all 3 disbursement rows are inserted), the balance check fails on partial groups: after row 1 (DEBIT only), debit_sum > 0 and credit_sum = 0 — the trigger raises an exception.

**Prevention:** The trigger function must skip the balance check when either side is still zero. The recommended guard:

```sql
IF debit_sum > 0 AND credit_sum > 0 AND debit_sum <> credit_sum THEN
    RAISE EXCEPTION ...
END IF;
```

This allows partial groups mid-transaction. The deferral to commit provides a final check window for any unbalanced group.

---

## Pitfall 5: `LedgerBalanceGuardTest` and `LedgerVerifier` Hardcode Collection Assumptions

**Phase:** Unit tests / E2E helper updates

**What goes wrong:** `LedgerBalanceGuardTest` (PITest MUT-02 target) and `LedgerVerifier.assertLedgerBalanced()` both hardcode `hasSize(2)` and expect only `CUSTOMER_WALLET` / `PROVIDER_CLEARING` account codes. Adding the disbursement path without updating these breaks the PITest mutation threshold (≥90%) and leaves the E2E verifier unable to assert on disbursement groups.

**Prevention:**
- Add a disbursement case to `LedgerBalanceGuardTest` covering: 3 entries, gross DEBIT = principal + fee, two CREDIT entries with correct account codes, balance holds.
- Add `assertDisbursementLedgerBalanced(String transactionId, BigDecimal principal, BigDecimal fee)` to `LedgerVerifier` without modifying the existing collection method.

**Detection:** PITest `mutationThreshold` of 90 will fail if the disbursement builder is not covered by tests that kill mutations.

---

## Pitfall 6: Existing `LedgerServiceIT` Uses the 4-Arg `postEntry` Signature

**Phase:** LedgerServiceIT update

**What goes wrong:** `LedgerServiceIT` calls the existing `postEntry(transactionId, tenantId, amount, currency)` signature. After the v9 migration removes the old 4-arg method, the test will not compile. If the deprecated 4-arg delegate is kept temporarily, the tests pass without verifying the new API — the intent is untested.

**Prevention:** Update `LedgerServiceIT` in the same phase as the `LedgerService` rewrite. Replace all `postEntry(txId, tenantId, amount, currency)` calls with `postEntry(txId, tenantId, LedgerPosting.collection(amount, currency))`. Add a new disbursement test method asserting 3 rows, correct account codes, and balanced amounts against a real PostgreSQL instance (Testcontainers).

---

## Pitfall 7: `@Transactional` on `OrangeMoneyPort.initiateCashout()` Holds DB Connection During HTTP

**Phase:** Orange cashout wiring

**What goes wrong:** The project rule (PROJECT.md Key Decisions) is explicit: no `@Transactional` on methods that make provider HTTP calls — holding a DB connection during a slow/hanging Cameroon network call exhausts the connection pool. Using `@Transactional` on `initiateCashout()` to simplify the ledger write violates this rule.

**Prevention:** Use `TransactionTemplate.execute()` for the ledger write, exactly as `persistPayToken()` does in the existing Orange adapter. The ledger call goes inside a `TransactionTemplate` block that executes after the HTTP call returns.

---

## Pitfall 8: `PaymentCommand` Does Not Carry `feeAmount` — Extra DB Query Required

**Phase:** Orange cashout wiring

**What goes wrong:** `OrangeMoneyPort.initiateCashout()` needs the fee for `LedgerPosting.disbursement(principal, fee, currency)`. The current `PaymentCommand` record carries `amount` but not `feeAmount`. Without extending `PaymentCommand`, resolving the fee requires a `TransactionRepository.findByTransactionId()` call inside the adapter — an extra DB query per cashout.

**Prevention:** Extend `PaymentCommand` with an optional `feeAmount` field (nullable `BigDecimal`). The orchestrator populates it from `FeeEvaluationService.evaluateFee()` before dispatch — identical to how `transaction.feeAmount` is already populated. This keeps `initiateCashout()` free of extra DB lookups.

---

## Pitfall 9: Dropping V23 Constraint Without Pre-Flight Data Validation

**Phase:** V25 migration

**What goes wrong:** V23 was added specifically because unbalanced ledger groups were possible (LEDGER-01). Dropping its constraint without first verifying all existing groups are balanced risks masking a pre-existing data integrity violation.

**Prevention:** Include a DO $$ pre-flight block in V25 that scans all existing entry groups and raises an exception if any are unbalanced. Migration fails fast with a clear error message rather than silently removing the guard from bad data.

---

## Phase-Specific Warnings Summary

| Phase Topic | Pitfall | Prevention |
|-------------|---------|------------|
| V25 migration | V23 constraint blocks disbursement 2nd CREDIT | Drop constraint, add balance-check trigger |
| V25 migration | `CHECK (amount > 0)` rejects zero-fee entries | Relax to `>= 0` in same migration |
| V25 migration | `transaction_aud` missing `flow` column causes startup failure | `ADD COLUMN IF NOT EXISTS flow VARCHAR(20)` in V25 |
| V25 migration | Balance trigger fails on partial groups mid-TX | Guard: skip check when either side is zero |
| V25 migration | Drop constraint without pre-flight data validation | DO $$ block verifying balance before DROP |
| LedgerService rewrite | Old 4-arg tests don't compile after signature change | Update LedgerServiceIT in same phase |
| Unit/E2E tests | PITest MUT-02 drops below 90% without disbursement cases | Add disbursement case to LedgerBalanceGuardTest; extend LedgerVerifier |
| Orange cashout wiring | `@Transactional` on initiateCashout exhausts connection pool | TransactionTemplate post-HTTP; no `@Transactional` on method |
| Orange cashout wiring | `feeAmount` not on `PaymentCommand` forces extra DB query | Extend PaymentCommand with optional `feeAmount` |

---

*Pitfalls research for: Payam v9 — Ledger Disbursement Support*
*Researched: 2026-04-21*
