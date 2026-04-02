# Roadmap: Payam

## Milestones

- ✅ **v1 Payment API** — Phases 1–13 (shipped 2026-03-26) — see [milestones/v1-ROADMAP.md](milestones/v1-ROADMAP.md)
- ✅ **v2 Logging Standardization** — Phases 14–17 (shipped 2026-03-27) — see [milestones/v2-ROADMAP.md](milestones/v2-ROADMAP.md)
- ✅ **v3 E2E Test Suite** — Phases 18–23 (shipped 2026-03-28) — see [milestones/v3-ROADMAP.md](milestones/v3-ROADMAP.md)
- ✅ **v4 Platform Config & Health** — Phases 24–26 (shipped 2026-04-02) — see [milestones/v4-ROADMAP.md](milestones/v4-ROADMAP.md)
- 🔄 **v5 Tenant & API Key Management** — Phases 27–33 (active)

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
- [x] Phase 22: Fraud, Reconciliation, Admin Flow Tests (2/2 plans) — completed 2026-03-27
- [x] Phase 23: Domain Invariants, Concurrency, SM, Mutation (5/5 plans) — completed 2026-03-28

</details>

<details>
<summary>✅ v4 Platform Config &amp; Health (Phases 24–26) — SHIPPED 2026-04-02</summary>

- [x] Phase 24: Platform Configuration (3/3 plans) — completed 2026-03-30
- [x] Phase 25: Provider Health Indicators (1/1 plans) — completed 2026-03-31
- [x] Phase 26: Health Dashboard UI (1/1 plans) — completed 2026-04-02

</details>

<details open>
<summary>🔄 v5 Tenant &amp; API Key Management (Phases 27–33) — ACTIVE</summary>

- [ ] **Phase 27: Schema and Enum Migration** — Fix v1 entity defects; add DB constraints for key prefix, environment enum, and one-ACTIVE-per-env uniqueness
- [ ] **Phase 28: Service Layer** — TenantService + ApiKeyService with correct business logic; suspension cascade; rotation; reactivation; webhook secret; Envers audit
- [ ] **Phase 29: Quartz Rotation Cleanup Job** — Automated ROTATED→REVOKED after 24-hour grace period
- [ ] **Phase 30: Email Notifications** — 6 tenant/key lifecycle event types via TenantEventEmailListener
- [ ] **Phase 31: REST API Expansion** — 8 new endpoints on TenantAdminResource; auth filter tenant-status check
- [ ] **Phase 32: Admin UI** — TenantListPage + TenantDetailPage + one-time key modal with copy-confirm gate + webhook secret reveal
- [ ] **Phase 33: E2E Tests** — Cross-layer validation of all critical tenant and key lifecycle flows

</details>

## Phase Details

### Phase 27: Schema and Enum Migration
**Goal**: The entity model and database constraints correctly represent the v5 tenant/key specification — v1 defects corrected, environment enum migrated, partial unique index in place
**Depends on**: Phase 26
**Requirements**: AKEY-01, AKEY-03
**Success Criteria** (what must be TRUE):
  1. `Tenant` entity has a non-nullable `keyPrefix` column (`updatable = false`) that stores the 3-char uppercase prefix derived from the tenant name at creation time
  2. `TenantApiKey.environment` maps to `ApiKeyEnvironment` enum with values `PROD`, `DEV`, `SANDBOX` — the legacy `LIVE` value no longer exists in DB or code
  3. A partial unique index `(tenant_id, environment) WHERE key_status = 'ACTIVE'` exists in the database and is enforced by Flyway migration
  4. A UNIQUE constraint on `key_hash` exists on the `tenant_api_key` table
  5. Flyway runs cleanly from a fresh schema with no `UPDATE before CHECK` ordering errors
**Plans**: 2 plans
Plans:
- [x] 27-01-PLAN.md — Flyway migrations V18/V19 + ApiKeyEnvironment enum + entity model + service updates
- [ ] 27-02-PLAN.md — LIVE-to-PROD call site migration across all test files

### Phase 28: Service Layer
**Goal**: TenantService and ApiKeyService implement the full v5 business logic — tenant CRUD, suspension cascade, reactivation with raw-key return, rotation grace period, webhook secret management, and Hibernate Envers audit on all state changes
**Depends on**: Phase 27
**Requirements**: TENT-01, TENT-02, TENT-03, TENT-04, TENT-07, TENT-08, AKEY-02, AKEY-04, AKEY-06, AKEY-08, WSEC-01, WSEC-03, AUDIT-01, AUDIT-02, AUDIT-03
**Success Criteria** (what must be TRUE):
  1. `TenantService.createTenant()` generates a UUID TenantRef, derives the immutable 3-char key prefix from the tenant name, auto-generates an initial PROD key, and returns both the tenant and raw key in a single result record
  2. `TenantService.suspend()` atomically revokes all ACTIVE and ROTATED keys across all environments for the tenant in a single JPQL bulk update within the same transaction as the status change
  3. `TenantService.reactivate()` generates a new PROD key and returns the raw key in the service return value — the raw key is accessible to callers without a second call
  4. `ApiKeyService.rotate()` moves any still-ROTATED key for the same environment to REVOKED immediately before inserting the new ACTIVE key (no two overlapping grace periods per environment)
  5. Hibernate Envers `@Audited` revision entries are created for every change to `Tenant` fields and `TenantApiKey` status transitions, with admin ID recorded as the revision author
**Plans**: TBD

