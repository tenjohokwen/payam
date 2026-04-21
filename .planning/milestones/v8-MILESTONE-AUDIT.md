---
milestone: v8
audited: 2026-04-20T11:28:19Z
status: gaps_found
scores:
  requirements: 11/11
  phases: 4/4
  integration: 10/11
  flows: 4/5
gaps:
  requirements: []
  integration:
    - id: GAP-01
      req_ids: [PIN-09]
      severity: medium
      title: "Add Provider dialog PIN is silently discarded on new row creation"
      phase: "43-pin-frontend + 42-pin-backend-api"
      description: >
        PlatformConfigPage.vue Add Provider dialog renders a masked PIN input with eye-toggle (satisfying
        the visual/behavioral spec for PIN-09). However, addProvider() sends the PIN to
        PlatformConfigService.update() which takes the orElseGet branch for new rows — this branch
        constructs PlatformConfig without the pin parameter, silently discarding it. The admin
        receives no error. The response correctly shows pinConfigured=false, but the UI provides
        no guidance that the PIN must be re-entered on a subsequent edit. The PIN entered in the
        Add Provider dialog is never persisted on first creation.
      files:
        - "src/main/java/com/softropic/payam/platform/service/PlatformConfigService.java (orElseGet branch, lines ~120-130)"
        - "src/frontend/src/pages/admin/PlatformConfigPage.vue (addProvider(), lines ~237-243)"
  flows: []
tech_debt:
  - phase: 43-pin-frontend
    id: TD-01
    items:
      - "Dead updatePlatformConfig(provider, platformMsisdn) method in admin.api.js (lines 59-61) is no longer called from PlatformConfigPage.vue — all saves use updatePlatformConfigFull. Risk: future developers may use stale method and bypass PIN semantics."
  - phase: 44-pin-email-notification
    id: TD-02
    items:
      - "PlatformConfigEmailListener uses @EventListener (synchronous, inside update() transaction) rather than @TransactionalEventListener directly. Matches established project pattern, but a failure in onConfigChanged() would roll back the config update. Low risk — pattern is consistent with TenantLifecycleEmailListener."
  - phase: 43-pin-frontend
    id: TD-03
    items:
      - "Countdown display may flash '0s' for one render cycle before reMaskPin clears it — setTimeout (60000ms) and the 60th setInterval decrement may not execute atomically in the same JS task. Cosmetic only."
nyquist:
  compliant_phases: [42]
  partial_phases: [41, 43, 44]
  missing_phases: []
  overall: partial
---

# Milestone v8 — Audit Report

**Milestone:** v8 Platform Config PIN
**Phases:** 41–44
**Audited:** 2026-04-20
**Status:** ⚠ gaps_found (1 medium gap — see GAP-01)

---

## Scope

| Phase | Name | Plans | VERIFICATION Status | Score |
|-------|------|-------|---------------------|-------|
| 41 | PIN Schema & Encryption Config | 1/1 | passed | 4/4 |
| 42 | PIN Backend API | 3/3 | passed | 17/17 |
| 43 | PIN Frontend | 1/1 | passed | 8/8 |
| 44 | PIN Email Notification | 2/2 | passed | 10/10 |

All 4 phases have VERIFICATION.md with status `passed`. No unverified phases.

---

## Requirements Coverage (3-Source Cross-Reference)

Sources: (V) VERIFICATION.md · (S) SUMMARY frontmatter · (R) REQUIREMENTS.md traceability

