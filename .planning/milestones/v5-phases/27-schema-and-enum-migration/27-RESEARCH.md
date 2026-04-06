# Phase 27: Schema and Enum Migration - Research

**Researched:** 2026-04-02
**Domain:** JPA entity model, Flyway SQL migration, PostgreSQL partial unique index, Java enum migration
**Confidence:** HIGH (all findings from direct codebase inspection and verified patterns in this project)

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| AKEY-01 | API keys follow format `PREFIX_UUID` where prefix is the first 3 chars of the tenant name at creation (uppercase, 0-padded to 3 chars), immutable even if tenant name changes | Requires: new `key_prefix` column on `Tenant` entity; Flyway migration backfill; `ApiKeyService.generateAndStore()` reads from `tenant.getKeyPrefix()` not `tenant.getName()` |
| AKEY-03 | At most one ACTIVE key per environment at any time — enforced by database-level partial unique index | Requires: Flyway partial unique index `(tenant_id, environment) WHERE key_status = 'ACTIVE'`; new `ApiKeyEnvironment` enum replacing VARCHAR "LIVE" |

</phase_requirements>

---

## Summary

Phase 27 corrects three v1 schema defects and establishes database-level constraints that later phases depend on. All work is foundational — no application logic is added, only entity model corrections and schema migrations. The three changes are: (1) add `key_prefix` to the `Tenant` entity (the immutable, tenant-name-derived prefix), (2) migrate the `environment` column from freeform VARCHAR ("LIVE") to a typed `ApiKeyEnvironment` enum (`PROD`, `DEV`, `SANDBOX`), and (3) add two database constraints — a partial unique index enforcing one ACTIVE key per tenant+environment, and a UNIQUE constraint on `key_hash`.

The v1 implementation stored `key_prefix` only on `TenantApiKey` and derived it from the first 8 characters of the randomly-generated raw key. The v5 requirement defines a completely different concept: a 3-char, tenant-name-derived prefix that is set once at tenant creation and never changes. Adding `key_prefix` to the `Tenant` entity is the anchor for this immutability guarantee. The per-key `keyPrefix` field on `TenantApiKey` remains as a denormalised copy for fast auth-path lookup but must be populated from `Tenant.keyPrefix`, not re-derived.

The environment enum migration is the most operationally sensitive change. Existing rows have `environment = 'LIVE'`. The Flyway migration must run `UPDATE ... SET environment = 'PROD' WHERE environment = 'LIVE'` before adding the CHECK constraint — this ordering is enforced by committing to a single SQL migration file. All tests that pass `"LIVE"` as the environment string (approximately 30 call sites in the test suite) must be updated to `"PROD"` in the same phase.

**Primary recommendation:** Implement Phase 27 as two Flyway migration files (V18 and V19) covering distinct concerns, update the `Tenant` and `TenantApiKey` entities, introduce `ApiKeyEnvironment` enum, and update all "LIVE" call sites in tests and production code in the same phase.

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Boot Data JPA | 3.5.11 (project standard) | Entity model, repositories | Already in use throughout project |
| Hibernate ORM | Managed by Spring Boot 3.5.11 | JPA provider | Project standard; `ddl-auto: none` so migrations are Flyway-only |
| Flyway Core | Managed by Spring Boot 3.5.11 | Versioned SQL migrations | Already configured; `flyway.enabled=true`, `defaultSchema=main` |
| flyway-database-postgresql | Managed by Spring Boot 3.5.11 | PostgreSQL-specific Flyway support | Already in pom.xml |
| PostgreSQL 14.18 | Test via Testcontainers | Database for integration tests | `TestConfig.java` uses `postgres:14.18` container |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Hibernate Envers | Managed by Spring Boot 3.5.11 | Audit revision capture | Both `Tenant` and `TenantApiKey` are `@Audited` — changes propagate automatically |
| hypersistence-utils-hibernate-63 | 3.9.10 (in pom.xml) | `@Tsid` ID generation | Already used in `BaseEntity` — no change needed |
| Lombok | Project standard | Boilerplate elimination | Builder/constructor annotations on entities |

