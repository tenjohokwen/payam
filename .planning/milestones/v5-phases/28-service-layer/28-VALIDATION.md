---
phase: 28
slug: service-layer
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-03
---

# Phase 28 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 / Spring Boot Test / Testcontainers |
| **Config file** | `src/test/resources/application-test.yml` |
| **Quick run command** | `mvn test -Dtest="TenantProvisioningIT,TenantServiceIT,TenantAuditIT" -q` |
| **Full suite command** | `mvn verify -q` |
| **Estimated runtime** | ~60 seconds |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -Dtest="TenantProvisioningIT,TenantServiceIT,TenantAuditIT" -q`
- **After every plan wave:** Run `mvn verify -q`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** ~60 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| WebhookSecret in createTenant | 01 | 1 | TENT-01/WSEC-01 | integration | `mvn test -Dtest="TenantProvisioningIT#createTenant_persistsEntities" -q` | ✅ Exists (add assertion) | ⬜ pending |
| updateName | 01 | 1 | TENT-02 | integration | `mvn test -Dtest="TenantServiceIT#updateName_persistsChange" -q` | ❌ Wave 0 | ⬜ pending |
| updateEmail | 01 | 1 | TENT-03 | integration | `mvn test -Dtest="TenantServiceIT#updateEmail_persistsChange" -q` | ❌ Wave 0 | ⬜ pending |
| updateWebhookUrl | 01 | 1 | TENT-04 | integration | `mvn test -Dtest="TenantServiceIT#updateWebhookUrl_persistsChange" -q` | ❌ Wave 0 | ⬜ pending |
| suspend | 01 | 1 | TENT-07 | integration | `mvn test -Dtest="TenantServiceIT#suspend_revokesAllKeys" -q` | ❌ Wave 0 | ⬜ pending |
| reactivate | 01 | 1 | TENT-08 | integration | `mvn test -Dtest="TenantServiceIT#reactivate_generatesNewProdKey" -q` | ❌ Wave 0 | ⬜ pending |
| generateAndStore guard | 01 | 1 | AKEY-02 | integration | `mvn test -Dtest="TenantServiceIT#generateKey_rejectsIfActiveExists" -q` | ❌ Wave 0 | ⬜ pending |
| AKEY-08 double-rotate | 01 | 1 | AKEY-08 | integration | `mvn test -Dtest="TenantServiceIT#rotate_revokesExistingRotatedKeyForSameEnv" -q` | ❌ Wave 0 | ⬜ pending |
| regenerateWebhookSecret | 01 | 1 | WSEC-03 | integration | `mvn test -Dtest="TenantServiceIT#regenerateWebhookSecret_replacesOldValue" -q` | ❌ Wave 0 | ⬜ pending |
| Envers V20 migration | 02 | 1 | AUDIT-01/AUDIT-02 | integration | `mvn test -Dtest="TenantAuditIT#updateName_createsAuditRow" -q` | ❌ Wave 0 | ⬜ pending |
| AUDIT-03 admin identity | 02 | 1 | AUDIT-03 | integration | `mvn test -Dtest="TenantAuditIT#generateKey_auditCapturesAdminIdentity" -q` | ❌ Wave 0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/.../tenant/TenantServiceIT.java` — stubs for TENT-02, TENT-03, TENT-04, TENT-07, TENT-08, AKEY-02, AKEY-08, WSEC-03
- [ ] `src/test/java/.../tenant/TenantAuditIT.java` — stubs for AUDIT-01, AUDIT-02, AUDIT-03

*Existing test infrastructure (Testcontainers, WireMock, TestDataCleaner) covers all phase needs.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Raw key returned exactly once and not stored in plaintext | AKEY-02, AKEY-04, TENT-08 | Service response only — no readable DB column | Call service, assert non-null return; verify DB stores only `key_hash` |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 60s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
