---
phase: 51
slug: orchestrator-public-api
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-25
---

# Phase 51 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + Testcontainers |
| **Config file** | `src/test/java/com/softropic/payam/config/` (PostgresContainerConfig, RedisContainerConfig) |
| **Quick run command** | `mvn test -pl . -Dtest=DisbursementOrchestratorTest,DisbursementResourceTest,DisbursementExpiryJobTest -q` |
| **Full suite command** | `mvn verify` |
| **Estimated runtime** | ~120 seconds |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -Dtest=<new test class> -q`
- **After every plan wave:** Run `mvn verify -pl . -q`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 120 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 51-01-01 | 01 | 1 | DISB-01 | Integration | `mvn test -Dtest=DisbursementOrchestratorIT` | ❌ W0 | ⬜ pending |
| 51-01-02 | 01 | 1 | PROV-01 | Integration | `mvn test -Dtest=DisbursementOrchestratorIT#mtn_happy_path` | ❌ W0 | ⬜ pending |
| 51-01-03 | 01 | 1 | PROV-02 | Integration | `mvn test -Dtest=DisbursementOrchestratorIT#orange_happy_path` | ❌ W0 | ⬜ pending |
| 51-01-04 | 01 | 1 | PROV-03 | Unit + Integration | `mvn test -Dtest=DisbursementOrchestratorTest#recipientInactive_returns422` | ❌ W0 | ⬜ pending |
| 51-01-05 | 01 | 1 | SEC-01 | Integration | `mvn test -Dtest=DisbursementIdempotencyIT` | ❌ W0 | ⬜ pending |
| 51-02-01 | 02 | 1 | SEC-02 | Integration | `mvn test -Dtest=DisbursementVelocityIT` | ❌ W0 | ⬜ pending |
| 51-02-02 | 02 | 1 | SEC-03 | Unit + Integration | `mvn test -Dtest=DisbursementFraudEvaluationServiceTest` | ❌ W0 | ⬜ pending |
| 51-02-03 | 02 | 1 | SEC-04 | Integration | `mvn test -Dtest=DisbursementExpiryJobIT,DisbursementOrchestratorIT#stepUp_*` | ❌ W0 | ⬜ pending |
| 51-03-01 | 03 | 2 | DISB-02 | Integration | `mvn test -Dtest=DisbursementResourceIT#getById_wrongTenant_returns404` | ❌ W0 | ⬜ pending |
| 51-03-02 | 03 | 2 | DISB-03 | Integration | `mvn test -Dtest=DisbursementResourceIT#list_filterByStatus` | ❌ W0 | ⬜ pending |
| 51-03-03 | 03 | 2 | DISB-04 | Integration | `mvn test -Dtest=DisbursementOrchestratorIT#confirm_*` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `DisbursementOrchestratorTest.java` — unit tests for orchestration logic (fraud block, balance release, step-up routing)
- [ ] `DisbursementOrchestratorIT.java` — integration tests with WireMock stubs for MTN + Orange disbursement endpoints; needs `mtn.disbursement-base-url` in `@ConfigureWireMock`
- [ ] `DisbursementResourceIT.java` — REST layer tests (tenant-scope 404, confirm endpoint, list filtering)
- [ ] `DisbursementIdempotencyIT.java` — idempotency namespace isolation test
- [ ] `DisbursementVelocityIT.java` — velocity limit enforcement tests
- [ ] `DisbursementFraudEvaluationServiceTest.java` — unit tests for all 3 new signals (new recipient +15, amount outlier +30, known-fraud MSISDN +80)
- [ ] `DisbursementExpiryJobIT.java` — Quartz expiry: PENDING_CONFIRMATION → EXPIRED, wallet balance unchanged

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Known-fraud MSISDN blocklist admin population | SEC-03 | Admin endpoint out of Phase 51 scope | Seed Redis SET `fraud:dsb:msisdn:blocklist` with a test MSISDN, submit disbursement to that MSISDN — expect FRAUD_BLOCK |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 120s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