**Installation:** No new dependencies. All required libraries are already in pom.xml.

---

## Architecture Patterns

### Current State (what exists in v1)

```
Tenant (main.tenant)
├── id                  BIGINT (TSID)
├── tenant_ref          VARCHAR(36) UNIQUE NOT NULL
├── name                VARCHAR(255) NOT NULL
├── tenant_status       VARCHAR(20) DEFAULT 'ACTIVE'
├── status              VARCHAR(20) DEFAULT 'INACTIVE'   ← from AbstractAuditingEntity
├── webhook_url         VARCHAR(2048)                     ← added by V8
├── webhook_secret      VARCHAR(255)                      ← added by V8
└── [audit columns]     created_by, created_date, last_modified_by, last_modified_date, request_id, session_id

TenantApiKey (main.tenant_api_key)
├── id                  BIGINT (TSID)
├── tenant_id           BIGINT NOT NULL → tenant(id)
├── key_hash            VARCHAR(64) NOT NULL              ← indexed, NOT unique (defect)
├── key_prefix          VARCHAR(8) NOT NULL               ← currently stores first 8 chars of raw key (v1 defect)
├── key_status          VARCHAR(20) DEFAULT 'ACTIVE'
├── environment         VARCHAR(10) DEFAULT 'LIVE'        ← freeform, no constraint (defect)
├── rotated_at          TIMESTAMP
└── [audit columns]

Java:
- TenantApiKey.environment: String, default "LIVE"
- No ApiKeyEnvironment enum exists
- Tenant entity has no keyPrefix field
- TenantAdminResource validation: regexp = "LIVE|SANDBOX"
```

### Target State (after Phase 27)

```
Tenant (main.tenant) — V18 adds:
├── key_prefix          VARCHAR(4) NOT NULL DEFAULT 'UNK'  ← new; immutable tenant-level prefix
└── email               VARCHAR(255)                        ← new; optional notification address

TenantApiKey (main.tenant_api_key) — V19 modifies:
├── environment         VARCHAR(10) → constrained to PROD/DEV/SANDBOX via CHECK
│                       existing LIVE rows → PROD via UPDATE
├── [partial unique index]  (tenant_id, environment) WHERE key_status = 'ACTIVE'
└── key_hash            [UNIQUE constraint added]

Java:
- new ApiKeyEnvironment enum: PROD, DEV, SANDBOX
- TenantApiKey.environment: String → ApiKeyEnvironment (with @Enumerated(EnumType.STRING))
- Tenant entity: add keyPrefix field (@Column nullable=false, updatable=false)
- Tenant entity: add email field (@Column nullable=true)
```

### Pattern 1: Flyway Incremental Migration (project convention)

Every schema change uses a new `V{n}__description.sql` file. The project currently uses V1–V17. Phase 27 adds V18 and V19.

**V18 — Tenant v5 fields:**
```sql
-- Source: project convention from V8__tenant_webhook_url.sql pattern
ALTER TABLE main.tenant
    ADD COLUMN IF NOT EXISTS key_prefix VARCHAR(4) NOT NULL DEFAULT 'UNK',
    ADD COLUMN IF NOT EXISTS email      VARCHAR(255);

COMMENT ON COLUMN main.tenant.key_prefix IS 'Immutable 3-char prefix (uppercase, 0-padded) derived from tenant name at creation time';
COMMENT ON COLUMN main.tenant.email      IS 'Tenant notification email — optional; used for lifecycle event emails';
```

