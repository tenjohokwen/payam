# Roadmap: Payam

## Milestones

- ✅ **v1 Payment API** — Phases 1–13 (shipped 2026-03-26) — see [milestones/v1-ROADMAP.md](milestones/v1-ROADMAP.md)
- ✅ **v2 Logging Standardization** — Phases 14–17 (shipped 2026-03-27) — see [milestones/v2-ROADMAP.md](milestones/v2-ROADMAP.md)
- ✅ **v3 E2E Test Suite** — Phases 18–23 (shipped 2026-03-28) — see [milestones/v3-ROADMAP.md](milestones/v3-ROADMAP.md)
- ✅ **v4 Platform Config & Health** — Phases 24–26 (shipped 2026-04-02) — see [milestones/v4-ROADMAP.md](milestones/v4-ROADMAP.md)
- ✅ **v5 Tenant & API Key Management Service Layer** — Phases 27–29 (shipped 2026-04-06) — see [milestones/v5-ROADMAP.md](milestones/v5-ROADMAP.md)
- ✅ **v6 REST API Surface, Notifications & Admin UI** — Phases 30–34 (shipped 2026-04-14) — see [milestones/v6-ROADMAP.md](milestones/v6-ROADMAP.md)
- 🚧 **v7 Backend Hardening & Bug Fixes** — Phases 35–40 (active)

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

<details>
<summary>✅ v6 REST API Surface, Notifications & Admin UI (Phases 30–34) — SHIPPED 2026-04-14</summary>

- [x] Phase 30: TENT-09 Auth Enforcement (1/1 plan) — completed 2026-04-06
- [x] Phase 31: Tenant REST API Surface (2/2 plans) — completed 2026-04-07
- [x] Phase 32: Email Notification Infrastructure (3/3 plans) — completed 2026-04-08
- [x] Phase 33: Admin UI — Tenant Management (4/4 plans) — completed 2026-04-09
- [x] Phase 34: Orange Money Adapter Alignment (2/2 plans) — completed 2026-04-14

</details>

<details open>
<summary>🚧 v7 Backend Hardening & Bug Fixes (Phases 35–40) — ACTIVE</summary>

- [ ] **Phase 35: Idempotency Correctness** - Fix write ordering (Postgres-first) and replace TOCTOU find+save with UPSERT
- [x] **Phase 36: Reconciliation Hardening** - Paginate unbounded fetch in batches and transition reports to FAILED on exception
- [ ] **Phase 37: Webhook Subsystem Fixes** - Eliminate N+1 tenant lookup, move enqueue to AFTER_COMMIT, add RestTemplate timeouts
- [ ] **Phase 38: Transaction Boundary & Fraud Ordering** - Move fee eval before transaction lock; reorder fraud eval to precede idempotency cache write
- [ ] **Phase 39: Concurrency Guards & DB Constraints** - Serialize concurrent API key rotation; enforce balanced ledger pairs at DB layer
- [ ] **Phase 40: Operational Resilience** - Bound advisory lock hold with poller transaction timeout; guarantee TenantContext cleanup in finally block

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

### Phase 34: Orange Money Adapter Alignment
**Goal**: Fix 7 root-cause issues in the Orange Money adapter so it correctly implements Use Case 1 (Initiate a Payment & Receive Notification) per the Orange Money spec
**Depends on**: Phase 33
**Requirements**: TBD
**Success Criteria** (what must be TRUE):
  1. OrangeMoneyClient sends a correctly structured 7-field POST /mp/pay body and calls POST /mp/init (not GET /infos/merchant)
  2. PlatformConfigService.findByProvider() correctly resolves channelMsisdn for Orange Money
  3. description field is propagated end-to-end: PaymentRequest → PaymentCommand → OrangeMoneyPort
  4. HMAC verification is removed from OrangeCallbackController (not part of Orange v1.0.2 spec)
  5. mvn verify passes with all existing E2E and integration tests green
