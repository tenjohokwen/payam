---
phase: 33
slug: admin-ui-tenant-management
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-08
---

# Phase 33 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | No automated frontend test framework — project has no jest/vitest setup |
| **Config file** | none |
| **Quick run command** | Manual browser verification (dev server hot-reload) |
| **Full suite command** | Manual smoke test of all new pages in running dev server |
| **Estimated runtime** | ~10 minutes manual |

---

## Sampling Rate

- **After every task commit:** Confirm dev server compiles without errors (quasar dev hot-reload)
- **After every plan wave:** Manual smoke test of all new/modified pages in browser
- **Before `/gsd:verify-work`:** All four success criteria verified manually in running app
- **Max feedback latency:** Per-wave (manual); build errors surface immediately via hot-reload

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 33-01-01 | 01 | 1 | UI-01 | manual | — (browser: list page loads with q-table) | ❌ W0 | ⬜ pending |
| 33-01-02 | 01 | 1 | UI-01 | manual | — (browser: status filter chips work) | ❌ W0 | ⬜ pending |
| 33-01-03 | 01 | 1 | UI-01 | manual | — (browser: row click navigates to detail) | ❌ W0 | ⬜ pending |
| 33-02-01 | 02 | 2 | UI-02 | manual | — (browser: inline save sends PATCH, shows toast) | ❌ W0 | ⬜ pending |
| 33-02-02 | 02 | 2 | UI-02 | manual | — (browser: suspend dialog confirms, calls API) | ❌ W0 | ⬜ pending |
| 33-02-03 | 02 | 2 | UI-02 | manual | — (browser: reactivate opens key modal) | ❌ W0 | ⬜ pending |
| 33-03-01 | 03 | 2 | UI-03 | manual | — (browser: modal undismissable without checkbox) | ❌ W0 | ⬜ pending |
| 33-03-02 | 03 | 2 | UI-03 | manual | — (browser: rawKey null in state after close) | ❌ W0 | ⬜ pending |
| 33-04-01 | 04 | 3 | UI-04 | manual | — (browser: secret fetched lazily on eye-click) | ❌ W0 | ⬜ pending |
| 33-04-02 | 04 | 3 | UI-04 | manual | — (browser: secret auto-masks after 30s) | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

No automated test framework setup required — project has no frontend automated test infrastructure. Validation is manual via browser dev server.

*Existing infrastructure covers all phase requirements (manual verification only).*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Tenant list page loads with paginated q-table and status filter | UI-01 | No frontend test framework | Open admin SPA, navigate to Tenants, verify table rows + filter chips |
| Row click navigates to tenant detail | UI-01 | No frontend test framework | Click any row, verify URL changes to /admin/tenants/:ref |
| Inline field save (name, email, webhookUrl) sends PATCH + toast | UI-02 | No frontend test framework | Edit a field, click save icon, check network tab for PATCH and toast |
| Suspend shows $q.dialog confirm, then calls POST /suspend | UI-02 | No frontend test framework | Click Suspend, confirm dialog, verify 204 + status badge changes |
| Reactivate calls POST /reactivate and opens key modal | UI-02 | No frontend test framework | Click Reactivate on suspended tenant, verify modal appears with rawKey |
| One-time key modal blocked until checkbox checked | UI-03 | No frontend test framework | Open modal, try to dismiss without checkbox — button must remain disabled |
| rawKey cleared from parent state after modal close | UI-03 | No frontend test framework | Close modal, reopen — raw key must not reappear |
| Webhook secret fetched lazily on first eye-click | UI-04 | No frontend test framework | Click eye icon, verify GET /webhook-secret call in network tab |
| Secret auto-masked after 30 seconds | UI-04 | No frontend test framework | Reveal secret, wait 30s, verify input returns to masked state |
| Second eye-click while revealed immediately re-masks | UI-04 | No frontend test framework | Reveal secret, click eye again before 30s — verify immediate re-mask |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 600s (manual per-wave)
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
