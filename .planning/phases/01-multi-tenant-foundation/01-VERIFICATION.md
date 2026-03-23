---
phase: 01-multi-tenant-foundation
verified: 2026-03-23T22:35:00Z
status: passed
score: 5/5 must-haves verified
re_verification:
  previous_status: gaps_found
  previous_score: 4/5
  gaps_closed:
    - "API keys can be rotated (old + new both valid during grace period) and revoked immediately — HTTP endpoints now exist"
  gaps_remaining: []
  regressions: []
---

# Phase 1: Multi-Tenant Foundation Verification Report

**Phase Goal:** Tenant schema, API key authentication, and per-client isolation that underpins every subsequent phase
**Verified:** 2026-03-23
**Status:** passed
**Re-verification:** Yes — after gap closure (plan 01-03 added rotate/revoke HTTP endpoints)

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Admin can create a new tenant and receive a scoped API key | VERIFIED | `POST /v1/admin/tenants` in `TenantAdminResource` calls `TenantService.createTenant()`, returns 201 with `TenantCreationResponse` containing `TenantDto` + `ApiKeyDto` with non-null `rawKey`. Tested in `TenantFilterChainIT.apiKeyChain_validKey_returns201()` and `TenantProvisioningIT.createTenant_persistsEntities()`. |
| 2 | Tenant API key authenticates via a dedicated @Order(1) filter chain, independent of JWT | VERIFIED | `TenantSecurityConfig` declares `@Order(1)` on `tenantApiKeyFilterChain`. `ApiKeyAuthenticationFilter` extracts `X-Api-Key`, calls `ApiKeyService.authenticate()`, sets `TenantContext` and `SecurityContextHolder`. JWT chain is unaffected. `TenantFilterChainIT` covers missing key (401), invalid key (401), valid key (201), JWT chain not affected. |
| 3 | One tenant cannot access another tenant's data — isolation enforced at query level | VERIFIED | `TenantApiKeyRepository.findAllByTenantId(tenantId)` requires explicit `tenantId`. `findValidKeyByHash` scopes to the matching hash only — cannot traverse tenant boundaries. `TenantProvisioningIT.tenantIsolation_cannotSeeOtherTenant()` asserts no key cross-contamination. DB-level FK (`tenant_id REFERENCES main.tenant(id)`) enforces referential boundary. |
| 4 | API keys can be rotated (old + new both valid during grace period) and revoked immediately — HTTP endpoints must exist | VERIFIED | `POST /v1/admin/tenants/{tenantId}/keys/{keyId}/rotate` (200 + new `ApiKeyDto` with `rawKey`) and `DELETE /v1/admin/tenants/{tenantId}/keys/{keyId}` (204) exist in `TenantAdminResource`, wired to `ApiKeyService.rotate(keyId)` and `ApiKeyService.revoke(keyId)`. `TenantAdminResourceIT` covers: rotate returns 200 with new non-null `rawKey` different from original, old key still valid during grace period; revoke returns 204 and key subsequently throws `BadCredentialsException`; unknown keyId returns 404 via `EntityNotFoundException` handler in `ApiAdvice`. |
| 5 | Idempotency keys are scoped to (tenantId, idempotencyKey) — cross-tenant collision is impossible | VERIFIED | V2 Flyway migration creates `CONSTRAINT uq_idempotency_tenant_key UNIQUE (tenant_id, idempotency_key)`. `TenantFilterChainIT.idempotencyKey_duplicateRejectedPerTenant_crossTenantAllowed()` asserts same key value under two tenants inserts two distinct rows, and duplicate within one tenant throws `DataIntegrityViolationException` naming the constraint. |

