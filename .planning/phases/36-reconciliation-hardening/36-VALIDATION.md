---
phase: 36
slug: reconciliation-hardening
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-14
---

# Phase 36 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + AssertJ |
| **Config file** | `pom.xml` (maven-failsafe-plugin for IT tests) |
| **Quick run command** | `mvn test -Dtest=ReconciliationProviderRunnerTest,ReconciliationJobIT -Dfailsafe.skip=true` |
| **Full suite command** | `mvn verify` |
| **Estimated runtime** | ~60 seconds (quick), ~3 minutes (full) |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -Dtest=ReconciliationProviderRunnerTest,ReconciliationJobIT -Dfailsafe.skip=true`
- **After every plan wave:** Run `mvn verify`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 60 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 36-01-01 | 01 | 0 | RECON-01, RECON-02 | unit stub | `mvn test -Dtest=ReconciliationProviderRunnerTest` | ❌ W0 | ⬜ pending |
| 36-01-02 | 01 | 0 | RECON-01, RECON-02 | integration stub | `mvn verify -Dtest=ReconciliationJobIT` | ✅ extend | ⬜ pending |
| 36-01-03 | 01 | 1 | RECON-01 | unit | `mvn test -Dtest=ReconciliationProviderRunnerTest` | ❌ W0 | ⬜ pending |
| 36-01-04 | 01 | 1 | RECON-02 | unit | `mvn test -Dtest=ReconciliationProviderRunnerTest` | ❌ W0 | ⬜ pending |
| 36-01-05 | 01 | 2 | RECON-01 | integration | `mvn verify -Dtest=ReconciliationJobIT` | ✅ extend | ⬜ pending |
| 36-01-06 | 01 | 2 | RECON-02 | integration | `mvn verify -Dtest=ReconciliationJobIT` | ✅ extend | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/softropic/payam/reconciliation/ReconciliationProviderRunnerTest.java` — unit test stubs for RECON-01 (Pageable call verification) and RECON-02 (FAILED transition on exception)
- [ ] New `@Test` in `ReconciliationJobIT`: integration test seeding 1001 rows, asserting `totalChecked == 1001` and pages ≤ 1000
- [ ] New `@Test` in `ReconciliationJobIT`: integration test asserting `FAILED` status when discrepancy persistence throws

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Heap usage does not grow linearly | RECON-01 | Memory profiling requires JVM tooling | Run reconciliation with 5000 rows; observe heap via jconsole or GC logs |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 60s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