**V19 — TenantApiKey environment enum migration and constraints:**
```sql
-- Step 1: migrate existing LIVE rows to PROD (MUST precede CHECK constraint)
UPDATE main.tenant_api_key SET environment = 'PROD' WHERE environment = 'LIVE';

-- Step 2: update column default so future rows default to PROD
ALTER TABLE main.tenant_api_key ALTER COLUMN environment SET DEFAULT 'PROD';

-- Step 3: add CHECK constraint (must come after UPDATE — this is the ordering invariant)
ALTER TABLE main.tenant_api_key
    ADD CONSTRAINT chk_api_key_environment
    CHECK (environment IN ('PROD', 'DEV', 'SANDBOX'));

-- Step 4: partial unique index — one ACTIVE key per tenant+environment
CREATE UNIQUE INDEX IF NOT EXISTS uidx_tenant_api_key_active_env
    ON main.tenant_api_key (tenant_id, environment)
    WHERE key_status = 'ACTIVE';

-- Step 5: unique constraint on key_hash (defense-in-depth)
ALTER TABLE main.tenant_api_key
    ADD CONSTRAINT uq_tenant_api_key_hash UNIQUE (key_hash);
```

### Pattern 2: JPA Entity Column Additions (project convention)

**Tenant entity additions:**
```java
// Source: direct inspection of Tenant.java and TenantApiKey.java
@Column(name = "key_prefix", nullable = false, updatable = false, length = 4)
private String keyPrefix;

@Column(name = "email")
private String email;
```

**TenantApiKey entity change** (String → enum):
```java
// Before (v1 defect):
@Column(name = "environment", nullable = false, length = 10)
@Builder.Default
private String environment = "LIVE";

// After (v5):
@Enumerated(EnumType.STRING)
@Column(name = "environment", nullable = false, length = 10)
@Builder.Default
private ApiKeyEnvironment environment = ApiKeyEnvironment.PROD;
```

**New ApiKeyEnvironment enum:**
```java
// Location: tenant/contract/ApiKeyEnvironment.java
package com.softropic.payam.tenant.contract;

public enum ApiKeyEnvironment { PROD, DEV, SANDBOX }
```

### Pattern 3: Prefix Derivation Rule

The `key_prefix` stored on `Tenant` is derived from `name` at creation time. The derivation is a pure function (fully testable without Spring context):

```
name == null or blank  →  "UNK"
name.length() >= 3     →  name.substring(0, 3).toUpperCase()
name.length() == 2     →  name.substring(0, 2).toUpperCase() + "0"
name.length() == 1     →  name.substring(0, 1).toUpperCase() + "00"
```

This function must live in a static helper or in `TenantService`, not in the entity constructor. The entity only stores the result. The `ApiKeyService.generateAndStore()` reads `tenant.getKeyPrefix()` to build the full key format `PREFIX_UUID` — it NEVER calls `tenant.getName()`.

### Anti-Patterns to Avoid

- **Do not derive prefix in generateAndStore from tenant.getName():** Name is mutable; prefix must be immutable. Always read `tenant.getKeyPrefix()`.
- **Do not add the CHECK constraint before the UPDATE:** Flyway migration fails with a constraint violation if existing `LIVE` rows are present when the CHECK is created.
- **Do not use `IF NOT EXISTS` on the CHECK constraint:** PostgreSQL does not support `IF NOT EXISTS` for `ADD CONSTRAINT CHECK`. Omit it; use `ADD CONSTRAINT IF NOT EXISTS` only for indexes.
- **Do not rely on `ddl-auto: validate` as a substitute for migration:** The project uses `ddl-auto: none` — Hibernate will not validate or auto-create columns. All schema changes must be in Flyway.
- **Do not rename the `environment` column:** The Java entity field name changes from `String` to `ApiKeyEnvironment`, but the DB column name stays `environment`. Only the type changes.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Partial unique index | Custom service-layer check + SELECT before INSERT | PostgreSQL partial unique index `WHERE key_status = 'ACTIVE'` | Service checks are vulnerable to TOCTOU races; DB constraint is atomic |
| UNIQUE key_hash enforcement | Catch duplicates in service code | PostgreSQL `UNIQUE` constraint | Let the DB enforce at insert time |
| Prefix derivation | Complex regex/string manipulation | Simple substring + toUpperCase (3 cases) | The spec is deterministic and simple — just implement it directly as a static method |
| Schema version management | Manual SQL execution order | Flyway V18, V19 files | Already configured; ordering is guaranteed by version number |

