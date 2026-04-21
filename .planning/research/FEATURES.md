# Feature Landscape — v9 Ledger Disbursement Support

**Domain:** Double-entry ledger extension — disbursement/cashout flow
**Researched:** 2026-04-21
**Overall confidence:** HIGH — all findings derived from live codebase and project spec.
No external inference required.

---

## Table Stakes

Features that must work correctly for v9 to be shippable. Missing or broken = not done.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| `LedgerFlow` enum (COLLECTION / DISBURSEMENT) | Callers must express intent without knowing account codes. Flow is the discriminant for routing to the right entry builder inside `LedgerService`. | Low | New enum in `transaction/contract`. Two values only — no polymorphism needed. |
| `LedgerPosting` record with factory methods | Encapsulates principal + fee + currency without leaking account codes. `collection()` / `disbursement()` factories make call-sites self-documenting and type-safe. | Low | Compact constructor must validate: `principal > 0`, `fee >= 0`, `currency` non-null. The existing four-arg `postEntry(amount, currency)` is a thin delegate during migration, then deleted. |
| `LedgerService` switch-dispatched entry builders | Routes COLLECTION to 2-entry builder and DISBURSEMENT to 3-entry builder. Keeps account codes private to `LedgerService` — callers never reference `MERCHANT_WALLET` etc. | Low | Replace current hard-coded body with `switch (posting.flow())`. No polymorphism needed — a `switch` expression is the right tool. |
| Correct gross debit for DISBURSEMENT | `MERCHANT_WALLET` DEBIT must equal `principal + fee`. This is the double-entry balance invariant for a 3-entry group. | Low | `gross = principal.add(fee)` — one line. Incorrect gross permanently corrupts the immutable ledger. |
| Double-entry balance invariant across both flows | Sum of DEBITs == sum of CREDITs within every `entry_group_id`. Currently enforced by `LedgerBalanceGuardTest` (PITest MUT-02 target). | Low | For disbursement: `DEBIT(gross) == CREDIT(principal) + CREDIT(fee)`. New unit test case needed alongside the existing collection case — see test coverage section. |
| V23 constraint dropped or replaced (CRITICAL) | `uq_ledger_entry_group_direction` is `UNIQUE (entry_group_id, direction) DEFERRABLE INITIALLY DEFERRED`. Disbursement has TWO CREDITs per group — this constraint rejects every disbursement write at commit. | Medium | **Hardest table-stakes item. Blocks all progress on disbursement writes.** See dedicated section below. |
| `amount` check relaxed to `>= 0` | V4 schema has `CHECK (amount > 0)`. A zero-fee disbursement must still emit a `PROVIDER_FEE` CREDIT with amount = 0 — this violates the current check. | Low | Flyway V25 `ALTER TABLE main.ledger_entry ALTER COLUMN amount ...` or `DROP CONSTRAINT / ADD CONSTRAINT`. Non-breaking: all existing rows have amount > 0. |
| Existing collection caller migrated to `LedgerPosting.collection()` | Zero regression on the established path. Current signature is `postEntry(transactionId, tenantId, amount, currency)`. | Low | One call-site identified: `WebhookTransitionService`. Update signature and delete the old four-arg method — it must not coexist with the new API. |
| `Transaction.flow` column (Flyway V25) | Reconciliation and reporting queries need to filter by flow type without inspecting account codes. Inferring intent from `account_code` in SQL is fragile. | Low | `ALTER TABLE main.transaction ADD COLUMN flow VARCHAR(20)`. Nullable for backward compatibility — existing rows null, new transactions set it at creation. |
| Orange cashout orchestration wires `LedgerPosting.disbursement()` | The disbursement ledger posting must fire when a cashout completes, the same way the collection posting fires in `WebhookTransitionService`. | Medium | Orange cashout is deferred in `OrangeMoneyPort` per PROJECT.md validated requirements. For v9, wire the call-site placeholder against the service layer; full cashout integration is a future milestone. Fee comes from `transaction.getFeeAmount()` — already persisted by `PaymentOrchestrator`. |
| Unit tests: COLLECTION 2 entries, DISBURSEMENT 3 entries, zero-fee, invalid inputs | PITest mutation threshold is 90% on 6 critical domain classes. Adding disbursement without unit tests breaks MUT-02 kill rate. | Low | Four cases: COLLECTION (2 entries, correct codes), DISBURSEMENT fee>0 (3 entries, gross debit = principal+fee), DISBURSEMENT fee=0 (3 entries, `PROVIDER_FEE` entry with amount=0), `LedgerPosting` constructor rejects negative principal / negative fee / null currency. |
| `LedgerBalanceGuardTest` updated for disbursement | Current test (`LedgerBalanceGuardTest`) hardcodes the collection path — only covers COLLECTION. PITest requires both paths to maintain coverage. | Low | Add disbursement assertion in the same test class. Capture the 3-entry list from `saveAll()`, verify gross debit = principal + fee, verify all three account codes. |
| Integration test: 3-entry group persisted in real DB | `LedgerServiceIT` currently tests collection via Testcontainers (real PostgreSQL). A disbursement integration test must verify 3 entries are written without constraint violation. | Medium | Depends on V25 migration being applied first. Mirrors `postEntry_insertsTwoRows_debitAndCredit()` but asserts 3 rows, correct codes, balanced amounts, shared `entryGroupId`. |
| `LedgerVerifier` updated for disbursement assertions | `LedgerVerifier.assertLedgerBalanced()` hardcodes `hasSize(2)` and `CUSTOMER_WALLET`/`PROVIDER_CLEARING`. E2E disbursement tests need a new overload. | Low | Add `assertDisbursementLedgerBalanced(String transactionId, BigDecimal principal, BigDecimal fee)` — checks 3 entries, correct account codes, correct amounts, shared `entryGroupId`, balance holds. Existing method unchanged (collection E2E). |

