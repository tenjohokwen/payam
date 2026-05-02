# Phase 54: V31 Schema Migration - Research

**Researched:** 2026-05-02
**Domain:** Flyway PostgreSQL DDL, Spring Data JPA entity refactor, Java enum state machine extension
**Confidence:** HIGH

---

## Summary

Phase 54 is a pure migration + application-layer retirement phase. No new business logic is introduced. The three deliverables are: (1) a single Flyway V31 SQL file that creates the `disbursement_transaction_ref` table, alters the `disbursement` table, and includes a pre-flight guard; (2) a `PENDING_ADMIN_APPROVAL` entry added to the `DisbursementStatus` enum with safe state-machine wiring; and (3) removal of all `WalletBalanceService` call sites from `DisbursementOrchestrator` and `DisbursementCallbackTransitionService` (entities and repository left in place).

The codebase has a well-established Flyway migration pattern: prior migrations (V23, V25) use the exact `DO $$ ... RAISE EXCEPTION` pre-flight technique required here. The `DisbursementStatus` enum already has six values and a `transitionTo` guard; extending it with one new non-terminal value requires updating the enum body and the existing `DisbursementStatusTest`. The `reserved_amount` column on `main.disbursement` is used by three call sites (`DisbursementService.create`, `DisbursementOrchestrator.confirm`, `DisbursementResource.toListItem`); all three must be updated before or in the same plan that drops the column, because Hibernate will fail on startup if the entity maps a non-existent column.

**Primary recommendation:** Implement Phase 54 in two plans — Plan A (Flyway V31 migration only) and Plan B (enum extension + application-layer wallet retirement + test updates) — so the DDL is independently testable and the Java changes are cohesive.

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| SCHEMA-01 | V31 migration creates `disbursement_transaction_ref` with partial unique index on `(transaction_id) WHERE ref_status IN ('PENDING', 'CLAIMED')` | Section "Partial Unique Indexes in PostgreSQL" + V28 schema pattern |
| SCHEMA-02 | V31 migration adds `admin_note` (TEXT nullable) and `retry_count` (INT NOT NULL DEFAULT 0) to `disbursement`, removes `reserved_amount` | Section "ALTER TABLE / DROP COLUMN with existing data" |
| SCHEMA-03 | V31 pre-flight RAISE EXCEPTION if any `disbursement` row has `disbursement_status IN ('PROCESSING', 'PENDING_CONFIRMATION')`; `merchant_wallet_balance` retired at app layer only | Section "Pre-Flight Pattern" + "Application-Layer Retirement" |
</phase_requirements>

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Flyway Core | managed by Spring Boot 3.5.11 parent | SQL-file database migrations | Already in use; V1–V30 all use plain .sql files |
| flyway-database-postgresql | managed by Spring Boot 3.5.11 parent | PostgreSQL dialect support | Required for PostgreSQL-specific DDL |
| Spring Data JPA / Hibernate 6.x | managed by Spring Boot 3.5.11 parent | ORM entity mapping | Project baseline; @Audited, @Enumerated(STRING) patterns already established |
| hibernate-envers | managed by Spring Boot 3.5.11 parent | Audit trail (_aud tables) | All entities with @Audited need matching _aud DDL in every migration |

### No New Dependencies Required
Phase 54 adds no new libraries. All tooling is already in the pom.xml.

---

## Architecture Patterns

### Flyway Migration File Convention
All migrations follow `V{N}__{description}.sql` in `src/main/resources/db/migration/`. The next available number is **V31** (V30 is `webhook_delivery_log_status`). File name must be `V31__disbursement_transaction_ref.sql` (or similar descriptive name).

### Pre-Flight Guard Pattern (HIGH confidence — verified from V23, V25)
V23 and V25 both use this exact idiom. Use it verbatim for the PROCESSING/PENDING_CONFIRMATION check:

```sql
-- Source: V23__ledger_group_constraint.sql, V25__ledger_disbursement_schema.sql
DO $$
DECLARE
    bad_count INT;
BEGIN
    SELECT COUNT(*) INTO bad_count
    FROM main.disbursement
    WHERE disbursement_status IN ('PROCESSING', 'PENDING_CONFIRMATION');
    IF bad_count > 0 THEN
        RAISE EXCEPTION 'V31 pre-flight: % disbursement(s) in PROCESSING or PENDING_CONFIRMATION — drain live traffic before running this migration', bad_count;
    END IF;
END $$;
```

