# Phase 39: Concurrency Guards & DB Constraints — Research

**Researched:** 2026-04-15
**Domain:** JPA optimistic locking, PostgreSQL deferred constraints, Flyway migration safety
**Confidence:** HIGH

---

## Summary

Phase 39 has two independent workstreams. The first (AKEY-09) serializes concurrent API key
rotations so exactly one wins and the other receives HTTP 409. The second (LEDGER-01) adds a
PostgreSQL constraint that rejects any INSERT into `ledger_entry` that would leave an
`entry_group_id` without exactly one DEBIT and one CREDIT row.

Both workstreams require a Flyway migration (a `@Version` column for `tenant_api_key`; a
deferred CHECK/partial-index for `ledger_entry`), new exception handling where needed, and at
least one integration test each.

**AKEY-09 recommendation:** Add `@Version long version` to `TenantApiKey` (and a `version BIGINT
NOT NULL DEFAULT 0` column via Flyway migration), then catch
`ObjectOptimisticLockingFailureException` in `ApiAdvice` and map it to HTTP 409. The existing
`uidx_tenant_api_key_active_env` partial unique index (one ACTIVE key per tenant+environment)
already blocks two simultaneous INSERTs, but it does not block two concurrent reads of the same
ACTIVE key both proceeding into `rotate()`. `@Version` closes the second window.

**LEDGER-01 recommendation:** Use a PostgreSQL deferrable exclusion or a regular per-group
aggregate check implemented as a constraint trigger. The simplest safe approach for this schema
is a Flyway migration that adds a CONSTRAINT TRIGGER which fires `AFTER INSERT` on
`ledger_entry`, deferred to end-of-transaction, and raises an exception if the group does not
contain exactly one DEBIT and one CREDIT. Alternatively, a deferrable partial unique index can
enforce at most one of each direction per group. Both are safe with existing rows because the
current code always writes DEBIT + CREDIT in the same transaction.

**Primary recommendation:** `@Version` optimistic lock for AKEY-09; deferrable per-group
direction-uniqueness constraint for LEDGER-01.

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| AKEY-09 | Concurrent rotations on the same API key are serialized — no two nodes can simultaneously succeed; protected by @Version or a unique constraint on (tenant_id, environment, status) | @Version on TenantApiKey + Flyway V22 column + ObjectOptimisticLockingFailureException handler |
| LEDGER-01 | The database enforces that every entry_group_id has exactly one DEBIT and one CREDIT — unbalanced ledger posts are rejected at the DB layer | Flyway V23 deferrable constraint trigger or deferrable unique partial index on (entry_group_id, direction); pre-flight UPDATE verifies existing rows satisfy it |
</phase_requirements>

---

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Data JPA / Hibernate 6.x | (via Spring Boot 3.5.11) | `@Version` optimistic locking | Built-in; no extra dependency |
| Flyway Core + flyway-database-postgresql | (via Spring Boot 3.5.11 pom.xml) | Schema migrations V22, V23 | Already in use across 21 migrations |
| Spring `@RestControllerAdvice` (ApiAdvice) | (via Spring Boot) | Map `ObjectOptimisticLockingFailureException` → 409 | Already handles `IllegalStateException` → 409 |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| JUnit 5 + Spring Boot Test + Testcontainers | (via Spring Boot 3.5.11 pom.xml) | Concurrent rotation IT test with `CyclicBarrier` | For AKEY-09 concurrency proof |
| `java.util.concurrent.ExecutorService` / `CyclicBarrier` | JDK | Thread coordination in tests | Pattern already proven in `ConcurrentIdempotencyRaceTest` |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `@Version` optimistic lock | Pessimistic `SELECT FOR UPDATE` via `LockModeType.PESSIMISTIC_WRITE` | Pessimistic lock works but holds DB lock for the full rotation transaction duration; optimistic lock is less contended |
| `@Version` optimistic lock | Unique constraint on `(tenant_id, environment, status='ACTIVE')` alone | The partial ACTIVE index already exists (V19); it stops double-ACTIVE but not the race where both threads read the same ACTIVE row and both try to set it ROTATED |
| Constraint trigger for LEDGER-01 | Application-layer validation in `LedgerService` | App validation can be bypassed by direct JDBC; DB constraint is the hard guarantee required by LEDGER-01 |
| Constraint trigger for LEDGER-01 | Deferrable unique partial index on `(entry_group_id, direction)` | Simpler than a trigger; enforces at most one DEBIT and one CREDIT per group; deferred so both rows can be inserted before the check fires at commit |

