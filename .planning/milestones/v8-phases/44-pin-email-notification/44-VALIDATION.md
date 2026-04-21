---
phase: 44
slug: pin-email-notification
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-18
---

# Phase 44 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + AssertJ (Maven Failsafe for ITs) |
| **Config file** | `pom.xml` (maven-failsafe-plugin; `*IT.java` pattern) |
| **Quick run command** | `mvn test -Dtest=PlatformConfigServiceTest,PlatformConfigEmailListenerTest` |
| **Full suite command** | `mvn verify` |
| **Estimated runtime** | ~30 seconds (unit), ~120 seconds (full) |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -Dtest=PlatformConfigServiceTest,PlatformConfigEmailListenerTest`
- **After every plan wave:** Run `mvn verify`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 30 seconds (unit tasks), 120 seconds (wave merge)

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 44-01-01 | 01 | 0 | PIN-11 | unit | `mvn test -Dtest=PlatformConfigEmailListenerTest` | ❌ W0 | ⬜ pending |
| 44-01-02 | 01 | 1 | PIN-10 | unit | `mvn test -Dtest=PlatformConfigServiceTest` | ✅ extend | ⬜ pending |
| 44-01-03 | 01 | 1 | PIN-10 | unit | `mvn test -Dtest=PlatformConfigServiceTest` | ✅ extend | ⬜ pending |
| 44-01-04 | 01 | 1 | PIN-11 | unit | `mvn test -Dtest=PlatformConfigEmailListenerTest` | ❌ W0 | ⬜ pending |
| 44-01-05 | 01 | 1 | PIN-11 | unit | `mvn test -Dtest=PlatformConfigEmailListenerTest` | ❌ W0 | ⬜ pending |
| 44-01-06 | 01 | 2 | PIN-10, PIN-11 | integration | `mvn verify -Dit.test=PlatformConfigAdminResourceIT` | ✅ extend | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/softropic/payam/email/infrastructure/listener/PlatformConfigEmailListenerTest.java` — unit test stub for listener data map (PIN-11); does not currently exist

*Wave 0 must create the listener unit test file before Wave 1 tasks execute.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Email body never contains PIN value (plaintext or ciphertext) | PIN-11 | Visual inspection of rendered email content | Trigger a PIN update via PUT, capture email in Mailhog/dev inbox, confirm no PIN characters appear in body |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references (`PlatformConfigEmailListenerTest`)
- [ ] No watch-mode flags
- [ ] Feedback latency < 120s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
