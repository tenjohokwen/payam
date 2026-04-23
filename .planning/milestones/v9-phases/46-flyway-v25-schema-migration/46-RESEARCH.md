# Phase 46: Flyway V25 Schema Migration - Research

**Researched:** 2026-04-21
**Domain:** PostgreSQL DDL migration (Flyway), PL/pgSQL triggers, Hibernate Envers AUD parity
**Confidence:** HIGH

---

## Summary

Phase 46 writes a single Flyway migration file: `V25__ledger_disbursement_schema.sql`. The migration has four distinct DDL concerns that must execute in dependency order within one file:

1. **Pre-flight guard** (DO $$ block) — assert no unbalanced entry groups before dropping the V23 constraint.
2. **Drop V23 unique constraint** and replace it with a PL/pgSQL trigger that checks `SUM(DEBIT) == SUM(CREDIT)` per `entry_group_id` at commit time (deferrable trigger via `CONSTRAINT TRIGGER … DEFERRABLE INITIALLY DEFERRED`).
3. **Relax ledger_entry amount CHECK** from `amount > 0` to `amount >= 0`.
4. **Add nullable `flow VARCHAR(20)` column** to `main.transaction` and `main.transaction_aud`.

The existing test `LedgerConstraintIT.unbalancedInsert_isRejectedByConstraint()` asserts the constraint name `uq_ledger_entry_group_direction` — it will fail once that constraint is gone. The test must be updated in the same phase commit to assert on trigger behavior instead.

**Primary recommendation:** Write V25 in five ordered steps: pre-flight DO, drop constraint, create trigger function + CONSTRAINT TRIGGER, alter CHECK, add flow columns. Update `LedgerConstraintIT` in the same commit.

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| SCHEMA-01 | Flyway V25 drops `uq_ledger_entry_group_direction` and replaces it with a deferrable balance-check trigger (`SUM(DEBIT) == SUM(CREDIT)` per entry_group_id at commit) | PL/pgSQL CONSTRAINT TRIGGER with DEFERRABLE INITIALLY DEFERRED is the standard PostgreSQL mechanism for deferred row-aggregate checks |
| SCHEMA-02 | Flyway V25 includes a pre-flight DO block that verifies no unbalanced entry groups exist before dropping the V23 constraint | Pattern established in V23 itself — DO $$ block with RAISE EXCEPTION; identical approach is safe here |
| SCHEMA-03 | Flyway V25 relaxes `CHECK (amount > 0)` to `CHECK (amount >= 0)` to allow zero-amount PROVIDER_FEE entries | `ALTER TABLE … DROP CONSTRAINT … ADD CONSTRAINT` pattern; no data migration needed (existing rows have amount > 0 so `>= 0` is backward-compatible) |
| SCHEMA-04 | Flyway V25 adds nullable `flow VARCHAR(20)` to `main.transaction` and `main.transaction_aud` | `ALTER TABLE … ADD COLUMN IF NOT EXISTS` — nullable column, no default, instant DDL; Envers AUD parity requires mirroring the column in `transaction_aud` |
</phase_requirements>

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Flyway Core | Managed by Spring Boot 3.5.11 BOM | Versioned SQL migration | Already in use (V1–V24); `flyway-database-postgresql` module also on classpath |
| PostgreSQL | 14.18 (Testcontainers image) | Target database | V23 already uses deferrable constraints; trigger DDL syntax confirmed in PG 14+ |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Testcontainers PostgreSQL | Managed by Spring Boot BOM | Real DB for integration tests | `LedgerConstraintIT` and `LedgerServiceIT` run against `postgres:14.18` via `TestConfig` |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| CONSTRAINT TRIGGER (deferrable) | Application-layer balance check | DB trigger is the requirement (SCHEMA-01); application check would not protect against direct JDBC inserts |
| `ALTER TABLE DROP CONSTRAINT … ADD CONSTRAINT` for amount CHECK | Leave old constraint, create new via `ADD CONSTRAINT CHECK` | Must drop old named constraint first; both work but drop+add is explicit |

**Installation:** No new dependencies. Migration file only.

---

