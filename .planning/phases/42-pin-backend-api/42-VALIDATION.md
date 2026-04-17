---
phase: 42
slug: pin-backend-api
status: draft
nyquist_compliant: false
wave_0_complete: false
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
| 42-01-01 | 01 | 0 | PIN-03 | unit | `mvn test -Dtest="PlatformConfigServiceTest"` | ❌ W0 | ⬜ pending |
| 42-01-02 | 01 | 1 | PIN-03 | unit | `mvn test -Dtest="PlatformConfigServiceTest"` | ✅ | ⬜ pending |
| 42-02-01 | 02 | 1 | PIN-04 | unit | `mvn test -Dtest="PlatformConfigDtoTest"` | ❌ W0 | ⬜ pending |
| 42-02-02 | 02 | 1 | PIN-04 | integration | `mvn verify -Dtest="PlatformConfigIT"` | ❌ W0 | ⬜ pending |
| 42-03-01 | 03 | 2 | PIN-05 | integration | `mvn verify -Dtest="PlatformConfigIT"` | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/resources/application.properties` — add `payam.platform.pin-encryption-secret=test-secret-for-testing` so Cryptopher bean starts in test context
- [ ] Test stubs for PIN-03 (service-layer encrypt/save) in `PlatformConfigServiceTest`
- [ ] Test stubs for PIN-04 (DTO pinConfigured field) in `PlatformConfigDtoTest`
- [ ] Test stubs for PIN-05 (GET /pin endpoint) in `PlatformConfigIT`

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Empty PIN on PUT does not overwrite existing PIN | PIN-03 | Semantic no-op behavior | PUT with `{"pin": ""}` then GET /pin — verify original PIN still returned |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 60s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
