---
phase: 54
slug: v31-schema-migration
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-05-02
---

# Phase 54 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + AssertJ + Mockito + Spring Boot Test + Testcontainers (PostgreSQL) |
| **Config file** | `pom.xml` (maven-surefire-plugin, maven-failsafe-plugin) |
| **Quick run command** | `mvn test -pl . -Dtest=DisbursementStatusTest,DisbursementRepositoryIT` |
| **Full suite command** | `mvn verify` |
| **Estimated runtime** | ~3 minutes |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -Dtest=DisbursementStatusTest`
- **After every plan wave:** Run `mvn verify`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** ~180 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 54-01-01 | 01 | 0 | SCHEMA-01, SCHEMA-02, SCHEMA-03 | integration | `mvn verify -Dtest=DisbursementTransactionRefIT` | ❌ W0 | ⬜ pending |
| 54-02-01 | 02 | 1 | SCHEMA-01 | integration | `mvn verify -Dtest=DisbursementTransactionRefIT` | ❌ W0 | ⬜ pending |
| 54-02-02 | 02 | 1 | SCHEMA-02 | integration | `mvn verify -Dtest=DisbursementTransactionRefIT` | ❌ W0 | ⬜ pending |
| 54-02-03 | 02 | 1 | SCHEMA-03 | integration | `mvn verify -Dtest=DisbursementTransactionRefIT` | ❌ W0 | ⬜ pending |
| 54-03-01 | 03 | 2 | SCHEMA-03 | unit | `mvn test -Dtest=DisbursementStatusTest` | ✅ (needs update) | ⬜ pending |
| 54-03-02 | 03 | 2 | SCHEMA-03 | unit | `mvn test -Dtest=DisbursementOrchestratorTest` | ✅ (needs update) | ⬜ pending |
| 54-04-01 | 04 | 2 | SCHEMA-03 | unit | `mvn test -Dtest=DisbursementCallbackTransitionServiceTest` | ✅ (needs update) | ⬜ pending |
| 54-05-01 | 05 | 3 | SC-6 | gate | `mvn verify` | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/softropic/payam/disbursement/repo/DisbursementTransactionRefIT.java` — stubs for SCHEMA-01, SCHEMA-02, SCHEMA-03 (pre-flight + DDL assertions against real Testcontainers PostgreSQL)
- [ ] `src/main/java/com/softropic/payam/disbursement/contract/DisbursementRefStatus.java` — new enum (PENDING, CLAIMED, RELEASED) needed before entity and migration can compile
- [ ] `src/main/java/com/softropic/payam/disbursement/repo/DisbursementTransactionRef.java` — new JPA entity stub
- [ ] `src/main/java/com/softropic/payam/disbursement/repo/DisbursementTransactionRefRepository.java` — repository stub (needed by Phase 55)

*Wave 0 stubs must compile and test runner must exit 0 before Wave 1 begins.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Migration runs against prod-like DB with existing live disbursement rows | SCHEMA-02 | Testcontainers uses fresh DB; prod snapshot needed for confidence | Run migration against a sanitized prod snapshot before deploy |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 180s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