---

## The V23 Constraint Conflict — Resolution

**What V23 enforces:** `UNIQUE (entry_group_id, direction)` — at most one row per
direction per group. This was designed to catch the "two DEBITs and no CREDIT" coding
bug (LEDGER-01).

**Why it breaks disbursement:** A disbursement group has one DEBIT and two CREDITs
(`CUSTOMER_WALLET` + `PROVIDER_FEE`). The unique constraint on `(group, direction)`
treats both CREDITs as duplicates and rejects the second one at commit time.

**Root cause:** The constraint encodes the wrong invariant. The real invariant is
"sum of debits equals sum of credits" — not "cardinality of rows per direction."

**Options:**

| Option | Description | Verdict |
|--------|-------------|---------|
| A: Drop constraint, enforce balance in service layer + tests | Remove V23 constraint via V25 DDL. Balance is enforced by `LedgerService` (construction-time), `LedgerBalanceGuardTest` (PITest), and optionally a DB trigger. | **Recommended.** Cleanest solution. The constraint was the wrong abstraction. |
| B: Replace with a PostgreSQL deferrable trigger checking balance per group | Trigger on `AFTER INSERT` verifies `SUM(DEBIT) = SUM(CREDIT)` per `entry_group_id` at commit. | Valid but adds ops complexity invisible to JPA. Overkill for this volume. |
| C: Replace with two partial unique constraints | One on `(entry_group_id)` WHERE `direction = 'DEBIT'`, one WHERE `direction = 'CREDIT'`. | Still incorrect — still rejects two CREDITs. |

**Recommended approach (Option A):**

1. Flyway V25 drops `uq_ledger_entry_group_direction`.
2. V25 includes the same pre-flight guard pattern as V23: assert no unbalanced groups
   exist before dropping (confirms data integrity before constraint removal).
3. `LedgerService` already enforces balance by construction — the `switch` dispatch
   produces exactly the right entries or throws before any DB write.
4. `LedgerBalanceGuardTest` (PITest MUT-02 coverage) kills mutations that break the
   balance computation. This is stronger than the DB constraint because it runs in CI
   before the code ships, not at runtime.

**V25 pre-flight guard pattern (mirrors V23):**
```sql
DO $$
DECLARE bad_count INT;
BEGIN
    SELECT COUNT(*) INTO bad_count
    FROM (
        SELECT entry_group_id
        FROM main.ledger_entry
        GROUP BY entry_group_id
        HAVING ABS(SUM(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END)) > 0.01
    ) unbalanced;
    IF bad_count > 0 THEN
        RAISE EXCEPTION 'V25 pre-flight: % unbalanced entry groups found', bad_count;
    END IF;
END $$;

ALTER TABLE main.ledger_entry DROP CONSTRAINT IF EXISTS uq_ledger_entry_group_direction;
```

---

## Zero-Fee Disbursement Handling

**Scenario:** A cashout where the provider charges no fee (fee = 0 XAF).

**Recommendation:** Always emit all 3 entries including `PROVIDER_FEE` CREDIT with
amount = 0 XAF.

**Rationale:**
- Uniform shape: "disbursement = 3 rows" is always true; reporting queries need no
  conditional branching on entry count.