## Architecture Patterns

### Recommended Migration File Structure

```
src/main/resources/db/migration/V25__ledger_disbursement_schema.sql
```

Single file containing all four concerns in order:
```sql
-- Step 1: Pre-flight — verify no unbalanced entry groups exist
DO $$ … RAISE EXCEPTION if unbalanced … END $$;

-- Step 2: Drop V23 unique constraint
ALTER TABLE main.ledger_entry DROP CONSTRAINT uq_ledger_entry_group_direction;

-- Step 3a: Create trigger function
CREATE OR REPLACE FUNCTION main.check_ledger_balance() RETURNS TRIGGER …

-- Step 3b: Bind as CONSTRAINT TRIGGER (deferrable)
CREATE CONSTRAINT TRIGGER trg_ledger_balance_check
    AFTER INSERT ON main.ledger_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION main.check_ledger_balance();

-- Step 4: Relax amount CHECK
ALTER TABLE main.ledger_entry DROP CONSTRAINT <old_check_name>;
ALTER TABLE main.ledger_entry ADD CONSTRAINT chk_ledger_amount_non_negative
    CHECK (amount >= 0);

-- Step 5: Add flow column to transaction and transaction_aud
ALTER TABLE main.transaction     ADD COLUMN IF NOT EXISTS flow VARCHAR(20);
ALTER TABLE main.transaction_aud ADD COLUMN IF NOT EXISTS flow VARCHAR(20);
```

### Pattern 1: Deferrable Balance-Check Trigger
**What:** PL/pgSQL CONSTRAINT TRIGGER fires AFTER INSERT (and AFTER UPDATE/DELETE for completeness), performs `SUM(DEBIT) == SUM(CREDIT)` aggregate check. With `DEFERRABLE INITIALLY DEFERRED`, PostgreSQL defers the per-row trigger check to commit time.
**When to use:** When multiple rows in the same entry group are inserted in one transaction (LedgerService.saveAll pattern) and individual inserts must be permitted before the group is complete.
**Example:**
```sql
-- Source: PostgreSQL 14 docs — CREATE TRIGGER / constraint triggers
CREATE OR REPLACE FUNCTION main.check_ledger_balance()
    RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    debit_sum  NUMERIC;
    credit_sum NUMERIC;
BEGIN
    SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'),  0),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
    INTO debit_sum, credit_sum
    FROM main.ledger_entry
    WHERE entry_group_id = NEW.entry_group_id;

    IF debit_sum <> credit_sum THEN
        RAISE EXCEPTION
            'Ledger balance violation: entry_group_id=% has DEBIT sum=% != CREDIT sum=%',
            NEW.entry_group_id, debit_sum, credit_sum;
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_ledger_balance_check
    AFTER INSERT OR UPDATE OR DELETE ON main.ledger_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION main.check_ledger_balance();
```

**Key nuance for DELETE/UPDATE:** The trigger fires on every row change. For DELETE, `NEW` is NULL — use `COALESCE(NEW.entry_group_id, OLD.entry_group_id)` to get the group id. Alternatively, scope to INSERT only if deletes are not expected on `ledger_entry` (they are not — `@Immutable` entity).

### Pattern 2: Pre-flight DO Block (from V23)
**What:** Assert invariants before destructive DDL using `DO $$ … END $$` with `RAISE EXCEPTION`. Established in V23.
**When to use:** Before any constraint drop that would silently succeed even on bad data.

```sql
DO $$
DECLARE bad_count INT;
BEGIN
    SELECT COUNT(*) INTO bad_count
    FROM (
        SELECT entry_group_id
        FROM main.ledger_entry
        GROUP BY entry_group_id
        HAVING COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'),  0) <>
               COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
    ) unbalanced;
    IF bad_count > 0 THEN
        RAISE EXCEPTION
            'V25 pre-flight: % unbalanced entry_group_id(s) found — cannot proceed', bad_count;
    END IF;
END $$;
```

