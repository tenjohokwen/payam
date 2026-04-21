# Phase 41: PIN Schema & Encryption Config - Research

**Researched:** 2026-04-17
**Domain:** Flyway schema migration, JPA entity column addition, Spring Boot @ConfigurationProperties extension
**Confidence:** HIGH

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| PIN-01 | Flyway migration adds nullable `pin` VARCHAR column to `main.platform_config`; plaintext PIN never persists | V24 migration follows ADD COLUMN IF NOT EXISTS pattern from V22; nullable column is safe on existing rows |
| PIN-02 | `PayamPlatformProperties` exposes `pinEncryptionSecret` field bound to `payam.platform.pin-encryption-secret`, mapped to `PLATFORM_PIN_ENCRYPTION_SECRET` env var | `PayamPlatformProperties` is a simple mutable POJO with one existing field; adding a field is a three-line change |
</phase_requirements>

---

## Summary

Phase 41 is a pure infrastructure setup phase — no business logic, no REST changes, no frontend work. It lays the database column and configuration property that every subsequent PIN phase depends on.

The `main.platform_config` table was created by V17 and has two live rows (ORANGE, MTN). The next available migration version is V24. Adding a nullable VARCHAR column to a table with existing rows is the safest possible DDL operation in PostgreSQL — it completes instantly with no row rewrites and cannot violate existing data. `ADD COLUMN IF NOT EXISTS` is the project idiom (V22).

`PayamPlatformProperties` is a lightweight `@ConfigurationProperties(prefix = "payam.platform")` POJO currently holding one field (`notificationEmail`). Adding `pinEncryptionSecret` follows an identical pattern. The `PlatformConfig` @Configuration class already calls `@EnableConfigurationProperties(PayamPlatformProperties.class)`, so no registration changes are needed. The property must bind from `payam.platform.pin-encryption-secret`, which maps to env var `PLATFORM_PIN_ENCRYPTION_SECRET` via Spring Boot's relaxed binding (`_` maps to `-` in kebab-case keys).

The `PlatformConfig` entity (at `platform/repo/PlatformConfig.java`) needs a `pin` field annotated `@Column(name = "pin")` — nullable by default (no `nullable = false`), no `@NotNull`. The entity uses `@SuperBuilder`, `@Getter`, `@NoArgsConstructor`, `@AllArgsConstructor` from Lombok. Phase 42 will add `updatePin()`, but Phase 41 only needs the field declared so Hibernate can map it.

**Primary recommendation:** Write V24 migration (ADD COLUMN), add `pinEncryptionSecret` to `PayamPlatformProperties`, add `pin` field to the `PlatformConfig` entity. Three surgical edits, zero new classes.

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Flyway | Managed by Spring Boot 3.5.11 BOM | Schema migration | Already in use — all DDL goes here |
| jasypt | 1.9.3 (explicit in pom.xml) | AES256 encryption via `Cryptopher` | Already in codebase; `Cryptopher` wraps `AES256TextEncryptor` |
| Spring Boot `@ConfigurationProperties` | 3.5.11 | Binding `payam.platform.*` YAML block | Already used by `PayamPlatformProperties` |
| JPA / Hibernate | Managed by Spring Boot BOM | Entity column mapping | All entities use this pattern |

### No new dependencies required
This phase introduces no new libraries. All building blocks already exist.

---

## Architecture Patterns

### Existing File Locations (reference for edits)

```
src/main/
├── resources/db/migration/
│   ├── V23__ledger_group_constraint.sql   ← last migration; V24 goes here
│   └── V24__platform_config_pin.sql       ← NEW
├── java/com/softropic/payam/platform/
│   ├── config/
│   │   ├── PayamPlatformProperties.java   ← ADD pinEncryptionSecret field
│   │   └── PlatformConfig.java            ← @EnableConfigurationProperties — no change
│   └── repo/
│       └── PlatformConfig.java            ← ADD pin @Column field
src/main/resources/
├── application.yaml                       ← ADD payam.platform.pin-encryption-secret entry
```

