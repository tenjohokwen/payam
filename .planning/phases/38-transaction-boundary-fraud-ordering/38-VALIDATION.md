---
phase: 38
slug: transaction-boundary-fraud-ordering
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-14
---

# Phase 38 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + WireMock Spring (failsafe runner) |
| **Config file** | `pom.xml` (failsafe plugin, `**/*IT.java` pattern) |
| **Quick run command** | `mvn test -pl . -Dtest=PaymentOrchestratorIT,FraudEngineIT,FraudScoringServiceIT` |
| **Full suite command** | `mvn verify` |
| **Estimated runtime** | ~90 seconds |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -pl . -Dtest=PaymentOrchestratorIT,FraudEngineIT,FraudScoringServiceIT`
- **After every plan wave:** Run `mvn verify`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 90 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 38-01-01 | 01 | 1 | TXN-01 | IT (regression) | `mvn verify -Dit.test=PaymentOrchestratorIT` | ✅ existing | ⬜ pending |
| 38-01-02 | 01 | 1 | TXN-01 | IT (new assertion) | `mvn verify -Dit.test=PaymentOrchestratorIT` | ✅ existing | ⬜ pending |
| 38-02-01 | 02 | 1 | OPS-02 | IT (new) | `mvn verify -Dit.test=FraudVelocityOrderingIT` | ❌ W0 | ⬜ pending |
| 38-02-02 | 02 | 1 | OPS-02 | IT (regression) | `mvn verify -Dit.test=FraudEngineIT` | ✅ existing | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/com/payam/fraud/FraudVelocityOrderingIT.java` — new IT for OPS-02 token ordering

*Existing infrastructure covers TXN-01 requirements via PaymentOrchestratorIT.*

---

## Manual-Only Verifications

*All phase behaviors have automated verification.*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 90s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
