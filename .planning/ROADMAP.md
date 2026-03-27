# Roadmap: Payam

## Milestones

- ✅ **v1 Payment API** — Phases 1–13 (shipped 2026-03-26) — see [milestones/v1-ROADMAP.md](milestones/v1-ROADMAP.md)
- ✅ **v2 Logging Standardization** — Phases 14–17 (shipped 2026-03-27) — see [milestones/v2-ROADMAP.md](milestones/v2-ROADMAP.md)
- 🚧 **v3 E2E Test Suite** — Phases 18–23 (in progress)

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
**Plans**: TBD

Plans:
- [ ] 18-01: AbstractPayamE2ETest, AbstractPaymentFlowTest, AbstractWebhookFlowTest, AbstractFailureFlowTest base classes
- [ ] 18-02: PostgresContainerConfig, RedisContainerConfig, WireMockConfig, TestClockConfig, E2ESecurityConfig, TestDataCleaner

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
- [ ] 19-01: DatabaseVerifier, HashChainVerifier, InvariantVerifier, EventVerifier, LedgerVerifier, ProviderCallVerifier, WebhookDeliveryVerifier, TenantIsolationVerifier, CacheVerifier, QueryCountVerifier
- [ ] 19-02: TenantBuilder, ApiKeyBuilder, PaymentRequestBuilder, MtnWebhookPayloadBuilder, OrangeWebhookPayloadBuilder, FraudSignalBuilder, ReconciliationReportBuilder + deterministic UUID seeding

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
- [ ] 20-01: MtnPaymentInitiationE2ETest, OrangePaymentInitiationE2ETest, polling fallback, Orange payToken expiry
- [ ] 20-02: PaymentIdempotencyE2ETest, fraud-blocked path, provider timeout + circuit breaker

#### Phase 21: Webhook Flow Tests
**Goal**: Inbound and outbound webhook pipelines verified end-to-end
**Depends on**: Phase 20
**Requirements**: FLOWS-HOOK-01, FLOWS-HOOK-02, FLOWS-HOOK-03, FLOWS-HOOK-04, FLOWS-HOOK-05, FLOWS-HOOK-06
**Success Criteria** (what must be TRUE):
  1. MTN PUT and Orange POST webhooks trigger correct state transitions via double-check
  2. Duplicate webhook delivery is rejected; transaction state unchanged; no duplicate outbox event
  3. Outbound delivery to tenant callback URL includes HMAC-SHA256 signature
  4. 5xx from tenant triggers retry with exponential backoff (≥3 attempts)
**Plans**: TBD

Plans:
- [ ] 21-01: MtnWebhookDoubleCheckE2ETest, OrangeWebhookDoubleCheckE2ETest, replay protection, MTN PUT acceptance
- [ ] 21-02: OutboundWebhookDeliveryE2ETest, retry + exponential backoff

#### Phase 22: Fraud, Reconciliation, and Admin Flow Tests
**Goal**: Fraud engine, daily reconciliation, and admin transaction investigation verified end-to-end
**Depends on**: Phase 21
**Requirements**: FLOWS-FRAUD-01, FLOWS-FRAUD-02, FLOWS-FRAUD-03, FLOWS-RECON-01, FLOWS-RECON-02, FLOWS-RECON-03, FLOWS-RECON-04, FLOWS-ADMIN-01
**Success Criteria** (what must be TRUE):
  1. Velocity-blocked payments stop before any provider call is made
  2. Fraud evaluation timestamp is recorded before provider HTTP call timestamp on every flow
  3. Reconciliation detects missing, mismatched, and WAT-offset entries correctly
  4. Admin transaction search returns results scoped to caller's tenant only
**Plans**: TBD

Plans:
- [ ] 22-01: FraudVelocityBlockE2ETest, allowed path, invariantVerifier.assertFraudEvaluatedBeforeProviderCall
- [ ] 22-02: DailyReconciliationE2ETest (matched, missing, mismatched, WAT timestamp), TransactionInvestigationE2ETest

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
**Plans**: TBD

Plans:
- [ ] 23-01: HashChainIntegrityTest, LedgerDoubleEntryTest, IdempotencyNoDoubleChargeTest, TenantIsolationTest, StateMachineLegalTransitionsTest, WebhookDoubleCheckTest, FraudBeforeProviderCallTest, CallbackUrlSsrfGuardTest, InitBeforeProviderCallTest, OrangeTimestampWatTest
- [ ] 23-02: ConcurrentIdempotencyRaceTest, WebhookPollingRaceTest, VelocityCounterFloodTest, ApiKeyRotationGracePeriodTest
- [ ] 23-03: SM parameterized tests (MTN + Orange path matrices), TXN boundary tests (TXN-01–04), PITest configuration + 6 critical mutation kills

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
| 18. Test Infrastructure | v3 | 0/2 | Not started | - |
| 19. Verifiers + Test Data Builders | v3 | 0/2 | Not started | - |
| 20. Payment Flow Tests | v3 | 0/2 | Not started | - |
| 21. Webhook Flow Tests | v3 | 0/2 | Not started | - |
| 22. Fraud, Reconciliation, Admin Flow Tests | v3 | 0/2 | Not started | - |
| 23. Domain Invariants, Concurrency, SM, Mutation | v3 | 0/3 | Not started | - |