| Req | Description | VERIFICATION | SUMMARY | REQUIREMENTS.md | Final Status |
|-----|-------------|-------------|---------|-----------------|-------------|
| PIN-01 | AES256-encrypted pin column, Flyway migration | SATISFIED (Ph 41) | ✓ 41-01 | [x] Complete | **satisfied** |
| PIN-02 | pinEncryptionSecret in PayamPlatformProperties | SATISFIED (Ph 41) | ✓ 41-01 | [x] Complete | **satisfied** |
| PIN-03 | PUT accepts optional pin, validates, encrypts atomically | SATISFIED (Ph 42) | not in frontmatter | [ ] Stale | **satisfied** |
| PIN-04 | GET returns pinConfigured boolean, no PIN value | SATISFIED (Ph 42) | not in frontmatter | [ ] Stale | **satisfied** |
| PIN-05 | GET /pin reveal endpoint, 404 if none set | SATISFIED (Ph 42) | not in frontmatter | [ ] Stale | **satisfied** |
| PIN-06 | Masked PIN input on provider card | SATISFIED (Ph 43) | ✓ 43-01 | [ ] Stale | **satisfied** |
| PIN-07 | Eye reveal + 60s countdown, auto-mask on expiry | SATISFIED (Ph 43) + human UAT | ✓ 43-01 | [ ] Stale | **satisfied** |
| PIN-08 | Save preserves existing PIN when field empty | SATISFIED (Ph 43) + human UAT | ✓ 43-01 | [ ] Stale | **satisfied** |
| PIN-09 | Add Provider dialog PIN field, no timer | SATISFIED visual spec (Ph 43) | ✓ 43-01 | [ ] Stale | **partial** — see GAP-01 |
| PIN-10 | Event fires on change, suppressed on no-op/first-time | SATISFIED (Ph 44) | not in frontmatter | [ ] Stale | **satisfied** |
| PIN-11 | Email: changed fields + username + timestamp, no PIN value | SATISFIED (Ph 44) | ✓ 44-02 | [x] Complete | **satisfied** |

**Score:** 11/11 requirements satisfied at code level. 1 partial (PIN-09) due to UX gap in E2E flow.

**REQUIREMENTS.md note:** Traceability table is stale — PIN-03 through PIN-10 show "Pending" but phase VERIFICATION.md and SUMMARY evidence confirm implementation. The checkboxes were not updated during execution. Will be corrected in requirements archive during milestone completion.

---

## Cross-Phase Integration

### Wiring Verified

| From | To | Via | Status | Req IDs |
|------|----|-----|--------|---------|
| Phase 41 V24 migration | Phase 42 PlatformConfig entity | main.platform_config.pin VARCHAR(500) → @Column(name="pin") → updatePin(ciphertext) | WIRED | PIN-01, PIN-03 |
| Phase 41 PayamPlatformProperties.pinEncryptionSecret | Phase 42 pinCryptopher @Bean | getPinEncryptionSecret() → new Cryptopher(secret) | WIRED | PIN-02, PIN-03 |
| Phase 42 API endpoints (GET/PUT/GET-pin) | Phase 43 adminApi.js + PlatformConfigPage.vue | getPlatformConfigPin(), updatePlatformConfigFull(), getPlatformConfig() | WIRED | PIN-03, PIN-04, PIN-05, PIN-06, PIN-07, PIN-08 |
| Phase 42 PlatformConfigService.update() event publish | Phase 44 PlatformConfigEmailListener | PlatformConfigChangedEvent → @EventListener → Envelope → MailManager AFTER_COMMIT | WIRED | PIN-10, PIN-11 |
| Phase 41 pin column | Phase 44 pinChanged logic (via Phase 42) | PlatformConfigService.update() oldPin snapshot → pinChanged = isNotBlank(pin) && oldPin != null | WIRED | PIN-10 |

### Gaps Found

**GAP-01 (medium) — Add Provider dialog PIN discarded silently**

The Add Provider dialog in `PlatformConfigPage.vue` shows a PIN input field and passes the value to `updatePlatformConfigFull()` on submit. However, `PlatformConfigService.update()` takes the `orElseGet` branch for new provider rows and constructs `PlatformConfig` without the pin parameter — the PIN is silently discarded.

**Affected req:** PIN-09 (partial — visual spec met, functional E2E broken for this sub-flow)
**Files:**
- `src/main/java/com/softropic/payam/platform/service/PlatformConfigService.java` (`orElseGet` branch)
- `src/frontend/src/pages/admin/PlatformConfigPage.vue` (`addProvider()` function)