- `GROUP BY entry_group_id` aggregations produce predictable row counts per flow.
- Reconciliation against provider reports is simpler when cardinality is invariant.

**Constraint consequence:** V4 schema `CHECK (amount > 0)` rejects zero. Flyway V25
must relax this to `CHECK (amount >= 0)`. This is non-breaking — all existing rows
satisfy the new constraint.

| Case | DEBIT MERCHANT_WALLET | CREDIT CUSTOMER_WALLET | CREDIT PROVIDER_FEE | Balanced |
|------|-----------------------|------------------------|---------------------|----------|
| fee = 5 XAF | 505 | 500 | 5 | Yes |
| fee = 0 XAF | 500 | 500 | 0 | Yes |

---

## Differentiators

Features beyond the minimum that add value to operations and reconciliation.
Achievable within v9 without scope creep.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| `Transaction.flow` indexed column | `WHERE flow = 'DISBURSEMENT'` is O(1) on an indexed column vs. joining `ledger_entry` and inspecting account codes. Supports admin dashboard filtering by flow type. | Low | Flyway V25 `ADD COLUMN flow VARCHAR(20)`. Add index. `TransactionService.initiate()` receives flow context. Nullable for existing rows. |
| Account balance queries per account code | Running balance by account without a separate balance table. `SELECT account_code, SUM(CASE direction WHEN 'DEBIT' THEN -amount ELSE amount END) FROM ledger_entry WHERE tenant_id = ? GROUP BY account_code` | Low | No schema change. Document as a named query method in `LedgerEntryRepository` for use in reconciliation and admin views. |
| `assertGroupBalanced(String groupId)` in `LedgerVerifier` | Reusable balance assertion for any group — collection or disbursement. Makes balance verification a first-class E2E concern rather than per-flow assertions. | Low | Pure Java. Uses existing `findByEntryGroupId` repository method. |

---

## Anti-Features

Features to explicitly NOT build in v9.

| Anti-Feature | Why Avoid | What to Do Instead |
|--------------|-----------|-------------------|
| Mutable ledger entries | `@Immutable` is the ledger invariant. Any UPDATE on a ledger row breaks the audit trail. Corrections require compensating entries. | Never expose update endpoints on `LedgerEntry`. Reversals are a future milestone. |
| Separate running-balance table per account | Two writes per transaction with race conditions. No benefit at XAF-only, moderate-volume cashout scale. | Query balance from `ledger_entry` on demand. Add index on `(tenant_id, account_code)` if query latency becomes a concern. |
| Omitting `PROVIDER_FEE` entry for zero-fee disbursements | Conditional shape (2 vs 3 rows) breaks every query that groups by row count per flow. Silent divergence between providers with/without fees. | Always emit 3 entries. Relax the `amount > 0` check to `amount >= 0` in V25. |
| `flow` field on `LedgerEntry` (instead of `Transaction`) | Duplicating flow on every entry row (3 per disbursement) is redundant. Flow is a property of the transaction, not of an individual entry. | Store `flow` on `Transaction`. Queries join to `transaction` when flow filter is needed. |
| Retaining V23 constraint with a workaround | Composite PK tricks or artificial sub-group IDs add complexity without fixing the actual problem: the constraint expresses the wrong invariant. | Drop the constraint in V25 and rely on the service-layer balance invariant + unit tests. |
| Full Orange cashout adapter implementation in v9 | Orange cashout is explicitly deferred per PROJECT.md validated requirements. Wiring the ledger to a stub adapter creates dead code and masks missing integration. | Wire `LedgerPosting.disbursement()` call-site as a placeholder. Full cashout implementation is a future milestone. |

---

## Feature Dependencies

```
LedgerFlow enum
  → LedgerPosting record (uses LedgerFlow)
    → LedgerService rewrite (accepts LedgerPosting, routes by flow)
      → Collection call-site migration: WebhookTransitionService
      → Disbursement call-site: Orange cashout orchestration path (placeholder)

V25 Flyway migration (prerequisite — must run before any disbursement integration test)
  ├── Drop uq_ledger_entry_group_direction   → enables 3-entry groups
  ├── Relax CHECK (amount > 0) to >= 0       → enables zero-fee PROVIDER_FEE entries
  └── ADD COLUMN transaction.flow            → enables flow-based reconciliation queries

Unit tests (depend on LedgerService rewrite)
  ├── LedgerBalanceGuardTest disbursement case  → maintains PITest MUT-02 coverage
  └── LedgerPosting constructor validation tests

Integration tests (depend on V25 migration + LedgerService rewrite)
  └── LedgerServiceIT disbursement integration test

E2E helpers (depend on LedgerService rewrite)
  └── LedgerVerifier.assertDisbursementLedgerBalanced()
```