### Pattern 3: Nullable Column Addition (from V24)
**What:** `ALTER TABLE … ADD COLUMN IF NOT EXISTS col VARCHAR(20)` with no default.
**Why it works:** Nullable column addition is metadata-only in PostgreSQL 14 — no table rewrite, no row lock beyond brief schema lock.
**Envers AUD parity rule:** The project established in V24 that when a new column is added to an `@Audited` entity, the matching `_aud` table must receive the same column in the same migration. `Transaction` is `@Audited` (confirmed in source). `transaction_aud` has no explicit DDL in any existing migration — V25 will need to create that table AND add the `flow` column.

### Anti-Patterns to Avoid
- **Omitting `transaction_aud` table creation:** `transaction_aud` has never been explicitly DDL-created (V20 created `tenant_aud`, `tenant_api_key_aud`; V24 created `platform_config_aud`). If Envers is writing to `transaction_aud` today via some other mechanism (ddl-auto fallback), V25 still needs to guarantee it has `flow`. Safe approach: `CREATE TABLE IF NOT EXISTS main.transaction_aud (…)` with all current `Transaction` fields plus `flow`, following the V20/V24 column list pattern — then `ADD COLUMN IF NOT EXISTS flow VARCHAR(20)` handles the case where the table already exists.
- **Using `BEFORE` trigger instead of `AFTER` with deferral:** Balance check requires aggregate over committed group — `BEFORE` fires before the row is visible to other statements, making the aggregate incomplete. Must use `AFTER` with `DEFERRABLE INITIALLY DEFERRED`.
- **Using statement-level trigger:** Statement-level triggers cannot access `NEW`/`OLD` row data. Must be `FOR EACH ROW`.
- **Forgetting to drop old CHECK constraint by name:** PostgreSQL requires DROP CONSTRAINT by name. The `CHECK (amount > 0)` in V4 has no explicit constraint name — it was created inline and PostgreSQL auto-generated a name (typically `ledger_entry_amount_check`). Must use `\d main.ledger_entry` (or `pg_constraint`) to discover the actual name. The migration should use `DROP CONSTRAINT IF EXISTS ledger_entry_amount_check` OR discover the name dynamically via a DO block.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Deferred aggregate balance check | Application-layer assertion in `@Transactional` method | PostgreSQL CONSTRAINT TRIGGER DEFERRABLE INITIALLY DEFERRED | DB trigger protects against direct JDBC inserts, Flyway repair scripts, and test data setup that bypasses the service layer |
| Discovering the auto-named CHECK constraint | Hardcode `ledger_entry_amount_check` | Use `DO $$ SELECT conname … FROM pg_constraint … $$` + dynamic DDL OR `ADD CONSTRAINT IF NOT EXISTS` pattern | Auto-generated names can vary by PostgreSQL version; dynamic discovery or `IF NOT EXISTS` is safer |

---

## Runtime State Inventory

> Rename/refactor scope does not apply here. This is a schema addition phase. Included for completeness.

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | `main.ledger_entry` rows with `amount > 0` exist in dev/prod | None — `CHECK (amount >= 0)` is backward-compatible; existing rows satisfy `>= 0` |
| Stored data | `uq_ledger_entry_group_direction` constraint on `main.ledger_entry` | Drop in V25 Step 2 |
| Live service config | None — no external service config references ledger constraint names | None |
| OS-registered state | None | None |
| Secrets/env vars | None | None |
| Build artifacts | None — migration is SQL-only; no compiled artifacts | None |

---

## Common Pitfalls

### Pitfall 1: CHECK Constraint Auto-Generated Name
**What goes wrong:** `ALTER TABLE main.ledger_entry DROP CONSTRAINT ledger_entry_amount_check` fails because PostgreSQL generated a different internal name for the inline CHECK in V4.
**Why it happens:** V4 used `CHECK (amount > 0)` inline without `CONSTRAINT <name>` — PostgreSQL auto-names it, typically `<table>_<column>_check` but this is not guaranteed.
**How to avoid:** Either use a DO block to find and drop by `pg_constraint.conname`, or use `ALTER TABLE main.ledger_entry DROP CONSTRAINT IF EXISTS ledger_entry_amount_check` followed by unconditional `ADD CONSTRAINT`. If the auto-name is wrong, the IF EXISTS silently passes and the old `> 0` check remains — which would cause TEST SCHEMA-03 to fail. The safest approach: discover the actual name via `pg_constraint` in the migration itself.
**Warning signs:** Migration completes but `INSERT` with `amount = 0` still fails — the old constraint was not dropped.

