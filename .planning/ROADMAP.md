# Roadmap: Payam

## Milestones

- ✅ **v1 Payment API** — Phases 1–13 (shipped 2026-03-26) — see [milestones/v1-ROADMAP.md](milestones/v1-ROADMAP.md)
- ✅ **v2 Logging Standardization** — Phases 14–17 (shipped 2026-03-27) — see [milestones/v2-ROADMAP.md](milestones/v2-ROADMAP.md)
- 🚧 **v3 E2E Test Suite** — Phases 18–23 (in progress)
- 📋 **v4 Platform Config & Health** — Phases 24–26 (planned)

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

### 🚧 v3 E2E Test Suite (In Progress)

**Milestone Goal:** Provably correct, fraud-resistant, tamper-evident payment processing verified end-to-end — every critical invariant machine-checked, every race condition covered, mutation testing at ≥90%.

#### Phase 18: Test Infrastructure
**Goal**: Spring Boot test context boots with Testcontainers, WireMock, and all support plumbing
**Depends on**: Phase 17
**Requirements**: INFRA-01, INFRA-02, INFRA-03, INFRA-04, INFRA-05, INFRA-06, INFRA-07, INFRA-08, INFRA-09
**Success Criteria** (what must be TRUE):
  1. Tests start with real PostgreSQL + Redis containers and Flyway schema applied
  2. WireMock stubs for both MTN and Orange endpoints are available
  3. Test data is wiped clean before each test (no state bleed between tests)
  4. Test API keys are injectable without real key-generation overhead
  5. Fixed WAT clock available for deterministic Orange timestamp tests
**Plans**: 2/2 — completed 2026-03-27

Plans:
- [x] 18-01: AbstractPayamE2ETest, AbstractPaymentFlowTest, AbstractWebhookFlowTest, AbstractFailureFlowTest base classes
- [x] 18-02: PostgresContainerConfig, RedisContainerConfig, WireMockConfig, TestClockConfig, E2ESecurityConfig, TestDataCleaner

#### Phase 19: Verifiers + Test Data Builders
**Goal**: All verifier components and data builders exist and are composable for any test scenario
**Depends on**: Phase 18
**Requirements**: VERIF-01, VERIF-02, VERIF-03, VERIF-04, VERIF-05, VERIF-06, VERIF-07, VERIF-08, VERIF-09, VERIF-10, BUILD-01, BUILD-02, BUILD-03, BUILD-04, BUILD-05, BUILD-06, BUILD-07, BUILD-08
**Success Criteria** (what must be TRUE):
  1. Every domain invariant can be asserted with a single-line verifier call
  2. Hash chain integrity can be verified for any event sequence
  3. Test data for any payment scenario is constructable with deterministic builders
  4. N+1 query regressions are detectable via QueryCountVerifier
**Plans**: TBD

Plans:
- [x] 19-01: DatabaseVerifier, HashChainVerifier, InvariantVerifier, EventVerifier, LedgerVerifier, ProviderCallVerifier, WebhookDeliveryVerifier, TenantIsolationVerifier, CacheVerifier, QueryCountVerifier
- [x] 19-02: TenantBuilder, ApiKeyBuilder, PaymentRequestBuilder, MtnWebhookPayloadBuilder, OrangeWebhookPayloadBuilder, FraudSignalBuilder, ReconciliationReportBuilder + deterministic UUID seeding

#### Phase 20: Payment Flow Tests
**Goal**: MTN and Orange happy/unhappy paths verified end-to-end through all verifiers
**Depends on**: Phase 19
**Requirements**: FLOWS-PAY-01, FLOWS-PAY-02, FLOWS-PAY-03, FLOWS-PAY-04, FLOWS-PAY-05, FLOWS-PAY-06, FLOWS-PAY-07
**Success Criteria** (what must be TRUE):
  1. MTN full lifecycle (INITIATED→PROCESSING→SUCCESS via webhook) passes all verifiers
  2. Orange full lifecycle with WAT timestamp handling passes all verifiers
  3. Polling fallback drives payment to SUCCESS when no webhook arrives
  4. Fraud-blocked path produces zero provider calls and zero ledger entries
  5. Idempotency: duplicate request returns same response; cross-tenant creates separate transaction