The pre-flight MUST come before any DDL in the file.

### Partial Unique Index in PostgreSQL (HIGH confidence)
PostgreSQL supports partial indexes natively. The SCHEMA-01 requirement maps directly to:

```sql
CREATE UNIQUE INDEX uq_dtr_txn_active_claim
    ON main.disbursement_transaction_ref (transaction_id)
    WHERE ref_status IN ('PENDING', 'CLAIMED');
```

This is a standard PostgreSQL feature — no extension or special configuration needed. A second INSERT for the same `transaction_id` with `ref_status = 'PENDING'` will fail with a unique constraint violation at the DB layer.

### Envers _aud Table Pattern (HIGH confidence — verified from V28)
Every `@Audited` entity requires a matching `_aud` table. The `disbursement_transaction_ref` entity will be `@Audited`, so V31 must create `main.disbursement_transaction_ref_aud` as well. Follow V28's exact column layout: all audited columns are nullable in the `_aud` table; include `id BIGINT`, `rev INTEGER REFERENCES main.revinfo(rev)`, `revtype SMALLINT`, then all audited columns.

### Entity ID Pattern (HIGH confidence — verified from BaseEntity.java)
All entities extend `BaseEntity` which uses `@Id @Tsid` — the id column is `BIGINT`, not UUID. The `disbursement_transaction_ref` table must use `BIGINT id` as the PK (TSID-generated). The `disbursement_id` FK will be `BIGINT` referencing `main.disbursement(id)`. The `transaction_id` FK will be `VARCHAR(36)` referencing the logical `transaction_id` value on `main.transaction` — consistent with how `ledger_entry.transaction_id` is typed (V4 schema: `transaction_id VARCHAR(36) NOT NULL`).

### Enum Stored as VARCHAR (HIGH confidence — verified from Disbursement.java)
`@Enumerated(EnumType.STRING)` is the project standard. The `ref_status` column on `disbursement_transaction_ref` will be `VARCHAR(30) NOT NULL`. No PostgreSQL ENUM type is used — plain VARCHAR with application-layer validation only.

### ALTER TABLE / DROP COLUMN Safety (HIGH confidence)
PostgreSQL allows `DROP COLUMN` on a table with existing rows, as long as the column is nullable or the default satisfies constraints. `reserved_amount` is defined `NUMERIC(20,2)` (nullable, no NOT NULL in V28) — confirmed in the V28 SQL. Dropping it while existing rows have value `0` (or NULL) is safe. Use `ALTER TABLE main.disbursement DROP COLUMN IF EXISTS reserved_amount` and mirror in `disbursement_aud`.

### Recommended V31 Migration Structure

```sql
-- Step 1: Pre-flight guard (NO DDL before this)
DO $$ ... RAISE EXCEPTION if PROCESSING/PENDING_CONFIRMATION rows exist ...

-- Step 2: New columns on disbursement
ALTER TABLE main.disbursement
    ADD COLUMN IF NOT EXISTS admin_note TEXT,
    ADD COLUMN IF NOT EXISTS retry_count INT NOT NULL DEFAULT 0;
ALTER TABLE main.disbursement_aud
    ADD COLUMN IF NOT EXISTS admin_note TEXT,
    ADD COLUMN IF NOT EXISTS retry_count INT;

-- Step 3: Remove reserved_amount from disbursement
ALTER TABLE main.disbursement DROP COLUMN IF EXISTS reserved_amount;
ALTER TABLE main.disbursement_aud DROP COLUMN IF EXISTS reserved_amount;

-- Step 4: Create disbursement_transaction_ref (_aud first, then base)
CREATE TABLE IF NOT EXISTS main.disbursement_transaction_ref_aud (...);
CREATE TABLE IF NOT EXISTS main.disbursement_transaction_ref (...);

-- Step 5: Partial unique index
CREATE UNIQUE INDEX uq_dtr_txn_active_claim
    ON main.disbursement_transaction_ref (transaction_id)
    WHERE ref_status IN ('PENDING', 'CLAIMED');
```

