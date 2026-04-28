# Roadmap: Payam

## Milestones

- ✅ **v1 Payment API** — Phases 1–13 (shipped 2026-03-26) — see [milestones/v1-ROADMAP.md](milestones/v1-ROADMAP.md)
- ✅ **v2 Logging Standardization** — Phases 14–17 (shipped 2026-03-27) — see [milestones/v2-ROADMAP.md](milestones/v2-ROADMAP.md)
- ✅ **v3 E2E Test Suite** — Phases 18–23 (shipped 2026-03-28) — see [milestones/v3-ROADMAP.md](milestones/v3-ROADMAP.md)
- ✅ **v4 Platform Config & Health** — Phases 24–26 (shipped 2026-04-02) — see [milestones/v4-ROADMAP.md](milestones/v4-ROADMAP.md)
- ✅ **v5 Tenant & API Key Management Service Layer** — Phases 27–29 (shipped 2026-04-06) — see [milestones/v5-ROADMAP.md](milestones/v5-ROADMAP.md)
- ✅ **v6 REST API Surface, Notifications & Admin UI** — Phases 30–34 (shipped 2026-04-14) — see [milestones/v6-ROADMAP.md](milestones/v6-ROADMAP.md)
- ✅ **v7 Backend Hardening & Bug Fixes** — Phases 35–40 (shipped 2026-04-17) — see [milestones/v7-ROADMAP.md](milestones/v7-ROADMAP.md)
- ✅ **v8 Platform Config PIN** — Phases 41–45 (shipped 2026-04-21) — see [milestones/v8-ROADMAP.md](milestones/v8-ROADMAP.md)
- ✅ **v9 Ledger Disbursement Support** — Phases 46–49 (shipped 2026-04-23) — see [milestones/v9-ROADMAP.md](milestones/v9-ROADMAP.md)
- 🚧 **v10 Client Disbursement API** — Phases 50–53 (in progress)

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

<details>
<summary>✅ v7 Backend Hardening & Bug Fixes (Phases 35–40) — SHIPPED 2026-04-17</summary>

- [x] Phase 35: Idempotency Correctness (2/2 plans) — completed 2026-04-14
- [x] Phase 36: Reconciliation Hardening (2/2 plans) — completed 2026-04-14
- [x] Phase 37: Webhook Subsystem Fixes (4/4 plans) — completed 2026-04-14
- [x] Phase 38: Transaction Boundary & Fraud Ordering (4/4 plans) — completed 2026-04-15
- [x] Phase 39: Concurrency Guards & DB Constraints (2/2 plans) — completed 2026-04-15
- [x] Phase 40: Operational Resilience (2/2 plans) — completed 2026-04-15

</details>

<details>
<summary>✅ v8 Platform Config PIN (Phases 41–45) — SHIPPED 2026-04-21</summary>

- [x] Phase 41: PIN Schema & Encryption Config (1/1 plans) — completed 2026-04-17
- [x] Phase 42: PIN Backend API (3/3 plans) — completed 2026-04-18
- [x] Phase 43: PIN Frontend (1/1 plans) — completed 2026-04-18
- [x] Phase 44: PIN Email Notification (2/2 plans) — completed 2026-04-18
- [x] Phase 45: PIN Add-Provider Fix (1/1 plans) — completed 2026-04-20

</details>

<details>
<summary>✅ v9 Ledger Disbursement Support (Phases 46–49) — SHIPPED 2026-04-23</summary>

- [x] Phase 46: Flyway V25 Schema Migration (1/1 plans) — completed 2026-04-21
- [x] Phase 47: Contract Types + LedgerService Rewrite (3/3 plans) — completed 2026-04-22
- [x] Phase 48: Test Coverage (2/2 plans) — completed 2026-04-22
- [x] Phase 49: Orange Cashout Wiring (2/2 plans) — completed 2026-04-23

</details>

### v10 Client Disbursement API (In Progress)

