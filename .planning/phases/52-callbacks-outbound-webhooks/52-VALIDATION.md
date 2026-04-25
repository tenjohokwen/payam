---
phase: 52
slug: callbacks-outbound-webhooks
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-25
---

# Phase 52 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 / Spring Boot Test / MockMvc |
| **Config file** | `pom.xml` (Maven Surefire + Failsafe plugins) |
| **Quick run command** | `mvn test -pl . -Dtest="*DisbursementCallback*,*OutboundWebhook*" -q` |
| **Full suite command** | `mvn verify` |
| **Estimated runtime** | ~90 seconds |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -pl . -Dtest="*DisbursementCallback*,*OutboundWebhook*" -q`
- **After every plan wave:** Run `mvn verify`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 90 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 52-01-01 | 01 | 1 | SEC-05 | unit | `mvn test -Dtest="MtnDisbursementCallbackControllerTest"` | ❌ W0 | ⬜ pending |
| 52-01-02 | 01 | 1 | SEC-05 | unit | `mvn test -Dtest="OrangeDisbursementCallbackControllerTest"` | ❌ W0 | ⬜ pending |
| 52-01-03 | 01 | 1 | SEC-05 | unit | `mvn test -Dtest="DisbursementCallbackTransitionServiceTest"` | ❌ W0 | ⬜ pending |
| 52-02-01 | 02 | 2 | SEC-06 | unit | `mvn test -Dtest="OutboundWebhookServiceTest"` | ❌ W0 | ⬜ pending |
| 52-02-02 | 02 | 2 | SEC-06 | integration | `mvn verify -Dit.test="OutboundWebhookRetryIT"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/.../disbursement/MtnDisbursementCallbackControllerTest.java` — stubs for SEC-05 (IP whitelist, signature, dedup)
- [ ] `src/test/java/.../disbursement/OrangeDisbursementCallbackControllerTest.java` — stubs for SEC-05
- [ ] `src/test/java/.../disbursement/DisbursementCallbackTransitionServiceTest.java` — stubs for SEC-05 (idempotency, double-check guard)
- [ ] `src/test/java/.../webhook/OutboundWebhookServiceTest.java` — stubs for SEC-06 (signature, retry backoff)
- [ ] `src/test/java/.../webhook/OutboundWebhookRetryIT.java` — integration stubs for SEC-06 retry (5 retries, exponential backoff)

*Wave 0 creates test skeletons before implementation tasks run.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Orange disbursement callback correlation field matches live provider | SEC-05 | Provider sandbox required to confirm field name | Send test cashout via Orange sandbox, capture callback, confirm `reference` vs `payToken` field |
| Tenant webhook receipt of signed payload with correct HMAC | SEC-06 | Requires live tenant endpoint | Configure test tenant URL, trigger SUCCESS disbursement, inspect `X-Payam-Signature` header |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 90s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