### Pitfall 2: `transaction_aud` Table Missing Flow Column
**What goes wrong:** Hibernate Envers tries to write an audit revision when `Transaction.flow` is set, but `main.transaction_aud` has no `flow` column → SQL error at runtime.
**Why it happens:** `transaction_aud` was never explicitly created in any migration (V20 only created `tenant_aud` and `tenant_api_key_aud`). If Envers created it via a previous `ddl-auto: update` run (possible in early dev), it exists without `flow`. If it was never created, V25 must create it.
**How to avoid:** V25 should `CREATE TABLE IF NOT EXISTS main.transaction_aud` with all columns matching the current `Transaction` entity, then `ADD COLUMN IF NOT EXISTS flow VARCHAR(20)`. The `IF NOT EXISTS` on both CREATE and ADD COLUMN makes this idempotent.
**Warning signs:** `LedgerServiceIT` or Envers-touching tests throw `column "flow" of relation "transaction_aud" does not exist` after migration.

### Pitfall 3: Trigger Fires on DELETE — `NEW` is NULL
**What goes wrong:** If the trigger is declared for INSERT OR UPDATE OR DELETE, `NEW` is NULL on DELETE. `NEW.entry_group_id` raises a null pointer in PL/pgSQL.
**Why it happens:** Standard PL/pgSQL trigger variable behavior — `NEW` undefined for DELETE triggers.
**How to avoid:** Since `ledger_entry` is `@Immutable` (append-only), scope the trigger to `AFTER INSERT` only. There is no legitimate DELETE or UPDATE path in production code. This simplifies the trigger function and eliminates the NULL guard.
**Warning signs:** Test that attempts a direct `DELETE FROM ledger_entry` triggers a PL/pgSQL error rather than the expected row deletion.

### Pitfall 4: LedgerConstraintIT Breaks After V25
**What goes wrong:** `LedgerConstraintIT.unbalancedInsert_isRejectedByConstraint()` asserts the exception message contains `uq_ledger_entry_group_direction` — that string will not appear in a trigger-raised exception.
**Why it happens:** The test was written against the V23 unique constraint name. V25 replaces the mechanism.
**How to avoid:** Update `LedgerConstraintIT` in the same V25 commit. The new assertion should verify the trigger exception message (e.g., `"Ledger balance violation"`) instead of the constraint name. The behavioral invariant (unbalanced insert is rejected at commit) is unchanged — only the detection string changes.
**Warning signs:** `mvn verify` fails on `LedgerConstraintIT` after migration with `AssertionError: Constraint violation chain must reference uq_ledger_entry_group_direction`.

### Pitfall 5: Trigger Does Not Fire at Commit for Single DEBIT Insert
**What goes wrong:** A single DEBIT insert without a matching CREDIT is not rejected — success criterion #2 requires it be caught at commit.
**Why it happens:** The `DEFERRABLE INITIALLY DEFERRED` trigger fires at commit time. The trigger function queries the aggregate for the `entry_group_id` at that point. If only one DEBIT exists, `DEBIT sum != CREDIT sum (0)` → exception is raised. This is the expected behavior — but only if the trigger function correctly handles the zero-credit case (COALESCE to 0 for missing direction).
**How to avoid:** Ensure the trigger function uses `COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)` so that a group with no CREDIT entries returns 0, not NULL. NULL != any number in PostgreSQL, so without COALESCE the comparison would silently pass.
**Warning signs:** Single-DEBIT insert commits without error when it should fail.

---

## Code Examples

### Complete V25 Migration Skeleton