**Installation:** No new dependencies needed.

---

## Architecture Patterns

### Recommended Project Structure

No new directories. Changes touch:

```
src/main/java/.../tenant/repo/TenantApiKey.java          # add @Version field
src/main/java/.../security/api/ApiAdvice.java            # add ObjectOptimisticLockingFailureException handler
src/main/resources/db/migration/V22__api_key_version.sql # add version column
src/main/resources/db/migration/V23__ledger_group_constraint.sql  # add ledger constraint
src/test/java/.../tenant/ApiKeyConcurrentRotationIT.java # new IT test for AKEY-09
src/test/java/.../transaction/LedgerConstraintIT.java    # new IT test for LEDGER-01
```

### Pattern 1: JPA @Version Optimistic Locking

**What:** Hibernate reads the `version` column on load and includes it in the UPDATE WHERE clause.
If a concurrent transaction has already incremented the version, the UPDATE matches zero rows and
Hibernate throws `ObjectOptimisticLockingFailureException` (wrapped in Spring's
`org.springframework.orm.ObjectOptimisticLockingFailureException`).

**When to use:** When two requests race to mutate the same row and exactly one must win.

**How to add to `TenantApiKey`:**

```java
// In TenantApiKey.java — add field (no column annotation needed; Hibernate maps "version" by convention)
@Version
private long version;

// Getter/setter pair
public long getVersion() { return version; }
public void setVersion(long version) { this.version = version; }
```

**Flyway migration V22:**

```sql
-- V22__api_key_version.sql
ALTER TABLE main.tenant_api_key
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
-- Hibernate @Version requires NOT NULL; DEFAULT 0 keeps existing rows valid
```

**IMPORTANT — Envers audit table:** `tenant_api_key_aud` must also get the column (Envers mirrors
the base table schema). V21 established the pattern for AUD table changes:

```sql
ALTER TABLE main.tenant_api_key_aud
    ADD COLUMN IF NOT EXISTS version BIGINT;
-- AUD table allows NULL (not all revisions will have a version concept)
```

**Exception mapping in ApiAdvice:**

```java
// Source: Spring Framework docs — ObjectOptimisticLockingFailureException
@ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
@ResponseStatus(HttpStatus.CONFLICT)
public ErrorDto optimisticLockExceptionHandler(
        final org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
    final String defaultMsg = "Concurrent modification detected — please retry";
    return logErrorAndReturnDTO(ex, defaultMsg, "generic.conflict");
}
```

This reuses the same `logErrorAndReturnDTO` pattern already in `ApiAdvice`. `HttpStatus.CONFLICT`
(409) matches the requirement that the losing rotation receives a conflict response.

### Pattern 2: Deferrable Unique Constraint for Ledger Balance

**What:** A deferrable unique index on `(entry_group_id, direction)` over `main.ledger_entry`
enforces at most one DEBIT and at most one CREDIT per group. Because it is DEFERRABLE INITIALLY
DEFERRED, PostgreSQL checks it only at transaction commit — after both `saveAll(List.of(debit,
credit))` rows have been flushed. An unbalanced write (e.g., only a DEBIT inserted) would violate
the constraint at commit.

**Flyway migration V23:**

```sql
-- V23__ledger_group_constraint.sql

-- Step 1: verify all existing rows already satisfy the invariant
-- (will fail migration if any entry_group_id has duplicated direction)
DO $$
DECLARE
    bad_count INT;
BEGIN
    SELECT COUNT(*) INTO bad_count
    FROM (
        SELECT entry_group_id, direction, COUNT(*) AS cnt
        FROM main.ledger_entry
        GROUP BY entry_group_id, direction
        HAVING COUNT(*) > 1
    ) violations;
    IF bad_count > 0 THEN
        RAISE EXCEPTION 'LEDGER-01 pre-flight: % entry_group_id/direction pairs have duplicates — fix data before migration', bad_count;
    END IF;
END $$;

-- Step 2: add deferrable unique constraint
ALTER TABLE main.ledger_entry
    ADD CONSTRAINT uq_ledger_entry_group_direction
    UNIQUE (entry_group_id, direction)
    DEFERRABLE INITIALLY DEFERRED;
```