**Severity:** Medium — affects only the "add new provider and set PIN simultaneously" flow. The more common flow (add provider, then save PIN via the provider card) works correctly. No data corruption risk; `pinConfigured=false` is returned correctly.

**Fix options:**
1. **Backend fix (preferred):** Extend `orElseGet` to accept the pin parameter and call `updatePin(ciphertext)` when non-blank — aligns with the UX intent
2. **Frontend fix (UX):** Remove the PIN field from the Add Provider dialog and add a note "Set PIN after adding provider"; or display a post-creation message "Provider added. Set a PIN via the provider card."

---

## E2E Flows

| Flow | Status | Req IDs |
|------|--------|---------|
| Admin sets PIN for first time (PUT → encrypt → persist → GET reveals) | ✅ COMPLETE | PIN-03, PIN-05, PIN-10 |
| Admin updates existing PIN (PUT → email fires with pinChanged=true) | ✅ COMPLETE | PIN-03, PIN-10, PIN-11 |
| Admin updates MSISDN only (email fires with msisdnChanged=true, PIN preserved) | ✅ COMPLETE | PIN-08, PIN-10, PIN-11 |
| Admin reveals PIN via UI (eye → 60s → auto-mask) | ✅ COMPLETE | PIN-07 |
| Admin saves via UI with empty PIN field (existing PIN preserved) | ✅ COMPLETE | PIN-08 |
| Admin adds provider via dialog with PIN field populated | ⚠ BROKEN | PIN-09 (GAP-01) |

---

## Security Checks

| Check | Status |
|-------|--------|
| GET /pin requires ROLE_ADMIN or ROLE_LTD_ADMIN (@PreAuthorize class-level) | ✅ PASS |
| Email template has zero ${map.pin} references | ✅ PASS |
| PlatformConfigDto always carries pin=null (ciphertext never sent to client) | ✅ PASS |
| Cryptopher fails fast at startup when pinEncryptionSecret is blank | ✅ PASS |

---

## Nyquist Compliance

| Phase | VALIDATION.md | nyquist_compliant | Action |
|-------|---------------|-------------------|--------|
| 41 | exists (draft) | false | Run `/gsd:validate-phase 41` to complete |
| 42 | exists (draft) | **true** | ✅ Compliant |
| 43 | exists (draft) | false | Run `/gsd:validate-phase 43` to complete |
| 44 | exists (draft) | false | Run `/gsd:validate-phase 44` to complete |

**Overall Nyquist:** 1/4 compliant. Phase 41, 43, 44 have VALIDATION.md files but are not marked nyquist_compliant. Not a blocker for milestone completion.

---

## Tech Debt

| ID | Phase | Item | Severity |
|----|-------|------|----------|
| TD-01 | 43-pin-frontend | Dead `updatePlatformConfig()` in admin.api.js — no longer called; risk of misuse by future devs | Low |
| TD-02 | 44-pin-email-notification | `@EventListener` (synchronous) on PlatformConfigEmailListener — failure would roll back config update. Matches project pattern but @TransactionalEventListener would be safer. | Low |
| TD-03 | 43-pin-frontend | Countdown may flash '0s' before auto-mask clears it | Cosmetic |

---

## Summary

**11/11 requirements satisfied** at implementation level. All 4 phases passed verification. All security checks pass.

**One medium gap (GAP-01):** The Add Provider dialog PIN field renders correctly per the PIN-09 visual spec, but the entered PIN is silently discarded by the backend `orElseGet` branch on new row creation. The admin has no feedback. Fix requires either a backend extension or a frontend UX change — estimated 1–2 hours.

**3 tech debt items** — none blocking milestone closure.

---

_Audited: 2026-04-20T11:28:19Z_
_Auditor: Claude (gsd-audit-milestone)_
