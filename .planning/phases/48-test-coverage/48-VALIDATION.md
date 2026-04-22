---
phase: 48
slug: test-coverage
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-22
---

# Phase 48 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 / PITest / Testcontainers |
| **Config file** | `pom.xml` |
| **Quick run command** | `mvn test -pl . -Dtest=LedgerBalanceGuardTest,LedgerPostingTest,LedgerVerifierTest` |
| **Full suite command** | `mvn verify` |
| **Estimated runtime** | ~60–120 seconds (integration tests with Testcontainers) |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -pl . -Dtest=LedgerBalanceGuardTest,LedgerPostingTest`
- **After every plan wave:** Run `mvn verify`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 120 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 48-01-01 | 01 | 1 | TEST-02 | unit | `mvn test -Dtest=LedgerBalanceGuardTest` | ✅ | ⬜ pending |
| 48-01-02 | 01 | 1 | TEST-03 | unit | `mvn test -Dtest=LedgerBalanceGuardTest` | ✅ | ⬜ pending |
| 48-01-03 | 01 | 1 | TEST-05 | mutation | `mvn verify -Ppittest` | ✅ | ⬜ pending |
| 48-01-04 | 01 | 1 | TEST-06 | integration | `mvn verify -Dtest=LedgerServiceIT` | ✅ | ⬜ pending |
| 48-01-05 | 01 | 1 | TEST-07 | unit | `mvn test -Dtest=LedgerVerifierTest` | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

Existing infrastructure covers all phase requirements. No new test infrastructure setup needed — JUnit 5, Testcontainers, and PITest are already on the classpath.

---

## Manual-Only Verifications

All phase behaviors have automated verification.

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 120s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
