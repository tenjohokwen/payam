---
phase: 62
slug: platform-layer-reorganization
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-05-07
---

# Phase 62 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | Maven / JUnit (integration tests via Spring Boot Test) |
| **Config file** | `pom.xml` |
| **Quick run command** | `mvn test -pl src/test/java -Dtest="*Test" -q` |
| **Full suite command** | `mvn verify` |
| **Estimated runtime** | ~120 seconds |

---

## Sampling Rate

- **After every task commit:** Run `mvn compile -q` (compilation check)
- **After every plan wave:** Run `mvn verify`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 120 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 62-01-01 | 01 | 1 | PLAT-01 | integration | `mvn verify -Dtest="*TenantIT"` | ✅ | ⬜ pending |
| 62-02-01 | 02 | 1 | PLAT-02 | integration | `mvn verify -Dtest="*SecurityIT"` | ✅ | ⬜ pending |
| 62-03-01 | 03 | 2 | PLAT-03 | integration | `mvn verify -Dtest="*NotificationIT"` | ✅ | ⬜ pending |
| 62-04-01 | 04 | 2 | PLAT-04 | integration | `mvn verify -Dtest="*MonitoringIT"` | ✅ | ⬜ pending |
| 62-05-01 | 05 | 3 | PLAT-05 | integration | `mvn verify -Dtest="*AdminIT"` | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

*Existing infrastructure covers all phase requirements.* Maven/JUnit test infrastructure is already present; no new test stubs needed before execution waves.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Tenant suspension/reactivation end-to-end | PLAT-01 | Requires live API + email delivery | POST /v1/admin/tenants/{id}/suspend, verify 403 on tenant API calls; POST .../reactivate, verify access restored |
| Fraud/failure alert email delivery | PLAT-03 | Requires live SMTP + payment event trigger | Trigger a payment failure, verify alert email delivered to configured recipient |
| Live MSISDN validation in health | PLAT-04 | Requires live provider connection | GET /manage/health — confirm both providers show live MSISDN status |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 120s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