**Plans**: 2 plans
Plans:
- [x] 34-01-PLAN.md — Core domain changes: description field on PaymentCommand/PaymentRequest, PlatformConfigService.findByProvider(), orchestrator wiring, PaymentRequestBuilder, construction sites
- [x] 34-02-PLAN.md — Orange adapter rewrite: new PayRequest/InitTransactionResponse DTOs, fixed OrangeMoneyClient, OrangeMoneyPort, OrangeMoneyConfig, OrangeCallbackController, config and test updates

### Phase 35: Idempotency Correctness
**Goal**: Idempotency storage is durable and race-free — Postgres holds the canonical record written before Redis, and concurrent duplicate requests produce exactly one DB row
**Depends on**: Phase 34
**Requirements**: IDEM-01, IDEM-02
**Success Criteria** (what must be TRUE):
  1. When Postgres write succeeds and Redis write subsequently fails, no stale value remains in Redis — the idempotency entry is authoritative in Postgres only
  2. When Postgres write fails, Redis is never updated — a subsequent retry is not incorrectly served a cached response
  3. A 20-thread concurrent flood with the same idempotency key produces exactly one DB row and all other threads receive the cached response (no duplicate inserts, no exception leaks)
  4. mvn verify passes with all existing concurrency and idempotency E2E tests green
**Plans**: 2 plans
Plans:
- [x] 35-01-PLAN.md — Repository upsert() + IdempotencyService.store() rewrite (Postgres-first) + new IDEM-01/IDEM-02 IT tests
- [x] 35-02-PLAN.md — Full mvn verify regression run + sign-off summary

### Phase 36: Reconciliation Hardening
**Goal**: Reconciliation is safe to run against large datasets and leaves no report permanently stuck in IN_PROGRESS on failure
**Depends on**: Phase 35
**Requirements**: RECON-01, RECON-02
**Success Criteria** (what must be TRUE):
  1. A reconciliation run for a day with more than 1000 transactions processes rows in pages of at most 1000 — heap usage does not grow linearly with transaction count
  2. Discrepancies discovered in each page are persisted before the next page is fetched — a crash mid-run produces partial results, not zero results
  3. When discrepancy persistence throws an exception during a run, the ReconciliationReport transitions to FAILED state — it is never left in IN_PROGRESS
  4. mvn verify passes including reconciliation E2E tests
**Plans**: 2 plans
Plans:
- [x] 36-01-PLAN.md — Paged repository query + ReconciliationProviderRunner bean (REQUIRES_NEW) + ReconciliationService rewrite + unit/IT tests (RECON-01, RECON-02)
- [x] 36-02-PLAN.md — Full mvn verify regression run + sign-off summary

### Phase 37: Webhook Subsystem Fixes
**Goal**: The webhook subsystem does not produce N+1 queries per delivery tick, does not enqueue before its triggering transaction commits, and cannot be blocked indefinitely by a slow tenant endpoint
**Depends on**: Phase 36
**Requirements**: WEBHOOK-01, WEBHOOK-02, WEBHOOK-03
**Success Criteria** (what must be TRUE):
  1. A Quartz job tick processing N pending webhook deliveries issues exactly 1 tenant-lookup query, not N+1
  2. Webhook enqueue fires only after the status-transition transaction has committed — a rollback of the state transition does not enqueue a delivery
  3. An enqueue failure does not roll back or otherwise affect the committed state transition
  4. The webhook RestTemplate has an explicit connect timeout of 5 seconds or less and a read timeout of 10 seconds or less — a hanging tenant endpoint cannot hold a Quartz thread indefinitely
  5. mvn verify passes including webhook E2E tests
**Plans**: 4 plans
Plans:
- [x] 37-01-PLAN.md — WEBHOOK-01: Bulk tenant load in WebhookDeliveryJob + WebhookDeliveryJobIT query-count regression test
- [x] 37-02-PLAN.md — WEBHOOK-02: WebhookEnqueueRequestedEvent + AFTER_COMMIT listener + WebhookEnqueueListenerIT rollback isolation
- [x] 37-03-PLAN.md — WEBHOOK-03: SimpleClientHttpRequestFactory 5s connect / 10s read timeouts + WebhookConfigTest
- [ ] 37-04-PLAN.md — Full mvn verify regression run + sign-off summary

