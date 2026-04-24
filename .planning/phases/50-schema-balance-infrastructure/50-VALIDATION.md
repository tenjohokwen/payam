---
phase: 50
slug: schema-balance-infrastructure
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-24
---

# Phase 50 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 / Spring Boot Test / Testcontainers |
| **Config file** | `pom.xml` (existing Maven Surefire + Failsafe config) |
| **Quick run command** | `mvn test -pl . -Dtest="WalletBalance*,DisbursementStatus*" -q` |
| **Full suite command** | `mvn verify` |
| **Estimated runtime** | ~120 seconds (including Testcontainers spin-up) |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -pl . -Dtest="WalletBalance*,DisbursementStatus*" -q`
- **After every plan wave:** Run `mvn verify`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 120 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 50-01-01 | 01 | 1 | BAL-01 | migration | `mvn flyway:migrate -Dflyway.url=... && mvn test -Dtest="*MigrationIT"` | ❌ W0 | ⬜ pending |
| 50-01-02 | 01 | 1 | BAL-01 | unit | `mvn test -Dtest="DisbursementStatusTest"` | ❌ W0 | ⬜ pending |
| 50-02-01 | 02 | 2 | BAL-02 | unit | `mvn test -Dtest="WalletBalanceServiceTest"` | ❌ W0 | ⬜ pending |
| 50-02-02 | 02 | 2 | BAL-02 | integration | `mvn verify -Dit.test="WalletBalanceConcurrencyIT"` | ❌ W0 | ⬜ pending |
| 50-02-03 | 02 | 2 | BAL-03 | unit | `mvn test -Dtest="WalletBalanceServiceReleaseTest"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/.../disbursement/WalletBalanceServiceTest.java` — unit stubs for BAL-02, BAL-03
- [ ] `src/test/java/.../disbursement/WalletBalanceConcurrencyIT.java` — concurrency integration test for BAL-02
- [ ] `src/test/java/.../disbursement/DisbursementStatusTest.java` — enum state machine guard tests for BAL-01
- [ ] `src/test/resources/db/migration/` — Testcontainers migration integration test setup

*All above are new files — existing infrastructure (Testcontainers, JUnit 5) is already present.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Flyway V28 applies cleanly on a real DB with V27 already applied | BAL-01 | Requires a running PostgreSQL instance with specific migration history | Run `mvn flyway:migrate` against a staging DB with V27; confirm `flyway_schema_history` has V28 entry |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 120s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