**Milestone Goal:** Expose a production-ready `POST /v1/disbursements` endpoint enabling tenants to send payouts to MTN MoMo and Orange Money subscribers, with full security controls, pre-funded balance gating, and E2E verification.

- [x] **Phase 50: Schema & Balance Infrastructure** — Flyway V26, Disbursement entity, WalletBalance entity with pessimistic locking, DisbursementStatus enum (completed 2026-04-25)
- [x] **Phase 51: Orchestrator & Public API** — DisbursementOrchestrator, DisbursementResource (POST+GET+LIST), step-up confirmation flow, MTN and Orange provider port wiring (completed 2026-04-25)
- [x] **Phase 52: Callbacks & Outbound Webhooks** — MTN and Orange disbursement callback controllers, DisbursementCompletedEvent, outbound webhook delivery (completed 2026-04-27)
- [x] **Phase 53: E2E Test Suite** — Both provider happy paths, balance gate + concurrency race, fraud block, idempotency race, step-up confirmation, callback replay (completed 2026-04-28)

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
- [x] 37-04-PLAN.md — Full mvn verify regression run + sign-off summary

### Phase 38: Transaction Boundary & Fraud Ordering
**Goal**: Fee evaluation and fraud scoring both execute outside the transaction boundary where they belong — neither holds a DB lock during computation
**Depends on**: Phase 37
**Requirements**: TXN-01, OPS-02
**Success Criteria** (what must be TRUE):
  1. Fee evaluation in PaymentOrchestrator completes before any transaction boundary is opened — the locked section covers only state writes
  2. Fraud velocity token consumption occurs only after the idempotency result is successfully cached — a cache write failure does not consume a rate-limit slot
  3. mvn verify passes including payment orchestration and fraud E2E tests
**Plans**: 4 plans
Plans:
- [x] 38-01-PLAN.md — TXN-01: hoist fee evaluation above transactionTemplate lock in PaymentOrchestrator + InOrder regression test
- [x] 38-02-PLAN.md — OPS-02: VelocityCheckService.probeVelocity + FraudScoringService.probe/consumeTokens + PaymentOrchestrator rewire + FraudVelocityOrderingIT
- [x] 38-03-PLAN.md — Full mvn verify regression run + sign-off summary (FAILED — CONC-03 regression)
- [x] 38-04-PLAN.md — Gap closure: OPS-02 via idempotency-key replay path + FraudVelocityOrderingIT + mvn verify

### Phase 39: Concurrency Guards & DB Constraints
**Goal**: Concurrent API key rotations are serialized at the DB layer and unbalanced ledger entries are rejected by a DB constraint before they can be committed
**Depends on**: Phase 38
**Requirements**: AKEY-09, LEDGER-01
**Success Criteria** (what must be TRUE):
  1. Two concurrent rotation requests for the same API key cannot both succeed — exactly one rotation wins; the other receives a conflict response
  2. The database rejects any INSERT into the ledger that would leave an entry_group_id without exactly one DEBIT and one CREDIT — a Flyway migration adds the constraint
  3. Existing ledger rows all satisfy the new constraint before the migration completes (no migration failure on a populated database)
  4. mvn verify passes including any concurrency and ledger integration tests
**Plans**: 2 plans
Plans:
- [x] 39-01-PLAN.md — AKEY-09: @Version optimistic lock on TenantApiKey + V22 migration + ApiAdvice 409 handler + ApiKeyConcurrentRotationIT
- [x] 39-02-PLAN.md — LEDGER-01: V23 deferrable unique constraint on ledger_entry(entry_group_id, direction) + LedgerConstraintIT