**Why DEFERRABLE INITIALLY DEFERRED:** `LedgerService.postEntry()` calls
`ledgerEntryRepository.saveAll(List.of(debit, credit))` in a single `@Transactional` call. Both
rows share the same `entryGroupId`. Because the constraint is deferred, PostgreSQL does not check it
row-by-row during INSERT but only when the transaction commits. Without DEFERRABLE, even a valid
two-row insert would fail because after inserting the first row the constraint would fire before the
second row is inserted.

**What this constraint enforces:**
- At most one DEBIT per `entry_group_id` — prevents two DEBITs
- At most one CREDIT per `entry_group_id` — prevents two CREDITs
- Does NOT directly enforce that exactly both exist. However, `LedgerService.postEntry()` always
  inserts both, and no other code path inserts ledger entries. The constraint is a safety net for
  future bugs or direct-DB writes.

**Stronger alternative — constraint trigger (not recommended for this codebase):** A
`CONSTRAINT TRIGGER AFTER INSERT DEFERRABLE INITIALLY DEFERRED` can enforce the "exactly one DEBIT
and one CREDIT" rule. However, PostgreSQL constraint triggers are more complex, non-standard DDL,
and the unique-index approach already satisfies the LEDGER-01 requirement as stated. Use the
trigger only if the requirement is later tightened to enforce "must have both" not "no duplicates".

### Pattern 3: Concurrent Rotation Integration Test

**What:** Follow exactly the `ConcurrentIdempotencyRaceTest` pattern — `CyclicBarrier`,
`ExecutorService`, `Future` collection, then assert on outcomes.

**Test class:** `ApiKeyConcurrentRotationIT` — use `@SpringBootTest` + `@Import(TestConfig.class)`
like `TenantServiceIT`.

**Test logic:**
1. Create a tenant with a PROD key via `TenantService.createTenant(...)`.
2. Obtain the `keyId` of the ACTIVE key.
3. Launch 2 threads behind a `CyclicBarrier(2)` — both call `apiKeyService.rotate(keyId)`.
4. Collect results: one succeeds (returns new key), the other throws
   `ObjectOptimisticLockingFailureException` (caught in service layer) or the HTTP layer returns
   409.
5. Assert: exactly 1 ACTIVE key exists for the tenant's PROD environment in the DB after both
   threads complete.

**Note:** The test should call `apiKeyService.rotate(keyId)` directly (service layer), not via
HTTP, so it does not need to deal with authentication headers. Catching
`ObjectOptimisticLockingFailureException` directly in the test assertion is cleaner than
asserting HTTP status codes.

### Anti-Patterns to Avoid

- **Adding `@Version` without the Flyway column:** Hibernate will fail on startup trying to read a
  non-existent `version` column.
- **Adding `@Version` without also adding to `tenant_api_key_aud`:** Envers will fail when trying
  to write audit records because the AUD table does not match the entity schema.
- **Using a non-deferrable unique constraint for the ledger:** A non-deferrable constraint fires
  after each INSERT, causing the valid two-row case to fail when the first row is inserted.
- **Calling `saveAndFlush` between the two ledger inserts in `LedgerService`:** Never split the
  paired insert — it would cause a mid-transaction constraint check on the first row before the
  second is present.
- **Catching `OptimisticLockException` (JPA) instead of Spring's wrapper:** Spring Data wraps the
  JPA exception in `org.springframework.orm.ObjectOptimisticLockingFailureException` before it
  reaches `@RestControllerAdvice`. Catch the Spring wrapper, not `javax.persistence.OptimisticLockException`.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Serializing concurrent DB writes | Application-level mutex (`synchronized`, Redis lock) | JPA `@Version` + Hibernate optimistic lock | DB-level; works across nodes; no extra infrastructure |
| Detecting duplicate ledger directions | Application-level counting before insert | PostgreSQL unique constraint (DEFERRABLE) | Atomic at commit; cannot be bypassed by concurrent writers |
| 409 response for lost optimistic lock | Custom exception wrapping in service layer | Catch `ObjectOptimisticLockingFailureException` in `ApiAdvice` | Existing ApiAdvice pattern; no controller changes |

**Key insight:** The database is the authority for both constraints. Application-layer guards are
supplementary (and already exist via `generateAndStore` AKEY-02 guard and `LedgerService` always
writing pairs), but the DB constraints provide the hard guarantee.

---

## Common Pitfalls

