---
phase: 01-multi-tenant-foundation
plan: "03"
subsystem: api
tags: [spring-boot, rest, api-key, tenant, integration-test]

# Dependency graph
requires:
  - phase: 01-01
    provides: ApiKeyService.rotate() and ApiKeyService.revoke() — service-layer implementation and TenantAdminResource class
  - phase: 01-02
    provides: API key filter chain protecting /v1/admin/** with X-Api-Key header authentication

provides:
  - POST /v1/admin/tenants/{tenantId}/keys/{keyId}/rotate — HTTP endpoint returning 200 with new ApiKeyDto
  - DELETE /v1/admin/tenants/{tenantId}/keys/{keyId} — HTTP endpoint returning 204 No Content
  - EntityNotFoundException → 404 mapping in ApiAdvice (previously fell through to 500)
  - TenantAdminResourceIT — 3 HTTP-layer integration tests

affects:
  - future phases that call rotate/revoke via HTTP (e.g. admin UI, partner onboarding scripts)
  - any phase adding exception handlers to ApiAdvice (EntityNotFoundException now claimed)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "REST gap closure: service methods were tested at service layer; HTTP routes added to expose them over API"
    - "Exception handler completeness: EntityNotFoundException added to @RestControllerAdvice to prevent generic 500 fallback"
    - "IT test pattern: @SpringBootTest RANDOM_PORT + RestTemplate + @BeforeEach JWT secret seed + @AfterEach full teardown"

key-files:
  created:
    - src/test/java/com/softropic/payam/tenant/TenantAdminResourceIT.java
  modified:
    - src/main/java/com/softropic/payam/tenant/api/TenantAdminResource.java
    - src/main/java/com/softropic/payam/security/api/ApiAdvice.java

key-decisions:
  - "TenantAdminResource now takes two constructor args (TenantService, ApiKeyService) — Spring auto-injects both @Service beans"
  - "tenantId path variable present for URL consistency and future ownership validation; no DB ownership check added in this plan"
  - "EntityNotFoundException handler added to ApiAdvice → 404; without it JPA EntityNotFoundException hit Throwable → 500"
  - "IT test uses RestTemplate + @LocalServerPort (same pattern as TenantFilterChainIT), not TestRestTemplate — consistent with existing test infrastructure"

patterns-established:
  - "Pattern: Service layer tested in *ProvisioningIT; HTTP layer tested in *ResourceIT — two distinct test concerns"
  - "Pattern: Every IT test class touching /v1/** must seed JWT secret row in @BeforeEach (see TenantFilterChainIT and TenantAdminResourceIT)"

# Metrics
duration: 4min
completed: 2026-03-23
---

# Phase 1 Plan 3: Rotate and Revoke Key HTTP Endpoints Summary

**POST /v1/admin/tenants/{id}/keys/{id}/rotate (200+ApiKeyDto) and DELETE .../{id} (204) wired to existing ApiKeyService, with EntityNotFoundException mapped to 404**

## Performance

- **Duration:** 4 min
- **Started:** 2026-03-23T22:23:22Z
- **Completed:** 2026-03-23T22:27:08Z
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments

- Added rotate and revoke HTTP endpoints to TenantAdminResource, closing the gap between service-layer capability and API accessibility
- Created TenantAdminResourceIT with 3 integration tests confirming HTTP routing, status codes, body content, and grace-period behavior
- Fixed EntityNotFoundException → 404 in ApiAdvice so unknown keyId calls return proper 404 rather than 500

## Task Commits

Each task was committed atomically:

1. **Task 1: Add rotate and revoke endpoints to TenantAdminResource** - `5f7f04c` (feat)
2. **Task 2: Integration test for rotate and revoke HTTP endpoints** - `8bfb0a8` (feat)

**Plan metadata:** see docs commit below

## Files Created/Modified

- `src/main/java/com/softropic/payam/tenant/api/TenantAdminResource.java` - Added ApiKeyService constructor arg, rotateKey() POST handler, revokeKey() DELETE handler
- `src/test/java/com/softropic/payam/tenant/TenantAdminResourceIT.java` - 3 IT tests: rotate 200+newRawKey, revoke 204+rejected, unknown key 404
- `src/main/java/com/softropic/payam/security/api/ApiAdvice.java` - Added EntityNotFoundException → 404 handler (auto-fix)

## Decisions Made

- **Two-arg constructor on TenantAdminResource:** Spring framework injects both `TenantService` and `ApiKeyService` automatically since both are `@Service` beans. No `@Qualifier` needed.
- **tenantId as path-only variable:** Present for URL consistency and future ownership/authorization work; not used for DB lookup in this plan (service tests already confirm per-tenant key scope).
- **EntityNotFoundException → 404 (auto-fix):** Without an explicit handler, `jakarta.persistence.EntityNotFoundException` from `ApiKeyService.rotate()/revoke()` fell through to the generic `Throwable → 500` catch-all. Added `@ExceptionHandler(EntityNotFoundException.class)` → `HttpStatus.NOT_FOUND` in `ApiAdvice`.
- **RestTemplate over TestRestTemplate:** Consistent with existing `TenantFilterChainIT` pattern — `RestTemplate` (from `TestConfig.restTemplate()` bean) with `@LocalServerPort` provides identical HTTP-layer coverage.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] EntityNotFoundException not mapped to 404 in ApiAdvice**

- **Found during:** Task 2 (writing rotateKey_unknownKeyId_returns404 test)
- **Issue:** `ApiAdvice` had no handler for `jakarta.persistence.EntityNotFoundException`. The generic `@ExceptionHandler(Throwable.class)` catch-all maps all unknown exceptions to 500. A request to rotate an unknown key would return 500, not 404 — incorrect API semantics.
- **Fix:** Added `@ExceptionHandler(EntityNotFoundException.class) @ResponseStatus(HttpStatus.NOT_FOUND)` handler to `ApiAdvice`.
- **Files modified:** `src/main/java/com/softropic/payam/security/api/ApiAdvice.java`
- **Verification:** `rotateKey_unknownKeyId_returns404` test passes; `TenantProvisioningIT` and `TenantFilterChainIT` still green (13 tests, 0 failures).
- **Committed in:** `8bfb0a8` (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (1 missing critical)
**Impact on plan:** Auto-fix required for correct API semantics (404 vs 500 for not-found resources). No scope creep.

## Issues Encountered

None — plan tasks executed cleanly. The EntityNotFoundException mapping gap was discovered during test writing and resolved inline under deviation Rule 2.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Phase 1 is now fully complete: tenant provisioning, API key filter chain, and rotate/revoke HTTP endpoints all tested end-to-end
- Phase 2 can proceed — all Phase 1 must-haves satisfied including "API keys can be rotated and revoked immediately" via HTTP
- No blockers from this plan
- Ongoing concern: any new IT test class making requests to /v1/** must seed the JWT secret row in main.sec (pattern documented in TenantAdminResourceIT and TenantFilterChainIT)

---
*Phase: 01-multi-tenant-foundation*
*Completed: 2026-03-23*
