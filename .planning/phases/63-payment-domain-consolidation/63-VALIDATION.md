---
phase: 63
slug: payment-domain-consolidation
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-05-07
---

# Phase 63 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 via Spring Boot Test 3.5.11 |
| **Config file** | `pom.xml` (maven-surefire-plugin + maven-failsafe-plugin) |
| **Quick run command** | `mvn test-compile -q` |
| **Full suite command** | `mvn verify` |
| **Estimated runtime** | ~3–5 minutes |

---

## Sampling Rate

- **After every task commit:** Run `mvn test-compile -q`
- **After every plan wave:** Run `mvn verify`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** ~300 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 63-01-xx | PAY-04 wave | 1 | PAY-04 | integration | `mvn verify` — `FeeEngineIT` | ✅ | ⬜ pending |
| 63-02-xx | PAY-05 wave | 2 | PAY-05 | integration | `mvn verify` — `ReconciliationJobIT`, `ReconciliationApiIT` | ✅ | ⬜ pending |
| 63-03-xx | PAY-06 wave | 3 | PAY-06 | integration | `mvn verify` — `FraudEngineIT`, `FraudVelocityOrderingIT`, `FraudVelocityBlockE2ETest` | ✅ | ⬜ pending |
| 63-04-xx | PAY-07 wave | 4 | PAY-07 | integration | `mvn verify` — `WebhookDeliveryIT`, `WebhookDeliveryJobIT`, `OutboundWebhookDeliveryE2ETest` | ✅ | ⬜ pending |
| 63-05-xx | PAY-01 wave | 5 | PAY-01 | integration | `mvn verify` — `PaymentOrchestratorIT`, E2E payment tests | ✅ | ⬜ pending |
| 63-06-xx | PAY-03 wave | 6 | PAY-03 | integration | `mvn verify` — `DisbursementResourceIT`, `DisbursementOrchestratorIT`, E2E disbursement suite | ✅ | ⬜ pending |
| 63-07-xx | PAY-02 wave | 7 | PAY-02 | integration | `mvn verify` — `IdempotencyServiceIT`, `LedgerServiceIT`, `TransactionStateMachineIT` | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

None — existing test infrastructure covers all phase requirements. This phase adds no new behavior. All tests already exist and will move with their source packages.

*Existing infrastructure covers all phase requirements.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Zero stale package imports after each wave | BUILD-01 | Compiler will catch it, but explicit grep confirms | After each wave: `grep -rn "com.softropic.payam.OLD_PACKAGE\." src/ --include="*.java"` → must return zero |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 300s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
