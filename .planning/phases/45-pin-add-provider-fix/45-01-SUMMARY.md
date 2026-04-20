---
phase: 45-pin-add-provider-fix
plan: "01"
subsystem: platform-config
tags: [pin, gap-closure, tdd, backend, frontend, GAP-01, PIN-09]
dependency_graph:
  requires: []
  provides: [GAP-01-closure, PIN-09]
  affects: [PlatformConfigService.update, PlatformConfigAdminResourceIT, PlatformConfigPage.vue]
tech_stack:
  added: []
  patterns: [TDD-Red-Green, orElseGet-PIN-persistence]
key_files:
  created: []
  modified:
    - src/main/java/com/softropic/payam/platform/service/PlatformConfigService.java
    - src/test/java/com/softropic/payam/platform/service/PlatformConfigServiceTest.java
    - src/test/java/com/softropic/payam/platform/PlatformConfigAdminResourceIT.java
    - src/frontend/src/pages/admin/PlatformConfigPage.vue
decisions:
  - "Call updatePin(ciphertext) BEFORE save(newConfig) in orElseGet branch — JPA flush must see the PIN"
  - "Return new PlatformConfigDto(..., newConfig.getPin() != null, null) — not hardcoded false"
  - "No PlatformConfigChangedEvent in orElseGet branch — PIN-10 semantics unchanged"
metrics:
  duration_seconds: 3941
  completed_date: "2026-04-20"
  tasks_completed: 2
  files_modified: 4
---

# Phase 45 Plan 01: PIN Add Provider Fix Summary

One-liner: Closed GAP-01 by extending `PlatformConfigService.update()` orElseGet branch to encrypt and persist the PIN on new-row creation, mirroring the existing map-branch pattern.

## Objective

Close GAP-01 from the v8 milestone audit: the `orElseGet` branch of
`PlatformConfigService.update()` silently discarded any PIN supplied when
creating a brand-new provider row. This plan fixed the backend, added 4 new
tests (TDD Red/Green), and enriched the Add Provider success notify to confirm
PIN storage.

## Tasks Completed

### Task 1: Fix orElseGet branch to persist PIN + add unit and IT tests

**Branch changed:** `orElseGet` (new-row creation path in `PlatformConfigService.update()`).
**Why:** GAP-01 — the branch lacked the PIN encryption block that the `map` branch already had.

**Fix applied in `PlatformConfigService.java`:**
- Added `if (StringUtils.isNotBlank(pin)) { String ciphertext = pinCryptopher.encrypt(pin); newConfig.updatePin(ciphertext); }` BEFORE `save(newConfig)`.
- Changed return DTO from hardcoded `false` to `newConfig.getPin() != null`.
- Updated Javadoc to remove the stale statement "PIN cannot be set on initial creation via this code path" and replaced it with accurate documentation referencing PIN-09.

**Four new test method names added:**
1. `update_shouldEncryptAndPersistPinOnNewRowCreation` (unit test — PlatformConfigServiceTest)
2. `update_shouldCreateNewRowWithNoPinWhenPinIsBlank` (unit test — PlatformConfigServiceTest)
3. `putConfig_shouldPersistPinOnFirstCreation` (IT test — PlatformConfigAdminResourceIT)
4. `putConfig_shouldCreateRowWithNoPinWhenPinFieldAbsent_orElseGetBranch` (IT test — PlatformConfigAdminResourceIT)

**Event semantics UNCHANGED (PIN-10):**
`PlatformConfigChangedEvent` is NOT published from the orElseGet branch regardless of whether a PIN was set. This is preserved as before — new-row creation never fires the changed event. The comment `// PIN-10: first-time row creation does not publish an event.` is retained unchanged. No `eventPublisher.publishEvent(...)` call was added.

### Task 2: Enrich Add Provider success notify with PIN confirmation

**Before:**
```javascript
$q.notify({ type: 'positive', message: `${provider} configuration added` })
```

**After:**
```javascript
const pinMsg = updated.pinConfigured ? ' (PIN set)' : ''
$q.notify({ type: 'positive', message: `${provider} configuration added${pinMsg}` })
```

When `updated.pinConfigured === true`: snackbar reads `"${provider} configuration added (PIN set)"`.
When `updated.pinConfigured === false`: snackbar reads `"${provider} configuration added"` (unchanged).

## TDD Commit Trail

| Commit | Hash | Description |
|--------|------|-------------|
| RED 1 | 9a15eda | test(45-01): add failing unit tests for orElseGet + PIN persistence |
| RED 2 | 8209127 | test(45-01): add failing IT tests for orElseGet + PIN first-creation |
| GREEN | 04e0c00 | feat(45-01): persist PIN on orElseGet branch (GAP-01 closure) |
| Task 2 | bad41ca | feat(45-01): enrich Add Provider notify with PIN-set confirmation |

## Verification Results

- `mvn test -Dtest=PlatformConfigServiceTest`: 22 tests, 0 failures, BUILD SUCCESS
- `mvn verify -Dtest=PlatformConfigAdminResourceIT`: 12 tests, 0 failures (IT run)
- `npm run build` (frontend): SUCCESS — Vite production build green
- Full `mvn verify`: pre-existing Testcontainers/Docker E2E failures (85 errors unchanged from baseline — confirmed pre-existing by running against prior commit)

## Deviations from Plan

None — plan executed exactly as written.

## Known Stubs

None.

## Self-Check: PASSED

All files verified present:
- FOUND: src/main/java/com/softropic/payam/platform/service/PlatformConfigService.java
- FOUND: src/test/java/com/softropic/payam/platform/service/PlatformConfigServiceTest.java
- FOUND: src/test/java/com/softropic/payam/platform/PlatformConfigAdminResourceIT.java
- FOUND: src/frontend/src/pages/admin/PlatformConfigPage.vue
- FOUND: .planning/phases/45-pin-add-provider-fix/45-01-SUMMARY.md

All commits verified present: 9a15eda, 8209127, 04e0c00, bad41ca

Acceptance criteria grep checks:
- `newConfig.getPin() != null` present: YES (1 match)
- `newConfig.updatePin(ciphertext)` present: YES (1 match)
- `PIN cannot be set on initial creation via this code path` present: NO (0 matches — stale Javadoc removed)