### Phase 40: Operational Resilience
**Goal**: Advisory locks are time-bounded so a crashed node cannot hold them indefinitely, and TenantContext is guaranteed cleared on every request path including exception paths
**Depends on**: Phase 39
**Requirements**: OPS-01, OPS-03
**Success Criteria** (what must be TRUE):
  1. MTN and Orange poller transactions declare an explicit timeout — a node crash or hung provider call cannot hold the advisory lock beyond the configured timeout
  2. TenantContext.clear() executes in a finally block on all request paths — an exception thrown during request processing does not leave the context populated for the next request on the same thread
  3. An integration test verifies that TenantContext is empty after a request that triggers an exception path
  4. mvn verify passes with the new integration test green
**Plans**: 2 plans
Plans:
- [x] 40-01-PLAN.md — OPS-01: @Transactional(timeout=300) on MTN/Orange poller executeInternal + reflection-based timeout unit tests
- [x] 40-02-PLAN.md — OPS-03: TenantContextExceptionIT (two-request probe: exception-path request followed by different-tenant probe)

### Phase 41: PIN Schema & Encryption Config
**Goal**: The system can store an AES256-encrypted PIN for each provider and resolve the encryption key from configuration
**Depends on**: Phase 40
**Requirements**: PIN-01, PIN-02
**Success Criteria** (what must be TRUE):
  1. A Flyway migration adds a nullable `pin` VARCHAR column to `main.platform_config` — the migration runs cleanly on a database that already has platform config rows
  2. `PayamPlatformProperties` exposes a `pinEncryptionSecret` field bound to `payam.platform.pin-encryption-secret`, which maps to the `PLATFORM_PIN_ENCRYPTION_SECRET` environment variable
  3. The `PlatformConfig` entity maps the `pin` column — the field holds ciphertext (never plaintext) when a PIN has been set
  4. mvn verify passes with no migration failures and no regressions
**Plans**: 1 plan
Plans:
- [x] 41-01-PLAN.md — V24 migration (pin column + platform_config_aud) + entity field + PayamPlatformProperties pinEncryptionSecret + YAML binding

### Phase 42: PIN Backend API
**Goal**: Admins can set, update, and retrieve a provider PIN through the existing platform config endpoints
**Depends on**: Phase 41
**Requirements**: PIN-03, PIN-04, PIN-05
**Success Criteria** (what must be TRUE):
  1. `PUT /v1/admin/platform-config/{provider}` accepts an optional `pin` field; a value that is not alphanumeric or is outside 4–8 characters returns HTTP 400; a valid PIN is encrypted via Cryptopher and saved atomically with MSISDN in one transaction
  2. `GET /v1/admin/platform-config/{provider}` returns `pinConfigured: true` when a PIN is stored and `pinConfigured: false` when none is set — the actual PIN value is never present in this response
  3. `GET /v1/admin/platform-config/{provider}/pin` returns the decrypted plaintext PIN when one is configured; returns HTTP 404 when no PIN has been set
  4. An empty or absent PIN field on PUT does not overwrite an existing stored PIN
  5. mvn verify passes including any platform config integration tests
**Plans**: TBD
**UI hint**: no

### Phase 43: PIN Frontend
**Goal**: Admins can view, reveal, and set a provider PIN directly from the platform config admin page
**Depends on**: Phase 42
**Requirements**: PIN-06, PIN-07, PIN-08, PIN-09
**Success Criteria** (what must be TRUE):
  1. Each provider card in `PlatformConfigPage.vue` shows a masked PIN input field (type=password) with a Quasar eye-toggle icon; the field is empty on page load (PIN value is not pre-fetched)
  2. Clicking the eye icon calls `GET /{provider}/pin`, populates the field with the decrypted plaintext, and starts a 60-second countdown; when the countdown expires the field is re-masked and the plaintext is cleared from component state
  3. Clicking the eye icon again before expiry re-masks the field immediately and cancels the countdown without waiting for it to expire
  4. The Save button submits MSISDN and PIN together in one PUT call; leaving the PIN field empty on save preserves the existing PIN — placeholder text communicates this to the admin
  5. The Add Provider dialog includes the same masked PIN input with eye toggle; no auto-mask timer applies in the dialog
**Plans**: TBD
**UI hint**: yes