### Pattern 1: ADD COLUMN IF NOT EXISTS migration (V22 precedent)

**What:** ALTER TABLE to add a nullable column on a live table with existing rows.
**When to use:** All schema additions to tables that already have data.
**Example:**
```sql
-- V24: Add nullable pin column for AES256-encrypted provider PIN (PIN-01)
ALTER TABLE main.platform_config
    ADD COLUMN IF NOT EXISTS pin VARCHAR(500);

COMMENT ON COLUMN main.platform_config.pin IS
    'AES256-encrypted PIN for this provider; NULL when no PIN has been set; never stores plaintext';
```

Notes on the `pin` column type:
- AES256TextEncryptor (jasypt) produces Base64-encoded ciphertext. A 4–8 character PIN encrypted with jasypt typically produces ~60–100 characters of Base64. VARCHAR(500) is safe with room to spare.
- Nullable — no default value. The column is `NULL` until a PIN is explicitly set.
- `IF NOT EXISTS` is idiomatic in this project (used in V17 `CREATE TABLE IF NOT EXISTS`).
- No Envers AUD table pairing needed — `PlatformConfig` does NOT opt out of `@Audited` at the entity level (it inherits `@Audited` from `AbstractAuditingEntity`). However, REQUIREMENTS.md explicitly states "PIN rotation history / audit trail — out of scope; Envers not needed for PlatformConfig." This creates a tension: the entity is already `@Audited` through the superclass, so the AUD table (`platform_config_aud`) already exists and mirrors the base table's schema. Adding a column to the base table **does** require adding the matching column to the AUD table to prevent Envers from throwing on the next config write. This is the same pattern as V22 (pairing `tenant_api_key` with `tenant_api_key_aud`).

**Revised V24 migration (correct pattern):**
```sql
-- V24: Add nullable pin column for AES256-encrypted provider PIN (PIN-01)
ALTER TABLE main.platform_config
    ADD COLUMN IF NOT EXISTS pin VARCHAR(500);

-- Envers AUD table must mirror the base schema (V22 pattern).
-- AUD column is nullable — Envers does not always populate every column on every revision.
ALTER TABLE main.platform_config_aud
    ADD COLUMN IF NOT EXISTS pin VARCHAR(500);

COMMENT ON COLUMN main.platform_config.pin IS
    'AES256-encrypted PIN for this provider; NULL when no PIN has been set; never stores plaintext';
```

**Verification:** Check whether `platform_config_aud` already exists by looking at V20 migration.

### Pattern 2: @ConfigurationProperties field addition

**What:** Adding a new field to an existing properties class.
**Example:**
```java
// Source: existing PayamPlatformProperties.java pattern
@ConfigurationProperties(prefix = "payam.platform")
public class PayamPlatformProperties {

    private String notificationEmail;

    /**
     * AES256 encryption secret for provider PINs.
     * Bound from {@code payam.platform.pin-encryption-secret}.
     * Set via {@code PLATFORM_PIN_ENCRYPTION_SECRET} environment variable.
     */
    private String pinEncryptionSecret;

    // getter + setter for each field
    public String getPinEncryptionSecret() { return pinEncryptionSecret; }
    public void setPinEncryptionSecret(String pinEncryptionSecret) {
        this.pinEncryptionSecret = pinEncryptionSecret;
    }
}
```

YAML entry to add to `application.yaml` under the existing `payam.platform:` block:
```yaml
payam:
  platform:
    notification-email: ${PLATFORM_NOTIFICATION_EMAIL:tenjoh_okwen@yahoo.com}
    pin-encryption-secret: ${PLATFORM_PIN_ENCRYPTION_SECRET:}
```

The empty default (`${PLATFORM_PIN_ENCRYPTION_SECRET:}`) means the property resolves to empty string when the env var is absent — tests can override it via `@TestPropertySource`. Phase 42 will validate that the secret is non-blank before constructing a `Cryptopher`; Phase 41 just wires the property.

### Pattern 3: JPA entity column field addition

