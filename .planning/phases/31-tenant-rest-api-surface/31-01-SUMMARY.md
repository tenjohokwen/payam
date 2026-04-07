---
phase: 31-tenant-rest-api-surface
plan: 01
subsystem: api
tags: [spring-boot, jpa, spring-data, rest, tenant-management]

# Dependency graph
requires:
  - phase: 30-tenant-rest-api-surface
    provides: "SUSPENDED tenant enforcement in ApiKeyAuthenticationFilter"
provides:
  - "TenantSummaryDto, TenantDetailDto (no webhookSecret), ApiKeySummaryDto (no rawKey), WebhookSecretDto"
  - "TenantQueryService with findAll (paginated + status filter), findByTenantRef, getWebhookSecret"
  - "GET /v1/admin/tenants — paginated list with optional status filter (TENT-05)"
  - "GET /v1/admin/tenants/{tenantRef} — full detail without webhook secret (TENT-06)"
  - "GET /v1/admin/tenants/{tenantRef}/webhook-secret — plaintext secret reveal (WSEC-03)"
  - "4 integration tests covering all 3 new endpoints"
affects: [32-tenant-rest-api-surface, admin-ui phases]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "TenantQueryService as read-only @Transactional(readOnly=true) service separate from mutation service"
    - "keyRepository.findAllByTenantId() instead of tenant.getApiKeys() to avoid LazyInitializationException"
    - "status param as String @RequestParam(required=false) with manual TenantStatus.valueOf() to avoid Spring binding issues on absent param"

key-files:
  created:
    - src/main/java/com/softropic/payam/tenant/contract/TenantSummaryDto.java
    - src/main/java/com/softropic/payam/tenant/contract/TenantDetailDto.java
    - src/main/java/com/softropic/payam/tenant/contract/ApiKeySummaryDto.java
    - src/main/java/com/softropic/payam/tenant/contract/WebhookSecretDto.java
    - src/main/java/com/softropic/payam/tenant/service/TenantQueryService.java
  modified:
    - src/main/java/com/softropic/payam/tenant/repo/TenantRepository.java
    - src/main/java/com/softropic/payam/tenant/api/TenantAdminResource.java
    - src/test/java/com/softropic/payam/tenant/TenantAdminResourceIT.java
    - src/test/java/com/softropic/payam/tenant/TenantFilterChainIT.java

key-decisions:
  - "TenantQueryService is a separate @Service from TenantService — keeps read-only queries (readOnly=true transaction) isolated from mutation operations"
  - "TenantDetailDto deliberately excludes webhookSecret field — secret reveal requires dedicated GET /{tenantRef}/webhook-secret endpoint (WSEC-03)"
  - "ApiKeySummaryDto excludes rawKey — raw keys are one-time values shown only at creation/rotation, never stored or re-served"
  - "status @RequestParam is String (not TenantStatus) to prevent binding failure when parameter is absent — converted manually with TenantStatus.valueOf()"

patterns-established:
  - "Read-only service pattern: @Transactional(readOnly=true) service class for query-only operations"
  - "Lazy collection avoidance: always use keyRepository.findAllByTenantId(id) not tenant.getApiKeys()"
  - "Security DTO separation: different DTOs for list vs detail vs secret reveal"

requirements-completed: [TENT-05, TENT-06, WSEC-03]

# Metrics
duration: 15min
completed: 2026-04-07
---

# Phase 31 Plan 01: Tenant REST API Read Layer Summary

**Read-only tenant query layer with paginated list, detail (no webhook secret), and dedicated secret-reveal endpoint — 4 DTOs, TenantQueryService, 3 GET controller methods, 4 integration tests**

## Performance

- **Duration:** ~15 min
- **Started:** 2026-04-07T06:39:00Z
- **Completed:** 2026-04-07T06:54:11Z
- **Tasks:** 2
- **Files modified:** 9

## Accomplishments