### Phase 44: PIN Email Notification
**Goal**: Admins receive an email that identifies which platform config field(s) changed and who made the change, without exposing any PIN value
**Depends on**: Phase 42
**Requirements**: PIN-10, PIN-11
**Success Criteria** (what must be TRUE):
  1. `PlatformConfigChangedEvent` carries `msisdnChanged` (boolean), `pinChanged` (boolean), and `changedBy` (String) — the event is not fired when neither field changes and is not fired when a PIN is set for the first time (was null before the update)
  2. The notification email body states the provider name, which field(s) changed (MSISDN, PIN, or both), the admin username who made the change, and a timestamp
  3. The email never contains the PIN value in plaintext or ciphertext form
  4. The email is delivered after the transaction commits — a rollback of the config update does not send an email
**Plans**: TBD

### Phase 45: PIN Add-Provider Fix
**Goal**: PIN entered in the Add Provider dialog is persisted on first creation and the admin receives clear UX feedback
**Depends on**: Phase 43, Phase 42
**Requirements**: PIN-09
**Gap Closure**: Closes GAP-01 from v8-MILESTONE-AUDIT.md
**Success Criteria** (what must be TRUE):
  1. When an admin fills in the PIN field in the Add Provider dialog and submits, the PIN is encrypted and persisted atomically with the new `PlatformConfig` row — `pinConfigured: true` is returned in the response
  2. An empty PIN field in the Add Provider dialog creates a row with no PIN — `pinConfigured: false`, no error
  3. The Add Provider dialog provides brief UX feedback so the admin knows whether the PIN was set (e.g., updated `pinConfigured` state reflected on the card after creation)
  4. `mvn verify` passes with the existing platform config integration tests and any new tests covering first-creation with PIN
**Plans**: 1 plan

### Phase 46: Flyway V25 Schema Migration
**Goal**: The database schema supports disbursement ledger writes — V25 migration runs cleanly, all balance invariants are enforced by trigger, and the transaction table tracks flow direction
**Depends on**: Phase 45
**Requirements**: SCHEMA-01, SCHEMA-02, SCHEMA-03, SCHEMA-04
**Success Criteria** (what must be TRUE):
  1. The V25 migration completes successfully on a database that already has ledger_entry rows from the V23 deferrable unique constraint — no existing rows are rejected
  2. The V23 `uq_ledger_entry_group_direction` constraint is replaced by a deferrable trigger that asserts `SUM(DEBIT) == SUM(CREDIT)` per entry_group_id at commit time — a single DEBIT insert without a matching CREDIT is caught at commit, not at insert
  3. A zero-amount `PROVIDER_FEE` entry (amount = 0) inserts without violating the `CHECK (amount >= 0)` constraint — the previous `amount > 0` would have rejected it
  4. `main.transaction` and `main.transaction_aud` both have a nullable `flow VARCHAR(20)` column after migration — existing transaction rows have `flow = NULL`
  5. `mvn verify` passes with no migration failures and no regressions in existing ledger or constraint integration tests
**Plans**: TBD

### Phase 47: Contract Types + LedgerService Rewrite
**Goal**: Callers express ledger intent through typed `LedgerPosting` values, and `LedgerService` routes them to flow-specific entry builders — the old 4-argument signature is gone
**Depends on**: Phase 46
**Requirements**: CONTRACT-01, CONTRACT-02, CONTRACT-03, CONTRACT-04, SERVICE-01, SERVICE-02, SERVICE-03, SERVICE-04, SERVICE-05, SERVICE-06
**Success Criteria** (what must be TRUE):
  1. `LedgerFlow.COLLECTION` and `LedgerFlow.DISBURSEMENT` exist as enum values in `transaction/contract`; no caller references account code strings directly
  2. `LedgerPosting.collection(principal, currency)` and `LedgerPosting.disbursement(principal, fee, currency)` compile and can be instantiated; the compact constructor rejects negative principal, negative fee, null flow, and null currency with an exception
  3. `LedgerService.postEntry(txId, tenantId, LedgerPosting)` accepts both flow variants — a COLLECTION posting produces 2 ledger entries (DEBIT CUSTOMER_WALLET + CREDIT PROVIDER_CLEARING), a DISBURSEMENT posting produces 3 entries (DEBIT MERCHANT_WALLET gross + CREDIT CUSTOMER_WALLET principal + CREDIT PROVIDER_FEE fee)
  4. `WebhookTransitionService` compiles and passes its existing integration tests after migrating its call-site to `LedgerPosting.collection(amount, currency)` — the old 4-arg `postEntry` method no longer exists in the codebase
  5. `Transaction` entity has a nullable `flow` field annotated `@Enumerated(STRING)`; `getEffectiveFlow()` returns `LedgerFlow.COLLECTION` when `flow` is null
  6. `mvn verify` passes with all existing webhook and ledger tests green