**What:** Adding a nullable mapped column to an existing entity.
**Example:**
```java
// In platform/repo/PlatformConfig.java — add after platformMsisdn field
/**
 * AES256-encrypted PIN for this provider.
 * NULL when no PIN has been set. Phase 42 populates this field.
 * The field holds ciphertext — never plaintext.
 */
@Column(name = "pin")
private String pin;

public String getPin() { return pin; }

public void updatePin(String encryptedPin) {
    this.pin = encryptedPin;
}
```

Wait — the success criteria for Phase 41 says "the field holds ciphertext (never plaintext) when a PIN has been set." The `updatePin` mutation method is used in Phase 42, but declaring the getter in Phase 41 is fine. However, the method is harmless to add now. Decision: add field + getter only in Phase 41 (matching minimal-scope principle); Phase 42 adds `updatePin`. Or add both now since they are trivial. Either is fine; follow the minimal-scope principle — Phase 41 adds field + getter only.

### Anti-Patterns to Avoid

- **NOT NULL column on live table:** Adding a non-nullable column without a DEFAULT will fail in PostgreSQL if the table has existing rows. The `pin` column must be nullable (or have a default), and it must be nullable by design (many providers won't have a PIN set initially). This is already the correct design.
- **Skipping the AUD table mirror:** If `PlatformConfig` inherits `@Audited` (it does via `AbstractAuditingEntity`), then the `platform_config_aud` table already exists. Failing to add `pin` to the AUD table causes Envers to throw `org.hibernate.tool.schema.spi.SchemaManagementException` or a column-not-found error at runtime when any `PlatformConfig` row is written. This is the most likely migration failure mode for this phase.
- **Using `NOT NULL` on the AUD column:** AUD columns are always nullable — Envers may not record every column on every revision.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| AES256 encryption | Custom crypto code | `Cryptopher` (already exists) | Wraps jasypt `AES256TextEncryptor` correctly; tested |
| Secret binding | Custom `@Value` parsing | Spring Boot `@ConfigurationProperties` relaxed binding | Handles `PLATFORM_PIN_ENCRYPTION_SECRET` → `pin-encryption-secret` automatically |
| Schema migration | Manual DDL at startup | Flyway V24 | All migrations are Flyway; Hibernate DDL-auto is `none` |

---

## Common Pitfalls

### Pitfall 1: Missing AUD table column
**What goes wrong:** Flyway V24 adds `pin` to `platform_config` but not to `platform_config_aud`. The first write to any `PlatformConfig` entity after migration causes Envers to fail with a column-not-found exception (or a silent schema mismatch, depending on Envers version).
**Why it happens:** Developers focus on the base table and forget that `@Audited` entities have shadow AUD tables with identical column sets.
**How to avoid:** Always pair base-table ADD COLUMN with the matching AUD-table ADD COLUMN (V22 pattern). Confirm `platform_config_aud` exists by reading V20 migration before writing V24.
**Warning signs:** `mvn verify` passes migration step but fails on integration tests that write PlatformConfig rows.

### Pitfall 2: VARCHAR too short for jasypt ciphertext
**What goes wrong:** `pin` column declared as `VARCHAR(50)` or `VARCHAR(100)` — jasypt ciphertext for even a 4-character plaintext with AES256 can be 80–120 characters depending on the salt and padding.
**Why it happens:** Developers size the column for the plaintext length, not the ciphertext length.
**How to avoid:** Use `VARCHAR(500)` — safe upper bound that accommodates jasypt Base64 output with room for future key rotation strategies.

### Pitfall 3: Binding gap — env var not reaching tests
**What goes wrong:** `PLATFORM_PIN_ENCRYPTION_SECRET` is not set in the test environment, causing `PayamPlatformProperties.pinEncryptionSecret` to be null/blank. Phase 42 will fail when it tries to construct `Cryptopher`. Phase 41 itself won't fail (it only wires the property), but the test design must account for this.
**Why it happens:** The env var exists only in production; the YAML default resolves to empty string.
**How to avoid:** In Phase 41, verify the YAML default is `${PLATFORM_PIN_ENCRYPTION_SECRET:}` (empty default, not missing). Integration tests in Phase 42 will override via `@TestPropertySource(properties = "payam.platform.pin-encryption-secret=test-secret")`.

### Pitfall 4: Envers @Audited inheritance
**What goes wrong:** Developer assumes `PlatformConfig` is NOT audited because the `@Audited` annotation is not visible on the class directly.
**Why it happens:** `@Audited` is on `AbstractAuditingEntity`, which `PlatformConfig` extends — the annotation is inherited.
**How to avoid:** Check the superclass chain, not just the target class. `AbstractAuditingEntity` carries `@Audited` explicitly.

---

## Runtime State Inventory

> Phase 41 is not a rename/refactor/migration of existing strings — it adds a new column and new property. Full runtime state inventory is not applicable. The narrow check below confirms no existing runtime state conflicts.

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | `main.platform_config`: 2 rows (ORANGE, MTN) with no `pin` column yet | Migration adds nullable column — existing rows unaffected |
| Stored data | `main.platform_config_aud`: mirror table exists (inferred from @Audited inheritance + V20 migration) | Migration must also add nullable `pin` column here |
| Live service config | None — no external service references the pin column before Phase 42 | None |
| OS-registered state | None | None |
| Secrets/env vars | `PLATFORM_PIN_ENCRYPTION_SECRET` — new env var, does not yet exist in any config | Add to YAML with empty default; document for ops |
| Build artifacts | None | None |

---

## Environment Availability

> This phase is code/config/DDL only. External tools needed: PostgreSQL (for Flyway migration) and Maven (for `mvn verify`).

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| PostgreSQL | Flyway migration, integration tests | via Testcontainers (postgres:14.18) | 14.18 (test container) | — |
| Maven | `mvn verify` | project standard | present | — |
| jasypt | Cryptopher (transitive) | 1.9.3 in pom.xml | 1.9.3 | — |

No missing dependencies.

---

## Validation Architecture

> `workflow.nyquist_validation` is absent from `.planning/config.json` — treated as enabled.

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test + Testcontainers |
| Config file | `src/test/resources/application.properties` |
| Quick run command | `mvn test -pl . -Dtest=PlatformConfigServiceTest` |
| Full suite command | `mvn verify` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| PIN-01 | V24 migration runs on a database with existing `platform_config` rows without error | Integration (Flyway) | `mvn verify` (Flyway runs on Testcontainer boot) | ❌ Wave 0 — no dedicated migration IT, but all IT tests exercise Flyway via Testcontainers |
| PIN-01 | `PlatformConfig` entity loads with new `pin` field (null) for existing rows | Integration | `mvn verify` | ❌ Wave 0 — new assertion in existing or new IT |
| PIN-02 | `PayamPlatformProperties.getPinEncryptionSecret()` returns the bound value | Unit | `mvn test -Dtest=PayamPlatformPropertiesTest` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `mvn test -Dtest=PlatformConfigServiceTest` (existing unit test, < 10s)
- **Per wave merge:** `mvn verify`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `src/test/java/com/softropic/payam/platform/config/PayamPlatformPropertiesTest.java` — covers PIN-02 (property binding via `@SpringBootTest` or `@ConfigurationPropertiesTest`)
- [ ] Migration correctness assertion (can be added to an existing IT as a `@Sql` check, or verified implicitly by `mvn verify` — Flyway fails fast on errors)

Note: PIN-01 is largely validated implicitly — if `mvn verify` passes, Flyway ran the migration on the Testcontainer without error. A dedicated LightweightIT that reads `platform_config` after migration and asserts `pin IS NULL` for both rows would make this explicit, but is optional given the simplicity of the DDL.

---

## Code Examples

### V24 Migration (complete)
```sql
-- V24: Add nullable AES256-encrypted PIN column to platform_config (PIN-01)
-- Safe on existing rows: nullable column with no default, instant DDL in PostgreSQL.

ALTER TABLE main.platform_config
    ADD COLUMN IF NOT EXISTS pin VARCHAR(500);

-- Mirror in Envers AUD table — PlatformConfig inherits @Audited from AbstractAuditingEntity.
-- AUD column is always nullable (V22 pattern).
ALTER TABLE main.platform_config_aud
    ADD COLUMN IF NOT EXISTS pin VARCHAR(500);

COMMENT ON COLUMN main.platform_config.pin IS
    'AES256-encrypted PIN for this provider; NULL when no PIN has been set; ciphertext only — never plaintext';
```

### PayamPlatformProperties addition
```java
// Add to existing PayamPlatformProperties (PIN-02)
// Bound from payam.platform.pin-encryption-secret
// Set via PLATFORM_PIN_ENCRYPTION_SECRET environment variable

/** AES256 encryption secret for provider PINs. */
private String pinEncryptionSecret;

public String getPinEncryptionSecret() {
    return pinEncryptionSecret;
}

public void setPinEncryptionSecret(String pinEncryptionSecret) {
    this.pinEncryptionSecret = pinEncryptionSecret;
}
```

### YAML addition (application.yaml)
```yaml
payam:
  platform:
    notification-email: ${PLATFORM_NOTIFICATION_EMAIL:tenjoh_okwen@yahoo.com}
    pin-encryption-secret: ${PLATFORM_PIN_ENCRYPTION_SECRET:}
```

### PlatformConfig entity field (platform/repo/PlatformConfig.java)
```java
// Add after platformMsisdn field
/**
 * AES256-encrypted PIN for this provider.
 * NULL when no PIN has been set.
 * Holds ciphertext only — never plaintext.
 * Populated by Phase 42 updatePin().
 */
@Column(name = "pin")
private String pin;

public String getPin() {
    return pin;
}
```

---

## Key Dependency: Confirm platform_config_aud Exists

Before writing V24, confirm `platform_config_aud` was created by V20:
- File: `src/main/resources/db/migration/V20__envers_audit_tables.sql`
- If `platform_config_aud` is listed, pair the ADD COLUMN.
- If it is NOT listed (Envers may auto-create AUD tables at startup rather than via migration), then adding the column to the AUD table in V24 may fail with "table does not exist." In that case, omit the AUD table DDL from V24 and instead verify the Envers auto-schema behavior.

This is the single open question for the planner to resolve by reading V20 before writing the migration.

---

## Open Questions

1. **Does V20 explicitly CREATE `platform_config_aud`, or does Envers auto-create it?**
   - What we know: V20 is named `envers_audit_tables.sql` — it likely creates AUD tables explicitly. The V22 precedent (pairing `tenant_api_key_aud`) confirms the project creates AUD tables via Flyway.
   - What's unclear: Whether `platform_config` was included in V20 (it was created in V17, which predates V20).
   - Recommendation: Read V20 first. If `platform_config_aud` is present, include the AUD table ADD COLUMN in V24. If it is absent, check whether Envers has `ddl-auto` set to allow it to create the table — given `hibernate.ddl-auto: none`, it probably cannot. If the AUD table doesn't exist at all, the entity is not effectively audited and no AUD column is needed.

---

## Sources

### Primary (HIGH confidence)
- Direct codebase inspection: `PayamPlatformProperties.java`, `PlatformConfig` entity, `Cryptopher.java`, `V17__platform_config_schema.sql`, `V22__api_key_version.sql`, `V23__ledger_group_constraint.sql`, `application.yaml`
- `AbstractAuditingEntity.java` — confirms `@Audited` on superclass

### Secondary (MEDIUM confidence)
- jasypt 1.9.3 `AES256TextEncryptor` ciphertext size: Base64-encoded AES-256/CBC output for short plaintext is ~80–120 chars; VARCHAR(500) is well within bounds.

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries already in use, versions verified from pom.xml
- Architecture: HIGH — patterns directly observed in V22, V23, and existing `PayamPlatformProperties`
- Pitfalls: HIGH — AUD table pairing is a verified project pattern (V22); VARCHAR sizing is established jasypt behavior
- Open question: MEDIUM — V20 content not yet read; planner should read it before writing V24

**Research date:** 2026-04-17
**Valid until:** Stable — no fast-moving dependencies
