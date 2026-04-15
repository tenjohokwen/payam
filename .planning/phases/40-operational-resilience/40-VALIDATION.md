---
phase: 40
slug: operational-resilience
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-15
---

# Phase 40 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 / Spring Boot Test / Testcontainers |
| **Config file** | `pom.xml` (maven-failsafe-plugin) |
| **Quick run command** | `mvn test -pl <module> -Dtest=<TestClass>` |
| **Full suite command** | `mvn verify` |
| **Estimated runtime** | ~120 seconds |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -pl <module> -Dtest=<TestClass>`
- **After every plan wave:** Run `mvn verify`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 120 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 40-01-01 | 01 | 1 | OPS-01 | annotation | `mvn verify -pl <poller-module>` | ✅ | ⬜ pending |
| 40-02-01 | 02 | 1 | OPS-03 | integration | `mvn verify -Dtest=TenantContextExceptionIT` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `TenantContextExceptionIT.java` — IT stub for OPS-03 exception-path test

*Existing infrastructure covers OPS-01 (annotation-only change).*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Lock released after simulated node crash | OPS-01 | Requires killing the JVM mid-transaction | Kill the poller JVM while holding the advisory lock; verify Postgres shows no dangling lock within timeout window |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 120s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
