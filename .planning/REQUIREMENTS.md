# Requirements: Payam v6 — REST API Surface, Notifications & Admin UI

**Milestone:** v6
**Goal:** Expose the v5 service layer over HTTP, wire email notifications for all key tenant/key lifecycle events, and build Admin UI screens for tenant management.
**Created:** 2026-04-07
**Status:** Active

---

## v6 Requirements

### Auth Enforcement

- [x] **TENT-09**: Admin API key filter blocks requests from SUSPENDED tenants with HTTP 403 before SecurityContext is populated

### Tenant REST API (TENT)

- [ ] **TENT-05**: Admin can retrieve a paginated, filterable (by status) list of tenants via `GET /v1/admin/tenants`
- [ ] **TENT-06**: Admin can retrieve full tenant detail (name, email, webhookUrl, status, keys) via `GET /v1/admin/tenants/{tenantRef}`
- [ ] **TENT-10**: Admin can update a tenant's display name via `PATCH /v1/admin/tenants/{tenantRef}/name`
- [ ] **TENT-02**: Admin can update a tenant's email address via `PATCH /v1/admin/tenants/{tenantRef}/email`
- [ ] **TENT-03**: Admin can update a tenant's webhookUrl via `PATCH /v1/admin/tenants/{tenantRef}/webhook-url`
- [ ] **TENT-04**: Admin can suspend a tenant via `POST /v1/admin/tenants/{tenantRef}/suspend` (atomically revokes all keys)
- [ ] **TENT-07**: Admin can reactivate a suspended tenant via `POST /v1/admin/tenants/{tenantRef}/reactivate` (response includes rawKey for new PROD key)
- [ ] **TENT-08**: Admin can regenerate a tenant's webhook secret via `POST /v1/admin/tenants/{tenantRef}/webhook-secret`

### Webhook Secret REST API (WSEC)

- [ ] **WSEC-03**: Admin can retrieve a tenant's plaintext webhook secret via `GET /v1/admin/tenants/{tenantRef}/webhook-secret` (excluded from standard tenant detail DTO; admin-only)

### Email Notifications (NOTIF)

- [ ] **NOTIF-01**: Admin and tenant receive an email when a new API key is generated for the tenant (no key material in email body)
- [ ] **NOTIF-02**: Admin and tenant receive an email when an API key is rotated (key prefix and environment included; no raw key)
- [ ] **NOTIF-03**: Admin and tenant receive an email when an API key is manually revoked
- [ ] **NOTIF-04**: Admin and tenant receive an email when a revoked API key is reactivated
- [ ] **NOTIF-05**: Admin and tenant receive an email when the webhook secret is regenerated
- [ ] **NOTIF-06**: Admin and tenant receive an email on: tenant suspended, tenant reactivated, tenant email changed (notification to old address), tenant webhookUrl changed

### Admin UI (UI)

- [ ] **UI-01**: Admin can view a tenant list page with paginated q-table, status filter, and row-click navigation to tenant detail
- [ ] **UI-02**: Admin can view and edit tenant detail (name, email, webhookUrl) with inline save; can toggle status (suspend/reactivate) with a confirmation step
- [ ] **UI-03**: Admin sees a one-time API key display modal (persistent QDialog, copy-confirm gate, rawKey cleared from component state on dismissal) after key generation or rotation
- [ ] **UI-04**: Admin can reveal and re-mask a tenant's webhook secret via eye icon on tenant detail page (lazy-fetch; auto-re-masks after 30s)

---

## Future Requirements

*Deferred from v6 — not blocked, but not in scope*

- Audit log viewer for tenant and API key changes (Envers data captured in v5; viewer deferred — webhook_secret masking required first)
- Key permission scopes (read-only, write-only API keys)
- Self-service tenant portal (admin-managed only through v6)
- DEV/SANDBOX key auto-generation on tenant create (PROD only auto-generated)
- Bulk tenant operations (suspend/reactivate multiple tenants)

---

## Out of Scope

- WebhookSecret encryption at rest — plaintext storage is intentional for HMAC-SHA256 signing; reversible encryption would add operational complexity with no current requirement driver
- Hard delete of tenants from UI — suspend is the correct v6 lifecycle action
- Envers audit viewer UI — requires masking webhook_secret column first; deferred until that design decision is made
- NOTIF email to both old and new address on email change — old address only; reducing blast radius of potential enumeration

---

## Traceability

*Filled by roadmapper — maps REQ-IDs to phases*

| REQ-ID | Phase | Status |
|--------|-------|--------|
| TENT-09 | Phase 30 | Complete |
| TENT-05 | Phase 31 | Pending |
| TENT-06 | Phase 31 | Pending |
| TENT-10 | Phase 31 | Pending |
| TENT-02 | Phase 31 | Pending |
| TENT-03 | Phase 31 | Pending |
| TENT-04 | Phase 31 | Pending |
| TENT-07 | Phase 31 | Pending |
| TENT-08 | Phase 31 | Pending |
| WSEC-03 | Phase 31 | Pending |
| NOTIF-01 | Phase 32 | Pending |
| NOTIF-02 | Phase 32 | Pending |
| NOTIF-03 | Phase 32 | Pending |
| NOTIF-04 | Phase 32 | Pending |
| NOTIF-05 | Phase 32 | Pending |
| NOTIF-06 | Phase 32 | Pending |
| UI-01 | Phase 33 | Pending |
| UI-02 | Phase 33 | Pending |
| UI-03 | Phase 33 | Pending |
| UI-04 | Phase 33 | Pending |

---

*Last updated: 2026-04-07*
