---
phase: 56
slug: claim-lifecycle-admin-approval
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-05-04
---

# Phase 56 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 / Quarkus @QuarkusTest |
| **Config file** | `src/test/resources/application.properties` |
| **Quick run command** | `./mvnw test -pl . -Dtest="*Claim*,*AdminApproval*,*InsufficientFunds*" -q` |
| **Full suite command** | `./mvnw verify -q` |
| **Estimated runtime** | ~120 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./mvnw test -pl . -Dtest="*Claim*,*AdminApproval*,*InsufficientFunds*" -q`
- **After every plan wave:** Run `./mvnw verify -q`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 120 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 56-01-01 | 01 | 1 | CLAIM-01 | unit | `./mvnw test -Dtest="DisbursementClaimServiceTest#createClaims"` | ❌ W0 | ⬜ pending |
| 56-01-02 | 01 | 1 | CLAIM-02 | unit | `./mvnw test -Dtest="DisbursementClaimServiceTest#transitionOnSuccess"` | ❌ W0 | ⬜ pending |
| 56-01-03 | 01 | 1 | CLAIM-03 | unit | `./mvnw test -Dtest="DisbursementClaimServiceTest#transitionOnFailure"` | ❌ W0 | ⬜ pending |
| 56-01-04 | 01 | 1 | CLAIM-04 | unit | `./mvnw test -Dtest="DisbursementClaimServiceTest#claimsHeldOnProcessingExpiry"` | ❌ W0 | ⬜ pending |
| 56-01-05 | 01 | 1 | CLAIM-05 | unit | `./mvnw test -Dtest="DisbursementClaimServiceTest#claimsReleasedOnAdminExpiry"` | ❌ W0 | ⬜ pending |
| 56-02-01 | 02 | 1 | ADMIN-01 | unit | `./mvnw test -Dtest="DisbursementOrchestratorTest#routesToAdminApproval"` | ❌ W0 | ⬜ pending |
| 56-02-02 | 02 | 1 | ADMIN-02 | unit | `./mvnw test -Dtest="DisbursementAdminApprovalExpiryJobTest"` | ❌ W0 | ⬜ pending |
| 56-02-03 | 02 | 1 | ADMIN-03 | integration | `./mvnw test -Dtest="DisbursementAdminApprovalIT"` | ❌ W0 | ⬜ pending |
| 56-03-01 | 03 | 2 | ALERT-01 | unit | `./mvnw test -Dtest="InsufficientFundsAlertServiceTest"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/.../DisbursementClaimServiceTest.java` — stubs for CLAIM-01 through CLAIM-05
- [ ] `src/test/java/.../DisbursementOrchestratorTest.java` — stubs for ADMIN-01
- [ ] `src/test/java/.../DisbursementAdminApprovalExpiryJobTest.java` — stubs for ADMIN-02
- [ ] `src/test/java/.../DisbursementAdminApprovalIT.java` — integration test stubs for ADMIN-03
- [ ] `src/test/java/.../InsufficientFundsAlertServiceTest.java` — stubs for ALERT-01

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Slack/PagerDuty alert delivery | ALERT-01 | Requires live external credentials | Trigger an IF failure in staging; verify alert received on Slack/PagerDuty channel |
| Platform Ops email notification on admin-approval routing | ADMIN-03 | Requires SMTP/email service | Submit disbursement above threshold in staging; verify notification email received |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 120s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