**Plans**: 3 plans
Plans:
- [x] 47-01-PLAN.md — LedgerFlow enum + LedgerPosting record (CONTRACT-01..04) with unit tests
- [x] 47-02-PLAN.md — LedgerService rewrite (switch-routed, private account-code constants) + migrate 3 call sites (SERVICE-01..05)
- [x] 47-03-PLAN.md — Transaction.flow field + getEffectiveFlow() + mvn verify phase gate (SERVICE-06)

### Phase 48: Test Coverage
**Goal**: Every ledger flow variant is proven correct by unit tests, the PITest mutation threshold is maintained, and a real-database integration test confirms disbursement rows persist without constraint violation
**Depends on**: Phase 47
**Requirements**: TEST-01, TEST-02, TEST-03, TEST-04, TEST-05, TEST-06, TEST-07
**Cross-cutting gate**: TEST-08 (`mvn verify` passes) applies to every phase in this milestone — it is verified here as the final quality gate
**Success Criteria** (what must be TRUE):
  1. A unit test verifies that a COLLECTION posting produces exactly 2 entries with the correct account codes and balanced amounts (DEBIT CUSTOMER_WALLET + CREDIT PROVIDER_CLEARING)
  2. A unit test verifies that a DISBURSEMENT posting with fee > 0 produces exactly 3 entries where gross debit equals principal + fee and entries are balanced; a second unit test verifies fee = 0 produces a zero-amount PROVIDER_FEE credit that still balances
  3. A unit test verifies that `LedgerPosting` constructor rejects negative principal, negative fee, null currency, and null flow — four distinct rejection cases
  4. `LedgerBalanceGuardTest` includes a disbursement case and the PITest mutation kill rate for MUT-02 targets remains at or above 90%
  5. `LedgerServiceIT` persists a disbursement group of 3 rows in a real Testcontainers PostgreSQL instance — no constraint violation, amounts balance, rows are queryable by entry_group_id
  6. `LedgerVerifier.assertDisbursementLedgerBalanced(txId, principal, fee)` exists as a reusable helper; `assertLedgerBalanced` (collection) is unchanged
  7. `mvn verify` (unit + integration tests) passes cleanly — this is the final gate confirming all v9 phases are complete
**Plans**: TBD

### Phase 49: Orange Cashout Wiring
**Goal**: The Orange Money cashout path records disbursement ledger entries after provider confirmation, using the fee evaluated by `FeeEvaluationService`
**Depends on**: Phase 47
**Requirements**: CASHOUT-01, CASHOUT-02
**Success Criteria** (what must be TRUE):
  1. `PaymentCommand` has an optional `feeAmount` field (nullable `BigDecimal`); the orchestrator populates it from `FeeEvaluationService` before dispatching to `OrangeMoneyPort` — existing payment command construction sites compile without change
  2. After `OrangeMoneyPort.initiateCashout()` receives provider confirmation of success, it calls `LedgerService.postEntry()` with `LedgerPosting.disbursement(principal, fee, currency)` inside a `TransactionTemplate` block — `@Transactional` is not used on the method
  3. A cashout call with `feeAmount = null` (no fee configured) posts a zero-fee disbursement — `LedgerPosting.disbursement(principal, BigDecimal.ZERO, currency)` — without throwing
  4. `mvn verify` passes with no regressions in existing Orange Money or orchestration tests
