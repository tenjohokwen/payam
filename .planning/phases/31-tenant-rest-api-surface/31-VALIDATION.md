---
phase: 31
slug: tenant-rest-api-surface
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-07
---

# Phase 31 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + AssertJ |
| **Config file** | none — Spring Boot auto-configures |
| **Quick run command** | `./mvnw test -Dtest="TenantAdminResourceIT" -q` |
| **Full suite command** | `./mvnw test -q` |
| **Estimated runtime** | ~90 seconds (full suite with Testcontainers) |

---

## Sampling Rate

- **After every task commit:** Run `./mvnw test -Dtest="TenantAdminResourceIT" -q`
- **After every plan wave:** Run `./mvnw test -q`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** ~30 seconds (TenantAdminResourceIT only)

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 31-01-01 | 01 | 1 | TENT-05 | integration | `./mvnw test -Dtest="TenantAdminResourceIT#listTenants*" -q` | ❌ W0 | ⬜ pending |
| 31-01-02 | 01 | 1 | TENT-06 | integration | `./mvnw test -Dtest="TenantAdminResourceIT#getTenantDetail*" -q` | ❌ W0 | ⬜ pending |
| 31-02-01 | 02 | 1 | TENT-10 | integration | `./mvnw test -Dtest="TenantAdminResourceIT#updateName*" -q` | ❌ W0 | ⬜ pending |
| 31-02-02 | 02 | 1 | TENT-02 | integration | `./mvnw test -Dtest="TenantAdminResourceIT#updateEmail*" -q` | ❌ W0 | ⬜ pending |
| 31-02-03 | 02 | 1 | TENT-03 | integration | `./mvnw test -Dtest="TenantAdminResourceIT#updateWebhookUrl*" -q` | ❌ W0 | ⬜ pending |
| 31-03-01 | 03 | 2 | TENT-04 | integration | `./mvnw test -Dtest="TenantAdminResourceIT#suspend*" -q` | ❌ W0 | ⬜ pending |
| 31-03-02 | 03 | 2 | TENT-07 | integration | `./mvnw test -Dtest="TenantAdminResourceIT#reactivate*" -q` | ❌ W0 | ⬜ pending |
| 31-03-03 | 03 | 2 | TENT-08 | integration | `./mvnw test -Dtest="TenantAdminResourceIT#regenerateWebhookSecret*" -q` | ❌ W0 | ⬜ pending |
| 31-03-04 | 03 | 2 | WSEC-03 | integration | `./mvnw test -Dtest="TenantAdminResourceIT#getWebhookSecret*" -q` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

All 9 test methods are new additions to the existing `TenantAdminResourceIT`. The file exists but only covers key rotation/revocation. Each new endpoint should be implemented TDD-style: failing test first, then implementation.

- [ ] `TenantAdminResourceIT#listTenants_returnsPage` — TENT-05
- [ ] `TenantAdminResourceIT#listTenants_filteredByStatus` — TENT-05
- [ ] `TenantAdminResourceIT#getTenantDetail_returnsDetailWithoutSecret` — TENT-06
- [ ] `TenantAdminResourceIT#updateName_returns204` — TENT-10
- [ ] `TenantAdminResourceIT#updateEmail_returns204` — TENT-02
- [ ] `TenantAdminResourceIT#updateWebhookUrl_returns204` — TENT-03
- [ ] `TenantAdminResourceIT#suspend_revokesAllKeys` — TENT-04
- [ ] `TenantAdminResourceIT#reactivate_returnsRawKey` — TENT-07
- [ ] `TenantAdminResourceIT#regenerateWebhookSecret_returns204` — TENT-08
- [ ] `TenantAdminResourceIT#getWebhookSecret_returnsPlaintextSecret` — WSEC-03

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| webhookSecret absent from GET /v1/admin/tenants/{ref} | TENT-06 / WSEC-03 | Risk of field accidentally included | Assert JSON response does NOT contain "webhookSecret" key |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
