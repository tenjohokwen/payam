---
phase: 47
slug: contract-types-ledgerservice-rewrite
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-21
---

# Phase 47 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test |
| **Config file** | `pom.xml` |
| **Quick run command** | `mvn test -pl . -Dtest="LedgerPostingTest,LedgerFlowTest" -q` |
| **Full suite command** | `mvn verify -q` |
| **Estimated runtime** | ~60 seconds |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -pl . -Dtest="LedgerPostingTest,LedgerFlowTest" -q`
- **After every plan wave:** Run `mvn verify -q`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 60 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 47-01-01 | 01 | 1 | CONTRACT-01 | unit | `mvn test -Dtest="LedgerFlowTest" -q` | ❌ W0 | ⬜ pending |
| 47-01-02 | 01 | 1 | CONTRACT-02 | unit | `mvn test -Dtest="LedgerPostingTest" -q` | ❌ W0 | ⬜ pending |
| 47-01-03 | 01 | 1 | CONTRACT-03 | unit | `mvn test -Dtest="LedgerPostingTest#validation" -q` | ❌ W0 | ⬜ pending |
| 47-02-01 | 02 | 1 | SERVICE-01,SERVICE-02 | integration | `mvn verify -Dtest="LedgerServiceIT" -q` | ✅ | ⬜ pending |
| 47-02-02 | 02 | 2 | SERVICE-03,SERVICE-04 | integration | `mvn verify -Dtest="LedgerServiceIT" -q` | ✅ | ⬜ pending |
| 47-02-03 | 02 | 2 | SERVICE-05 | integration | `mvn verify -Dtest="LedgerBalanceGuardTest" -q` | ✅ | ⬜ pending |
| 47-03-01 | 03 | 2 | CONTRACT-04 | integration | `mvn verify -Dtest="WebhookTransitionServiceIT" -q` | ✅ | ⬜ pending |
| 47-03-02 | 03 | 2 | SERVICE-06 | integration | `mvn verify -q` | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/.../LedgerFlowTest.java` — unit tests for CONTRACT-01 enum values
- [ ] `src/test/java/.../LedgerPostingTest.java` — unit tests for CONTRACT-02 factory methods + CONTRACT-03 constructor validation

*Existing `LedgerServiceIT` and `LedgerBalanceGuardTest` and `WebhookTransitionServiceIT` cover integration requirements.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| `getEffectiveFlow()` null→COLLECTION for legacy rows | CONTRACT-04 | Requires live DB row with null flow | Insert transaction with null flow, call `getEffectiveFlow()`, assert COLLECTION |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 60s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
