---
phase: 27-schema-and-enum-migration
verified: 2026-04-03T00:00:00Z
status: passed
score: 5/5 must-haves verified
re_verification: false
---

# Phase 27: Schema and Enum Migration — Verification Report

**Phase Goal:** The entity model and database constraints correctly represent the v5 tenant/key specification — v1 defects corrected, environment enum migrated, partial unique index in place.
**Verified:** 2026-04-03
**Status:** passed
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | `Tenant` entity has a non-nullable `keyPrefix` column (`updatable = false`) that stores the 3-char uppercase prefix derived from the tenant name at creation time | ✓ VERIFIED | `Tenant.java` L54-55: `@Column(name = "key_prefix", nullable = false, updatable = false, length = 4)` + `private String keyPrefix`; no `setKeyPrefix()` method; `TenantService.deriveKeyPrefix()` sets it at creation via builder |
| 2 | `TenantApiKey.environment` maps to `ApiKeyEnvironment` enum with values `PROD`, `DEV`, `SANDBOX` — the legacy `LIVE` value no longer exists in DB or code | ✓ VERIFIED | `TenantApiKey.java` L48-51: `@Enumerated(EnumType.STRING)` on `ApiKeyEnvironment environment = ApiKeyEnvironment.PROD`; `ApiKeyEnvironment.java`: `enum ApiKeyEnvironment { PROD, DEV, SANDBOX }`; zero `"LIVE"` occurrences in `src/` (grep confirmed) |
| 3 | A partial unique index `(tenant_id, environment) WHERE key_status = 'ACTIVE'` exists in the database and is enforced by Flyway migration | ✓ VERIFIED | `V19__api_key_env_constraints.sql` L13-15: `CREATE UNIQUE INDEX IF NOT EXISTS uidx_tenant_api_key_active_env ON main.tenant_api_key (tenant_id, environment) WHERE key_status = 'ACTIVE'`; confirmed present and correctly placed after the LIVE→PROD UPDATE |
| 4 | A UNIQUE constraint on `key_hash` exists on the `tenant_api_key` table | ✓ VERIFIED | `V19__api_key_env_constraints.sql` L18-19: `ADD CONSTRAINT uq_tenant_api_key_hash UNIQUE (key_hash)` |
| 5 | Flyway runs cleanly from a fresh schema with no `UPDATE before CHECK` ordering errors | ✓ VERIFIED | V19 line 2: UPDATE (LIVE→PROD) is the first statement; CHECK constraint added at line 9 — correct ordering preserved; `mvn compile` exits 0; 27-02-SUMMARY confirms `mvn test` ran 234 tests, 0 failures, 0 errors via Testcontainers |

**Score:** 5/5 truths verified