### Application-Layer Retirement Pattern

The requirement (SCHEMA-03) is to remove all `WalletBalanceService` call sites from `DisbursementOrchestrator` and `DisbursementCallbackTransitionService` only. `WalletBalanceService`, `MerchantWalletBalance`, and `MerchantWalletBalanceRepository` must remain in code (table is not dropped until V32 in Phase 57).

Specific call sites to remove from `DisbursementOrchestrator`:
1. `this.feeService = feeService` + `feeService.evaluateFee(...)` (Step 5 in initiate) — also removes `FeeEvaluationService` per SCHEMA-03's intent; fee is always `BigDecimal.ZERO`
2. `this.walletBalanceService = walletBalanceService` + `walletBalanceService.checkAndReserve(...)` (Step 6)
3. `walletBalanceService.release(...)` in `releaseAndFail` private method
4. The `confirm()` method reconstructs `fee` and `totalAmount` from `dsb.getReservedAmount()` — these must be replaced with `BigDecimal.ZERO` / `dsb.getAmount()` after `reserved_amount` is removed

Specific call sites to remove from `DisbursementCallbackTransitionService`:
1. `this.walletBalanceService = walletBalanceService` field + constructor injection
2. `walletBalanceService.release(locked.getTenantId(), locked.getReservedAmount())` in `applyDisbursementTransition`

### DisbursementStatus State Machine Extension

Current states: `INITIATED → PENDING_CONFIRMATION → PROCESSING → SUCCESS | FAILED | EXPIRED`

New state: `PENDING_ADMIN_APPROVAL` (distinct from `PENDING_CONFIRMATION` — merchant step-up vs ops approval)

Required state machine wiring for `PENDING_ADMIN_APPROVAL`:
- `INITIATED.allowedTransitions()` must include `PENDING_ADMIN_APPROVAL` (admin-approval path is an alternative to direct PROCESSING dispatch)
- `PENDING_ADMIN_APPROVAL.allowedTransitions()` must include `PROCESSING` (admin approves) and `EXPIRED` (auto-timeout per ADMIN-03)
- All existing transitions must remain unchanged (`PENDING_CONFIRMATION` paths must still work)

Updated `DisbursementStatusTest.allValuesDeclared()` must include `PENDING_ADMIN_APPROVAL`.

### Disbursement Entity Changes Required

`reserved_amount` column is mapped on `Disbursement.java` line 70-71. After V31 drops the column, Hibernate will throw `org.hibernate.MappingException: Could not determine type for: main.disbursement.reserved_amount`. The field and `@Column` annotation must be removed from `Disbursement.java` before `mvn verify` can pass.

Downstream impacts of removing `reserved_amount` from `Disbursement`:
- `DisbursementOrchestrator.confirm()` — line 237-238: `dsb.getReservedAmount().subtract(dsb.getAmount())` and `dsb.getReservedAmount()` become `BigDecimal.ZERO` and `dsb.getAmount()` respectively
- `DisbursementResource.toListItem()` — line 149-150: fee derivation from `reserved_amount` must become `BigDecimal.ZERO`
- `DisbursementService.create()` — `reservedAmount` parameter no longer written; method signature changes
- Test: `DisbursementRepositoryIT` line 44: `.reservedAmount(new BigDecimal("100.00"))` in the save helper — must be removed

### New Entity: DisbursementTransactionRef

New JPA entity extending `AbstractAuditingEntity` (so it gets Envers `@Audited` and the audit columns automatically):

```java
@Audited
@Entity
@Table(name = "disbursement_transaction_ref", schema = "main")
public class DisbursementTransactionRef extends AbstractAuditingEntity {

    @Column(name = "disbursement_id", nullable = false, updatable = false)
    private Long disbursementId;   // FK to disbursement.id (BIGINT TSID)

    @Column(name = "transaction_id", nullable = false, updatable = false, length = 36)
    private String transactionId;  // VARCHAR(36) — logical UUID, mirrors ledger_entry.transaction_id

    @Enumerated(EnumType.STRING)
    @Column(name = "ref_status", nullable = false, length = 30)
    private DisbursementRefStatus refStatus;  // new enum: PENDING, CLAIMED, RELEASED
}
```