### Phase 38: Transaction Boundary & Fraud Ordering
**Goal**: Fee evaluation and fraud scoring both execute outside the transaction boundary where they belong — neither holds a DB lock during computation
**Depends on**: Phase 37
**Requirements**: TXN-01, OPS-02
**Success Criteria** (what must be TRUE):
  1. Fee evaluation in PaymentOrchestrator completes before any transaction boundary is opened — the locked section covers only state writes
  2. Fraud velocity token consumption occurs only after the idempotency result is successfully cached — a cache write failure does not consume a rate-limit slot
  3. mvn verify passes including payment orchestration and fraud E2E tests
**Plans**: TBD

### Phase 39: Concurrency Guards & DB Constraints
**Goal**: Concurrent API key rotations are serialized at the DB layer and unbalanced ledger entries are rejected by a DB constraint before they can be committed
**Depends on**: Phase 38
**Requirements**: AKEY-09, LEDGER-01
**Success Criteria** (what must be TRUE):
  1. Two concurrent rotation requests for the same API key cannot both succeed — exactly one rotation wins; the other receives a conflict response
  2. The database rejects any INSERT into the ledger that would leave an entry_group_id without exactly one DEBIT and one CREDIT — a Flyway migration adds the constraint
  3. Existing ledger rows all satisfy the new constraint before the migration completes (no migration failure on a populated database)
  4. mvn verify passes including any concurrency and ledger integration tests
**Plans**: TBD

### Phase 40: Operational Resilience
**Goal**: Advisory locks are time-bounded so a crashed node cannot hold them indefinitely, and TenantContext is guaranteed cleared on every request path including exception paths
**Depends on**: Phase 39
**Requirements**: OPS-01, OPS-03
**Success Criteria** (what must be TRUE):
  1. MTN and Orange poller transactions declare an explicit timeout — a node crash or hung provider call cannot hold the advisory lock beyond the configured timeout
  2. TenantContext.clear() executes in a finally block on all request paths — an exception thrown during request processing does not leave the context populated for the next request on the same thread
  3. An integration test verifies that TenantContext is empty after a request that triggers an exception path
  4. mvn verify passes with the new integration test green
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
| 27. Schema and Enum Migration | v5 | 2/2 | Complete | 2026-04-03 |
| 28. Service Layer | v5 | 2/2 | Complete | 2026-04-06 |
| 28.1. API Key Format Fix (AKEY-01) | v5 | 1/1 | Complete | 2026-04-06 |
| 29. Quartz Rotation Cleanup Job | v5 | 1/1 | Complete | 2026-04-06 |
| 30. TENT-09 Auth Enforcement | v6 | 1/1 | Complete | 2026-04-06 |
| 31. Tenant REST API Surface | v6 | 2/2 | Complete | 2026-04-07 |
| 32. Email Notification Infrastructure | v6 | 3/3 | Complete | 2026-04-08 |
| 33. Admin UI — Tenant Management | v6 | 4/4 | Complete | 2026-04-09 |
| 34. Orange Money Adapter Alignment | v6 | 2/2 | Complete | 2026-04-14 |
| 35. Idempotency Correctness | v7 | 2/2 | Complete    | 2026-04-14 |
| 36. Reconciliation Hardening | v7 | 2/2 | Complete    | 2026-04-14 |
| 37. Webhook Subsystem Fixes | v7 | 3/4 | In Progress|  |
| 38. Transaction Boundary & Fraud Ordering | v7 | 0/? | Not started | — |
| 39. Concurrency Guards & DB Constraints | v7 | 0/? | Not started | — |
| 40. Operational Resilience | v7 | 0/? | Not started | — |