**Plans**: 2 plans
Plans:
- [x] 49-01-PLAN.md — PaymentCommand feeAmount field + 13-arg compat constructor + withFeeAmount helper + PaymentOrchestrator fee wiring (CASHOUT-01)
- [x] 49-02-PLAN.md — OrangeMoneyPort.initiateCashout implementation (LedgerService DI + provider call + disbursement ledger posting in TransactionTemplate) + integration tests replacing stub test (CASHOUT-02)

### Phase 50: Schema & Balance Infrastructure
**Goal**: The database schema supports disbursements and the wallet balance gate enforces atomic reservation under concurrent load
**Depends on**: Phase 49
**Requirements**: BAL-01, BAL-02, BAL-03
**Success Criteria** (what must be TRUE):
  1. Flyway V28 runs cleanly and creates `main.disbursement`, `main.disbursement_aud`, `main.merchant_wallet_balance`, and `main.merchant_wallet_balance_aud` tables without error on a database that already has V27 applied (V26 and V27 are the existing security and audit-gap-closure migrations)
  2. `WalletBalanceService.checkAndReserve()` uses `SELECT FOR UPDATE` (pessimistic write lock) — under 20 concurrent requests with only enough balance for 1, exactly 1 succeeds and 19 receive `422 INSUFFICIENT_BALANCE` with no overdraft
  3. `WalletBalanceService.release()` restores the full reserved amount to the wallet when called for a `FAILED` disbursement — the wallet balance is identical before reservation and after release
  4. `DisbursementStatus` enum includes `INITIATED`, `PENDING_CONFIRMATION`, `PROCESSING`, `SUCCESS`, `FAILED`, and `EXPIRED` values; `EXPIRED` is terminal (empty allowedTransitions) and `WalletBalanceService.release` is NOT called for EXPIRED (per BAL-03, reserved balance is held pending manual ops resolution)
  5. `mvn verify` passes including `WalletBalanceConcurrencyIT` and any schema migration integration tests
**Plans**: 2 plans
Plans:
- [x] 50-01-PLAN.md — V28 Flyway migration (disbursement + merchant_wallet_balance tables) + DisbursementStatus enum with EXPIRED terminal state + state-machine unit tests (BAL-03)
- [ ] 50-02-PLAN.md — JPA entities (Disbursement, MerchantWalletBalance), repositories with @Lock(PESSIMISTIC_WRITE), WalletBalanceService.checkAndReserve/release, unit tests, WalletBalanceConcurrencyIT 20-thread no-overdraft proof, TestDataCleaner update (BAL-01, BAL-02)

### Phase 51: Orchestrator & Public API
**Goal**: Tenants can initiate and query disbursements through a production-ready API that enforces idempotency, fraud controls, step-up confirmation, and routes to the correct provider
**Depends on**: Phase 50
**Requirements**: DISB-01, DISB-02, DISB-03, DISB-04, PROV-01, PROV-02, PROV-03, SEC-01, SEC-02, SEC-03, SEC-04
**Success Criteria** (what must be TRUE):
  1. Tenant sends `POST /v1/disbursements` with a valid body and receives `202 Accepted` with a `disbursementId` and `status: PROCESSING`; the disbursement is routed to MTN or Orange based on MSISDN prefix; recipient account is validated as active before the provider call is made
  2. A disbursement with amount > 500,000 XAF returns `202 Accepted` with `status: PENDING_CONFIRMATION`; tenant must call `POST /v1/disbursements/{id}/confirm` to trigger the provider transfer; an unconfirmed disbursement in `PENDING_CONFIRMATION` transitions to `EXPIRED` after 15 minutes with no provider call
  3. Tenant can list disbursements via `GET /v1/disbursements` (paginated, filterable by status and date range) and query a single disbursement via `GET /v1/disbursements/{id}`; a second tenant's disbursement ID returns `404 Not Found`
  4. A duplicate `POST /v1/disbursements` with the same `Idempotency-Key` header within 24 hours returns the cached response without calling the provider; the idempotency key is stored under the `idempotency:dsb:<tenantId>:<key>` Redis namespace
  5. A disbursement that exceeds velocity limits (> 20/minute or > 200/hour per tenant, or > 10/day to same MSISDN) returns `429` or `422 DAILY_LIMIT_EXCEEDED` respectively; a disbursement triggering fraud score > 80 (new recipient +15, amount outlier +30, known-fraud MSISDN +80) is blocked with `FRAUD_BLOCK` before any provider call