The `DisbursementRefStatus` enum (new): `PENDING`, `CLAIMED`, `RELEASED`.

### Anti-Patterns to Avoid
- **FK on VARCHAR transaction_id:** Do not add a database-level FK from `disbursement_transaction_ref.transaction_id` to `main.transaction.transaction_id`. Prior tables (ledger_entry, webhook_delivery_log) use VARCHAR transaction_id with no FK — consistent with the existing pattern that avoids cross-table FK for performance reasons.
- **Adding @NotAudited to new fields:** All fields on `DisbursementTransactionRef` should be audited by default (inherited `@Audited` from `AbstractAuditingEntity`). Only annotate `@NotAudited` if there is a specific reason (there is none here).
- **Dropping reserved_amount before removing Java mapping:** The migration will succeed, but `mvn verify` will fail on application startup because Hibernate validates schema. The Java entity change and the migration must be tested together.
- **Removing `WalletBalanceService` bean entirely:** The bean must remain; only remove injections in `DisbursementOrchestrator` and `DisbursementCallbackTransitionService`. The V32 migration (Phase 57) drops the table; that is when the service and entity can be fully deleted.
- **Forgetting `disbursement_transaction_ref_aud`:** Envers auto-creates `_aud` tables at startup in dev mode, but the migration must create it explicitly for production safety and test environment parity.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Partial unique index enforcement | Application-layer duplicate check | PostgreSQL partial unique index | DB constraint is atomic and races-proof; app-layer check has TOCTOU gap |
| Pre-flight row count check | Custom Java pre-migration check | `DO $$ RAISE EXCEPTION $$` in .sql file | Flyway runs SQL in a transaction; a `RAISE EXCEPTION` rolls back everything and prevents partial migration |
| Enum persistence | PostgreSQL ENUM type | VARCHAR + `@Enumerated(STRING)` | Project standard; adding PostgreSQL ENUMs requires a migration for every new value |

---

## Runtime State Inventory

> This is a schema migration phase with application-layer code changes. No rename/rebrand involved. Standard inventory below.

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | `main.disbursement` rows with `reserved_amount` column — all existing rows have `reserved_amount = 0` (enforced by v10 test cleanup) or NULL | DROP COLUMN is safe; verify with pre-flight if needed |
| Stored data | `main.merchant_wallet_balance` — rows may exist in production | No action in V31; table is NOT dropped until V32 (Phase 57) |
| Live service config | No external service config involved | None |
| OS-registered state | No OS-registered state | None |
| Secrets/env vars | No new env vars in this phase | None |
| Build artifacts | TestDataCleaner.wipeAll() references `main.disbursement_aud` and `main.merchant_wallet_balance` — both tables still exist after V31; no change needed here | Verify cleanup order after `disbursement_transaction_ref` table is added (add DELETE FROM main.disbursement_transaction_ref before disbursement) |

---

## Common Pitfalls

### Pitfall 1: Hibernate Fails on Startup if Java Entity Maps Dropped Column
**What goes wrong:** V31 drops `reserved_amount` from `main.disbursement`. If `Disbursement.java` still has the `@Column(name = "reserved_amount")` field, Hibernate schema validation fails on startup with `Schema-validation: missing column [reserved_amount] in table [main.disbursement]`.
**Why it happens:** Hibernate validates entity mappings against the live schema at boot.
**How to avoid:** Remove the `reservedAmount` field from `Disbursement.java` in the same plan that introduces V31, before running `mvn verify`.
**Warning signs:** `HibernateException` or `SchemaManagementException` in the test startup log.

### Pitfall 2: DisbursementOrchestrator.confirm() Breaks When reserved_amount is Gone
**What goes wrong:** `confirm()` reads `dsb.getReservedAmount()` on line 237-238 to reconstruct fee and totalAmount. After removing the field, this is a NullPointerException or compile error.
**Why it happens:** The v10 `confirm()` was designed around the wallet model. In v11, fees are always zero and amounts come directly from the request.
**How to avoid:** Replace `dsb.getReservedAmount().subtract(dsb.getAmount())` with `BigDecimal.ZERO` and `dsb.getReservedAmount()` with `dsb.getAmount()` when removing the field.
**Warning signs:** Compilation failure in `DisbursementOrchestrator.java` after removing entity field.

