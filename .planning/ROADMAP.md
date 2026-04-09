# Roadmap: Payam

## Milestones

- ✅ **v1 Payment API** — Phases 1–13 (shipped 2026-03-26) — see [milestones/v1-ROADMAP.md](milestones/v1-ROADMAP.md)
- ✅ **v2 Logging Standardization** — Phases 14–17 (shipped 2026-03-27) — see [milestones/v2-ROADMAP.md](milestones/v2-ROADMAP.md)
- ✅ **v3 E2E Test Suite** — Phases 18–23 (shipped 2026-03-28) — see [milestones/v3-ROADMAP.md](milestones/v3-ROADMAP.md)
- ✅ **v4 Platform Config & Health** — Phases 24–26 (shipped 2026-04-02) — see [milestones/v4-ROADMAP.md](milestones/v4-ROADMAP.md)
- ✅ **v5 Tenant & API Key Management Service Layer** — Phases 27–29 (shipped 2026-04-06) — see [milestones/v5-ROADMAP.md](milestones/v5-ROADMAP.md)
- 🚧 **v6 REST API Surface, Notifications & Admin UI** — Phases 30–33 (active)

## Phases

<details>
<summary>✅ v1 Payment API (Phases 1–13) — SHIPPED 2026-03-26</summary>

- [x] Phase 1: Multi-Tenant Foundation (3/3 plans) — completed 2026-03-23
- [x] Phase 2: Transaction Core + Event Sourcing (3/3 plans) — completed 2026-03-23
- [x] Phase 3: Orange Money Adapter (4/4 plans) — completed 2026-03-24
- [x] Phase 4: MTN MoMo Adapter (2/2 plans) — completed 2026-03-24
- [x] Phase 5: Payment Orchestration (2/2 plans) — completed 2026-03-24
- [x] Phase 6: Webhook Processing (3/3 plans) — completed 2026-03-24
- [x] Phase 7: Fraud Engine (2/2 plans) — completed 2026-03-24
- [x] Phase 8: Admin Dashboard + Monitoring (3/3 plans) — completed 2026-03-24
- [x] Phase 9: Reconciliation (2/2 plans) — completed 2026-03-25
- [x] Phase 10: Operational Hardening (4/4 plans) — completed 2026-03-25
- [x] Phase 11: Fee Exposure (1/1 plan) — completed 2026-03-25
- [x] Phase 12: Test & Doc Polish (1/1 plan) — completed 2026-03-25
- [x] Phase 13: Ledger Wiring + Webhook Access Control (1/1 plan) — completed 2026-03-26

</details>

<details>
<summary>✅ v2 Logging Standardization (Phases 14–17) — SHIPPED 2026-03-27</summary>

- [x] Phase 14: Logging Infrastructure (1/1 plans) — completed 2026-03-26
- [x] Phase 15: MDC & Request Lifecycle (2/2 plans) — completed 2026-03-27
- [x] Phase 16: Business Event Logging (5/5 plans) — completed 2026-03-27
- [x] Phase 17: Code Standards Enforcement (4/4 plans) — completed 2026-03-27

</details>

<details>
<summary>✅ v3 E2E Test Suite (Phases 18–23) — SHIPPED 2026-03-28</summary>

- [x] Phase 18: Test Infrastructure (2/2 plans) — completed 2026-03-27
- [x] Phase 19: Verifiers + Test Data Builders (2/2 plans) — completed 2026-03-27
- [x] Phase 20: Payment Flow Tests (2/2 plans) — completed 2026-03-27
- [x] Phase 21: Webhook Flow Tests (2/2 plans) — completed 2026-03-27
- [x] Phase 22: Fraud, Reconciliation, and Admin Flow Tests (2/2 plans) — completed 2026-03-27
- [x] Phase 23: Domain Invariants, Concurrency, State Machine, and Mutation Tests (5/5 plans) — completed 2026-03-28

</details>

<details>
<summary>✅ v4 Platform Config & Health (Phases 24–26) — SHIPPED 2026-04-02</summary>

- [x] Phase 24: Platform Configuration (3/3 plans) — completed 2026-03-30
- [x] Phase 25: Provider Health Indicators (1/1 plan) — completed 2026-03-31
- [x] Phase 26: Health Dashboard UI (1/1 plan) — completed 2026-04-02

</details>

<details>
<summary>✅ v5 Tenant & API Key Management Service Layer (Phases 27–29) — SHIPPED 2026-04-06</summary>

- [x] Phase 27: Schema and Enum Migration (2/2 plans) — completed 2026-04-03
- [x] Phase 28: Service Layer (2/2 plans) — completed 2026-04-06
- [x] Phase 28.1: API Key Format Fix AKEY-01 (1/1 plan) — completed 2026-04-06
- [x] Phase 29: Quartz Rotation Cleanup Job (1/1 plan) — completed 2026-04-06

</details>