**Plans**: 4 plans
- [x] 51-01-PLAN.md — DTOs, error codes, dsb-namespaced idempotency service (SEC-01)
- [x] 51-02-PLAN.md — DisbursementVelocityService + DisbursementFraudEvaluationService (SEC-02, SEC-03)
- [ ] 51-03-PLAN.md — DisbursementOrchestrator (initiate + confirm) + repo extensions (DISB-01, DISB-04, PROV-01-03, SEC-04 entry)
- [ ] 51-04-PLAN.md — DisbursementResource + DisbursementExpiryJob (DISB-02, DISB-03, SEC-04 expiry)

### Phase 52: Callbacks & Outbound Webhooks
**Goal**: Provider callbacks complete the async disbursement lifecycle and terminal state transitions trigger signed outbound webhook delivery to tenants
**Depends on**: Phase 51
**Requirements**: SEC-05, SEC-06
**Success Criteria** (what must be TRUE):
  1. MTN callbacks arrive at `/v1/callbacks/mtn/disbursement/{ref}` and Orange callbacks arrive at `/v1/callbacks/orange/disbursement`; neither path overlaps with the existing collection callback paths
  2. Each callback controller validates the request against: IP whitelist, signature/token verification, Redis replay deduplication on `providerReferenceId` (namespace `callbacks:dsb:<providerRefId>`), and a double-check against the provider status API before committing any state transition
  3. A replayed callback (same `providerReferenceId` received twice) is silently deduplicated — the second arrival does not trigger a second state transition, balance action, or webhook delivery
  4. When a disbursement reaches `SUCCESS` or `FAILED`, an outbound webhook is delivered to the tenant's configured URL with event `disbursement.completed` or `disbursement.failed`, signed with `X-Payam-Signature` (HMAC-SHA256); a non-2xx response from the tenant URL triggers exponential backoff with a maximum of 5 retries
  5. `mvn verify` passes including any callback controller integration tests
**Plans**: TBD

### Phase 53: E2E Test Suite
**Goal**: The disbursement system is machine-verified correct across both providers, all security controls, and financial-safety edge cases
**Depends on**: Phase 52
**Requirements**: TEST-01, TEST-02, TEST-03, TEST-04
**Success Criteria** (what must be TRUE):
  1. MTN disbursement E2E: happy path (initiate → PROCESSING → callback SUCCESS → SUCCESS with `LedgerVerifier.assertDisbursementLedgerBalanced`), callback FAILED → FAILED with balance released, MTN callback replay (second identical callback ignored with no second state transition)
  2. Orange disbursement E2E: happy path (initiate → PROCESSING → callback → SUCCESS), insufficient balance returns `422 INSUFFICIENT_BALANCE` without calling the provider, Orange callback replay protection confirmed
  3. Step-up confirmation E2E: large disbursement (> 500,000 XAF) returns `PENDING_CONFIRMATION`, confirm endpoint triggers provider call and transitions to `PROCESSING`, unconfirmed disbursement expires to `EXPIRED` after 15-minute Quartz tick with no provider call
  4. Concurrency race: 20 simultaneous disbursements against the same `MERCHANT_WALLET` with balance covering exactly 1 — exactly 1 succeeds (`PROCESSING`), 19 return `422 INSUFFICIENT_BALANCE` with zero overdraft confirmed by wallet balance query
  5. Fraud block E2E: a disbursement triggering fraud score > 80 returns `FRAUD_BLOCK` with zero provider calls made (confirmed via WireMock request count); idempotency race (20 concurrent threads with same key) produces exactly 1 disbursement row