### Pitfall 3: DisbursementCallbackTransitionService Still Calls release() After Wiring Change
**What goes wrong:** If `walletBalanceService` is removed from the constructor but the `release()` call is not removed, or vice versa, Spring fails to wire the bean.
**Why it happens:** Constructor injection — removing the field but not the constructor param (or vice versa) causes a Spring `UnsatisfiedDependencyException`.
**How to avoid:** Remove field declaration, constructor parameter, assignment, and both call sites atomically in one edit.
**Warning signs:** Spring context fails to load in any IT test.

### Pitfall 4: Missing DisbursementStatusTest Update Causes Test Failure
**What goes wrong:** `DisbursementStatusTest.allValuesDeclared()` uses `containsExactlyInAnyOrder` with 6 values. Adding `PENDING_ADMIN_APPROVAL` without updating the test causes a test failure.
**Why it happens:** AssertJ's `containsExactlyInAnyOrder` fails if the actual set has more elements than expected.
**How to avoid:** Update `DisbursementStatusTest` to include `PENDING_ADMIN_APPROVAL` in all relevant test methods.
**Warning signs:** `AssertionError: Expecting actual ... to contain exactly` in test output.

### Pitfall 5: TestDataCleaner Missing disbursement_transaction_ref Delete
**What goes wrong:** E2E and IT tests that insert into `disbursement_transaction_ref` will leave orphan rows, breaking subsequent tests that depend on the uniqueness constraint.
**Why it happens:** `TestDataCleaner.wipeAll()` does not know about the new table.
**How to avoid:** Add `jdbcTemplate.execute("DELETE FROM main.disbursement_transaction_ref_aud")` and `jdbcTemplate.execute("DELETE FROM main.disbursement_transaction_ref")` to `wipeAll()` before the `disbursement_aud` and `disbursement` deletes (FK order).
**Warning signs:** Unique constraint violation errors in tests that run after tests that populate the ref table.

### Pitfall 6: Pre-Flight Does Not Fire in Test if DB is Empty
**What goes wrong:** The pre-flight assertion passes trivially on a fresh test DB (no rows). This is correct behavior — the guard is for production use. Do not write a test asserting the pre-flight fails on an empty DB.
**Why it happens:** Not a bug; the pre-flight is designed to protect production databases with in-flight disbursements.
**How to avoid:** Write one test that inserts a PROCESSING row and then calls the migration via a raw JDBC execute — assert it throws. This pattern is used by LedgerConstraintIT for V23 pre-flight.
**Warning signs:** No pre-flight test at all (easy to miss).

---

## Code Examples

### Pre-Flight Assertion (from V23 — exact pattern to follow)
```sql
-- Source: V23__ledger_group_constraint.sql
DO $$
DECLARE
    bad_count INT;
BEGIN
    SELECT COUNT(*) INTO bad_count
    FROM main.disbursement
    WHERE disbursement_status IN ('PROCESSING', 'PENDING_CONFIRMATION');
    IF bad_count > 0 THEN
        RAISE EXCEPTION 'V31 pre-flight: % disbursement(s) in PROCESSING or PENDING_CONFIRMATION — drain live traffic before running this migration', bad_count;
    END IF;
END $$;
```

### Partial Unique Index
```sql
-- Source: PostgreSQL documentation + SCHEMA-01 requirement
CREATE UNIQUE INDEX uq_dtr_txn_active_claim
    ON main.disbursement_transaction_ref (transaction_id)
    WHERE ref_status IN ('PENDING', 'CLAIMED');
```

### DisbursementStatus Enum Addition (pattern from existing enum)
```java
// Source: DisbursementStatus.java (current)
// Add after PENDING_CONFIRMATION block, before PROCESSING:
PENDING_ADMIN_APPROVAL {
    @Override
    public Set<DisbursementStatus> allowedTransitions() {
        return EnumSet.of(PROCESSING, EXPIRED);
    }
},
// Also update INITIATED.allowedTransitions() to include PENDING_ADMIN_APPROVAL:
INITIATED {
    @Override
    public Set<DisbursementStatus> allowedTransitions() {
        return EnumSet.of(PENDING_CONFIRMATION, PENDING_ADMIN_APPROVAL, PROCESSING, FAILED);
    }
},
```

