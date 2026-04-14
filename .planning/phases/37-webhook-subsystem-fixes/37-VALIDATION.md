---
phase: 37
slug: webhook-subsystem-fixes
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-14
---

# Phase 37 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test |
| **Config file** | `src/test/resources/application-test.properties` |
| **Quick run command** | `mvn test -pl . -Dtest=WebhookDeliveryJobTest,WebhookTransitionServiceTest,WebhookConfigTest -q` |
| **Full suite command** | `mvn verify -q` |
| **Estimated runtime** | ~60 seconds (full verify) |

---

## Sampling Rate

- **After every task commit:** Run quick test command
- **After every plan wave:** Run `mvn verify -q`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 60 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 37-01-01 | 01 | 1 | WEBHOOK-01 | integration | `mvn test -Dtest=WebhookDeliveryJobTest -q` | ✅ | ⬜ pending |
| 37-01-02 | 01 | 1 | WEBHOOK-01 | integration | `mvn test -Dtest=WebhookDeliveryJobTest -q` | ✅ | ⬜ pending |
| 37-02-01 | 02 | 1 | WEBHOOK-02 | integration | `mvn test -Dtest=WebhookTransitionServiceTest -q` | ✅ | ⬜ pending |
| 37-02-02 | 02 | 1 | WEBHOOK-02 | integration | `mvn test -Dtest=WebhookTransitionServiceTest -q` | ✅ | ⬜ pending |
| 37-03-01 | 03 | 1 | WEBHOOK-03 | unit | `mvn test -Dtest=WebhookConfigTest -q` | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

*Existing infrastructure covers all phase requirements.* The project already has:
- `QueryCountVerifier` + datasource-proxy wired via `TestConfig.spyDataSource` (for WEBHOOK-01 N+1 assertion)
- `@SpringBootTest` + `@Transactional` test infrastructure (for WEBHOOK-02 event listener test)
- JUnit 5 unit test infrastructure (for WEBHOOK-03 timeout config test)

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Rollback of state transition does not enqueue delivery | WEBHOOK-02 | Structural guarantee from AFTER_COMMIT — automated test covers enqueue-after-commit path | Run integration test that forces tx rollback and verify no WebhookDeliveryLog row created |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 60s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