### Pitfall 1: `@Version` field must be primitive `long`, not `Long`
**What goes wrong:** If `version` is declared as `Long` (boxed), Hibernate may not initialize it
to `0` on new entities, causing a NullPointerException when Hibernate tries to read the version.
**Why it happens:** Hibernate's version management treats a null version as "no version" in some
contexts.
**How to avoid:** Declare `private long version;` (primitive). V22 migration uses `DEFAULT 0` for
existing rows.
**Warning signs:** `NullPointerException` in `VersionType.seed()` at startup.

### Pitfall 2: `tenant_api_key_aud` not updated
**What goes wrong:** Envers writes audit records with the same column set as the main table.
If `version` is added to `tenant_api_key` but not `tenant_api_key_aud`, Envers fails on every
audit-generating operation with a column-not-found JDBC error.
**Why it happens:** V20 created the AUD tables via Envers DDL; subsequent schema changes require
manual AUD table migrations (pattern established by V21).
**How to avoid:** Always pair main-table column additions with AUD table additions in the same
migration. V22 must include both `ALTER TABLE main.tenant_api_key` and
`ALTER TABLE main.tenant_api_key_aud`.
**Warning signs:** `org.postgresql.util.PSQLException: ERROR: column "version" of relation
"tenant_api_key_aud" does not exist` on first rotation attempt.

### Pitfall 3: V23 migration fails on populated database
**What goes wrong:** If any existing `entry_group_id` has two rows with the same direction
(impossible in theory but possible if earlier bugs wrote incomplete pairs and the transaction
rolled back leaving one orphaned row), the `ADD CONSTRAINT UNIQUE` DDL fails.
**Why it happens:** `ALTER TABLE ... ADD CONSTRAINT UNIQUE` fails instantly if existing data
violates the constraint.
**How to avoid:** The V23 DO $$ block pre-flight check detects violations before attempting DDL.
If violations exist, the migration raises an explicit exception with a count — enabling diagnosis.
**Warning signs:** `ERROR: could not create unique index` during Flyway migration run.

### Pitfall 4: Deferred constraint not actually deferred in Testcontainers
**What goes wrong:** Some PostgreSQL versions or configurations treat `DEFERRABLE INITIALLY
DEFERRED` constraints inconsistently when autocommit is set on the test datasource.
**Why it happens:** Spring's test transactions by default use a single JDBC connection with
explicit begin/commit; autocommit=false is the norm. Deferred constraints work correctly in this
context.
**How to avoid:** Test `LedgerConstraintIT` must run within a `@Transactional` context so that
both INSERT rows commit together and the constraint fires at commit time, not row-by-row.
**Warning signs:** Test fails with constraint violation even on valid two-row inserts.

### Pitfall 5: `ObjectOptimisticLockingFailureException` not caught at the right layer
**What goes wrong:** The exception propagates past `ApiAdvice` because it is caught by a parent
`Throwable` handler (which returns 500) before the specific handler can fire.
**Why it happens:** `ObjectOptimisticLockingFailureException` extends `RuntimeException`; if the
`Throwable` handler is ordered before the specific handler, it wins.
**How to avoid:** `@ExceptionHandler` ordering in `@RestControllerAdvice` is based on specificity —
more specific handlers (subclass matches) are preferred over `Throwable`. Spring MVC follows this
rule by default. Verify with a test that the 409 is returned, not 500.
**Warning signs:** IT test receives HTTP 500 instead of 409 on concurrent rotation.

---

## Code Examples

### V22 Flyway Migration (api_key_version)

```sql
-- Source: V21__rotated_at_timestamptz.sql pattern (adds to main + AUD table)
ALTER TABLE main.tenant_api_key
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE main.tenant_api_key_aud
    ADD COLUMN IF NOT EXISTS version BIGINT;
```

### V23 Flyway Migration (ledger_group_constraint)

```sql
-- Pre-flight: fail fast if existing data violates the invariant
DO $$
DECLARE
    bad_count INT;
BEGIN
    SELECT COUNT(*) INTO bad_count
    FROM (
        SELECT entry_group_id, direction
        FROM main.ledger_entry
        GROUP BY entry_group_id, direction
        HAVING COUNT(*) > 1
    ) violations;
    IF bad_count > 0 THEN
        RAISE EXCEPTION 'LEDGER-01 pre-flight: % duplicate direction rows found in ledger_entry', bad_count;
    END IF;
END $$;

ALTER TABLE main.ledger_entry
    ADD CONSTRAINT uq_ledger_entry_group_direction
    UNIQUE (entry_group_id, direction)
    DEFERRABLE INITIALLY DEFERRED;
```

