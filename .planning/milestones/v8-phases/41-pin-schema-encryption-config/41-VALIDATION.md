---
phase: 41
slug: pin-schema-encryption-config
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-17
---

# Phase 41 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 / Spring Boot Test / Flyway Test |
| **Config file** | `pom.xml` (Maven Surefire / Failsafe) |
| **Quick run command** | `mvn test -pl . -Dtest=PlatformConfigTest,PayamPlatformPropertiesTest` |
| **Full suite command** | `mvn verify` |
| **Estimated runtime** | ~60 seconds |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -pl . -Dtest=PlatformConfigTest,PayamPlatformPropertiesTest`
- **After every plan wave:** Run `mvn verify`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 60 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 41-01-01 | 01 | 1 | PIN-01 | migration | `mvn flyway:migrate -Dflyway.url=... && mvn verify` | ❌ W0 | ⬜ pending |
| 41-01-02 | 01 | 2 | PIN-01 | entity | `mvn test -Dtest=PlatformConfigTest` | ❌ W0 | ⬜ pending |
| 41-02-01 | 02 | 1 | PIN-02 | unit | `mvn test -Dtest=PayamPlatformPropertiesTest` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/.../PlatformConfigTest.java` — test pin field nullable, field holds null by default
- [ ] `src/test/java/.../PayamPlatformPropertiesTest.java` — test pinEncryptionSecret binding from `payam.platform.pin-encryption-secret`

*If existing test infra covers: "Existing infrastructure covers all phase requirements."*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Flyway migration runs cleanly on live DB with existing rows | PIN-01 | Requires live DB connection | Run `mvn flyway:migrate` against staging DB, verify no errors |
| `PLATFORM_PIN_ENCRYPTION_SECRET` env var resolves at runtime | PIN-02 | Env var binding needs runtime check | Start app with env set, verify no startup errors |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 60s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
