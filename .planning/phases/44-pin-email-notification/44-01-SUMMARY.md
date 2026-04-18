---
phase: 44-pin-email-notification
plan: "01"
subsystem: platform-config
tags: [pin, event, tdd, PIN-10, PlatformConfigChangedEvent, conditional-publish]
dependency_graph:
  requires: [42-02]
  provides: [PlatformConfigChangedEvent-6-component, PIN-10-fire-rules, SecurityUtil-changedBy]
  affects: [platform-config-service, platform-config-event, platform-config-email-listener]
tech_stack:
  added: []
  patterns: [conditional-event-publishing, oldPin-snapshot-before-mutation, SecurityUtil-request-thread-resolution, unknown-fallback-for-null-username]
key_files:
  created: []
  modified:
    - src/main/java/com/softropic/payam/platform/contract/event/PlatformConfigChangedEvent.java
    - src/main/java/com/softropic/payam/platform/service/PlatformConfigService.java
    - src/test/java/com/softropic/payam/platform/service/PlatformConfigServiceTest.java
decisions:
  - "Snapshot oldPin before mutation — config.getPin() must be called before config.updatePin() to capture pre-mutation state for pinChanged logic"
  - "pinChanged = StringUtils.isNotBlank(pin) && oldPin != null — first-time PIN (oldPin==null) does not set pinChanged=true per PIN-10"
  - "Suppress event entirely when !msisdnChanged && !pinChanged — eliminates spurious notifications on no-op updates"
  - "Remove publishEvent from orElseGet branch — first-time row creation is not a change event"
  - "Resolve changedBy on request thread (inside @Transactional) — SecurityContextHolder is thread-local; AFTER_COMMIT listener runs on a different thread"
  - "changedBy fallback to 'unknown' when SecurityUtil returns null — null-safe for unauthenticated or test contexts"
metrics:
  duration: "~15 minutes"
  completed: "2026-04-18T16:10:27Z"
  tasks_completed: 2
  files_changed: 3
  files_created: 0
---

# Phase 44 Plan 01: PIN-10 Event Widening + Conditional Publish Summary

6-component PlatformConfigChangedEvent with PIN-10 conditional fire rules: event fires only when MSISDN changed or existing PIN replaced; first-time PIN creation, no-op updates, and new-row creation are all suppressed; changedBy resolved on request thread via SecurityUtil with "unknown" fallback.

## Objective Achieved

Widened `PlatformConfigChangedEvent` from 3 to 6 components (`msisdnChanged`, `pinChanged`, `changedBy` added), rewrote `PlatformConfigService.update()` publishing logic to enforce PIN-10 semantics: conditional guard, oldPin snapshot before mutation, SecurityUtil injection with null fallback. TDD discipline maintained: RED commit with compile-failing tests, GREEN commit with all 20 tests passing. Plan 44-02 can now consume `event.msisdnChanged()`, `event.pinChanged()`, `event.changedBy()`.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 (RED) | Add failing PIN-10 test cases for PlatformConfigService event emission | fcc8767 | PlatformConfigServiceTest.java |
| 2 (GREEN) | Widen PlatformConfigChangedEvent and enforce PIN-10 fire rules in service | f79fce0 | PlatformConfigChangedEvent.java, PlatformConfigService.java |

## Files Modified

- `src/main/java/com/softropic/payam/platform/contract/event/PlatformConfigChangedEvent.java` — Replaced 3-component record with 6-component record: added `boolean msisdnChanged`, `boolean pinChanged`, `String changedBy`; updated Javadoc with PIN-10 fire rules documentation.
- `src/main/java/com/softropic/payam/platform/service/PlatformConfigService.java` — Added `SecurityUtil` and `Objects` imports; injected `private final SecurityUtil securityUtil` field via @RequiredArgsConstructor; rewrote `.map()` lambda to snapshot `oldPin` before mutation, compute `msisdnChanged`/`pinChanged` booleans, suppress event when neither is true, resolve `changedBy` with "unknown" fallback; removed `eventPublisher.publishEvent()` from `.orElseGet()` branch entirely.
- `src/test/java/com/softropic/payam/platform/service/PlatformConfigServiceTest.java` — Added `@Mock SecurityUtil securityUtil` field; added `ArgumentCaptor` import; added `when(securityUtil.getCurrentUserName()).thenReturn("admin@test")` stubs to MSISDN-changing existing tests; changed `update_shouldCreateNewConfigIfNotFound` assertion from `verify(eventPublisher).publishEvent(...)` to `verifyNoInteractions(eventPublisher)`; added 10 new PIN-10 test methods (A-J covering all suppression branches).