### Phase 29: Quartz Rotation Cleanup Job
**Goal**: ROTATED API keys are automatically moved to REVOKED status after their 24-hour grace period expires, without manual intervention and without partial-failure rollback risk
**Depends on**: Phase 28
**Requirements**: AKEY-05
**Success Criteria** (what must be TRUE):
  1. A Quartz job (`KeyRotationCleanupJob`) runs on an hourly cron and moves all `ROTATED` keys whose rotation timestamp is more than 24 hours old to `REVOKED` status via a single bulk `@Modifying` JPQL update
  2. The job class is annotated `@DisallowConcurrentExecution` — concurrent runs do not produce duplicate revocations
  3. Quartz thread pool `threadCount` is increased to 5 in `application.yaml` to prevent job starvation
  4. A `SYSTEM:grace-period-job` principal is set in the security context before the bulk update and cleared in a `finally` block, so Envers records a non-null audit actor for automated revocations
**Plans**: TBD

### Phase 30: Email Notifications
**Goal**: All 6 tenant and API key lifecycle events trigger email notifications to the platform notification address and to the tenant email address when present
**Depends on**: Phase 28
**Requirements**: NOTIF-01, NOTIF-02, NOTIF-03, NOTIF-04, NOTIF-05, NOTIF-06
**Success Criteria** (what must be TRUE):
  1. API key generation and rotation events each trigger an email to the platform notification address (and tenant email if set), delivered via the existing `MailManager` AFTER_COMMIT pattern
  2. API key revocation and tenant reactivation events each trigger an email to both recipient addresses
  3. WebhookSecret generation and regeneration events each trigger an email to both recipient addresses
  4. Tenant status change (ACTIVE to SUSPENDED or vice versa) triggers an email to both recipient addresses
  5. Tenant webhookUrl change and tenant email address change each trigger an email to both recipient addresses
  6. All 6 event handlers live in a single `TenantEventEmailListener` bean, mirroring the `PlatformConfigEmailListener` pattern — `@EventListener` (not `@TransactionalEventListener`) on the listener method
**Plans**: TBD

### Phase 31: REST API Expansion
**Goal**: Admin can manage tenants and API keys through a complete set of REST endpoints, and the authentication filter correctly rejects all requests from suspended tenants regardless of individual key status
**Depends on**: Phases 28, 29, 30
**Requirements**: TENT-05, TENT-06, TENT-09
**Success Criteria** (what must be TRUE):
  1. Admin can retrieve a paginated, searchable list of tenants via `GET /admin/tenants` — results include tenant status and are filterable by name, email, ref, and status
  2. Admin can retrieve a tenant's full detail (name, email, TenantRef, status, webhookUrl, keys per environment) via `GET /admin/tenants/{id}`
  3. An API request using a valid, ACTIVE key belonging to a SUSPENDED tenant receives a 401 response — `ApiKeyAuthenticationFilter` checks both key status and tenant status as defense-in-depth
  4. All 8 new REST endpoints on `TenantAdminResource` are secured to `ROLE_ADMIN` and return appropriate HTTP status codes for all documented error conditions
**Plans**: TBD
**UI hint**: yes

### Phase 32: Admin UI
**Goal**: Admin can manage the full tenant and API key lifecycle through the Quasar SPA — create tenants, view/edit details, toggle status, manage keys per environment, and reveal the webhook secret
**Depends on**: Phase 31
**Requirements**: WSEC-02, AKEY-07
**Success Criteria** (what must be TRUE):
  1. The one-time key display modal shows the raw key once and has its close/dismiss button disabled until the admin either clicks a "Copy to clipboard" action or checks an explicit "I have saved this key" confirmation — the modal cannot be dismissed without acknowledgment
  2. Admin can reveal the current WebhookSecret via an eye icon toggle on the tenant detail page — the secret is fetched from the reveal endpoint and displayed inline, never stored in frontend state on page load
  3. `TenantListPage.vue` shows all tenants in a searchable, paginated `q-table` with status chips and action buttons for suspend/reactivate
  4. `TenantDetailPage.vue` shows all tenant fields, per-environment key cards (with ROTATED keys showing expiry countdown), and key action buttons (generate, rotate, revoke)
**Plans**: TBD
**UI hint**: yes

### Phase 33: E2E Tests
**Goal**: Cross-layer correctness of all critical tenant and API key lifecycle flows is machine-verified — suspension cascade, rotation grace period, reactivation raw-key return, concurrent rotation, and all notification events are covered by automated tests
**Depends on**: Phases 27–32
**Requirements**: (cross-layer validation — all 29 v5 requirements are exercised; no new requirements originate here)
**Success Criteria** (what must be TRUE):
  1. Suspension cascade E2E: after suspending a tenant, API requests using both formerly-ACTIVE and formerly-ROTATED keys receive 401 responses; the Envers audit log records the suspension with admin ID and timestamp
  2. Rotation grace period E2E: a ROTATED key is accepted within 24 hours; after the cleanup job runs, the same ROTATED key is rejected; exactly 1 ACTIVE key exists per environment at all times
  3. Reactivation E2E: the reactivation REST response body contains a non-null `rawKey` field; a second call to retrieve the key is impossible (404); the new PROD key authenticates successfully
  4. Re-rotation during grace period E2E: rotating a key while a prior ROTATED key is still in grace period immediately moves the prior ROTATED key to REVOKED — only 1 ROTATED key ever exists per environment
  5. All 6 email notification events fire and reach the `MailManager` queue during their respective lifecycle E2E flows
**Plans**: TBD

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
| 27. Schema and Enum Migration | v5 | 1/2 | In Progress|  |
| 28. Service Layer | v5 | 0/? | Not started | — |
| 29. Quartz Rotation Cleanup Job | v5 | 0/? | Not started | — |
| 30. Email Notifications | v5 | 0/? | Not started | — |
| 31. REST API Expansion | v5 | 0/? | Not started | — |
| 32. Admin UI | v5 | 0/? | Not started | — |
| 33. E2E Tests | v5 | 0/? | Not started | — |
