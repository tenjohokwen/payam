# Technology Stack — v9 Ledger Disbursement Support

**Project:** Payam — unified multi-tenant mobile money API for Cameroon
**Researched:** 2026-04-21
**Scope:** Additions and changes needed for disbursement/cashout ledger flows only. Existing stack is not re-evaluated.
**Overall confidence:** HIGH — all findings verified directly against codebase.

---

## Summary Verdict

**No new library dependencies are required.** All capability needed for v9 — BigDecimal arithmetic, JPA persistence, enum columns, Flyway migrations — is already present in the stack.

The only deliverables are:
1. Two new Java types in `transaction/contract` (`LedgerFlow` enum, `LedgerPosting` record)
2. A rewritten `LedgerService` (pure logic change, no new imports beyond the new contract types)
3. One Flyway migration (V25) covering two DDL statements
4. One call-site update in `WebhookTransitionService`
5. New wiring in the Orange cashout path

The single most important finding is a **schema conflict** between the existing V23 constraint and the disbursement entry shape that must be resolved in V25.

---

## Critical Schema Conflict: V23 Constraint vs Disbursement

### What the constraint does

V23 (`V23__ledger_group_constraint.sql`) added:

```sql
ALTER TABLE main.ledger_entry
    ADD CONSTRAINT uq_ledger_entry_group_direction
    UNIQUE (entry_group_id, direction)
    DEFERRABLE INITIALLY DEFERRED;
```

This limits each entry group to exactly one DEBIT and one CREDIT row. It was correct for the existing two-entry collection pattern.

### Why it conflicts

A disbursement group per the spec has three entries:
- `DEBIT  MERCHANT_WALLET  gross` (principal + fee)
- `CREDIT CUSTOMER_WALLET  principal`
- `CREDIT PROVIDER_FEE     fee`

Two rows share `direction = 'CREDIT'` within the same `entry_group_id`. PostgreSQL evaluates the DEFERRABLE constraint at transaction commit. Even though it is deferred, it still enforces the uniqueness predicate — it just does so at commit rather than per-insert. Two rows with `(same entry_group_id, 'CREDIT')` will violate the constraint and the transaction will roll back.

### Resolution

Drop the constraint in V25. The double-entry balance invariant is enforced at the application layer by `LedgerService` (debits = credits per group) and is covered by unit tests. The V23 constraint was belt-and-suspenders; dropping it does not weaken the domain invariant.

A deferred trigger alternative (summing amounts per group at commit) would provide equivalent DB-level enforcement but adds operational complexity and is not standard in this codebase. Do not add a trigger.

**Confidence:** HIGH — constraint text read directly from V23 migration; disbursement entry shape read directly from `requirements/payam-ledger.md`.

---

## V25 Migration: Complete DDL

Two statements. Both are safe on production PostgreSQL 14+.

```sql
-- V25: Ledger disbursement support
--
-- (1) Drop the V23 unique constraint that prevents multi-credit entry groups.
--     Disbursement requires two CREDIT rows per group (CUSTOMER_WALLET + PROVIDER_FEE).
--     Balance invariant (debits = credits per group) is enforced at application layer.
ALTER TABLE main.ledger_entry
    DROP CONSTRAINT IF EXISTS uq_ledger_entry_group_direction;

-- (2) Add flow column to transaction table.
--     All existing rows default to COLLECTION — correct, no disbursements existed before v9.
--     PostgreSQL 11+: ADD COLUMN with a constant DEFAULT is a metadata-only operation.
--     No table rewrite, no full scan, ACCESS EXCLUSIVE held only for schema catalog update.
ALTER TABLE main.transaction
    ADD COLUMN IF NOT EXISTS flow VARCHAR(20) NOT NULL DEFAULT 'COLLECTION';
```

### `ADD COLUMN` lock behaviour

`ADD COLUMN ... DEFAULT 'literal'` (constant, not a function call) acquires `ACCESS EXCLUSIVE` briefly for the catalog update, then releases. No row-level lock, no table rewrite. Safe on any table size with PostgreSQL 11+. Confirmed by PostgreSQL official documentation: https://www.postgresql.org/docs/current/ddl-alter.html

This is **not** the same as `DEFAULT gen_random_uuid()` (which triggers a full rewrite). `'COLLECTION'` is a constant — metadata-only path.

### `IF EXISTS` / `IF NOT EXISTS` guards

Both `DROP CONSTRAINT IF EXISTS` and `ADD COLUMN IF NOT EXISTS` make the migration idempotent. Safe to re-run in CI/CD environments. The pre-flight DO block pattern from V23 is not required here — a missing constraint is not a data integrity issue, and `IF NOT EXISTS` on the ADD COLUMN handles re-runs cleanly.

### Why `VARCHAR(20)` not a `CHECK` constraint

A CHECK constraint coupling the column to the current enum values (`CHECK (flow IN ('COLLECTION', 'DISBURSEMENT'))`) requires a schema migration every time a flow type is added. The `@Enumerated(EnumType.STRING)` on the entity enforces valid values at write time. `VARCHAR(20)` is the established pattern in this project (see `tx_status VARCHAR(20)` in V3, `direction VARCHAR(6)` in V4).

### No index on `flow` in V25

`flow` is a low-cardinality column (two values). A partial or full index on it adds write overhead for negligible read gain at current transaction volume. Add a partial index in a future migration if reconciliation queries filter heavily by flow.

### Flyway transactional lock mode

The project uses default Flyway settings (`flyway.postgresql.transactional.lock=true`). Both V25 statements run safely inside a transaction. Neither requires `CREATE INDEX CONCURRENTLY` or any other statement that mandates `flyway.postgresql.transactional.lock=false`.

---

## New Java Types Required