- Created 4 DTO records: TenantSummaryDto, TenantDetailDto (no webhookSecret), ApiKeySummaryDto (no rawKey), WebhookSecretDto
- Created TenantQueryService (@Transactional readOnly) with findAll (paginated + status filter), findByTenantRef, getWebhookSecret
- Added 3 GET endpoints to TenantAdminResource: list with filter, detail without secret, dedicated secret reveal
- Added 4 integration tests covering all 3 new endpoints; 7 total tests in TenantAdminResourceIT

## Task Commits

Each task was committed atomically:

1. **Task 1: DTOs, TenantQueryService, and paginated TenantRepository query** - `561cb0f` (feat)
2. **Task 2: GET endpoints on TenantAdminResource with integration tests** - `1853ec3` (feat)

## Files Created/Modified

- `src/main/java/com/softropic/payam/tenant/contract/TenantSummaryDto.java` - Summary DTO for paginated list (id, tenantRef, name, tenantStatus)
- `src/main/java/com/softropic/payam/tenant/contract/TenantDetailDto.java` - Detail DTO without webhookSecret (includes keys list)
- `src/main/java/com/softropic/payam/tenant/contract/ApiKeySummaryDto.java` - Key summary DTO without rawKey (id, keyPrefix, environment, keyStatus)
- `src/main/java/com/softropic/payam/tenant/contract/WebhookSecretDto.java` - Webhook secret response DTO
- `src/main/java/com/softropic/payam/tenant/service/TenantQueryService.java` - Read-only query service with findAll, findByTenantRef, getWebhookSecret
- `src/main/java/com/softropic/payam/tenant/repo/TenantRepository.java` - Added findByTenantStatus(TenantStatus, Pageable) query method
- `src/main/java/com/softropic/payam/tenant/api/TenantAdminResource.java` - Added TenantQueryService injection + 3 GET endpoints
- `src/test/java/com/softropic/payam/tenant/TenantAdminResourceIT.java` - Added 4 new integration tests
- `src/test/java/com/softropic/payam/tenant/TenantFilterChainIT.java` - Fixed Rule 1 bug (String "LIVE" → ApiKeyEnvironment.PROD)

## Decisions Made

- TenantQueryService kept separate from TenantService to isolate read-only transactions
- webhookSecret absent from TenantDetailDto by design — secret reveal is a separate privileged operation (WSEC-03)
- rawKey absent from ApiKeySummaryDto by design — raw keys are one-time values never re-served
- `status` param accepted as `String` with manual `TenantStatus.valueOf()` to avoid Spring binding failure on absent optional param

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed TenantFilterChainIT type mismatch — String "LIVE" passed to ApiKeyEnvironment param**
- **Found during:** Task 2 compilation
- **Issue:** Two calls in TenantFilterChainIT.java passed `"LIVE"` (String) to `createTenant(String, ApiKeyEnvironment)` — introduced in Phase 30 TDD RED commit before the enum migration
- **Fix:** Replaced both occurrences with `ApiKeyEnvironment.PROD`
- **Files modified:** `src/test/java/com/softropic/payam/tenant/TenantFilterChainIT.java`
- **Verification:** `mvn compile test-compile -q` exits 0
- **Committed in:** `1853ec3` (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (1 bug)
**Impact on plan:** Fix was required for compilation. No scope creep.

## Issues Encountered

- Docker daemon not running in this environment — Testcontainers-based integration tests (TenantAdminResourceIT) cannot execute. This is an infrastructure constraint affecting all IT tests in the project, not caused by this plan. Code correctness confirmed via compilation only.

## Known Stubs

None — all endpoints wire real data from TenantQueryService → TenantRepository/TenantApiKeyRepository.

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness

- TENT-05, TENT-06, WSEC-03 read endpoints are in place — mutation endpoints (create, suspend, reactivate, update) can be added in subsequent plans
- Admin UI can now call GET /v1/admin/tenants to display tenant list and GET /v1/admin/tenants/{ref} for detail view

---
*Phase: 31-tenant-rest-api-surface*
*Completed: 2026-04-07*
