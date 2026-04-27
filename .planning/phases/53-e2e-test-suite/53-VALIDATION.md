---
phase: 53
slug: e2e-test-suite
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-27
---

# Phase 53 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Testcontainers + WireMock |
| **Config file** | `src/test/resources/application-test.yml` |
| **Quick run command** | `./mvnw -pl backend test -Dtest=*DisbursementE2E* -q` |
| **Full suite command** | `./mvnw -pl backend verify -Dit.test=*E2EIT,*ConcurrencyIT,*IdempotencyIT -q` |
| **Estimated runtime** | ~120 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./mvnw -pl backend test -Dtest=*DisbursementE2E* -q`
- **After every plan wave:** Run `./mvnw -pl backend verify -Dit.test=*E2EIT,*ConcurrencyIT,*IdempotencyIT -q`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 120 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 53-01-01 | 01 | 1 | TEST-01 | integration | `./mvnw -pl backend test -Dtest=MtnDisbursementE2EIT -q` | ❌ W0 | ⬜ pending |
| 53-02-01 | 02 | 1 | TEST-02 | integration | `./mvnw -pl backend test -Dtest=OrangeDisbursementE2EIT -q` | ❌ W0 | ⬜ pending |
| 53-03-01 | 03 | 2 | TEST-03 | integration | `./mvnw -pl backend test -Dtest=StepUpConfirmationE2EIT -q` | ❌ W0 | ⬜ pending |
| 53-04-01 | 04 | 2 | TEST-03 | integration | `./mvnw -pl backend test -Dtest=DisbursementExpiryE2EIT -q` | ❌ W0 | ⬜ pending |
| 53-05-01 | 05 | 3 | TEST-04 | integration | `./mvnw -pl backend test -Dtest=DisbursementConcurrencyRaceIT -q` | ❌ W0 | ⬜ pending |
| 53-06-01 | 06 | 3 | TEST-04 | integration | `./mvnw -pl backend test -Dtest=DisbursementFraudBlockE2EIT -q` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- Existing test infrastructure (AbstractPayamIT, WireMock stubs, LedgerVerifier, TestDataCleaner) covers all infrastructure needs.
- Each plan creates its own new `*IT.java` test class — no Wave 0 stub creation required.

*Existing infrastructure covers all phase requirements.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Quartz expiry fires after 15-minute tick | TEST-03 | Time-based scheduler requires clock manipulation or real wait | In `DisbursementExpiryE2EIT`, call `executeInternal(null)` directly to simulate the Quartz tick without waiting 15 minutes. |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 120s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