### `LedgerFlow` enum

**Package:** `com.softropic.payam.transaction.contract`
**No new dependencies** — plain Java enum, same pattern as `LedgerDirection` already in that package.

```java
public enum LedgerFlow {
    COLLECTION,
    DISBURSEMENT
}
```

### `LedgerPosting` record

**Package:** `com.softropic.payam.transaction.contract`
**No new dependencies** — Java 16+ record, uses `BigDecimal` and `Objects` (already present everywhere).

The compact constructor validates: positive principal, non-negative fee, non-null flow and currency. Factory methods `collection()` and `disbursement()` are the public API; callers never construct the record directly.

### `flow` field on `Transaction` entity

Add `@Enumerated(EnumType.STRING) LedgerFlow flow` to `Transaction.java`.

Mark `@NotAudited` — the established pattern for columns added after V14 that are not in the Envers `transaction_aud` table (see `feeAmount` and `feeRuleId` with identical `@NotAudited` annotations). V25 adds the column to `main.transaction` only; the Envers AUD table does not change unless audit of the `flow` field is explicitly required (it is not in v9 spec).

If a `setFlow()` setter is needed for the Orange cashout path: the `Transaction` entity already uses explicit setters for mutable fields (e.g. `setProviderRef`, `setFeeAmount`). Follow the same pattern.

---

## Libraries Considered and Rejected

### JSR-354 / Moneta (`org.javamoney:moneta:1.4.2`)

**Verdict: Do not add.**

XAF (Central African CFA franc) has no decimal subunit — amounts are whole numbers. The existing schema uses `NUMERIC(20, 2)` and `BigDecimal` uniformly across 45 phases and every entity, DTO, and provider response parser. The disbursement calculation is two lines: `principal.add(fee)` for gross. Introducing `MonetaryAmount` at this boundary would require conversion wrappers at the entity layer, every DTO, and every provider response parser — high churn for zero domain benefit.

**Confidence:** HIGH

### Joda-Money (`org.joda:joda-money`)

**Verdict: Do not add.** Same reasoning as JSR-354. Additionally, the `java.money` standard largely subsumes Joda-Money; recommending Joda-Money in 2026 would be a regression.

### Double-entry accounting libraries (e.g. `pgledger`, `ledger4j`, open-source accounting frameworks)

**Verdict: Do not add.** The `requirements/payam-ledger.md` spec is a complete, self-contained implementation with two entry builders under 50 lines. No library adds value when the domain model is fully specified. Introducing a library would add an external dependency lifecycle cost without solving any unsolved problem.

### Hibernate Envers (already present)

No changes to Envers configuration needed. The `Transaction` entity is already `@Audited`; the new `flow` field uses `@NotAudited` (same pattern as `feeAmount`).

---

## Integration Points with Existing Stack

| Existing Component | v9 Integration | Notes |
|--------------------|---------------|-------|
| `LedgerService.postEntry(txId, tenantId, amount, currency)` | Replace signature with `postEntry(txId, tenantId, LedgerPosting)` | Single active caller: `WebhookTransitionService` line 97 |
| `WebhookTransitionService` | Update collection call-site to `LedgerPosting.collection(tx.getAmount(), tx.getCurrency())` | Line 97 — semantically identical, no behaviour change |
| `Transaction` entity | Add `LedgerFlow flow` field with `@NotAudited` | Matches `feeAmount` / `feeRuleId` pattern |
| `V23__ledger_group_constraint.sql` | V25 must drop `uq_ledger_entry_group_direction` | See critical finding above — disbursement writes 2 CREDITs per group |
| `LedgerEntryRepository` | No changes — `findByTransactionId` and `findByEntryGroupId` work with 3-row groups | Spring Data derives queries from field names; 3-row group is just a larger result set |
| `FeeEvaluationService` | No changes — `transaction.getFeeAmount()` already on `Transaction` entity | Fee flows into `LedgerPosting.disbursement(principal, fee, currency)` |
| Orange cashout path | Wire `LedgerPosting.disbursement()` when cashout reaches SUCCESS state | Parallel to collection wiring in `WebhookTransitionService` |
| `V24__platform_config_pin.sql` | V25 is next — no gap in migration numbering | V24 is confirmed latest shipped migration |

---

## What Does Not Change

- `pom.xml` — no dependency additions
- `LedgerEntry` entity — immutable, append-only; a disbursement group returns 3 rows instead of 2, which is not a schema change
- `LedgerEntryRepository` — no new queries needed for v9
- `FeeEvaluationService` — fee computation unchanged
- `TransactionStatus` state machine — unaffected
- Flyway version numbering — V25 is next (V24 is the latest shipped migration)

---

## Sources

- V23 migration (constraint text): `src/main/resources/db/migration/V23__ledger_group_constraint.sql` — read directly
- `LedgerEntry` entity: `src/main/java/com/softropic/payam/transaction/repo/LedgerEntry.java` — read directly
- `LedgerService` current implementation: `src/main/java/com/softropic/payam/transaction/service/LedgerService.java` — read directly
- `Transaction` entity (NotAudited pattern): `src/main/java/com/softropic/payam/transaction/repo/Transaction.java` — read directly
- `WebhookTransitionService` call-site (line 97): `src/main/java/com/softropic/payam/webhook/service/WebhookTransitionService.java` — read directly
- V3 transaction schema (VARCHAR(20) convention): `src/main/resources/db/migration/V3__transaction_schema.sql` — read directly
- Disbursement entry shape: `requirements/payam-ledger.md` — read directly
- PostgreSQL ADD COLUMN with constant DEFAULT (metadata-only, no table rewrite, PostgreSQL 11+): https://www.postgresql.org/docs/current/ddl-alter.html
