---
plan: 27-01
phase: 27-schema-and-enum-migration
status: complete
completed: 2026-04-03
---

# Plan 27-01 Summary: Flyway Migrations + Entity Model + Services

## What Was Built

Two Flyway migrations and all Java entity/service updates required for the v5 schema:

**Task 1 — Flyway V18 and V19 migrations:**
- `V18__tenant_v5_fields.sql`: Adds `key_prefix` (NOT NULL, DEFAULT 'UNK', backfill from name) and `email` columns to `tenant` table
- `V19__api_key_env_constraints.sql`: Migrates `LIVE` → `PROD` first (ordering critical), then adds CHECK constraint, partial unique index `uidx_tenant_api_key_active_env (tenant_id, environment) WHERE key_status = 'ACTIVE'`, and UNIQUE constraint on `key_hash`

**Task 2 — Entity model + enum + service fixes:**
- `ApiKeyEnvironment.java`: New enum `{ PROD, DEV, SANDBOX }` replacing freeform String
- `Tenant.java`: Added `keyPrefix` (non-nullable, immutable, `updatable=false`) and `email` fields
- `TenantApiKey.java`: `environment` field typed as `ApiKeyEnvironment` with `@Enumerated(EnumType.STRING)`
- `ApiKeyDto.java`: `environment` field changed from `String` to `ApiKeyEnvironment`
- `ApiKeyService.java`: Prefix now derived from `tenant.getKeyPrefix()` (not `rawKey.substring(0, 8)`)
- `TenantService.java`: Added `deriveKeyPrefix()` helper, sets `keyPrefix` on Tenant at creation
- `TenantAdminResource.java`: Validation regex updated to `PROD|DEV|SANDBOX`, parses to enum via `ApiKeyEnvironment.valueOf()`

## Key Files

- `src/main/resources/db/migration/V18__tenant_v5_fields.sql`
- `src/main/resources/db/migration/V19__api_key_env_constraints.sql`
- `src/main/java/com/softropic/payam/tenant/contract/ApiKeyEnvironment.java`
- `src/main/java/com/softropic/payam/tenant/repo/Tenant.java`
- `src/main/java/com/softropic/payam/tenant/repo/TenantApiKey.java`
- `src/main/java/com/softropic/payam/tenant/contract/ApiKeyDto.java`
- `src/main/java/com/softropic/payam/tenant/service/ApiKeyService.java`
- `src/main/java/com/softropic/payam/tenant/service/TenantService.java`
- `src/main/java/com/softropic/payam/tenant/api/TenantAdminResource.java`

## Verification

- `mvn compile` exits 0 — all production code compiles cleanly
- No `"LIVE"` string in any production Java file under `src/main/`
- V19 UPDATE precedes ADD CONSTRAINT (ordering verified)
- Partial unique index `uidx_tenant_api_key_active_env` in V19
- `ApiKeyEnvironment` enum has exactly 3 values: PROD, DEV, SANDBOX

## Deviations

None — plan executed as specified.