### WalletBalanceService Removal from DisbursementOrchestrator (surgical)
```java
// REMOVE from constructor parameters and field:
//   private final FeeEvaluationService feeService;
//   private final WalletBalanceService walletBalanceService;

// REPLACE Step 5 (fee evaluation) with:
BigDecimal fee = BigDecimal.ZERO;
BigDecimal totalAmount = request.amount();

// REMOVE Step 6 entirely (wallet reserve block)

// REPLACE releaseAndFail() wallet release with no-op:
//   Remove: walletBalanceService.release(tenantId, totalAmount);
//   The transitionToFailed() call remains.

// REPLACE confirm() fee/totalAmount derivation:
//   OLD: BigDecimal fee = dsb.getReservedAmount().subtract(dsb.getAmount());
//   OLD: BigDecimal totalAmount = dsb.getReservedAmount();
//   NEW: BigDecimal fee = BigDecimal.ZERO;
//   NEW: BigDecimal totalAmount = dsb.getAmount();
```

### Disbursement Entity Field Removal
```java
// REMOVE from Disbursement.java:
//   @Column(name = "reserved_amount", precision = 20, scale = 2)
//   private BigDecimal reservedAmount;

// DisbursementService.create() — remove reservedAmount parameter:
//   OLD: .reservedAmount(reservedAmount)
//   NEW: (omit — no field)
//   Update method signature accordingly.
```

---

## Environment Availability

Step 2.6: SKIPPED — this phase is purely code and SQL migration changes. The build tool (`mvn verify`) is the only external dependency and is already verified to work per PROJECT.md.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + AssertJ + Mockito + Spring Boot Test + Testcontainers (PostgreSQL) |
| Config file | `pom.xml` (maven-surefire-plugin, maven-failsafe-plugin) |
| Quick run command | `mvn test -pl . -Dtest=DisbursementStatusTest,DisbursementRepositoryIT` |
| Full suite command | `mvn verify` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| SCHEMA-01 | `disbursement_transaction_ref` table + partial unique index created; duplicate active claim INSERT rejected | integration | `mvn verify -Dtest=DisbursementTransactionRefIT` | ❌ Wave 0 |
| SCHEMA-02 | `admin_note` and `retry_count` added; `reserved_amount` dropped; migration runs on DB with existing rows | integration | `mvn verify -Dtest=DisbursementTransactionRefIT` | ❌ Wave 0 |
| SCHEMA-03 | Pre-flight raises exception if PROCESSING row exists; pre-flight passes on empty/terminal DB | integration | `mvn verify -Dtest=DisbursementTransactionRefIT` | ❌ Wave 0 |
| SCHEMA-03 (app layer) | WalletBalanceService not called from DisbursementOrchestrator or DisbursementCallbackTransitionService | unit | `mvn test -Dtest=DisbursementOrchestratorTest,DisbursementCallbackTransitionServiceTest` | ✅ (needs update) |
| SC-4 | `DisbursementStatus` includes `PENDING_ADMIN_APPROVAL`; existing transitions unchanged | unit | `mvn test -Dtest=DisbursementStatusTest` | ✅ (needs update) |
| SC-6 | `mvn verify` passes — no migration failures, no regressions | gate | `mvn verify` | ✅ |

### Sampling Rate
- **Per task commit:** `mvn test -Dtest=DisbursementStatusTest`
- **Per wave merge:** `mvn verify`
- **Phase gate:** `mvn verify` fully green before marking Phase 54 complete

### Wave 0 Gaps
- [ ] `src/test/java/com/softropic/payam/disbursement/repo/DisbursementTransactionRefIT.java` — covers SCHEMA-01, SCHEMA-02, SCHEMA-03 (pre-flight + DDL assertions against real Testcontainers PostgreSQL)
- [ ] `src/main/java/com/softropic/payam/disbursement/contract/DisbursementRefStatus.java` — new enum (PENDING, CLAIMED, RELEASED)
- [ ] `src/main/java/com/softropic/payam/disbursement/repo/DisbursementTransactionRef.java` — new JPA entity
- [ ] `src/main/java/com/softropic/payam/disbursement/repo/DisbursementTransactionRefRepository.java` — repository stub (needed by Phase 55)
- [ ] `src/main/resources/db/migration/V31__disbursement_transaction_ref.sql` — the migration file itself

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Wallet reservation model (pre-fund then decrement) | Claim-based locking (back disbursement with real collection transactions) | v11 (Phase 54 starts transition) | `reserved_amount` on disbursement and `merchant_wallet_balance` table become dead state in V31; fully dropped in V32 |
| Fee evaluation for disbursements (`FeeEvaluationService`) | No fee for disbursements (`fee = BigDecimal.ZERO` always) | v11 FEE-01 | `feeService` field removed from orchestrator; this is also done in Phase 54 since orchestrator is being restructured |

