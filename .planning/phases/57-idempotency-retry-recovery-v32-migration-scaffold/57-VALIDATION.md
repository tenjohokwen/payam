---
phase: 57
slug: idempotency-retry-recovery-v32-migration-scaffold
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-05-04
---

# Phase 57 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + Testcontainers |
| **Config file** | none — Spring Boot auto-configuration via `@SpringBootTest` |
| **Quick run command** | `mvn test -pl . -Dtest=DisbursementOrchestratorTest,DisbursementStatusTest -q` |
| **Full suite command** | `mvn verify -q` |
| **Estimated runtime** | ~180 seconds (full verify with Testcontainers) |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -Dtest=DisbursementOrchestratorTest,DisbursementStatusTest -q`
- **After every plan wave:** Run `mvn verify -q`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 30 seconds (unit tests only)

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 57-01-01 | 01 | 1 | IDEM-02 | unit | `mvn test -Dtest=DisbursementStatusTest -q` | ✅ (needs update) | ⬜ pending |
| 57-01-02 | 01 | 1 | IDEM-01,IDEM-02,IDEM-03 | unit | `mvn test -Dtest=DisbursementOrchestratorTest -q` | ✅ (needs cases) | ⬜ pending |
| 57-01-03 | 01 | 1 | IDEM-01,IDEM-02,IDEM-03 | integration | `mvn test -Dtest=DisbursementIdempotencyRetryIT -q` | ❌ W0 | ⬜ pending |
| 57-02-01 | 02 | 2 | SCHEMA-04 | integration | `mvn test -Dtest=V32MigrationIT -q` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/softropic/payam/disbursement/service/DisbursementIdempotencyRetryIT.java` — integration test stubs for IDEM-01, IDEM-02, IDEM-03
- [ ] `src/test/java/com/softropic/payam/disbursement/repo/V32MigrationIT.java` — schema test stub verifying wallet tables absent after V32

*Existing tests needing update (not Wave 0 — they need code changes):*
- `DisbursementStatusTest` — update FAILED state assertion to expect `EnumSet.of(INITIATED)` instead of `EnumSet.noneOf()`
- `DisbursementOrchestratorTest` — add cases for IDEM-01, IDEM-02, IDEM-03 retry paths

---

## Manual-Only Verifications

*All phase behaviors have automated verification.*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s (unit tests)
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