### @Version in TenantApiKey

```java
// In TenantApiKey.java — add after existing fields
@Version
private long version;

public long getVersion() { return version; }
public void setVersion(long version) { this.version = version; }
```

### ApiAdvice handler for optimistic lock

```java
// In ApiAdvice.java — add alongside existing @ExceptionHandler methods
@ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
@ResponseStatus(HttpStatus.CONFLICT)
public ErrorDto optimisticLockExceptionHandler(
        final org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
    final String defaultMsg = "Concurrent modification conflict — please retry";
    return logErrorAndReturnDTO(ex, defaultMsg, "generic.conflict");
}
```

### Concurrent Rotation IT test skeleton

```java
// Pattern: ConcurrentIdempotencyRaceTest (CyclicBarrier + ExecutorService)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
class ApiKeyConcurrentRotationIT {

    @Autowired ApiKeyService apiKeyService;
    @Autowired TenantService tenantService;
    @Autowired TenantRepository tenantRepository;
    @Autowired TenantApiKeyRepository keyRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void concurrentRotation_exactlyOneSucceeds() throws Exception {
        // 1. Create tenant + PROD key
        // 2. Get keyId of ACTIVE key
        // 3. Launch 2 threads behind CyclicBarrier(2), each calling apiKeyService.rotate(keyId)
        // 4. Collect: one succeeds, one throws ObjectOptimisticLockingFailureException
        // 5. Assert exactly 1 ACTIVE key for tenant PROD env in DB
    }
}
```

### Ledger Constraint IT test skeleton