---

## Open Questions

1. **`DisbursementRefStatus` enum placement**
   - What we know: Project uses `DisbursementStatus` in `contract` package; `EntityStatus` in `common/persistence`
   - What's unclear: Should `DisbursementRefStatus` live in `disbursement/contract` or `disbursement/repo`?
   - Recommendation: Place in `disbursement/contract` — it is a business-domain state, not a persistence artifact. Consistent with `DisbursementStatus` placement.

2. **`confirm()` method post-removal behavior**
   - What we know: `confirm()` currently reads `dsb.getReservedAmount()` to reconstruct fee; after v11 Phase 55 the confirm flow will change significantly (no wallet, new claim validation)
   - What's unclear: Should `confirm()` be left as a stub returning `INVALID_STATE` since Phase 55 rewrites it?
   - Recommendation: Keep `confirm()` functional but with `fee = BigDecimal.ZERO` and `totalAmount = dsb.getAmount()` — Phase 55 will rewrite it fully. Do not stub it out and break existing step-up E2E tests.

3. **`DisbursementService.create()` signature change**
   - What we know: `create()` currently takes a `reservedAmount` parameter
   - What's unclear: Phase 55 will add `transactionIds` to the creation flow — should the signature change be minimal in Phase 54 or anticipate Phase 55?
   - Recommendation: In Phase 54, simply remove the `reservedAmount` parameter and stop writing it. Phase 55 will add `transactionIds`. Keep changes minimal to limit regression risk.

---

## Sources

### Primary (HIGH confidence)
- Codebase: `src/main/resources/db/migration/V23__ledger_group_constraint.sql` — pre-flight pattern
- Codebase: `src/main/resources/db/migration/V25__ledger_disbursement_schema.sql` — pre-flight + complex DDL pattern
- Codebase: `src/main/resources/db/migration/V28__disbursement_schema.sql` — disbursement table definition with `reserved_amount`
- Codebase: `src/main/java/com/softropic/payam/disbursement/contract/DisbursementStatus.java` — current enum
- Codebase: `src/main/java/com/softropic/payam/disbursement/repo/Disbursement.java` — entity with `reserved_amount` field
- Codebase: `src/main/java/com/softropic/payam/disbursement/service/DisbursementOrchestrator.java` — wallet call sites
- Codebase: `src/main/java/com/softropic/payam/disbursement/service/DisbursementCallbackTransitionService.java` — wallet call sites
- Codebase: `src/main/java/com/softropic/payam/common/persistence/BaseEntity.java` — TSID BIGINT id pattern
- Codebase: `.planning/STATE.md` — v11 key decisions (last migration V30, next V31, `PENDING_ADMIN_APPROVAL` semantics)
- Codebase: `.planning/REQUIREMENTS.md` — SCHEMA-01, SCHEMA-02, SCHEMA-03 full text

### Secondary (MEDIUM confidence)
- PostgreSQL documentation (via training data): partial unique index `WHERE` clause syntax is standard and well-established
- Spring Boot 3.5.11 pom parent: Flyway and Hibernate versions managed

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — verified from pom.xml and existing migration files
- Architecture patterns: HIGH — all patterns taken directly from V23/V25/V28 codebase evidence
- Pitfalls: HIGH — derived from reading actual call sites (DisbursementOrchestrator.confirm line 237-238, DisbursementResource.toListItem lines 149-150, DisbursementRepositoryIT line 44)

**Research date:** 2026-05-02
**Valid until:** 2026-06-02 (stable — no external API dependencies; all findings are from internal codebase)