**Key insight:** The two constraints (partial unique index, UNIQUE on key_hash) are deliberately at the database level. Any service-layer enforcement is secondary. The DB is the last line of defence against concurrent bugs.

---

## Common Pitfalls

### Pitfall 1: UPDATE before CHECK ordering failure
**What goes wrong:** Flyway migration fails with `ERROR: check constraint "chk_api_key_environment" of relation "tenant_api_key" is violated by some row` if the CHECK is added before the existing `LIVE` rows are updated.
**Why it happens:** V1 schema seeded `environment = 'LIVE'`. Existing integration test data or the dev DB will have these rows.
**How to avoid:** The UPDATE must be the first statement in V19. Never reorder these statements. The `TestConfig` Testcontainers-based test setup runs Flyway from a clean schema on every test run — this also catches the ordering bug.
**Warning signs:** Flyway migration fails in CI with a constraint violation message.

### Pitfall 2: `IllegalArgumentException: No enum constant ApiKeyEnvironment.LIVE` at runtime
**What goes wrong:** After the entity field is changed from `String` to `ApiKeyEnvironment`, any row with `environment = 'LIVE'` that Hibernate loads will throw this exception. This can happen if: (a) V19 runs but the UPDATE fails silently, (b) tests bypass Flyway and insert rows directly with `"LIVE"`, or (c) a test still calls `tenantService.createTenant(name, "LIVE")` after the enum is in place.
**Why it happens:** Hibernate's `@Enumerated(EnumType.STRING)` maps the DB string to the Java enum by name. `LIVE` is not a valid `ApiKeyEnvironment` name.
**How to avoid:** Update all test call sites from `"LIVE"` to `"PROD"` in the same phase. There are approximately 30 occurrences across the test suite (grepped above). Also update `TenantBuilder.environment` default field from `"LIVE"` to `"PROD"`.
**Warning signs:** `IllegalArgumentException` in any test that creates a tenant.

### Pitfall 3: `CreateTenantRequest` validation regex not updated
**What goes wrong:** `TenantAdminResource.CreateTenantRequest` has `@Pattern(regexp = "LIVE|SANDBOX")`. After the enum migration, `"LIVE"` is no longer valid and `"PROD"` and `"DEV"` are not accepted. The regex must be updated to `PROD|DEV|SANDBOX` in the same phase.
**Why it happens:** The validation annotation is in the REST layer, separate from the entity layer — it's easy to miss in a pure schema migration phase.
**How to avoid:** Update `@Pattern(regexp = "LIVE|SANDBOX")` to `@Pattern(regexp = "PROD|DEV|SANDBOX")` in `TenantAdminResource` as part of Phase 27.
**Warning signs:** `createTenant` endpoint returns 400 for `environment: "PROD"`.

### Pitfall 4: `TenantApiKey.keyPrefix` still derives from raw key bytes
**What goes wrong:** After adding `Tenant.keyPrefix`, if `ApiKeyService.generateAndStore()` still computes `prefix = rawKey.substring(0, 8)` (the v1 logic), the per-key `keyPrefix` field will contain random base64 characters, not the tenant-name-derived prefix. The `Tenant.keyPrefix` column will exist and be correct, but the `TenantApiKey.keyPrefix` will be wrong. Phase 28 will then use the wrong `keyPrefix` for key format construction.
**Why it happens:** The entity field `Tenant.keyPrefix` is new, but `ApiKeyService` is not modified in this phase. Phase 27 only adds the column and the entity field; Phase 28 updates the service logic. However, Phase 27 must ensure `generateAndStore` reads from `tenant.getKeyPrefix()` or the tests will pass with incorrect prefix values.
**How to avoid:** Update `ApiKeyService.generateAndStore()` as part of Phase 27 to read from `tenant.getKeyPrefix()` for the per-key `keyPrefix` field. This is a one-line change but is critical for correctness.
**Warning signs:** A key generated for tenant "Acme Corp" does not start with `ACM_`.

