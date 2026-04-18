---
phase: 42
slug: pin-backend-api
status: draft
nyquist_compliant: true
wave_0_complete: true
created: 2026-04-18
---

# Phase 42 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 / Spring Boot Test / RestAssured |
| **Config file** | `src/test/resources/application.properties` |
| **Quick run command** | `mvn test -pl . -Dtest="PlatformConfig*Test"` |
| **Full suite command** | `mvn verify` |
| **Estimated runtime** | ~60 seconds |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -pl . -Dtest="PlatformConfig*Test"`
- **After every plan wave:** Run `mvn verify`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 60 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 42-01-01 | 01 | 1 | PIN-03 | unit | `mvn test -Dtest="PlatformConfigServiceTest"` | ✅ (Plan 01 Task 1 adds test secret) | ⬜ pending |
| 42-01-02 | 01 | 1 | PIN-03 | unit | `mvn test -Dtest="PlatformConfigServiceTest"` | ✅ | ⬜ pending |
| 42-02-01 | 02 | 2 | PIN-04 | unit | `mvn test -Dtest="PlatformConfigServiceTest"` | ✅ | ⬜ pending |
| 42-02-02 | 02 | 2 | PIN-04 | integration | `mvn verify -Dtest="PlatformConfigAdminResourceIT"` | ✅ (Plan 03 Task 2 creates IT class) | ⬜ pending |
| 42-03-01 | 03 | 3 | PIN-05 | integration | `mvn verify -Dtest="PlatformConfigAdminResourceIT"` | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

All Wave 0 prerequisites are folded into the plan tasks themselves — no separate Wave 0 plan is required:

- [x] `src/test/resources/application.properties` — `payam.platform.pin-encryption-secret=test-pin-secret-for-tests` is added by **Plan 01 Task 1** (the very first action of Wave 1) so the `pinCryptopher` bean starts in every IT context that follows
- [x] Test stubs for PIN-03 (service-layer encrypt/save) — covered by **Plan 02 Task 1 (RED)** in `PlatformConfigServiceTest`
- [x] Test stubs for PIN-04 (DTO `pinConfigured` field) — covered by **Plan 01 Task 3** which extends `PlatformConfigServiceTest` to assert on the new `pinConfigured` accessor; HTTP-level `pinConfigured` shape is then covered by **Plan 03 Task 2** in `PlatformConfigAdminResourceIT`
- [x] Test stubs for PIN-05 (GET /pin endpoint) — covered by **Plan 02 Task 1 (RED)** at the service boundary and **Plan 03 Task 2** at the HTTP boundary in `PlatformConfigAdminResourceIT`

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Empty PIN on PUT does not overwrite existing PIN | PIN-03 | Semantic no-op behavior | PUT with `{"pin": ""}` then GET /pin — verify original PIN still returned |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references (folded into Plan 01 Task 1 + Plan 03 Task 2)
- [x] No watch-mode flags
- [x] Feedback latency < 60s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** approved