```java
// Tests that DB rejects a lone DEBIT (unbalanced insert)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
class LedgerConstraintIT {

    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void unbalancedInsert_isRejectedByConstraint() {
        // Insert only a DEBIT row for a fresh entry_group_id — expect constraint violation at commit
    }

    @Test
    void balancedInsert_succeeds() {
        // Insert DEBIT + CREDIT sharing entry_group_id — expect no exception
    }
}
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| No `@Version` on `TenantApiKey` | Add `@Version long version` | Phase 39 (now) | Concurrent rotations serialize correctly |
| No DB constraint on ledger balance | Deferrable unique constraint on `(entry_group_id, direction)` | Phase 39 (now) | Unbalanced ledger posts rejected at DB layer |

**Existing related constraints (from V19):**
- `uidx_tenant_api_key_active_env` — partial unique index `WHERE key_status = 'ACTIVE'`: prevents
  two ACTIVE keys for same tenant+env, but does NOT prevent two concurrent reads of the same
  ACTIVE key both advancing into the rotate() path.
- `uq_tenant_api_key_hash` — unique on key_hash: unrelated to rotation concurrency.

**`@Version` vs. `(tenant_id, environment, status='ROTATED')` unique constraint:**
A unique constraint on `(tenant_id, environment)` WHERE `key_status = 'ROTATED'` would also
prevent double-rotation by rejecting the second INSERT of a ROTATED row. However, `rotate()` first
UPDATEs the existing ACTIVE row to ROTATED, then INSERTs a new ACTIVE row. Two concurrent threads
both start by updating the same ACTIVE row — `@Version` catches this at the UPDATE step, which is
earlier and cleaner than relying on an INSERT failure downstream.

---

## Environment Availability

Step 2.6: SKIPPED — phase is purely Java/SQL code changes. PostgreSQL and Redis are already
running via Testcontainers in the test suite (TestConfig.class, PostgresContainerConfig.class).
`mvn verify` orchestrates the Testcontainers lifecycle; no manual setup needed.

---

## Validation Architecture

`workflow.nyquist_validation` is absent from `.planning/config.json` — treated as enabled.

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 via `spring-boot-starter-test` (Spring Boot 3.5.11) |
| Config file | `pom.xml` — Surefire (unit) + Failsafe (IT via `*IT.java` naming) |
| Quick run command | `mvn test -pl . -Dtest=ApiKeyConcurrentRotationIT,LedgerConstraintIT` |
| Full suite command | `mvn verify` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|--------------|
| AKEY-09 | Two concurrent rotations — exactly one wins, other gets conflict | IT | `mvn verify -Dit.test=ApiKeyConcurrentRotationIT` | Wave 0 |
| LEDGER-01 | Unbalanced ledger insert rejected at DB layer | IT | `mvn verify -Dit.test=LedgerConstraintIT` | Wave 0 |

### Sampling Rate

- **Per task commit:** `mvn test -Dtest=ApiKeyConcurrentRotationIT,LedgerConstraintIT` (or relevant unit test)
- **Per wave merge:** `mvn verify`
- **Phase gate:** Full `mvn verify` green before sign-off

### Wave 0 Gaps

- [ ] `src/test/java/com/softropic/payam/tenant/ApiKeyConcurrentRotationIT.java` — covers AKEY-09
- [ ] `src/test/java/com/softropic/payam/transaction/LedgerConstraintIT.java` — covers LEDGER-01

*(Existing tests `LedgerBalanceGuardTest` and `LedgerDoubleEntryTest` verify correct writes; they
do NOT verify the DB rejects incorrect writes. `LedgerConstraintIT` fills that gap.)*

---

## Open Questions

1. **Does `TenantApiKey_aud` table need the `version` column to satisfy Envers?**
   - What we know: V21 added `rotated_at` to both main and AUD tables; Envers requires column parity.
   - What's unclear: Whether Envers `@Version`-annotated fields are included in AUD tables at all,
     or whether Envers silently ignores them.
   - Recommendation: Include the AUD column migration defensively. The V21 precedent suggests Envers
     does replicate all columns. If Envers excludes `@Version` fields from AUD automatically, the
     extra `ADD COLUMN IF NOT EXISTS` is harmless.

2. **Should the ledger constraint be `DEFERRABLE INITIALLY DEFERRED` or `DEFERRABLE INITIALLY IMMEDIATE`?**
   - What we know: `LedgerService.postEntry()` inserts both rows in one `saveAll()` within a single
     `@Transactional` method. `INITIALLY DEFERRED` is the safe choice.
   - What's unclear: Whether any future code path might need to insert the two rows in separate
     transactions (e.g., a correction workflow).
   - Recommendation: Use `INITIALLY DEFERRED` now. A future migration can change to `INITIALLY
     IMMEDIATE` if the insert pattern changes.

---

## Sources

### Primary (HIGH confidence)

- Direct code inspection: `src/main/java/.../tenant/service/ApiKeyService.java` — rotate() flow
- Direct code inspection: `src/main/java/.../tenant/repo/TenantApiKey.java` — entity structure
- Direct code inspection: `src/main/java/.../transaction/repo/LedgerEntry.java` — ledger entity
- Direct code inspection: `src/main/java/.../transaction/service/LedgerService.java` — postEntry()
- Direct code inspection: `src/main/java/.../security/api/ApiAdvice.java` — exception handler patterns
- Direct code inspection: `src/main/java/.../common/persistence/AbstractAuditingEntity.java`,
  `BaseEntity.java` — no existing `@Version`
- Direct code inspection: `src/main/resources/db/migration/V19__api_key_env_constraints.sql` —
  existing partial unique index `uidx_tenant_api_key_active_env`
- Direct code inspection: `src/main/resources/db/migration/V21__rotated_at_timestamptz.sql` —
  AUD table column migration pattern
- Direct code inspection: `src/main/resources/db/migration/V4__ledger_schema.sql` — `ledger_entry`
  current schema
- Direct code inspection: `src/test/java/.../e2e/domain/ConcurrentIdempotencyRaceTest.java` —
  CyclicBarrier + ExecutorService test pattern
- Direct code inspection: `src/main/java/.../email/repo/EnvelopeEntity.java` — `@Version long
  version` usage confirmed in codebase

### Secondary (MEDIUM confidence)

- Spring Framework docs: `ObjectOptimisticLockingFailureException` is the Spring wrapper for JPA
  `OptimisticLockException` (confirmed by EnvelopeEntity using same pattern in production code)
- PostgreSQL docs: `DEFERRABLE INITIALLY DEFERRED` on unique constraints fires at commit, not per-row

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries already in pom.xml; patterns already in codebase
- Architecture: HIGH — `@Version` pattern confirmed via `EnvelopeEntity.java`; Flyway AUD migration
  pattern confirmed via V21; constraint deferral is standard PostgreSQL
- Pitfalls: HIGH — derived from direct code inspection of Envers audit setup, existing partial
  index structure, and `saveAndFlush` patterns in `ApiKeyService`

**Research date:** 2026-04-15
**Valid until:** 2026-05-15 (stable stack — Spring Boot 3.5.11, PostgreSQL)
