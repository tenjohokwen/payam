---
phase: 28-service-layer
plan: "02"
subsystem: tenant
tags: [testing, tenant-lifecycle, api-key, envers, audit, integration-test]
dependency_graph:
  requires: [28-01]
  provides: [tenant-service-integration-tests, tenant-audit-integration-tests]
  affects: []
tech_stack:
  added: []
  patterns: [spring-integration-test-with-security-context, envers-audit-jdbc-verification, teardown-audit-tables]
key_files:
  created:
    - src/test/java/com/softropic/payam/tenant/TenantServiceIT.java
    - src/test/java/com/softropic/payam/tenant/TenantAuditIT.java
  modified:
    - src/test/java/com/softropic/payam/tenant/TenantProvisioningIT.java
decisions:
  - "TenantServiceIT tearDown deletes audit tables (tenant_api_key_aud, tenant_aud, revinfo) before main tables to avoid FK constraint errors"
  - "TenantAuditIT uses @BeforeEach SecurityContextHolder setup so AuditingEntityListener sees admin@test.com as createdBy"
  - "SecurityContextHolder.clearContext() in @AfterEach to prevent security context bleed between tests"
  - "Envers audit rows are committed by service @Transactional methods; jdbcTemplate reads committed rows in test without @Transactional annotation needed"
  - "AKEY-08 test asserts exactly 3 keys (1 ACTIVE, 1 ROTATED, 1 REVOKED) after two rotations"
requirements_completed: [TENT-01, TENT-02, TENT-03, TENT-04, TENT-07, TENT-08, AKEY-02, AKEY-08, WSEC-01, WSEC-03, AUDIT-01, AUDIT-02, AUDIT-03]
metrics:
  duration: 8 min
  completed: 2026-04-06
  tasks: 2
  files: 3
---

# Phase 28 Plan 02: Service Layer Integration Tests Summary

**18 integration tests proving TenantService lifecycle, ApiKeyService guards, and Envers audit trail all work correctly against a live PostgreSQL dev database.**

## Performance

- **Duration:** 8 min
- **Started:** 2026-04-06T17:02:26Z
- **Completed:** 2026-04-06T17:10:15Z
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments

- TenantServiceIT: 9 tests covering TENT-02/03/04/07/08, AKEY-02/08, WSEC-01/03 — all lifecycle operations and key guards verified
- TenantAuditIT: 3 tests verifying Envers audit rows in `main.tenant_aud` and `main.tenant_api_key_aud`, including admin identity capture
- TenantProvisioningIT: webhookSecret UUID assertion added to `createTenant_persistsEntities` (WSEC-01)

## Task Commits

Each task was committed atomically:

1. **Task 1: TenantServiceIT — lifecycle + key guard tests** - `8f33fe4` (feat)
2. **Task 2: TenantAuditIT — Envers audit trail tests + TenantProvisioningIT webhookSecret assertion** - `1b6f7e6` (feat)

## Files Created/Modified

- `src/test/java/com/softropic/payam/tenant/TenantServiceIT.java` — 9 integration tests for TenantService and ApiKeyService
- `src/test/java/com/softropic/payam/tenant/TenantAuditIT.java` — 3 integration tests for Hibernate Envers audit trail
- `src/test/java/com/softropic/payam/tenant/TenantProvisioningIT.java` — added webhookSecret UUID regex assertion

## Decisions Made

| Decision | Rationale |
|----------|-----------|
| tearDown deletes audit tables before main tables | FK constraint: `revinfo.rev` is referenced by `tenant_aud.rev` and `tenant_api_key_aud.rev`; must delete dependents first |
| `@BeforeEach` sets Spring SecurityContext to `admin@test.com` | `AuditingEntityListener` reads `SpringSecurityAuditorAware` for `createdBy`; test must set auth before service calls |
| `SecurityContextHolder.clearContext()` in `@AfterEach` | Prevents authentication set in one test from leaking into subsequent tests sharing the same Spring context |
| No `@Transactional` on test class | Each `@Transactional` service call commits its own transaction; Envers writes audit rows on commit. If test itself were transactional, the DB transaction would roll back and no audit rows would exist for verification |
| AKEY-08 test performs two rotations, asserts 3 keys total | After 2 rotations: initial key is REVOKED (was ROTATED, revoked by AKEY-08), first-rotation key is ROTATED (in grace), second-rotation key is ACTIVE |

## Deviations from Plan

None — plan executed exactly as written.

## Known Stubs

None — all tests operate against live data and assert real persisted values.

## Verification Results

```
Tests run: 9, Failures: 0, Errors: 0  -- TenantServiceIT
Tests run: 6, Failures: 0, Errors: 0  -- TenantProvisioningIT
Tests run: 3, Failures: 0, Errors: 0  -- TenantAuditIT
Total: 18 tests, BUILD SUCCESS
```

## Self-Check: PASSED