<details open>
<summary>🚧 v6 REST API Surface, Notifications & Admin UI (Phases 30–33) — ACTIVE</summary>

- [ ] **Phase 30: TENT-09 Auth Enforcement** - One-line filter change; SUSPENDED tenants blocked with 403 before SecurityContext population
- [ ] **Phase 31: Tenant REST API Surface** - 8 new endpoint methods on TenantAdminResource + TenantQueryService + DTOs; webhook secret reveal endpoint
- [ ] **Phase 32: Email Notification Infrastructure** - Six lifecycle email events wired via domain event records + TenantLifecycleEmailListener + Thymeleaf templates
- [ ] **Phase 33: Admin UI — Tenant Management** - Tenant list page, detail/edit page, one-time key modal, webhook secret reveal toggle

</details>

## Phase Details

### Phase 30: TENT-09 Auth Enforcement
**Goal**: SUSPENDED tenants are blocked at the API key filter before any request reaches the application layer
**Depends on**: Nothing (standalone filter change; no service layer or controller dependency)
**Requirements**: TENT-09
**Success Criteria** (what must be TRUE):
  1. A request carrying a valid API key for a SUSPENDED tenant receives HTTP 403 before SecurityContext is populated
  2. A request carrying a valid API key for an ACTIVE tenant proceeds normally (no regression)
  3. The 403 response body matches the existing error format (no new error schema introduced)
**Plans**: 1 plan
Plans:
- [x] 30-01-PLAN.md — SUSPENDED tenant 403 enforcement in ApiKeyAuthenticationFilter + integration tests

### Phase 31: Tenant REST API Surface
**Goal**: Admins can perform all tenant and API key lifecycle operations via HTTP endpoints
**Depends on**: Phase 30
**Requirements**: TENT-02, TENT-03, TENT-04, TENT-05, TENT-06, TENT-07, TENT-08, TENT-10, WSEC-03
**Success Criteria** (what must be TRUE):
  1. Admin can retrieve a paginated, status-filtered list of tenants via `GET /v1/admin/tenants`
  2. Admin can retrieve full tenant detail (name, email, webhookUrl, status, keys by env) via `GET /v1/admin/tenants/{tenantRef}`; `webhookSecret` is absent from the response
  3. Admin can update tenant name, email address, and webhookUrl each via their respective `PATCH` endpoints
  4. Admin can suspend a tenant via `POST /v1/admin/tenants/{tenantRef}/suspend`; all tenant API keys are atomically revoked
  5. Admin can reactivate a tenant via `POST /v1/admin/tenants/{tenantRef}/reactivate`; response includes `rawKey` for the newly generated PROD key
  6. Admin can regenerate a tenant's webhook secret and can retrieve the plaintext secret via `GET /v1/admin/tenants/{tenantRef}/webhook-secret`; the secret never appears in the standard tenant detail response
**Plans**: 3 plans
Plans:
- [x] 31-01-PLAN.md — DTOs, TenantQueryService, repository query, and 3 read endpoints (TENT-05, TENT-06, WSEC-03)
- [x] 31-02-PLAN.md — 6 mutation endpoints (PATCH name/email/webhook-url, POST suspend/reactivate/webhook-secret) + IllegalStateException handler + integration tests (TENT-02, TENT-03, TENT-04, TENT-07, TENT-08, TENT-10)
**UI hint**: no

### Phase 32: Email Notification Infrastructure
**Goal**: Admins and tenants receive transactional email notifications for all six key lifecycle and tenant status events
**Depends on**: Phase 31
**Requirements**: NOTIF-01, NOTIF-02, NOTIF-03, NOTIF-04, NOTIF-05, NOTIF-06
**Success Criteria** (what must be TRUE):
  1. Admin and tenant receive an email when a new API key is generated or rotated; the email body contains no raw key material
  2. Admin and tenant receive an email when an API key is manually revoked or reactivated
  3. Admin and tenant receive an email when the webhook secret is regenerated; no secret value appears in the email
  4. Admin and tenant receive an email on tenant suspension, tenant reactivation, and tenant webhookUrl change
  5. On tenant email address change, a notification is delivered to the old address only
  6. All notification emails are delivered after the triggering transaction commits (no sends on rollback)
**Plans**: 3 plans
Plans:
- [x] 32-01-PLAN.md — Domain event records, EmailTemplate enum, i18n keys, 6 Thymeleaf HTML templates
- [x] 32-02-PLAN.md — TenantLifecycleEmailListener, event publishing in TenantService/ApiKeyService, unit tests
- [x] 32-03-PLAN.md — Gap closure: ApiKeyService.reactivate() + REST endpoint + tests (NOTIF-04)

