---
phase: 59
slug: v11-javadoc-tech-debt-cleanup
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-05-05
---

# Phase 59 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 via Maven Surefire + Failsafe |
| **Config file** | `pom.xml` (Surefire/Failsafe plugin config) |
| **Quick run command** | `mvn test -pl . -q` |
| **Full suite command** | `mvn verify` |
| **Estimated runtime** | ~90 seconds (unit only) / ~300 seconds (full) |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -pl . -q`
- **After every plan wave:** Run `mvn verify`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 300 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 59-01-01 | 01 | 1 | SC-1 | grep | `grep -n "Fee evaluation" src/main/java/com/softropic/payam/disbursement/service/DisbursementOrchestrator.java` → empty | ✅ | ⬜ pending |
| 59-01-02 | 01 | 1 | SC-2 | grep | `grep -n "Wallet balance reserve" src/main/java/com/softropic/payam/disbursement/service/DisbursementOrchestrator.java` → empty | ✅ | ⬜ pending |
| 59-01-03 | 01 | 1 | SC-3 | grep | `grep -n "BAL-02\|BAL-03" src/main/java/com/softropic/payam/disbursement/service/DisbursementOrchestrator.java` → empty | ✅ | ⬜ pending |
| 59-01-04 | 01 | 1 | SC-4 | grep | `grep -n "wallet release\|walletBalanceService.release\|BAL-02" src/main/java/com/softropic/payam/disbursement/service/DisbursementCallbackTransitionService.java` → empty | ✅ | ⬜ pending |
| 59-01-05 | 01 | 1 | SC-5 | full suite | `mvn verify` → EXIT_CODE=0 | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

*Existing infrastructure covers all phase requirements. No new test files required — verification is by grep and `mvn verify`.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| DisbursementOrchestrator Javadoc step label updated | SC-1/SC-2/SC-3 | Javadoc comment check | `grep -n "Fee evaluation\|Wallet balance reserve\|BAL-02\|BAL-03" src/main/java/com/softropic/payam/disbursement/service/DisbursementOrchestrator.java` → must be empty |
| DisbursementCallbackTransitionService Javadoc clean | SC-4 | Javadoc comment check | `grep -n "wallet release\|walletBalanceService.release\|BAL-02" src/main/java/com/softropic/payam/disbursement/service/DisbursementCallbackTransitionService.java` → must be empty |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 300s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
