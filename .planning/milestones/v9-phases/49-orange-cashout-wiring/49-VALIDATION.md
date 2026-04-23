---
phase: 49
slug: orange-cashout-wiring
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-22
---

# Phase 49 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + WireMock |
| **Config file** | `pom.xml` |
| **Quick run command** | `mvn test -pl . -Dtest="OrangeMoneyPortIT,FraudScoringServiceIT,MtnMoMoPortIT,FraudThresholdGuardTest" -q` |
| **Full suite command** | `mvn verify -q` |
| **Estimated runtime** | ~120 seconds |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -pl . -Dtest="OrangeMoneyPortIT,FraudScoringServiceIT,MtnMoMoPortIT,FraudThresholdGuardTest" -q`
- **After every plan wave:** Run `mvn verify -q`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 120 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 49-01-01 | 01 | 1 | CASHOUT-01 | compile | `mvn test -pl . -Dtest="OrangeMoneyPortIT,FraudScoringServiceIT,MtnMoMoPortIT,FraudThresholdGuardTest" -q` | ✅ | ⬜ pending |
| 49-01-02 | 01 | 1 | CASHOUT-01 | unit | `mvn test -pl . -Dtest="OrangeMoneyPortIT,FraudScoringServiceIT,MtnMoMoPortIT,FraudThresholdGuardTest" -q` | ✅ | ⬜ pending |
| 49-02-01 | 02 | 2 | CASHOUT-02 | integration | `mvn test -pl . -Dtest="OrangeMoneyPortIT" -q` | ✅ | ⬜ pending |
| 49-02-02 | 02 | 2 | CASHOUT-02 | integration | `mvn verify -q` | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

*Existing infrastructure covers all phase requirements. No new test infrastructure needed — `OrangeMoneyPortIT` and `LedgerEntryRepository` already exist.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Orange cashout HTTP endpoint field mapping | CASHOUT-02 | Full cashout HTTP adapter deferred (out of scope); WireMock stubs simulate success only | Verify WireMock stub returns 200 and ledger entries are posted — automated in OrangeMoneyPortIT |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 120s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