### Phase 33: Admin UI — Tenant Management
**Goal**: Admins can manage the full tenant lifecycle and API key display through the Admin SPA
**Depends on**: Phase 31
**Requirements**: UI-01, UI-02, UI-03, UI-04
**Success Criteria** (what must be TRUE):
  1. Admin can navigate to a tenant list page showing a paginated q-table with status filter; clicking a row navigates to tenant detail
  2. Admin can edit a tenant's name, email, and webhookUrl inline on the detail page with per-field save confirmation; admin can toggle tenant status (suspend or reactivate) behind a confirmation dialog
  3. After key generation or rotation, admin sees a persistent one-time modal displaying the raw key; dismissal is gated on confirming the key has been copied; the raw key is cleared from component state immediately on dismissal
  4. Admin can reveal a tenant's webhook secret via an eye icon on the detail page; the secret is fetched lazily from the dedicated endpoint, displayed in a masked input, and automatically re-masked after 30 seconds
**Plans**: 4 plans
Plans:
- [x] 33-01-PLAN.md — Backend: DTO field additions (email, createdAt) + POST /keys/generate endpoint (UI-01, UI-02, UI-03)
- [x] 33-02-PLAN.md — Frontend foundation: API client methods, routing, nav item, OneTimeKeyModal (UI-03)
- [x] 33-03-PLAN.md — TenantListPage with server-side paginated q-table and status filter (UI-01)
- [x] 33-04-PLAN.md — TenantDetailPage with inline edit, status toggle, key table, webhook secret reveal (UI-02, UI-03, UI-04)
**UI hint**: yes

## Progress

| Phase | Milestone | Plans Complete | Status | Completed |
|-------|-----------|----------------|--------|-----------|
| 1. Multi-Tenant Foundation | v1 | 3/3 | Complete | 2026-03-23 |
| 2. Transaction Core + Event Sourcing | v1 | 3/3 | Complete | 2026-03-23 |
| 3. Orange Money Adapter | v1 | 4/4 | Complete | 2026-03-24 |
| 4. MTN MoMo Adapter | v1 | 2/2 | Complete | 2026-03-24 |
| 5. Payment Orchestration | v1 | 2/2 | Complete | 2026-03-24 |
| 6. Webhook Processing | v1 | 3/3 | Complete | 2026-03-24 |
| 7. Fraud Engine | v1 | 2/2 | Complete | 2026-03-24 |
| 8. Admin Dashboard + Monitoring | v1 | 3/3 | Complete | 2026-03-24 |
| 9. Reconciliation | v1 | 2/2 | Complete | 2026-03-25 |
| 10. Operational Hardening | v1 | 4/4 | Complete | 2026-03-25 |
| 11. Fee Exposure | v1 | 1/1 | Complete | 2026-03-25 |
| 12. Test & Doc Polish | v1 | 1/1 | Complete | 2026-03-25 |
| 13. Ledger Wiring + Webhook Access Control | v1 | 1/1 | Complete | 2026-03-26 |
| 14. Logging Infrastructure | v2 | 1/1 | Complete | 2026-03-26 |
| 15. MDC & Request Lifecycle | v2 | 2/2 | Complete | 2026-03-27 |
| 16. Business Event Logging | v2 | 5/5 | Complete | 2026-03-27 |
| 17. Code Standards Enforcement | v2 | 4/4 | Complete | 2026-03-27 |
| 18. Test Infrastructure | v3 | 2/2 | Complete | 2026-03-27 |
| 19. Verifiers + Test Data Builders | v3 | 2/2 | Complete | 2026-03-27 |
| 20. Payment Flow Tests | v3 | 2/2 | Complete | 2026-03-27 |
| 21. Webhook Flow Tests | v3 | 2/2 | Complete | 2026-03-27 |
| 22. Fraud, Reconciliation, Admin Flow Tests | v3 | 2/2 | Complete | 2026-03-27 |
| 23. Domain Invariants, Concurrency, SM, Mutation | v3 | 5/5 | Complete | 2026-03-28 |
| 24. Platform Configuration | v4 | 3/3 | Complete | 2026-03-30 |
| 25. Provider Health Indicators | v4 | 1/1 | Complete | 2026-03-31 |
| 26. Health Dashboard UI | v4 | 1/1 | Complete | 2026-04-02 |
| 27. Schema and Enum Migration | v5 | 2/2 | Complete | 2026-04-03 |
| 28. Service Layer | v5 | 2/2 | Complete | 2026-04-06 |
| 28.1. API Key Format Fix (AKEY-01) | v5 | 1/1 | Complete | 2026-04-06 |
| 29. Quartz Rotation Cleanup Job | v5 | 1/1 | Complete | 2026-04-06 |
| 30. TENT-09 Auth Enforcement | v6 | 1/1 | Complete    | 2026-04-06 |
| 31. Tenant REST API Surface | v6 | 2/2 | Complete    | 2026-04-07 |
| 32. Email Notification Infrastructure | v6 | 3/3 | Complete   | 2026-04-08 |
| 33. Admin UI — Tenant Management | v6 | 4/4 | Complete   | 2026-04-09 |
