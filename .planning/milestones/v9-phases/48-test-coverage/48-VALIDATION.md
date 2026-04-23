---
phase: 48
slug: test-coverage
status: approved
nyquist_compliant: true
wave_0_complete: true
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
| **Quick run command** | `mvn test -pl . -Dtest=LedgerBalanceGuardTest,LedgerPostingTest` |
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
| 48-01-01 | 01 | 1 | TEST-01, TEST-04 | unit | `mvn test -pl . -Dtest=LedgerBalanceGuardTest,LedgerPostingTest -q` | ✅ | ⬜ pending |
| 48-01-02 | 01 | 1 | TEST-02, TEST-03 | unit | `mvn test -pl . -Dtest=LedgerBalanceGuardTest -q` | ✅ | ⬜ pending |
| 48-01-03 | 01 | 1 | TEST-05 | mutation | `mvn pitest:mutationCoverage -Pmutation -pl . -q` | ✅ | ⬜ pending |
| 48-02-01 | 02 | 2 | TEST-07 | unit | `mvn test -pl . -Dtest=LedgerVerifierTest -q` | ❌ W2 | ⬜ pending |
| 48-02-02 | 02 | 2 | TEST-06 | integration | `mvn verify -pl . -Dit.test=LedgerServiceIT -DfailIfNoTests=false -q` | ✅ | ⬜ pending |
| 48-02-03 | 02 | 2 | TEST-08 gate | full-suite | `mvn verify -pl . -q` | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*
*W2 = file created during Wave 2 execution (plan 02 Task 1)*

---

## Wave 0 Requirements

Existing infrastructure covers all phase requirements. No new test infrastructure setup needed — JUnit 5, Testcontainers, and PITest are already on the classpath. `LedgerVerifierTest.java` is created during plan 02 Task 1 execution (not a prerequisite).

---

## Manual-Only Verifications

All phase behaviors have automated verification.

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 120s (note: PITest and IT tasks exceed 30s but are unavoidable for mutation testing and Testcontainers — documented in Sampling Rate)
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** approved 2026-04-22
