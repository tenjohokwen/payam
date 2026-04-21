---
phase: 42-pin-backend-api
plan: "02"
subsystem: platform-config
tags: [pin, encryption, service, tdd, PIN-03, PIN-04, PIN-05]
dependency_graph:
  requires: [42-01]
  provides: [PlatformConfigService-3arg-update, findPinByProvider, PIN-03-service, PIN-05-service]
  affects: [platform-config-service, platform-config-resource]
tech_stack:
  added: []
  patterns: [StringUtils-isNotBlank-guard, ResourceNotFoundException-vs-ISE-404-vs-409, Transactional-readOnly-override]
key_files:
  created: []
  modified:
    - src/main/java/com/softropic/payam/platform/service/PlatformConfigService.java
    - src/main/java/com/softropic/payam/platform/api/PlatformConfigAdminResource.java
    - src/test/java/com/softropic/payam/platform/service/PlatformConfigServiceTest.java
decisions:
  - StringUtils.isNotBlank(pin) to guard encrypt path — null and blank both skip encryption (PIN-08 keep-existing semantics, consistent with Cryptopher's own StringUtils use)
  - "@Transactional(readOnly=true) on findPinByProvider — overrides class-level @Transactional; no mutation; consistent with findByProvider and findAll patterns"
  - ResourceNotFoundException (HTTP 404) for null pin vs IllegalStateException (HTTP 409) for missing config row — reserve 404 for the more specific 'row exists but no pin set'
  - No logging inside findPinByProvider — reveal endpoints should not produce log entries that could be correlated with the secret value (defense in depth)
  - orElseGet branch ignores pin param — first-time row creation defers PIN to a subsequent update once the row exists
  - Pre-existing 2-arg update() test calls adapted to 3-arg with null pin (adapter for signature widening — tests still verify same behavior)
metrics:
  duration: "~18 minutes"
  completed: "2026-04-18T07:05:26Z"
  tasks_completed: 2
  files_changed: 3
  files_created: 0
---

# Phase 42 Plan 02: PIN Service Encryption + Reveal Logic Summary

Service encryption and reveal: PlatformConfigService widened to 3-arg update() with StringUtils.isNotBlank guard for atomic PIN+MSISDN commits, plus findPinByProvider() that decrypts on demand and distinguishes 404 (no PIN set) from 409 (no config row). TDD discipline: RED commit before GREEN; all 10 unit tests pass.

## Objective Achieved

Wired the encryption + reveal business logic into PlatformConfigService, satisfying PIN-03 (encrypted write, atomic with MSISDN, blank-preserves-existing semantics) and PIN-05 (decrypt + 404) at the service boundary. All logic is unit-testable with Mockito without spinning up Spring or Postgres. Plan 03 can now expose these via HTTP endpoints.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 (RED) | Add 6 failing PlatformConfigServiceTest cases for encrypt/no-op/decrypt/404/409 paths | ca9fa09 | PlatformConfigServiceTest.java |
| 2 (GREEN) | Inject pinCryptopher, widen update() to 3-arg, add findPinByProvider() | c1dabe7 | PlatformConfigService.java, PlatformConfigAdminResource.java, PlatformConfigServiceTest.java |

## Files Modified

- `src/main/java/com/softropic/payam/platform/service/PlatformConfigService.java` — Added Cryptopher/PinDto/ResourceNotFoundException/StringUtils imports; injected `Cryptopher pinCryptopher` field via @RequiredArgsConstructor; widened `update(String, String)` to `update(String, String, String)` with StringUtils.isNotBlank guard and config.updatePin(ciphertext) inside .map() for atomicity; added `findPinByProvider(String)` @Transactional(readOnly=true) with ISE for missing row and RNF for null pin.
- `src/main/java/com/softropic/payam/platform/api/PlatformConfigAdminResource.java` — Updated `platformConfigService.update(provider, dto.platformMsisdn())` to 3-arg `platformConfigService.update(provider, dto.platformMsisdn(), dto.pin())` to keep main sources compiling. Plan 03 adds @Valid and the new GET /pin endpoint.
- `src/test/java/com/softropic/payam/platform/service/PlatformConfigServiceTest.java` — Added `@Mock Cryptopher pinCryptopher` field; added 6 new test methods (RED commit); updated 2 pre-existing tests from 2-arg to 3-arg update() call with null pin; final GREEN state: 10 tests all pass.

## Decisions Made

| Decision | Rationale |
|----------|-----------|
| `StringUtils.isNotBlank(pin)` guard | Null and blank both skip encryption, consistent with Cryptopher's own StringUtils use and PIN-08 keep-existing semantics |
| `@Transactional(readOnly = true)` on findPinByProvider | Overrides class-level @Transactional; no mutation occurs; consistent with findByProvider and findAll pattern |
| `ResourceNotFoundException` (404) for null pin | Reserve 404 for the specific 'row exists but PIN not configured' case; ISE (409) for 'no config row at all' mirrors existing findByProvider behavior |
| No logging in findPinByProvider | Defense in depth — reveal operations should not produce log entries correlatable with the secret value |
| orElseGet branch ignores pin param | First-time row creation cannot set PIN; admin must do a subsequent update once the row exists; avoids a null-ciphertext path the contract does not support |
| Pre-existing tests adapted to 3-arg call | update() signature widened — adapting existing tests to null pin preserves all behavioral assertions unchanged |

## TDD Discipline

- RED commit `ca9fa09`: 6 new test methods + @Mock Cryptopher pinCryptopher field added. test-compile fails because 3-arg update() and findPinByProvider() do not exist in PlatformConfigService yet.
- GREEN commit `c1dabe7`: Service implementation + call-site update + test adapter. All 10 PlatformConfigServiceTest tests pass; PayamPlatformPropertiesTest still green.

## Plan 03 Dependencies Satisfied

- `update(String provider, String newMsisdn, String pin)` 3-arg signature in place; Plan 03 adds `@Valid` to the request body
- `findPinByProvider(String provider)` returns `PinDto` ready to wrap in `ResponseEntity.ok(...)` for the GET /pin endpoint
- ResourceNotFoundException (404) and IllegalStateException (409) error contracts match ApiAdvice mapping

## Requirements Satisfied

- PIN-03: `StringUtils.isNotBlank(pin)` guards encrypt path; `config.updatePin(ciphertext)` inside `.map()` commits atomically with MSISDN update in single @Transactional
- PIN-04: Returned DTO always has `null` as pin component — no ciphertext leakage. All 4 PlatformConfigDto construction sites pass null as 4th arg.
- PIN-05: `findPinByProvider()` throws ResourceNotFoundException (404) when pin is null; IllegalStateException (409) when no config row

## Verification Results

- `mvn -o compile -q` exits 0
- `mvn -o test-compile -q` exits 0
- `mvn -o test -Dtest=PlatformConfigServiceTest` exits 0 — Tests run: 10, Failures: 0, Errors: 0
- `mvn -o test -Dtest=PayamPlatformPropertiesTest` exits 0 — Tests run: 3, Failures: 0, Errors: 0
- All 4 PlatformConfigDto construction sites in PlatformConfigService use null as 4th arg (no ciphertext leakage)
- git log shows `test(42-02):` RED commit followed by `feat(42-02):` GREEN commit

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Updated pre-existing 2-arg update() test calls to 3-arg**
- **Found during:** Task 2 (GREEN)
- **Issue:** Pre-existing tests `update_shouldUpdateExistingConfig` and `update_shouldCreateNewConfigIfNotFound` called `platformConfigService.update(provider, newMsisdn)` (2-arg), which fails to compile after the signature is widened to 3-arg in PlatformConfigService
- **Fix:** Updated both calls to `platformConfigService.update(provider, newMsisdn, null)` — null pin preserves existing behavior (StringUtils.isNotBlank(null) is false, so no encryption, no pin update)
- **Files modified:** PlatformConfigServiceTest.java
- **Commit:** c1dabe7

## Known Stubs

None — all business logic is wired through to the repository and event publisher. No placeholder data.

## Self-Check: PASSED