**Plans**: TBD

Plans:
- [x] 20-01: MtnPaymentInitiationE2ETest, OrangePaymentInitiationE2ETest, polling fallback, Orange payToken expiry
- [x] 20-02: PaymentIdempotencyE2ETest, fraud-blocked path, provider timeout + circuit breaker

#### Phase 21: Webhook Flow Tests
**Goal**: Inbound and outbound webhook pipelines verified end-to-end
**Depends on**: Phase 20
**Requirements**: FLOWS-HOOK-01, FLOWS-HOOK-02, FLOWS-HOOK-03, FLOWS-HOOK-04, FLOWS-HOOK-05, FLOWS-HOOK-06
**Success Criteria** (what must be TRUE):
  1. MTN PUT and Orange POST webhooks trigger correct state transitions via double-check
  2. Duplicate webhook delivery is rejected; transaction state unchanged; no duplicate outbox event
  3. Outbound delivery to tenant callback URL includes HMAC-SHA256 signature
  4. 5xx from tenant triggers retry with exponential backoff (≥3 attempts)
**Plans**: 2/2 — completed 2026-03-27

Plans:
- [x] 21-01: MtnWebhookDoubleCheckE2ETest, OrangeWebhookDoubleCheckE2ETest, replay protection, MTN PUT acceptance
- [x] 21-02: OutboundWebhookDeliveryE2ETest, retry + exponential backoff

#### Phase 22: Fraud, Reconciliation, and Admin Flow Tests
**Goal**: Fraud engine, daily reconciliation, and admin transaction investigation verified end-to-end
**Depends on**: Phase 21
**Requirements**: FLOWS-FRAUD-01, FLOWS-FRAUD-02, FLOWS-FRAUD-03, FLOWS-RECON-01, FLOWS-RECON-02, FLOWS-RECON-03, FLOWS-RECON-04, FLOWS-ADMIN-01
**Success Criteria** (what must be TRUE):
  1. Velocity-blocked payments stop before any provider call is made
  2. Fraud evaluation timestamp is recorded before provider HTTP call timestamp on every flow
  3. Reconciliation detects missing, mismatched, and WAT-offset entries correctly
  4. Admin transaction search returns results scoped to caller's tenant only
**Plans**: 2/2 — completed 2026-03-27

Plans:
- [x] 22-01: FraudVelocityBlockE2ETest, allowed path, invariantVerifier.assertFraudEvaluatedBeforeProviderCall
- [x] 22-02: DailyReconciliationE2ETest (matched, missing, mismatched, WAT timestamp), TransactionInvestigationE2ETest

#### Phase 23: Domain Invariants, Concurrency, State Machine, and Mutation Tests
**Goal**: All critical domain invariants provably hold under concurrency; mutation testing ≥90%
**Depends on**: Phase 22
**Requirements**: INV-01-TEST, INV-02-TEST, INV-03-TEST, INV-04-TEST, INV-05-TEST, INV-06-TEST, INV-07-TEST, INV-08-TEST, INV-09-TEST, INV-10-TEST, CONC-01, CONC-02, CONC-03, CONC-04, SM-01, SM-02, SM-03, SM-04, TXN-01, TXN-02, TXN-03, TXN-04, MUT-01, MUT-02
**Success Criteria** (what must be TRUE):
  1. Hash chain, ledger double-entry, idempotency, and tenant isolation invariants all pass
  2. Concurrent idempotency race (20 threads) produces exactly 1 payment row and 1 provider call
  3. Webhook/polling race produces exactly 1 SUCCESS row and 1 outbound delivery
  4. All illegal state transitions throw without DB mutation
  5. PITest kills all 6 critical mutations with mutationThreshold=90
**Plans**: 5/5 — completed 2026-03-28

