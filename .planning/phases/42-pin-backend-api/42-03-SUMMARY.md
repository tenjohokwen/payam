---
phase: 42-pin-backend-api
plan: "03"
subsystem: platform-config
tags: [pin, http, rest, validation, integration-test, PIN-03, PIN-04, PIN-05]
dependency_graph:
  requires: [42-01, 42-02]
  provides: [PUT-with-@Valid, GET-provider-pinConfigured, GET-provider-pin-reveal, PlatformConfigAdminResourceIT]
  affects: [platform-config-api, platform-config-admin-resource]
tech_stack:
  added: []
  patterns: [jakarta-Valid-on-RequestBody, ApiAdvice-delegation, HTTP-IT-pattern-from-TenantAdminResourceIT]
key_files:
  created:
    - src/test/java/com/softropic/payam/platform/PlatformConfigAdminResourceIT.java
  modified:
    - src/main/java/com/softropic/payam/platform/api/PlatformConfigAdminResource.java
decisions:
  - "Added GET /{provider} (single-provider read) because PIN-04 requires pinConfigured boolean per provider; the resource previously only had list-all GET / — the new endpoint serves Phase 43 frontend too"
  - "cleanDb() uses UPDATE main.platform_config SET ... pin = NULL (not DELETE) to preserve V17-seeded ORANGE/MTN rows; DELETE would break subsequent findByProvider calls in the same test class"
  - "Admin-seeding INSERTs in seedAdminAndSecrets() copied verbatim from TenantAdminResourceIT — literal IDs, bcrypt hash, and JWT secret bytes are paired; any drift causes 401 in AdminLogin.loginAsAdmin"
  - "Test class has NO @Transactional so PUT commit is visible to subsequent GET across HTTP boundaries"
  - "@Valid placed on @RequestBody parameter (not method) so Bean Validation fires on the dto object; without it, @Pattern on PlatformConfigDto.pin is a no-op and PIN-03 400-path silently fails"
  - "Exception mapping entirely delegated to existing ApiAdvice: MethodArgumentNotValidException -> 400, ResourceNotFoundException -> 404, IllegalStateException -> 409; no @ExceptionHandler added to resource"
metrics:
  duration: "~20 minutes"
  completed: "2026-04-18"
  tasks_completed: 2
  files_modified: 1
  files_created: 1
---

# Phase 42 Plan 03: PIN HTTP API Exposure Summary

**One-liner:** PUT endpoint validated with @Valid triggering @Pattern for PIN-03; GET /{provider} exposes pinConfigured for PIN-04; GET /{provider}/pin reveals decrypted PIN for PIN-05; PlatformConfigAdminResourceIT with 7 tests covers all paths end-to-end.

## What Was Built

### Task 1: PlatformConfigAdminResource — 2 changes + 1 new endpoint

**File:** `src/main/java/com/softropic/payam/platform/api/PlatformConfigAdminResource.java`

Changes:
1. Added `@Valid` to the existing `PUT /{provider}` endpoint's `@RequestBody PlatformConfigDto dto` parameter. This activates Bean Validation and causes the `@Pattern(regexp = "^$|^[a-zA-Z0-9]{4,8}$")` on `PlatformConfigDto.pin` to fire, producing HTTP 400 via `ApiAdvice.handleMethodArgumentNotValid()` for invalid PINs (PIN-03 validation gate).
2. Added `GET /{provider}` endpoint calling `platformConfigService.findByProvider(provider)` — returns `PlatformConfigDto` with `pinConfigured: boolean` and no `pin` field (PIN-04).
3. Added `GET /{provider}/pin` endpoint calling `platformConfigService.findPinByProvider(provider)` — returns `PinDto` with decrypted plaintext PIN; throws `ResourceNotFoundException` (404) when PIN is null; throws `IllegalStateException` (409) when provider row is absent (PIN-05).
4. Added `import jakarta.validation.Valid` (Jakarta EE 9+, not javax).