---

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/resources/db/migration/V18__tenant_v5_fields.sql` | key_prefix and email columns on tenant table with backfill | ✓ VERIFIED | Adds `key_prefix VARCHAR(4) NOT NULL DEFAULT 'UNK'` and `email VARCHAR(255)`; UPDATE backfills existing rows using CASE/WHEN/UPPER/SUBSTRING logic |
| `src/main/resources/db/migration/V19__api_key_env_constraints.sql` | LIVE-to-PROD migration, CHECK constraint, partial unique index, key_hash UNIQUE | ✓ VERIFIED | All 5 steps present; UPDATE first, then default change, CHECK constraint, partial unique index `uidx_tenant_api_key_active_env`, UNIQUE on `key_hash` |
| `src/main/java/com/softropic/payam/tenant/contract/ApiKeyEnvironment.java` | Enum with PROD, DEV, SANDBOX values | ✓ VERIFIED | `public enum ApiKeyEnvironment { PROD, DEV, SANDBOX }` — exactly 3 values, no LIVE |
| `src/main/java/com/softropic/payam/tenant/repo/Tenant.java` | keyPrefix and email fields | ✓ VERIFIED | `key_prefix` column (`nullable = false, updatable = false, length = 4`), `email` column; `getKeyPrefix()` getter only (no setter — correctly immutable) |
| `src/main/java/com/softropic/payam/tenant/repo/TenantApiKey.java` | Typed environment field using ApiKeyEnvironment enum | ✓ VERIFIED | `@Enumerated(EnumType.STRING)` + `private ApiKeyEnvironment environment = ApiKeyEnvironment.PROD`; getter/setter typed as `ApiKeyEnvironment` |
| `src/test/java/com/softropic/payam/e2e/builder/TenantBuilder.java` | Updated default environment from LIVE to PROD with ApiKeyEnvironment type | ✓ VERIFIED | `private ApiKeyEnvironment environment = ApiKeyEnvironment.PROD`; `withEnvironment(ApiKeyEnvironment)` signature |
| `src/test/java/com/softropic/payam/e2e/builder/ApiKeyBuilder.java` | Updated default environment from LIVE to PROD | ✓ VERIFIED | `private String environment = "PROD"` |

---

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `ApiKeyService.java` | `Tenant.java` | `tenant.getKeyPrefix()` in `generateAndStore()` | ✓ WIRED | L37: `String prefix = tenant.getKeyPrefix();` — confirmed; no `rawKey.substring()` present |
| `TenantService.java` | `Tenant.java` | `.keyPrefix(deriveKeyPrefix(name))` in builder | ✓ WIRED | L39: `.keyPrefix(deriveKeyPrefix(name))` in `Tenant.builder()` chain; `deriveKeyPrefix()` static method at L27-33 |
| `TenantApiKey.java` | `ApiKeyEnvironment.java` | `@Enumerated(EnumType.STRING)` on environment field | ✓ WIRED | L4 import + L48-51 `@Enumerated(EnumType.STRING)` on `ApiKeyEnvironment environment` |
| `TenantBuilder.java` | `TenantService.java` | `createTenant()` call with `ApiKeyEnvironment` parameter | ✓ WIRED | L63: `tenantService.createTenant(name, environment)` with `environment` typed as `ApiKeyEnvironment` |

---

### Data-Flow Trace (Level 4)

Not applicable — this phase is schema/entity/service layer. No components render dynamic UI data. The data flow is entity persistence (entity → DB via JPA) which is validated by the test suite running against Testcontainers PostgreSQL (234 tests passing).

---

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Production code compiles | `mvn compile -q` | Exit 0 (no output) | ✓ PASS |
| No "LIVE" enum value in production Java sources | `grep -r '"LIVE"' src/main/java` | No matches | ✓ PASS |
| V19 UPDATE precedes CHECK constraint | `grep -n "UPDATE\|ADD CONSTRAINT chk" V19…sql` | UPDATE on line 2, CHECK on line 9 | ✓ PASS |
| Full test suite passes (per SUMMARY) | `mvn test -Dspring.profiles.active=dev` | 234 tests, 0 failures, 0 errors (Testcontainers, commit 66d52d8) | ✓ PASS (documented in 27-02-SUMMARY) |

---

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| AKEY-01 | 27-01, 27-02 | API keys follow `PREFIX_UUID` format; prefix = first 3 chars of tenant name (uppercase, 0-padded), immutable | ✓ SATISFIED | `TenantService.deriveKeyPrefix()` derives 3-char prefix; `Tenant.keyPrefix` is `updatable=false` with no setter; `ApiKeyService` uses `tenant.getKeyPrefix()` for key prefix |
| AKEY-03 | 27-01, 27-02 | At most one ACTIVE key per environment per tenant — enforced by DB-level partial unique index | ✓ SATISFIED | `V19`: `CREATE UNIQUE INDEX uidx_tenant_api_key_active_env ON main.tenant_api_key (tenant_id, environment) WHERE key_status = 'ACTIVE'`; `ApiKeyService.rotate()` uses `saveAndFlush()` to prevent constraint violation |

---

### Anti-Patterns Found

None identified. Specific checks performed:

- No TODO/FIXME/placeholder comments in modified files
- No stub `return null` / `return {}` / `return []` in service code
- No `"LIVE"` string in any Java source file (production or test)
- `ApiKeyService.rotate()` correctly uses `saveAndFlush(old)` before inserting a new ACTIVE key — preventing partial unique index violation from Hibernate flush ordering

---

### Human Verification Required

None. All success criteria are verifiable programmatically:

- SQL migration content is readable and ordering is confirmed
- Entity annotations are visible in source
- Compile success is confirmed
- Enum values are confirmed
- "LIVE" absence is confirmed by grep

The full integration test suite passing (234 tests via Testcontainers PostgreSQL, as documented in 27-02-SUMMARY and evidenced by commit 66d52d8) provides behavioral confidence that Flyway migrations V18 and V19 run cleanly on a fresh schema.

---

### Gaps Summary

No gaps. All 5 success criteria are satisfied:

1. `Tenant.keyPrefix` is non-nullable, `updatable = false`, no setter, derived by `deriveKeyPrefix()` at creation time.
2. `TenantApiKey.environment` is typed as `ApiKeyEnvironment { PROD, DEV, SANDBOX }` — `LIVE` is gone from both code and migration logic.
3. Partial unique index `uidx_tenant_api_key_active_env (tenant_id, environment) WHERE key_status = 'ACTIVE'` is present in V19 in correct position (after LIVE→PROD UPDATE).
4. UNIQUE constraint `uq_tenant_api_key_hash` on `key_hash` is present in V19.
5. V19 migration has UPDATE before CHECK constraint — no ordering error possible; confirmed by test suite running 234 tests cleanly on Testcontainers.

---

_Verified: 2026-04-03_
_Verifier: Claude (gsd-verifier)_