Plans:
- [x] 23-01: HashChainIntegrityTest, LedgerDoubleEntryTest, IdempotencyNoDoubleChargeTest, TenantIsolationTest, StateMachineLegalTransitionsTest, WebhookDoubleCheckTest, FraudBeforeProviderCallTest, CallbackUrlSsrfGuardTest, InitBeforeProviderCallTest, OrangeTimestampWatTest
- [x] 23-02: ConcurrentIdempotencyRaceTest, WebhookPollingRaceTest, VelocityCounterFloodTest, ApiKeyRotationGracePeriodTest
- [x] 23-03: SM parameterized tests (MTN + Orange path matrices), TXN boundary tests (TXN-01–04), PITest configuration + 6 critical mutation kills
- [x] 23-04: CONC-02 gap closure — WebhookPollingRaceTest outbound provider call count assertion
- [x] 23-05: MUT-02 gap closure — PITest targetClasses expanded to all 6 MUT-02 classes; domain unit tests rewritten to call real production classes

### 📋 v4 Platform Config & Health (Planned)

**Milestone Goal:** Admin can view and update platform MSISDNs for both providers; Spring Boot Actuator reflects live provider health and circuit breaker state; health dashboard is accessible in the admin UI to admin users only.

#### Phase 24: Platform Configuration ✅
**Goal**: Admin can view and update platform MSISDNs for both providers, with email notification on change
**Depends on**: Phase 23
**Requirements**: PCONF-01, PCONF-02, PCONF-03, PCONF-04
**Success Criteria** (what must be TRUE):
  1. Admin can view the current Orange and MTN platform MSISDNs in the admin UI ✅
  2. Admin can update the Orange platform MSISDN and see it persisted on reload ✅
  3. Admin can update the MTN platform MSISDN and see it persisted on reload ✅
  4. A notification email is sent to the configured address whenever either platform MSISDN is changed ✅
**Plans**: 3/3 — completed 2026-03-30

Plans:
- [x] 24-01: Flyway V17 migration, PlatformConfig entity/repo, PlatformConfigService, PlatformConfigAdminResource (GET + PUT)
- [x] 24-02: EmailTemplate enum entry, PlatformConfigEmailListener, platformConfigChanged.html Thymeleaf template
- [x] 24-03: PlatformConfigPage.vue (Vue 3 Composition API), admin.api.js API functions, routes.js child route

#### Phase 25: Provider Health Indicators
**Goal**: Spring Boot Actuator `/manage/health` reflects live Orange and MTN MSISDN validation and circuit breaker state
**Depends on**: Phase 24
**Requirements**: HLTH-01, HLTH-02, HLTH-03, HLTH-04, HLTH-05
**Success Criteria** (what must be TRUE):
  1. `/manage/health` returns UP when both Orange and MTN platform MSISDNs pass their provider validations
  2. `/manage/health` returns DOWN when either MSISDN fails provider validation
  3. Health response includes circuit breaker status for the Orange Money provider adapter
  4. Health response includes circuit breaker status for the MTN MoMo provider adapter
**Plans**: 1/1 — completed 2026-03-31

Plans:
- [x] 25-01: OrangePlatformHealthIndicator + MtnPlatformHealthIndicator (HealthIndicator beans, validateSubscriber, CB state detail)

#### Phase 26: Health Dashboard UI
**Goal**: Admin UI health dashboard surfaces all health check results; access is restricted to admin users
**Depends on**: Phase 25
**Requirements**: HLTH-06, HLTH-07
**Success Criteria** (what must be TRUE):
  1. Admin users can view a health dashboard page showing all health check results
  2. Non-admin (client/tenant) users see access-denied banner (no component details shown)
  3. Dashboard displays live provider MSISDN validation status and circuit breaker state for both providers
**Plans**: 1/1 — completed 2026-04-02

Plans:
- [x] 26-01: HealthDashboardPage.vue, getHealth() in admin.api.js, health-dashboard route