```sql
-- V25: Ledger disbursement schema support
-- (1) Pre-flight balance check
-- (2) Replace uq_ledger_entry_group_direction with balance trigger
-- (3) Relax amount CHECK to allow zero-fee entries
-- (4) Add nullable flow column to transaction + transaction_aud

-- ============================================================
-- Step 1: Pre-flight — no unbalanced entry groups allowed
-- ============================================================
DO $$
DECLARE bad_count INT;
BEGIN
    SELECT COUNT(*) INTO bad_count
    FROM (
        SELECT entry_group_id
        FROM main.ledger_entry
        GROUP BY entry_group_id
        HAVING COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'),  0) <>
               COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
    ) unbalanced;
    IF bad_count > 0 THEN
        RAISE EXCEPTION 'V25 pre-flight: % unbalanced entry_group_id(s) in main.ledger_entry — fix before migration', bad_count;
    END IF;
END $$;

-- ============================================================
-- Step 2: Drop V23 unique constraint
-- ============================================================
ALTER TABLE main.ledger_entry
    DROP CONSTRAINT IF EXISTS uq_ledger_entry_group_direction;

-- ============================================================
-- Step 3: Replace with deferrable balance-check trigger
-- ============================================================
CREATE OR REPLACE FUNCTION main.check_ledger_balance()
    RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    debit_sum  NUMERIC;
    credit_sum NUMERIC;
BEGIN
    SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'),  0),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
    INTO debit_sum, credit_sum
    FROM main.ledger_entry
    WHERE entry_group_id = NEW.entry_group_id;

    IF debit_sum <> credit_sum THEN
        RAISE EXCEPTION
            'Ledger balance violation: entry_group_id=% DEBIT sum=% != CREDIT sum=%',
            NEW.entry_group_id, debit_sum, credit_sum;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_ledger_balance_check
    AFTER INSERT ON main.ledger_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION main.check_ledger_balance();

-- ============================================================
-- Step 4: Relax amount CHECK (amount > 0) → (amount >= 0)
-- Discover and drop the auto-named constraint from V4.
-- ============================================================
DO $$
DECLARE v_conname TEXT;
BEGIN
    SELECT conname INTO v_conname
    FROM pg_constraint
    WHERE conrelid = 'main.ledger_entry'::regclass
      AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%amount > 0%';
    IF v_conname IS NOT NULL THEN
        EXECUTE 'ALTER TABLE main.ledger_entry DROP CONSTRAINT ' || quote_ident(v_conname);
    END IF;
END $$;

ALTER TABLE main.ledger_entry
    ADD CONSTRAINT chk_ledger_amount_non_negative CHECK (amount >= 0);

-- ============================================================
-- Step 5: Add flow column to transaction and transaction_aud
-- ============================================================
ALTER TABLE main.transaction
    ADD COLUMN IF NOT EXISTS flow VARCHAR(20);

-- Envers AUD parity — create table if it does not yet exist,
-- then ensure flow column is present.
CREATE TABLE IF NOT EXISTS main.transaction_aud (
    id                  BIGINT      NOT NULL,
    rev                 INTEGER     NOT NULL REFERENCES main.revinfo(rev),
    revtype             SMALLINT,
    transaction_id      VARCHAR(36),
    trace_id            VARCHAR(255),
    external_reference  VARCHAR(255),
    tenant_id           BIGINT,
    tx_status           VARCHAR(20),
    status              VARCHAR(20),
    provider            VARCHAR(20),
    amount              NUMERIC(20, 2),
    currency            CHAR(3),
    provider_ref        VARCHAR(255),
    mtn_financial_tx_id VARCHAR(255),
    pay_token           VARCHAR(255),
    pay_token_issued_at TIMESTAMP,
    poll_attempts       INTEGER,
    flow                VARCHAR(20),
    created_by          VARCHAR(50),
    created_date        TIMESTAMP,
    last_modified_by    VARCHAR(50),
    last_modified_date  TIMESTAMP,
    request_id          VARCHAR(255),
    session_id          TEXT,
    PRIMARY KEY (id, rev)
);

ALTER TABLE main.transaction_aud
    ADD COLUMN IF NOT EXISTS flow VARCHAR(20);
```