---

## Reporting and Reconciliation — Impact of `Transaction.flow` Column

Without the column, identifying disbursement transactions in SQL requires joining
`ledger_entry` and filtering on `account_code IN ('MERCHANT_WALLET', 'PROVIDER_FEE')`.
This leaks account codes into reporting queries and breaks silently if codes are renamed.

With the column, standard queries become straightforward:

**Volume by flow (daily reconciliation):**
```sql
SELECT flow, COUNT(*), SUM(amount)
FROM main.transaction
WHERE tx_status = 'SUCCESS'
  AND created_date >= :since
  AND tenant_id = :tenantId
GROUP BY flow;
```

**Balance discrepancy detection (disbursements only):**
```sql
SELECT t.transaction_id, t.amount, t.fee_amount,
       SUM(le.amount) FILTER (WHERE le.direction = 'DEBIT')  AS total_debited,
       SUM(le.amount) FILTER (WHERE le.direction = 'CREDIT') AS total_credited
FROM main.transaction t
JOIN main.ledger_entry le ON le.transaction_id = t.transaction_id
WHERE t.flow = 'DISBURSEMENT'
  AND t.tx_status = 'SUCCESS'
GROUP BY t.transaction_id, t.amount, t.fee_amount
HAVING SUM(le.amount) FILTER (WHERE le.direction = 'DEBIT')
    != SUM(le.amount) FILTER (WHERE le.direction = 'CREDIT');
```

**Per-account running balance (admin audit):**
```sql
SELECT le.account_code,
       SUM(CASE le.direction WHEN 'DEBIT' THEN -le.amount ELSE le.amount END) AS net_balance
FROM main.ledger_entry le
JOIN main.transaction t ON t.transaction_id = le.transaction_id
WHERE t.tx_status = 'SUCCESS'
  AND t.tenant_id = :tenantId
GROUP BY le.account_code;
```

---

## MVP Delivery Order

1. **Flyway V25** — drop constraint, relax amount check, add `transaction.flow` column.
   Must run first; integration tests cannot write disbursement groups without it.

2. **`LedgerFlow` + `LedgerPosting`** in `transaction/contract`.

3. **`LedgerService` rewrite** with switch-dispatched builders and private account codes.

4. **Collection call-site migration** — `WebhookTransitionService` updated to
   `LedgerPosting.collection()`. Delete the old four-arg method.

5. **Unit tests** — COLLECTION + DISBURSEMENT fee>0 + DISBURSEMENT fee=0 +
   constructor validation.

6. **`LedgerBalanceGuardTest` disbursement case** — maintains PITest MUT-02 kill rate.

7. **`LedgerServiceIT` disbursement integration test** — real DB verification.

8. **`LedgerVerifier.assertDisbursementLedgerBalanced()`** — E2E assertion helper.

Defer: Orange cashout full adapter implementation. Wire the ledger call-site
placeholder; actual cashout HTTP integration is a future milestone.

---

## Sources

- Codebase: `V4__ledger_schema.sql` — original schema with `CHECK (amount > 0)` and `NUMERIC(20,2)`
- Codebase: `V23__ledger_group_constraint.sql` — deferrable unique constraint design and rationale
- Codebase: `LedgerService.java` — current hard-coded collection implementation
- Codebase: `LedgerEntry.java` — `@Immutable`, column constraints, `@Tsid` PK
- Codebase: `LedgerEntryRepository.java` — existing query methods
- Codebase: `LedgerBalanceGuardTest.java` — PITest MUT-02 scope and assertion structure
- Codebase: `LedgerServiceIT.java` — integration test baseline and setup pattern
- Codebase: `LedgerVerifier.java` — hardcoded collection expectations to be extended
- Codebase: `Transaction.java` — `feeAmount` field present; `flow` column absent
- Codebase: `PaymentOrchestrator.java` — fee evaluated and persisted before provider dispatch
- Codebase: `WebhookTransitionService.java` — sole existing call-site for `LedgerService.postEntry()`
- Spec: `requirements/payam-ledger.md` — proposed entry shapes, open questions, migration steps
- Spec: `PROJECT.md` — LEDGER-01 history (V23), Orange cashout deferral, PITest thresholds

---

*Feature research for: Payam v9 — Ledger Disbursement Support*
*Researched: 2026-04-21*