### Pitfall 5: Backfill of existing tenant.key_prefix rows
**What goes wrong:** V18 adds `key_prefix VARCHAR(4) NOT NULL DEFAULT 'UNK'`. Existing tenant rows will have `key_prefix = 'UNK'` after migration — which is correct for dev/test environments where names are known. However, if the dev DB has real tenants with actual names and the backfill is not performed, those tenants will permanently have prefix `UNK`.
**Why it happens:** The DEFAULT 'UNK' is a placeholder to satisfy NOT NULL for existing rows. A production deployment would need a backfill UPDATE deriving prefix from `name`.
**How to avoid:** For this project (not yet in production), the dev/test DB state is controlled by TestDataCleaner and Testcontainers — `UNK` default is acceptable. However, V18 should include a backfill UPDATE so any existing rows get correct values:
```sql
UPDATE main.tenant SET key_prefix =
    CASE WHEN LENGTH(TRIM(name)) = 0 THEN 'UNK'
         WHEN LENGTH(TRIM(name)) = 1 THEN UPPER(SUBSTRING(TRIM(name), 1, 1)) || '00'
         WHEN LENGTH(TRIM(name)) = 2 THEN UPPER(SUBSTRING(TRIM(name), 1, 2)) || '0'
         ELSE UPPER(SUBSTRING(TRIM(name), 1, 3))
    END
WHERE key_prefix = 'UNK';
```
**Warning signs:** Existing tenant rows have `key_prefix = 'UNK'` after migration.

---

## Code Examples

### Enum declaration (new file)
```java
// Source: project convention — tenant/contract/ApiKeyStatus.java is the model
// Location: src/main/java/com/softropic/payam/tenant/contract/ApiKeyEnvironment.java
package com.softropic.payam.tenant.contract;

public enum ApiKeyEnvironment { PROD, DEV, SANDBOX }
```

### Tenant entity additions
```java
// Source: project convention — Tenant.java existing pattern for column additions
@Column(name = "key_prefix", nullable = false, updatable = false, length = 4)
private String keyPrefix;

@Column(name = "email")
private String email;

// Getters and setters (no setter for keyPrefix — updatable=false enforced by JPA,
// but omitting the setter provides an additional compile-time signal):
public String getKeyPrefix() { return keyPrefix; }
// No setKeyPrefix() — keyPrefix is set only at creation via builder
public String getEmail() { return email; }
public void setEmail(String email) { this.email = email; }
```

### TenantApiKey environment field change
```java
// Before:
@Column(name = "environment", nullable = false, length = 10)
@Builder.Default
private String environment = "LIVE";

// After:
@Enumerated(EnumType.STRING)
@Column(name = "environment", nullable = false, length = 10)
@Builder.Default
private ApiKeyEnvironment environment = ApiKeyEnvironment.PROD;

// Getter/setter type update:
public ApiKeyEnvironment getEnvironment() { return environment; }
public void setEnvironment(ApiKeyEnvironment environment) { this.environment = environment; }
```

### ApiKeyService.generateAndStore — prefix source
```java
// Phase 27 responsibility: change prefix source from raw key to tenant.keyPrefix
// Before (v1):
String prefix = rawKey.substring(0, 8);

// After (Phase 27):
String prefix = tenant.getKeyPrefix();  // tenant-level immutable prefix
// Note: the full key format PREFIX_UUID is constructed in Phase 28.
// Phase 27 only ensures the source is correct.
```

### Partial unique index (V19 SQL)
```sql
-- PostgreSQL partial unique index — enforces one ACTIVE key per tenant+environment
-- Source: PostgreSQL documentation on partial indexes
CREATE UNIQUE INDEX IF NOT EXISTS uidx_tenant_api_key_active_env
    ON main.tenant_api_key (tenant_id, environment)
    WHERE key_status = 'ACTIVE';
```