**Note on `transaction_aud`:** The `ADD COLUMN IF NOT EXISTS` at the end handles the case where the table was just created (column already in CREATE) — the statement is a no-op. If the table already existed without `flow`, the ADD COLUMN runs. The `CREATE TABLE IF NOT EXISTS` followed by `ADD COLUMN IF NOT EXISTS` is the idiomatic pattern established in V24 for retroactive Envers table creation (`platform_config_aud`).

**Note on `@NotAudited` columns:** `Transaction.riskScore`, `deviceFingerprint`, `feeAmount`, `feeRuleId` are annotated `@NotAudited` — they do not need to be in `transaction_aud`. The `flow` field will be `@Audited` (no `@NotAudited` on it per SCHEMA-04 and SERVICE-06 requirements). Omit `risk_score`, `device_fingerprint`, `fee_amount`, `fee_rule_id` from the `_aud` table.

### Updated LedgerConstraintIT Assertion (after V25)

The test `unbalancedInsert_isRejectedByConstraint` must replace its constraint-name assertion:

```java
// Before (V23 era):
assertThat(chain.toString())
    .contains("uq_ledger_entry_group_direction");

// After (V25 era — trigger message):
assertThat(chain.toString())
    .containsAnyOf("Ledger balance violation", "trg_ledger_balance_check");
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `UNIQUE (entry_group_id, direction) DEFERRABLE INITIALLY DEFERRED` (V23) | PL/pgSQL CONSTRAINT TRIGGER (V25) | V25 | Allows 3-row groups (DEBIT + 2 CREDITs) — the old unique constraint allowed at most one DEBIT and one CREDIT per group, which is incompatible with the disbursement flow's 3-entry pattern |
| `CHECK (amount > 0)` (V4) | `CHECK (amount >= 0)` (V25) | V25 | Allows zero-amount PROVIDER_FEE entries in zero-fee disbursements |

**Why the V23 unique constraint must go:** The disbursement flow requires 3 entries per group: DEBIT `MERCHANT_WALLET` + CREDIT `CUSTOMER_WALLET` + CREDIT `PROVIDER_FEE`. The V23 constraint `UNIQUE(entry_group_id, direction)` would reject the second CREDIT insert (duplicate `(group_id, 'CREDIT')`). The trigger approach has no such restriction — it only checks the aggregate sum, not the count per direction.

---

## Environment Availability

> Step 2.6: This phase is SQL-only with no new external tooling dependencies. Flyway, PostgreSQL, and Testcontainers are already in use.

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| PostgreSQL | Migration target | ✓ | 14.18 (Testcontainers) | — |
| Flyway Core + flyway-database-postgresql | Migration execution | ✓ | Spring Boot 3.5.11 BOM managed | — |
| Maven (`mvn verify`) | Integration test gate | ✓ | Present (existing project) | — |

---

## Validation Architecture

> `workflow.nyquist_validation` not explicitly set to false in config.json — section included.

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test + Testcontainers (`postgres:14.18`) |
| Config file | None — Spring Boot auto-configuration |
| Quick run command | `mvn test -pl . -Dtest=LedgerConstraintIT,LedgerServiceIT -DfailIfNoTests=false` |
| Full suite command | `mvn verify` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| SCHEMA-01 | Drop V23 constraint; trigger rejects single-DEBIT group at commit | integration | `mvn test -Dtest=LedgerConstraintIT` | ✅ (needs update) |
| SCHEMA-01 | Balanced 3-entry group (DEBIT + 2 CREDITs) commits without violation | integration | `mvn test -Dtest=LedgerConstraintIT` | ❌ new test method needed |
| SCHEMA-02 | Pre-flight DO block: unbalanced rows → migration fails with diagnostic message | manual-only (can't simulate mid-migration state in unit test) | — | — |
| SCHEMA-03 | `amount = 0` insert succeeds without CHECK violation | integration | `mvn test -Dtest=LedgerConstraintIT` | ❌ new test method needed |
| SCHEMA-04 | `transaction.flow` column exists and is nullable; existing rows have `flow = NULL` | integration | `mvn test -Dtest=LedgerServiceIT` | ❌ new test method needed |

### Sampling Rate
- **Per task commit:** `mvn test -Dtest=LedgerConstraintIT,LedgerServiceIT`
- **Per wave merge:** `mvn verify`
- **Phase gate:** Full `mvn verify` green before phase transition

### Wave 0 Gaps
- [ ] `LedgerConstraintIT` — update `unbalancedInsert_isRejectedByConstraint` to assert trigger message (not constraint name)
- [ ] `LedgerConstraintIT` — add `threeEntryDisbursementGroup_succeeds` test: insert DEBIT + CREDIT + CREDIT for same group → commits without error
- [ ] `LedgerConstraintIT` — add `zeroAmountEntry_succeeds` test: insert row with `amount = 0.00` → no CHECK violation
- [ ] `LedgerServiceIT` — add `flowColumn_isNullableAndExists` test: query `information_schema.columns` for `flow` in `main.transaction` and `main.transaction_aud`; verify existing transaction rows have `flow IS NULL`

---

## Open Questions

1. **Does `transaction_aud` currently exist in production/dev databases?**
   - What we know: V20 did not create it. `hibernate.ddl-auto=none`. No explicit DDL in any migration.
   - What's unclear: Whether Envers has been silently failing to audit `Transaction` rows, or whether an earlier dev run with `ddl-auto: update` created the table.
   - Recommendation: The `CREATE TABLE IF NOT EXISTS` + `ADD COLUMN IF NOT EXISTS` pattern in V25 is safe either way. Verify by running `\dt main.*aud` against the dev database before the migration to document what exists.

2. **Exact auto-generated name of the V4 CHECK constraint**
   - What we know: V4 inline `CHECK (amount > 0)` without explicit name. PostgreSQL typically names it `ledger_entry_amount_check`.
   - What's unclear: Whether the actual name matches the convention on PostgreSQL 14.
   - Recommendation: Use the dynamic DO block approach (query `pg_constraint`) to find and drop by actual name, rather than hardcoding. This is already documented in the Code Examples section.

---

## Sources

### Primary (HIGH confidence)
- Codebase direct inspection: `V4__ledger_schema.sql`, `V23__ledger_group_constraint.sql`, `V24__platform_config_pin.sql`, `V20__envers_audit_tables.sql` — confirmed migration history and DDL patterns
- Codebase direct inspection: `LedgerEntry.java`, `Transaction.java`, `LedgerService.java` — confirmed entity structure, `@Audited` annotations, `@Immutable` on `LedgerEntry`
- Codebase direct inspection: `LedgerConstraintIT.java`, `LedgerServiceIT.java`, `LedgerBalanceGuardTest.java`, `LedgerVerifier.java` — confirmed existing test assertions and what must change
- Codebase direct inspection: `TestConfig.java`, `createSchema.sql` — confirmed `postgres:14.18` Testcontainers image
- Codebase direct inspection: `pom.xml` — Spring Boot 3.5.11 BOM, `flyway-core` + `flyway-database-postgresql` managed dependencies
- Codebase direct inspection: `application.yaml` — `hibernate.ddl-auto: none`, `flyway.defaultSchema: main`

### Secondary (MEDIUM confidence)
- PostgreSQL 14 documentation pattern for `CREATE CONSTRAINT TRIGGER … DEFERRABLE INITIALLY DEFERRED FOR EACH ROW` — standard feature, available since PostgreSQL 9.x, confirmed available in PG 14 used by Testcontainers

### Tertiary (LOW confidence)
- None — all findings are directly grounded in codebase inspection and stable PostgreSQL DDL semantics

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — no new dependencies; migration uses established Flyway + PostgreSQL patterns already in the codebase
- Architecture: HIGH — migration structure follows V23/V24 patterns exactly; trigger DDL is standard PostgreSQL
- Pitfalls: HIGH — pitfalls identified from direct codebase analysis (V4 anonymous CHECK, missing `transaction_aud`, `LedgerConstraintIT` assertion, COALESCE null trap)

**Research date:** 2026-04-21
**Valid until:** 2026-05-21 (stable domain — PostgreSQL DDL semantics, Flyway file conventions, existing test patterns are all stable)