**Plans**: 6 plans
Plans:
- [x] 53-01-PLAN.md — MtnDisbursementE2EIT: full MTN happy path (initiate → callback SUCCESS) + LedgerVerifier ledger assertion + FAILED callback wallet release + MTN callback replay (TEST-01)
- [x] 53-02-PLAN.md — OrangeDisbursementE2EIT: full Orange happy path (initiate → callback SUCCESSFULL) + insufficient balance 422 + Orange callback replay (TEST-02)
- [x] 53-03-PLAN.md — StepUpConfirmationE2EIT: amount>500K XAF gates to PENDING_CONFIRMATION (no provider call) + confirm dispatches + INVALID_STATE on already-processed (TEST-03)
- [x] 53-04-PLAN.md — DisbursementExpiryE2EIT: HTTP-initiated step-up + direct DisbursementExpiryJob.executeInternal → EXPIRED with BAL-03 wallet held + GET shows EXPIRED (TEST-03)
- [x] 53-05-PLAN.md — DisbursementConcurrencyRaceIT: 20 simultaneous POSTs against single-spend wallet → exactly 1 PROCESSING + 19 INSUFFICIENT_BALANCE + no overdraft (TEST-04)
- [x] 53-06-PLAN.md — DisbursementFraudBlockE2EIT: Redis blocklist (NEW_RECIPIENT+BLOCKLIST=95>80) → FRAUD_BLOCK + idempotency race 20 threads same key → exactly 1 disbursement row (TEST-01, TEST-04)

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
| 35. Idempotency Correctness | v7 | 2/2 | Complete | 2026-04-14 |
| 36. Reconciliation Hardening | v7 | 2/2 | Complete | 2026-04-14 |
| 37. Webhook Subsystem Fixes | v7 | 4/4 | Complete | 2026-04-14 |
| 38. Transaction Boundary & Fraud Ordering | v7 | 4/4 | Complete | 2026-04-15 |
| 39. Concurrency Guards & DB Constraints | v7 | 2/2 | Complete | 2026-04-15 |
| 40. Operational Resilience | v7 | 2/2 | Complete | 2026-04-15 |
| 41. PIN Schema & Encryption Config | v8 | 1/1 | Complete | 2026-04-17 |
| 42. PIN Backend API | v8 | 3/3 | Complete | 2026-04-18 |
| 43. PIN Frontend | v8 | 1/1 | Complete | 2026-04-18 |
| 44. PIN Email Notification | v8 | 2/2 | Complete | 2026-04-18 |
| 45. PIN Add-Provider Fix | v8 | 1/1 | Complete | 2026-04-20 |
| 46. Flyway V25 Schema Migration | v9 | 1/1 | Complete | 2026-04-21 |
| 47. Contract Types + LedgerService Rewrite | v9 | 3/3 | Complete | 2026-04-22 |
| 48. Test Coverage | v9 | 2/2 | Complete | 2026-04-22 |
| 49. Orange Cashout Wiring | v9 | 2/2 | Complete | 2026-04-23 |
| 50. Schema & Balance Infrastructure | v10 | 1/2 | Complete    | 2026-04-25 |
| 51. Orchestrator & Public API | v10 | 2/4 | Complete    | 2026-04-25 |
| 52. Callbacks & Outbound Webhooks | v10 | 4/4 | Complete    | 2026-04-27 |
| 53. E2E Test Suite | v10 | 6/6 | Complete    | 2026-04-28 |
