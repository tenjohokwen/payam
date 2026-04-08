---
phase: 32-email-notification-infrastructure
plan: "03"
subsystem: tenant/email
tags: [gap-closure, notif-04, api-key-reactivation, tdd]
dependency_graph:
  requires: ["32-01", "32-02"]
  provides: ["NOTIF-04 chain complete"]
  affects: [tenant, email]
tech_stack:
  added: []
  patterns: [revoke-pattern-mirror, AKEY-02-guard, TDD-RED-GREEN]
key_files:
  created:
    - src/test/java/com/softropic/payam/tenant/service/ApiKeyServiceReactivateTest.java
  modified:
    - src/main/java/com/softropic/payam/tenant/service/ApiKeyService.java
    - src/main/java/com/softropic/payam/tenant/api/TenantAdminResource.java
decisions:
  - "reactivate() mirrors revoke() structure exactly — same pattern, same event publish shape"
  - "AKEY-02 guard applied in reactivate: reject if active key already exists for same tenant+environment"
  - "IllegalStateException thrown for non-REVOKED keys — maps to 409 via existing ApiAdvice handler"
  - "Endpoint returns 204 No Content (not ApiKeyDto) — reactivation restores existing key, no new raw key"
metrics:
  duration: "7 minutes"
  completed_date: "2026-04-08"
  tasks_completed: 2
  files_modified: 3
---

# Phase 32 Plan 03: NOTIF-04 Gap Closure — Key Reactivation Summary

Gap closure for NOTIF-04: `ApiKeyService.reactivate()` + REST endpoint + unit tests that complete the REACTIVATED event chain.

## What Was Built

The NOTIF-04 chain was already 90% in place from Plans 32-01 and 32-02 — the `Action.REACTIVATED` enum value, the Thymeleaf template `tenantApiKeyReactivated.html`, and the `TenantLifecycleEmailListener.onApiKeyEvent()` handler were all wired and tested. The missing piece was the production code path that publishes the event.

This plan adds:

1. **`ApiKeyService.reactivate(Long keyId)`** — Validates the key is in `REVOKED` status, enforces AKEY-02 safety (no active key exists for same tenant+environment), transitions status to `ACTIVE`, and publishes `TenantApiKeyEvent(Action.REACTIVATED)`.

2. **`POST /{tenantId}/keys/{keyId}/reactivate`** in `TenantAdminResource` — Returns 204 No Content, admin-only via `@PreAuthorize`, delegates to the service method. `IllegalStateException` -> 409 via existing `ApiAdvice`; `EntityNotFoundException` -> 404.

3. **`ApiKeyServiceReactivateTest`** — 5 unit tests (TDD): happy path, not-found, ACTIVE guard, ROTATED guard, AKEY-02 conflict.

## Complete NOTIF-04 Chain

```
POST /{tenantId}/keys/{keyId}/reactivate (TenantAdminResource)
  -> ApiKeyService.reactivate(keyId)
     -> publishes TenantApiKeyEvent(Action.REACTIVATED)
        -> TenantLifecycleEmailListener.onApiKeyEvent()
           -> maps to EmailTemplate.TENANT_API_KEY_REACTIVATED
              -> tenantApiKeyReactivated.html rendered and sent
```

## Tasks

| Task | Description | Commit | Status |
|------|-------------|--------|--------|
| 1 | TDD RED: failing tests for reactivate() | fb79733 | done |
| 1 | TDD GREEN: implement ApiKeyService.reactivate() | 31dbfc6 | done |
| 2 | REST endpoint POST /{tenantId}/keys/{keyId}/reactivate | 6442256 | done |

## Decisions Made

- **reactivate() mirrors revoke() exactly** — same find-or-throw, same event publish shape. Minimizes surface area for bugs.
- **AKEY-02 guard** — `findActiveKeyByTenantIdAndEnvironment` check before setting ACTIVE prevents two active keys for the same environment.
- **204 No Content** — reactivation restores the existing key, no new raw key is generated, so there is nothing to return in the body.
- **IllegalStateException -> 409** — `ApiAdvice` already maps this exception type; no new handler needed.

## Deviations from Plan

None — plan executed exactly as written.

## Known Stubs

None — all data flows are fully wired. The reactivation chain is end-to-end: REST -> service -> event -> listener -> template.

## Self-Check: PASSED

Files exist:
- src/main/java/com/softropic/payam/tenant/service/ApiKeyService.java — FOUND
- src/main/java/com/softropic/payam/tenant/api/TenantAdminResource.java — FOUND
- src/test/java/com/softropic/payam/tenant/service/ApiKeyServiceReactivateTest.java — FOUND

Commits verified:
- fb79733 — test(32-03): add failing tests
- 31dbfc6 — feat(32-03): implement reactivate()
- 6442256 — feat(32-03): add REST endpoint

Unit tests: all 5 pass (`mvn test -Dtest=ApiKeyServiceReactivateTest`)
Compile: clean (`mvn compile`)