**Score:** 5/5 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/resources/db/migration/V1__tenant_schema.sql` | tenant + tenant_api_key tables | VERIFIED | Both tables with BIGINT PKs, correct FK, indexes on `key_hash` and `tenant_id`. |
| `src/main/resources/db/migration/V2__idempotency_key_schema.sql` | idempotency_key with composite UNIQUE | VERIFIED | 20 lines. `CONSTRAINT uq_idempotency_tenant_key UNIQUE (tenant_id, idempotency_key)` confirmed present. |
| `src/main/java/.../tenant/service/ApiKeyService.java` | generate/authenticate/rotate/revoke | VERIFIED | 81 lines. All four operations present: `generateAndStore`, `authenticate`, `rotate`, `revoke`. All `@Transactional`. |
| `src/main/java/.../tenant/api/TenantAdminResource.java` | POST create + POST rotate + DELETE revoke | VERIFIED | 79 lines. Three HTTP handlers: `createTenant` (POST /v1/admin/tenants, 201), `rotateKey` (POST /{tenantId}/keys/{keyId}/rotate, 200), `revokeKey` (DELETE /{tenantId}/keys/{keyId}, 204). `ApiKeyService` constructor-injected and called at lines 58 and 70. |
| `src/main/java/.../security/api/ApiAdvice.java` | EntityNotFoundException → 404 | VERIFIED | `@ExceptionHandler(EntityNotFoundException.class)` at line 348 returns `HttpStatus.NOT_FOUND`. Prevents unknown keyId from falling through to generic 500 handler. |
| `src/main/java/.../tenant/config/TenantSecurityConfig.java` | @Order(1) SecurityFilterChain | VERIFIED | 96 lines. `@Order(1)` confirmed at line 68 on `tenantApiKeyFilterChain`. |
| `src/main/java/.../tenant/config/ApiKeyAuthenticationFilter.java` | OncePerRequestFilter with X-Api-Key, TenantContext, try/finally | VERIFIED | 129 lines. No changes from plan-02 — unaffected by plan-03. |
| `src/test/java/.../tenant/TenantAdminResourceIT.java` | 3 HTTP-layer integration tests for rotate/revoke | VERIFIED | 169 lines. `rotateKey_returns200_withNewRawKey` (HTTP 200 + grace period check), `revokeKey_returns204_andKeyIsUnusable` (HTTP 204 + BadCredentialsException check), `rotateKey_unknownKeyId_returns404` (HttpClientErrorException.NotFound). All three tests assert HTTP status codes, response bodies, and downstream service-layer behavior. |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `TenantAdminResource.rotateKey()` | `ApiKeyService.rotate(keyId)` | Constructor injection | WIRED | Line 58: `ApiKeyService.ApiKeyAndRawKey result = apiKeyService.rotate(keyId)`. Result mapped to `ApiKeyDto` with new `rawKey`. |
| `TenantAdminResource.revokeKey()` | `ApiKeyService.revoke(keyId)` | Constructor injection | WIRED | Line 70: `apiKeyService.revoke(keyId)`. Returns void; controller returns 204. |
| `ApiKeyService.rotate()/revoke()` | `EntityNotFoundException` → `ApiAdvice` → 404 | Spring `@RestControllerAdvice` | WIRED | `ApiAdvice` line 348–353: `@ExceptionHandler(EntityNotFoundException.class)` returns `NOT_FOUND`. Verified by `rotateKey_unknownKeyId_returns404` test. |
| `TenantAdminResource` | `TenantService.createTenant()` | Constructor injection | WIRED | Unchanged from plan-01. |
| `ApiKeyAuthenticationFilter` | `ApiKeyService.authenticate()` | Constructor injection in `TenantSecurityConfig` | WIRED | Unchanged from plan-02. |
| V2 migration | `idempotency_key` table UNIQUE constraint | Flyway auto-discovery | WIRED | Unchanged from plan-01. |

### Requirements Coverage

| Requirement | Status | Blocking Issue |
|-------------|--------|----------------|
| TENANT-01: Tenant schema, API key auth, per-client isolation, rotate/revoke HTTP endpoints | SATISFIED | None — all sub-requirements verified end-to-end. |

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| None found | - | - | - | No TODO/FIXME, placeholder, empty return, or stub patterns detected in any plan-03 modified file. |

### Human Verification Required

No items require human testing. All behaviors are deterministic and verified through code inspection and integration test evidence. The three `TenantAdminResourceIT` tests exercise the full HTTP path including grace-period behavior and error semantics.

### Gaps Summary

No gaps. All five must-haves are verified.

The single gap from initial verification — rotate and revoke operations had no HTTP exposure — is closed:

- `TenantAdminResource` now has two additional handlers: `POST /{tenantId}/keys/{keyId}/rotate` (returns 200 + new `ApiKeyDto`) and `DELETE /{tenantId}/keys/{keyId}` (returns 204).
- `ApiKeyService` is constructor-injected as a second argument alongside `TenantService`.
- `ApiAdvice` gained an `EntityNotFoundException` handler to return 404 instead of 500 for unknown key IDs.
- `TenantAdminResourceIT` provides three integration tests covering the HTTP layer: rotate success with grace-period assertion, revoke success with rejection assertion, and unknown keyId 404.

All other must-haves from the initial verification remain intact — file sizes, `@Order(1)` annotation, composite UNIQUE constraint, and filter chain wiring are all unchanged.

---

_Verified: 2026-03-23_
_Verifier: Claude (gsd-verifier)_