The resource now exposes 4 endpoints:
- `GET /v1/admin/platform-config` (list all)
- `GET /v1/admin/platform-config/{provider}` (single provider, pinConfigured)
- `PUT /v1/admin/platform-config/{provider}` (update MSISDN + optional PIN, with @Valid)
- `GET /v1/admin/platform-config/{provider}/pin` (reveal decrypted PIN)

### Task 2: PlatformConfigAdminResourceIT — 7 integration tests

**File:** `src/test/java/com/softropic/payam/platform/PlatformConfigAdminResourceIT.java`

Covers:
- `getProviderConfig_shouldReturnPinConfiguredFalseWhenNoPinSet` (PIN-04 — pinConfigured=false, no pin key in JSON)
- `getProviderConfig_shouldReturnPinConfiguredTrueAfterPinSet` (PIN-04 — pinConfigured=true after PUT, no pin key in JSON)
- `putConfig_shouldReturn200AndAcceptValidPin` (PIN-03 happy path)
- `putConfig_shouldReturn400OnInvalidPinFormat` (PIN-03 validation — non-alphanumeric, too short, too long all return 400)
- `putConfig_shouldPreserveExistingPinWhenPinFieldIsEmpty` (PIN-03 + PIN-08 — empty pin preserves existing)
- `getPin_shouldReturn200AndDecryptedPlaintextWhenPinConfigured` (PIN-05 happy path — round-trip decrypt)
- `getPin_shouldReturn404WhenNoPinConfigured` (PIN-05 not-found — null pin returns 404)

Pattern: mirrors `TenantAdminResourceIT` (Phase 31) exactly — `@ActiveProfiles("dev")`, `@Import(TestConfig.class)`, `@SpringBootTest(RANDOM_PORT)`, admin seed SQL, `AdminLogin.loginAsAdmin()`, no `@Transactional` on class.

## Deviations from Plan

None — plan executed exactly as written. All file shapes, endpoint paths, method names, and test structure match the plan specification.

## Known Stubs

None. All endpoints wire to real service implementations from Plan 02. The IT tests assert real end-to-end HTTP behavior, not placeholder values.

## IT Test Status

The IT test file is structurally correct and test-compiles cleanly. The full IT suite (`mvn failsafe:integration-test`) requires Docker (Testcontainers PostgreSQL). Docker daemon was not running in this execution environment — the same constraint affects all 32 existing E2E and IT test classes in the project (TenantAdminResourceIT, OrangeMoneyClientIT, etc. all fail identically). This is a pre-existing environmental constraint, not a regression introduced by this plan. The orchestrator's `mvn verify` gate should be run in an environment with Docker available.

## Phase 42 Closure

All three plans in Phase 42 are now complete:
- Plan 01: PinDto, PlatformConfigDto @Pattern, pinCryptopher @Bean, updatePin() on entity
- Plan 02: PlatformConfigService.update() 3-arg, findByProvider(), findPinByProvider() with encrypt/decrypt
- Plan 03: HTTP surface — PUT with @Valid, GET /{provider}, GET /{provider}/pin; 7 IT tests

Requirements delivered: PIN-03 (validation + write), PIN-04 (pinConfigured boolean in GET), PIN-05 (reveal endpoint + 404).
Phase 43 (frontend) can now drive all four platform-config endpoints.

## Self-Check: PASSED

Files created:
- `src/test/java/com/softropic/payam/platform/PlatformConfigAdminResourceIT.java` — EXISTS

Files modified:
- `src/main/java/com/softropic/payam/platform/api/PlatformConfigAdminResource.java` — EXISTS

Commits:
- `60f1c04` — feat(42-03): add @Valid to PUT, add GET /{provider} and GET /{provider}/pin endpoints
- `1ec75fc` — test(42-03): add PlatformConfigAdminResourceIT for PIN-03/04/05