### Validation annotation update
```java
// TenantAdminResource.CreateTenantRequest — before:
@Pattern(regexp = "LIVE|SANDBOX", message = "environment must be LIVE or SANDBOX") String environment

// After:
@Pattern(regexp = "PROD|DEV|SANDBOX", message = "environment must be PROD, DEV, or SANDBOX") String environment
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `environment = 'LIVE'` (v1 freeform string) | `environment` typed enum `PROD/DEV/SANDBOX` | Phase 27 | Hibernate enum mapping; existing rows need UPDATE migration |
| `key_prefix` on TenantApiKey only (8 random chars) | `key_prefix` on Tenant (3-char name-derived, immutable) | Phase 27 | Prefix semantics change from random to name-derived |
| No per-environment uniqueness enforcement | Partial unique index `(tenant_id, environment) WHERE key_status = 'ACTIVE'` | Phase 27 | DB-level TOCTOU protection |
| `key_hash` with plain index (not unique) | `UNIQUE` constraint on `key_hash` | Phase 27 | Defense-in-depth against hash duplicates |

**Deprecated/outdated after Phase 27:**
- `"LIVE"` string anywhere in code: replaced by `ApiKeyEnvironment.PROD`
- `rawKey.substring(0, 8)` in `ApiKeyService.generateAndStore()`: replaced by `tenant.getKeyPrefix()`
- `@Pattern(regexp = "LIVE|SANDBOX")` in `TenantAdminResource`: replaced by `PROD|DEV|SANDBOX`
- `TenantBuilder.environment` default `"LIVE"`: replaced by `"PROD"`

---

## Open Questions

1. **V18 backfill: should `key_prefix` be required for tenant rows inserted before Phase 28 service logic?**
   - What we know: V18 adds `key_prefix VARCHAR(4) NOT NULL DEFAULT 'UNK'`. Existing rows get `UNK`. The backfill UPDATE in V18 should derive correct prefixes from names.
   - What's unclear: Whether any existing dev DB tenants have names that are unusual (blank, single-char) and need the edge-case handling.
   - Recommendation: Include the backfill UPDATE in V18 as shown in Pitfall 5. It is safe to run even on an empty table.

2. **ApiKeyDto.environment field type: String or ApiKeyEnvironment?**
   - What we know: `ApiKeyDto` currently has `String environment`. After Phase 27, `TenantApiKey.environment` is `ApiKeyEnvironment`. The DTO is used in `TenantAdminResource.rotateKey()` which calls `result.entity().getEnvironment()`.
   - What's unclear: Whether the DTO should also become typed or remain String for JSON compatibility.
   - Recommendation: Change `ApiKeyDto.environment` to `ApiKeyEnvironment` in Phase 27. Jackson serializes enums as their name by default — `"PROD"`, `"DEV"`, `"SANDBOX"` are valid JSON strings.

---

## Environment Availability

Step 2.6: SKIPPED (no external dependencies beyond the project's existing PostgreSQL + Testcontainers setup, which is already verified by the test infrastructure)

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test + Testcontainers |
| Config file | `TestConfig.java` (PostgreSQL 14.18 container via `@ServiceConnection`) |
| Quick run command | `mvn test -pl . -Dtest=TenantProvisioningIT -Dspring.profiles.active=dev` |
| Full suite command | `mvn test -Dspring.profiles.active=dev` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| AKEY-01 | Key prefix is 3-char tenant-name-derived, immutable even after name update | Integration | `mvn test -Dtest=TenantProvisioningIT -Dspring.profiles.active=dev` | Existing file — new test method needed |
| AKEY-01 | Key prefix edge cases: 1-char name → "X00", 2-char name → "XY0", blank name → "UNK" | Unit | Static method unit test | New test file needed |
| AKEY-03 | At most one ACTIVE key per environment — DB constraint enforced | Integration | `mvn test -Dtest=TenantProvisioningIT -Dspring.profiles.active=dev` | Existing file — new test method needed |
| AKEY-03 | Flyway V19 runs cleanly on a DB with existing LIVE rows | Integration | Spring context load in any `@SpringBootTest` | Covered by existing Testcontainers setup |

### Sampling Rate
- **Per task commit:** `mvn test -Dtest=TenantProvisioningIT -Dspring.profiles.active=dev`
- **Per wave merge:** `mvn test -Dspring.profiles.active=dev`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `TenantProvisioningIT` — new test methods: `keyPrefix_derivedFromName`, `keyPrefix_immutableAfterNameUpdate`, `oneActiveKeyPerEnv_constraintEnforced`
- [ ] New unit test class `KeyPrefixDerivationTest` — covers the static prefix derivation function for all edge cases (1-char, 2-char, 3-char, blank, null names)

---

## Runtime State Inventory

> Included because Phase 27 renames/migrates the `environment` column value from `LIVE` to `PROD`.

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | `main.tenant_api_key.environment` column: all existing rows have value `'LIVE'` | Data migration: `UPDATE main.tenant_api_key SET environment = 'PROD' WHERE environment = 'LIVE'` in V19 (before CHECK constraint) |
| Stored data | `main.tenant.key_prefix` column: does not yet exist; will be added with `DEFAULT 'UNK'` | Backfill UPDATE in V18 to derive correct prefix from existing tenant names |
| Live service config | None — no external service stores environment values | None |
| OS-registered state | None | None |
| Secrets/env vars | None affected by this migration | None |
| Build artifacts | None — no compiled enum class exists yet for `ApiKeyEnvironment` | None |

**Test call sites referencing `"LIVE"` (approximately 30 occurrences):** These are in test Java files, not stored data. They are code edits, not data migrations. Must all change to `"PROD"` in Phase 27.

Files containing `"LIVE"` string that must be updated:
- `TenantProvisioningIT.java` (~8 occurrences)
- `TenantFilterChainIT.java` (~10 occurrences)
- `TenantAdminResourceIT.java` (~3 occurrences)
- `TenantBuilder.java` (1 occurrence — default field `environment = "LIVE"`)
- `WebhookDoubleCheckIT.java` (1 occurrence)
- `WebhookDeliveryIT.java` (1 occurrence)
- `IdempotencyServiceIT.java`, `LedgerServiceIT.java`, `PaymentEventLogIT.java`, `TransactionStateMachineIT.java`, `FeeEngineIT.java`, `FraudEngineIT.java`, `PaymentOrchestratorIT.java`, `MtnMoMoPortIT.java` (~1 each)

---

## Sources

### Primary (HIGH confidence)
- Direct inspection: `Tenant.java`, `TenantApiKey.java`, `ApiKeyService.java`, `TenantService.java`, `TenantAdminResource.java`, `TenantApiKeyRepository.java` — current state of all affected entities
- Direct inspection: `V1__tenant_schema.sql`, `V8__tenant_webhook_url.sql`, `V17__platform_config_schema.sql` — existing migration patterns and current schema
- Direct inspection: `.planning/research/ARCHITECTURE.md`, `.planning/research/PITFALLS.md` — v5 milestone research (April 2, 2026) with full gap analysis
- Direct inspection: `TenantProvisioningIT.java`, `TenantBuilder.java` — test infrastructure and call site inventory
- Direct inspection: `pom.xml` — Spring Boot 3.5.11, Flyway, Testcontainers versions
- Direct inspection: `application.yaml` — `ddl-auto: none`, Flyway configuration, schema settings

### Secondary (MEDIUM confidence)
- PostgreSQL partial unique index syntax verified against project's existing migration patterns (`V12`, `V13` use similar index creation)
- `@Enumerated(EnumType.STRING)` behavior from Spring Data JPA / Hibernate docs (well-established, stable behavior since JPA 2.0)

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries already in use, no new dependencies
- Architecture: HIGH — based on direct code inspection of all affected files
- Pitfalls: HIGH — identified by direct inspection of existing v1 defects and confirmed by PITFALLS.md research document
- Migration ordering: HIGH — PostgreSQL constraint behavior is well-documented

**Research date:** 2026-04-02
**Valid until:** 2026-05-02 (stable domain; no fast-moving dependencies)