#### Phase 27: Schema and Enum Migration
**Goal**: The entity model and database constraints correctly represent the v5 tenant/key specification — v1 defects corrected, environment enum migrated, partial unique index in place
**Depends on**: Phase 26
**Requirements**: AKEY-01, AKEY-03
**Success Criteria** (what must be TRUE):
  1. `Tenant` entity has a non-nullable `keyPrefix` column (`updatable = false`) that stores the 3-char uppercase prefix derived from the tenant name at creation time
  2. `TenantApiKey.environment` maps to `ApiKeyEnvironment` enum with values `PROD`, `DEV`, `SANDBOX` — the legacy `LIVE` value no longer exists in DB or code
  3. A partial unique index `(tenant_id, environment) WHERE key_status = 'ACTIVE'` exists in the database and is enforced by Flyway migration
  4. A UNIQUE constraint on `key_hash` exists on the `tenant_api_key` table
  5. Flyway runs cleanly from a fresh schema with no `UPDATE before CHECK` ordering errors
**Plans**: 2/2 — completed 2026-04-03

Plans:
- [x] 27-01-PLAN.md — Flyway migrations V18/V19 + ApiKeyEnvironment enum + entity model + service updates
- [x] 27-02-PLAN.md — LIVE-to-PROD call site migration across all test files

#### Phase 28: Service Layer
**Goal**: Complete tenant and API key service layer — tenant lifecycle (create/update/suspend/reactivate), per-environment key generation and rotation, WebhookSecret management, and Hibernate Envers audit trail
**Depends on**: Phase 27
**Requirements**: TENT-01, TENT-02, TENT-03, TENT-04, TENT-07, TENT-08, AKEY-02, AKEY-04, AKEY-06, AKEY-08, WSEC-01, WSEC-03, AUDIT-01, AUDIT-02, AUDIT-03
**Success Criteria** (what must be TRUE):
  1. Admin can create a tenant with auto-generated TenantRef (UUID), initial PROD API key (raw key returned once), and WebhookSecret
  2. Admin can update a tenant's name, email, and webhookUrl
  3. Admin can suspend a tenant — all API keys across all environments are immediately revoked
  4. Admin can reactivate a suspended tenant — a new PROD key is auto-generated and returned once
  5. Admin can generate a per-environment key (PROD/DEV/SANDBOX) and receive raw key exactly once
  6. Admin can rotate a key — old key enters ROTATED (24h grace), new ACTIVE key raw value returned once; if another ROTATED key exists for same environment it is immediately REVOKED
  7. Admin can manually revoke a key (immediate, no grace period)
  8. Admin can regenerate WebhookSecret (new secret replaces old)
  9. Hibernate Envers captures all Tenant and TenantApiKey mutations; every key generation/rotation event logs acting admin ID and timestamp
**Plans**: 2 plans

Plans:
- [x] 28-01-PLAN.md — Flyway V20 Envers audit tables + TenantService lifecycle + ApiKeyService guards
- [x] 28-02-PLAN.md — TenantServiceIT + TenantAuditIT integration tests + TenantProvisioningIT webhookSecret assertion

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
| 19. Verifiers + Test Data Builders | v3 | 0/2 | Not started | - |
| 20. Payment Flow Tests | v3 | 0/2 | Not started | - |
| 21. Webhook Flow Tests | v3 | 2/2 | Complete | 2026-03-27 |
| 22. Fraud, Reconciliation, Admin Flow Tests | v3 | 2/2 | Complete | 2026-03-27 |
| 23. Domain Invariants, Concurrency, SM, Mutation | v3 | 5/5 | Complete | 2026-03-28 |
| 24. Platform Configuration | v4 | 3/3 | Complete | 2026-03-30 |
| 25. Provider Health Indicators | v4 | 1/1 | Complete | 2026-03-31 |
| 26. Health Dashboard UI | v4 | 1/1 | Complete | 2026-04-02 |
| 27. Schema and Enum Migration | v5 | 2/2 | Complete | 2026-04-03 |
| 28. Service Layer | v5 | 2/2 | Complete    | 2026-04-06 |
| 29. Quartz Rotation Cleanup Job | v5 | 0/? | Not started | — |
| 30. Email Notifications | v5 | 0/? | Not started | — |
| 31. REST API Expansion | v5 | 0/? | Not started | — |
| 32. Admin UI | v5 | 0/? | Not started | — |
| 33. E2E Tests | v5 | 0/? | Not started | — |
