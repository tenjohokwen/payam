---
phase: 58
slug: integration-e2e-test-suite
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-05-05
---

# Phase 58 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Testcontainers + WireMock + Awaitility |
| **Config file** | `pom.xml` (maven-failsafe-plugin, IT/E2E suffixes) |
| **Quick run command** | `mvn test -pl . -Dtest=DisbursementResourceIT -q` |
| **Full suite command** | `mvn verify -q` |
| **Estimated runtime** | ~3–5 minutes (Testcontainers startup) |

---

## Sampling Rate

- **After every task commit:** Run quick unit/IT compile check: `mvn test-compile -q`
- **After every plan wave:** Run `mvn verify -q`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** ~300 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 58-01-01 | 01 | 1 | SC-1 | E2E | `mvn verify -Dit.test=MtnDisbursementE2EIT -q` | ✅ | ⬜ pending |
| 58-01-02 | 01 | 1 | SC-2 | E2E | `mvn verify -Dit.test=OrangeDisbursementE2EIT -q` | ✅ | ⬜ pending |
| 58-02-01 | 02 | 1 | SC-3 | E2E | `mvn verify -Dit.test=DisbursementAdminApprovalE2EIT -q` | ❌ W0 | ⬜ pending |
| 58-03-01 | 03 | 2 | SC-4 | IT | `mvn verify -Dit.test=DisbursementIdempotencyRetryIT -q` | ✅ | ⬜ pending |
| 58-04-01 | 04 | 3 | SC-5 | all | `mvn verify -q` | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/softropic/payam/e2e/disbursement/DisbursementAdminApprovalE2EIT.java` — stub class for SC-3

*All other test classes already exist; only the admin approval E2E class needs Wave 0 creation.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| None | — | — | — |

*All phase behaviors have automated verification.*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 300s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