## Decisions Made

| Decision | Rationale |
|----------|-----------|
| Snapshot `oldPin = config.getPin()` before mutation | `config.updatePin(ciphertext)` overwrites the field; must read `oldPin` first to detect whether a PIN previously existed |
| `pinChanged = StringUtils.isNotBlank(pin) && oldPin != null` | Only when both conditions are true is this a PIN replacement; if `oldPin == null` it is first-time creation (PIN-10 suppression) |
| Guard with `if (msisdnChanged || pinChanged)` | Eliminates spurious notifications when admin submits a PUT with unchanged MSISDN and blank PIN |
| Remove `publishEvent` from `orElseGet` branch | New-row creation is not a "change" in the audit sense; PIN-10 explicitly excludes this path |
| Resolve `changedBy` on request thread inside service | SecurityContextHolder is thread-local; by AFTER_COMMIT time the listener runs on a pool thread where the context is empty |
| Fallback to `"unknown"` when SecurityUtil returns null | Defensive null-safety for unit tests (no SecurityContext) and edge cases |

## TDD Discipline

- RED commit `fcc8767`: 10 new PIN-10 test methods + `@Mock SecurityUtil` field added; `update_shouldCreateNewConfigIfNotFound` updated to `verifyNoInteractions(eventPublisher)`. test-compile fails with `cannot find symbol` errors for `msisdnChanged()`, `pinChanged()`, `changedBy()` on the 3-component record — confirmed RED signal.
- GREEN commit `f79fce0`: 6-component record + conditional publish logic. All 20 `PlatformConfigServiceTest` tests pass; `mvn compile` exits 0.

## Plan 44-02 Dependencies Satisfied

- `event.msisdnChanged()` — available on the 6-component record
- `event.pinChanged()` — available on the 6-component record
- `event.changedBy()` — available on the 6-component record; "unknown" fallback prevents null values
- `PlatformConfigEmailListener.onConfigChanged()` still compiles (reads only `provider()`, `oldMsisdn()`, `newMsisdn()`) — no callers broken

## Requirements Satisfied

- PIN-10: Event carries `msisdnChanged`, `pinChanged`, `changedBy`; fired only when MSISDN changed OR existing PIN replaced; suppressed on first-time PIN creation (oldPin was null), on no-op updates, and on new-row (orElseGet) creation; `changedBy` resolved via SecurityUtil with "unknown" fallback

## Verification Results

- `mvn -o test -Dtest=PlatformConfigServiceTest` exits 0 — Tests run: 20, Failures: 0, Errors: 0
- `mvn -o compile -q` exits 0 — no production callers broken
- 2 commits on branch: `test(44-01):` RED (compile-failing) followed by `feat(44-01):` GREEN (all tests pass)
- `grep -c "eventPublisher.publishEvent" PlatformConfigService.java` returns 1 (orElseGet branch removed)

## Deviations from Plan

None — plan executed exactly as written. The worktree required a `git merge main` to incorporate Phase 41-43 commits before work could begin; this is operational setup, not a plan deviation.

## Known Stubs

None. All changes are complete production logic. Plan 44-02 (listener + template) is required to make the PIN-10 behavior observable end-to-end, but that is explicitly noted in the plan objective as out of scope for Plan 44-01.

## Self-Check: PASSED

- FOUND: PlatformConfigChangedEvent.java
- FOUND: PlatformConfigService.java
- FOUND: 44-01-SUMMARY.md
- FOUND: fcc8767 (RED commit)
- FOUND: f79fce0 (GREEN commit)
- Tests run: 20, Failures: 0, Errors: 0
