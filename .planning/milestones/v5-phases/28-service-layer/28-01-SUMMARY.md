---
phase: 28-service-layer
plan: "01"
subsystem: tenant
tags: [service-layer, tenant-lifecycle, api-key, envers, audit, flyway]
dependency_graph:
  requires: [27-schema-and-enum-migration]
  provides: [tenant-service-layer, api-key-guards, envers-audit-tables]
  affects: [tenant-api, admin-tenant-management]
tech_stack:
  added: []
  patterns: [bulk-jpql-update, envers-audit-ddl-flyway, entity-not-found-helper]
key_files:
  created:
    - src/main/resources/db/migration/V20__envers_audit_tables.sql
  modified:
    - src/main/java/com/softropic/payam/tenant/repo/TenantApiKeyRepository.java
    - src/main/java/com/softropic/payam/tenant/service/ApiKeyService.java
    - src/main/java/com/softropic/payam/tenant/service/TenantService.java
    - src/main/resources/application.yaml
    - src/main/resources/application-dev.yaml
    - src/main/resources/application-uat.yaml
decisions:
  - "V20 Flyway DDL creates Envers tables explicitly (ddl-auto=none means Envers cannot auto-create them)"
  - "org.hibernate.envers.default_schema=main ensures Envers finds tables in correct schema"
  - "revokeAllActiveAndRotatedByTenantId uses @Modifying JPQL bulk update (single query, not N saves)"
  - "rotate() pre-revokes ROTATED key with saveAndFlush to ensure ordering before new ACTIVE key inserted"
  - "reactivate() delegates new PROD key generation to ApiKeyService.generateAndStore() (no duplication)"
metrics:
  duration: 11 min
  completed: 2026-04-06
  tasks: 3
  files: 6
---

# Phase 28 Plan 01: Service Layer Summary

**One-liner:** Flyway V20 Envers audit DDL, TenantService full lifecycle (update/suspend/reactivate/webhookSecret), and ApiKeyService guards (AKEY-02 duplicate-active, AKEY-08 revoke-prior-rotated).

## Tasks Completed

| Task | Description | Commit | Files |
|------|-------------|--------|-------|
| 1 | Flyway V20 Envers audit tables + Envers schema config | eea9fe9 | V20 migration, application*.yaml |
| 2 | Repository queries + ApiKeyService guards (AKEY-02, AKEY-08) | ae1d022 | TenantApiKeyRepository.java, ApiKeyService.java |
| 3 | TenantService lifecycle methods (TENT-01..08, WSEC-01, WSEC-03) | 893ae6b | TenantService.java |

## What Was Built

### Flyway V20 (Task 1)
Created `V20__envers_audit_tables.sql` with:
- `main.revinfo_seq` sequence (START 1, INCREMENT 50 — matches Envers default allocation)
- `main.revinfo` table (rev, revtstmp)
- `main.tenant_aud` shadow table — all Tenant columns + revtype + rev FK
- `main.tenant_api_key_aud` shadow table — all TenantApiKey columns + revtype + rev FK
- Added `org.hibernate.envers.default_schema: main` to `application.yaml`, `application-dev.yaml`, `application-uat.yaml`

### Repository Queries (Task 2)
Added to `TenantApiKeyRepository`:
- `revokeAllActiveAndRotatedByTenantId(Long tenantId)` — `@Modifying` bulk JPQL UPDATE for TENT-07 suspend
- `findRotatedKeyByTenantIdAndEnvironment(Long tenantId, ApiKeyEnvironment)` — AKEY-08 pre-rotation check
- `findActiveKeyByTenantIdAndEnvironment(Long tenantId, ApiKeyEnvironment)` — AKEY-02 duplicate guard

### ApiKeyService Guards (Task 2)
- `generateAndStore()`: Added AKEY-02 guard — throws `IllegalStateException` if an ACTIVE key already exists for the same (tenant, environment) pair
- `rotate()`: Added AKEY-08 pre-revoke — revokes any existing ROTATED key for same (tenant, environment) before creating new ROTATED+ACTIVE pair; uses `saveAndFlush` to ensure ordering

### TenantService Lifecycle (Task 3)
- `createTenant()`: Now sets `webhookSecret(UUID.randomUUID().toString())` (WSEC-01)
- `findTenantOrThrow(String tenantRef)`: Private helper with `EntityNotFoundException` on miss
- `updateName(tenantRef, name)`: TENT-02
- `updateEmail(tenantRef, email)`: TENT-03
- `updateWebhookUrl(tenantRef, webhookUrl)`: TENT-04
- `suspend(tenantRef)`: Sets SUSPENDED + bulk-revokes all ACTIVE+ROTATED keys in one transaction (TENT-07)
- `reactivate(tenantRef)`: Sets ACTIVE + delegates new PROD key to `ApiKeyService.generateAndStore()` (TENT-08)
- `regenerateWebhookSecret(tenantRef)`: Replaces webhook secret and returns new UUID value (WSEC-03)
- Constructor updated to accept `TenantApiKeyRepository keyRepository`

## Decisions Made

| Decision | Rationale |
|----------|-----------|
| V20 explicit DDL for Envers tables | `hibernate.ddl-auto=none` prevents Envers auto-creation; Flyway V20 must own the DDL |
| `org.hibernate.envers.default_schema: main` in all 3 profiles | Without this, Envers looks in the default schema, not `main`, causing table-not-found at boot |
| `@Modifying` bulk JPQL for suspend | Single query atomically revokes all active/rotated keys rather than N individual saves |
| `saveAndFlush` on prior ROTATED key in rotate() | Ensures REVOKED status is durable before new ACTIVE key is inserted, preventing overlapping grace periods |
| `reactivate()` delegates to `generateAndStore()` | Avoids code duplication; inherits AKEY-02 guard automatically |

## Deviations from Plan

None — plan executed exactly as written.

## Known Stubs

None — all new methods persist real data and return real values.

## Verification Results

All 6 existing `TenantProvisioningIT` tests pass after all 3 tasks (including with V20 Flyway migration, AKEY-02 guard, and updated TenantService constructor).

## Self-Check: PASSED
