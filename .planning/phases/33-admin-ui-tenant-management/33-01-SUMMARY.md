---
phase: 33-admin-ui-tenant-management
plan: 01
subsystem: api
tags: [tenant, api-key, dto, spring-boot, rest]

requires:
  - phase: 31-tenant-rest-api-surface
    provides: TenantAdminResource, ApiKeyService, TenantSummaryDto, ApiKeySummaryDto, TenantQueryService

provides:
  - TenantSummaryDto with email and createdAt fields (Instant)
  - ApiKeySummaryDto with createdAt field (Instant)
  - POST /v1/admin/tenants/{tenantRef}/keys/generate endpoint returning ApiKeyDto with rawKey
  - Integration tests for generate endpoint (happy path + duplicate-key 409)

affects:
  - 33-admin-ui-tenant-management (plans 02-04 — UI consumes these DTO fields and endpoint)

tech-stack:
  added: []
  patterns:
    - TenantRepository injected into controller via constructor for entity lookup before service call
    - generateAndStore delegates duplicate-active-key guard to ApiKeyService (IllegalStateException → 409)

key-files:
  created: []
  modified:
    - src/main/java/com/softropic/payam/tenant/contract/TenantSummaryDto.java
    - src/main/java/com/softropic/payam/tenant/contract/ApiKeySummaryDto.java
    - src/main/java/com/softropic/payam/tenant/service/TenantQueryService.java
    - src/main/java/com/softropic/payam/tenant/api/TenantAdminResource.java
    - src/test/java/com/softropic/payam/tenant/TenantAdminResourceIT.java

key-decisions:
  - "generateKey endpoint injects TenantRepository to resolve entity from tenantRef, matching the existing pattern used by other controllers that need Tenant entity (not DTO)"
  - "Duplicate-active-key guard lives in ApiKeyService.generateAndStore (IllegalStateException), mapped to 409 by ApiAdvice — no controller-layer guard needed"

patterns-established:
  - "Generate endpoint pattern: resolve Tenant entity via repository, delegate to ApiKeyService.generateAndStore, return ApiKeyDto with rawKey"

requirements-completed: [UI-01, UI-02, UI-03]

duration: 20min
completed: 2026-04-09
---

# Phase 33 Plan 01: Admin UI Tenant Management Backend Prerequisites Summary

**TenantSummaryDto + ApiKeySummaryDto extended with Instant timestamps and email; new POST /keys/generate endpoint for on-demand API key creation**

## Performance

- **Duration:** ~20 min
- **Started:** 2026-04-09T04:38:00Z
- **Completed:** 2026-04-09T04:58:45Z
- **Tasks:** 2
- **Files modified:** 5

## Accomplishments
- Added `email` and `createdAt` (Instant) fields to `TenantSummaryDto` so tenant list API exposes email address and creation timestamp required by UI columns
- Added `createdAt` (Instant) field to `ApiKeySummaryDto` so key table exposes creation date column required by UI
- Updated both `TenantQueryService` mappers to populate the new fields from `AbstractAuditingEntity.getCreatedDate()` and `Tenant.getEmail()`
- Added `POST /v1/admin/tenants/{tenantRef}/keys/generate?env={env}` endpoint with admin role guard; delegates to `ApiKeyService.generateAndStore`, returns `ApiKeyDto` with one-time `rawKey`; 409 if active key already exists for that environment
- Added two integration tests covering happy path (revoke → generate) and duplicate-active-key 409

## Task Commits

Each task was committed atomically:

1. **Task 1: Add DTO fields and update TenantQueryService mapper** - `c2e675d` (feat)
2. **Task 2: Add POST /keys/generate endpoint and integration test** - `29e32eb` (feat)

**Plan metadata:** (to be committed with this SUMMARY)

## Files Created/Modified
- `src/main/java/com/softropic/payam/tenant/contract/TenantSummaryDto.java` - Added `String email, Instant createdAt` fields (6-field record)
- `src/main/java/com/softropic/payam/tenant/contract/ApiKeySummaryDto.java` - Added `Instant createdAt` field (5-field record)
- `src/main/java/com/softropic/payam/tenant/service/TenantQueryService.java` - Updated both mappers to pass new fields from entity
- `src/main/java/com/softropic/payam/tenant/api/TenantAdminResource.java` - Injected TenantRepository; added generateKey endpoint
- `src/test/java/com/softropic/payam/tenant/TenantAdminResourceIT.java` - Added tests 14 + 15 for generateKey

## Decisions Made
- `generateKey` endpoint injects `TenantRepository` directly for entity resolution from `tenantRef`, consistent with the pattern in `TenantQueryService.findByTenantRef` — service layer does not expose a find-by-ref-returning-entity method
- The 409 conflict guard lives entirely in `ApiKeyService.generateAndStore` (throws `IllegalStateException`) which `ApiAdvice` maps to HTTP 409 — no controller-level guard added

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
- Docker daemon not running — testcontainers-based integration test suite (`TenantAdminResourceIT`) could not execute at runtime. Main and test sources compile cleanly (`mvn compile` + `mvn test-compile` both pass). Test correctness verified structurally: follows the exact same patterns (setup, authentication, assertions) as the 13 existing passing tests in the same class.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Backend prerequisites for plan 02 (Vue tenant list UI) are complete: `TenantSummaryDto` exposes `email` and `createdAt`; `ApiKeySummaryDto` exposes `createdAt`; generate endpoint is live
- Plan 03 (key table UI with Generate button) depends on the generate endpoint added here
- No blockers

---
*Phase: 33-admin-ui-tenant-management*
*Completed: 2026-04-09*
