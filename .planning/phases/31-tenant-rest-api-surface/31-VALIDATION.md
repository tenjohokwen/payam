---
phase: 31
slug: tenant-rest-api-surface
status: draft
nyquist_compliant: true
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
| 31-01-01 | 01 | 1 | TENT-05, TENT-06, WSEC-03 | compile | `./mvnw compile -q 2>&1 \| tail -5` | ✅ | ⬜ pending |
| 31-01-02 | 01 | 1 | TENT-05, TENT-06, WSEC-03 | integration | `./mvnw test -Dtest="TenantAdminResourceIT" -q` | ❌ TDD | ⬜ pending |
| 31-02-01 | 02 | 2 | TENT-02, TENT-03, TENT-04, TENT-07, TENT-08, TENT-10 | compile | `./mvnw compile -q 2>&1 \| tail -5` | ✅ | ⬜ pending |
| 31-02-02 | 02 | 2 | TENT-02, TENT-03, TENT-04, TENT-07, TENT-08, TENT-10 | integration | `./mvnw test -Dtest="TenantAdminResourceIT" -q` | ❌ TDD | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*
*File Exists: ✅ already exists · ❌ TDD — test written inline as part of task*

---

## TDD Checklist

All 9+ test methods are new additions to the existing `TenantAdminResourceIT`. Tests are written inline with each implementation task (TDD-inline, not pre-written). The file exists but only covers key rotation/revocation.

**Plan 01 (read endpoints) — implemented in task 2:**
- [ ] `listTenants_returnsPage` — TENT-05
- [ ] `listTenants_filteredByStatus` — TENT-05
- [ ] `getTenantDetail_returnsDetailWithoutSecret` — TENT-06
- [ ] `getWebhookSecret_returnsPlaintextSecret` — WSEC-03

**Plan 02 (mutation endpoints) — implemented in task 2:**
- [ ] `updateName_returns204` — TENT-10
- [ ] `updateEmail_returns204` — TENT-02
- [ ] `updateWebhookUrl_returns204` — TENT-03
- [ ] `suspend_revokesAllKeys` — TENT-04
- [ ] `reactivate_returnsRawKey` — TENT-07
- [ ] `regenerateWebhookSecret_returns204` — TENT-08

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| webhookSecret absent from GET /v1/admin/tenants/{ref} response body | TENT-06 / WSEC-03 | Risk of accidental field inclusion | Assert JSON response does NOT contain "webhookSecret" key |

---

## Validation Sign-Off

- [x] All tasks have automated verify (compile or integration test)
- [x] Sampling continuity: window [31-01-02, 31-02-01, 31-02-02] has 2/3 with test commands — passes
- [x] No `MISSING` markers — tests are inline TDD, no pre-written stubs required
- [x] No watch-mode flags
- [x] Feedback latency < 30s for TenantAdminResourceIT
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** 2026-04-07
