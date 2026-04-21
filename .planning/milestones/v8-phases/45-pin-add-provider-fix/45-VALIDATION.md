---
phase: 45
slug: pin-add-provider-fix
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-20
---

# Phase 45 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Mockito (unit); Spring Boot Test + Testcontainers (IT) |
| **Config file** | `src/test/resources/` (inherits from project; IT uses `@ActiveProfiles("dev")`) |
| **Quick run command** | `mvn test -pl . -Dtest=PlatformConfigServiceTest -q` |
| **Full suite command** | `mvn verify` |
| **Estimated runtime** | ~120 seconds (full verify with Testcontainers) |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -Dtest=PlatformConfigServiceTest -q`
- **After every plan wave:** Run `mvn verify`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 120 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 45-01-01 | 01 | 0 | PIN-09 | unit stub | `mvn test -Dtest=PlatformConfigServiceTest -q` | ❌ W0 | ⬜ pending |
| 45-01-02 | 01 | 0 | PIN-09 | IT stub | `mvn verify -Dtest=PlatformConfigAdminResourceIT` | ❌ W0 | ⬜ pending |
| 45-01-03 | 01 | 1 | PIN-09 | unit | `mvn test -Dtest=PlatformConfigServiceTest#update_shouldEncryptAndPersistPinOnNewRowCreation` | ❌ W0 | ⬜ pending |
| 45-01-04 | 01 | 1 | PIN-09 | unit | `mvn test -Dtest=PlatformConfigServiceTest#update_shouldCreateNewRowWithNoPinWhenPinIsBlank` | ❌ W0 | ⬜ pending |
| 45-01-05 | 01 | 1 | PIN-09 | IT | `mvn verify -Dtest=PlatformConfigAdminResourceIT` | ❌ W0 | ⬜ pending |
| 45-01-06 | 01 | 1 | PIN-09 | IT | `mvn verify -Dtest=PlatformConfigAdminResourceIT` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] New test method `update_shouldEncryptAndPersistPinOnNewRowCreation` stub in `PlatformConfigServiceTest.java`
- [ ] New test method `update_shouldCreateNewRowWithNoPinWhenPinIsBlank` stub in `PlatformConfigServiceTest.java`
- [ ] New test method `putConfig_shouldPersistPinOnFirstCreation` stub in `PlatformConfigAdminResourceIT.java`
- [ ] New test method `putConfig_shouldCreateRowWithNoPinWhenPinFieldAbsent_orElseGetBranch` stub in `PlatformConfigAdminResourceIT.java`

*Wave 0 must add stubs so Wave 1 can fill them with real assertions.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Admin sees PIN-set indicator on provider card after Add Provider | PIN-09 | UI state update is visual | Open admin UI → Add Provider with PIN → confirm card shows PIN indicator |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 120s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
