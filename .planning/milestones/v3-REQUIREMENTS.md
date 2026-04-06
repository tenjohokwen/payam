# Requirements Archive: v3 E2E Test Suite

**Archived:** 2026-03-28
**Milestone:** v3 — all 74 v1 requirements shipped
**Original defined:** 2026-03-26

---

# Requirements: Payam E2E Test Suite

**Defined:** 2026-03-26
**Core Value:** Reliable, fraud-resistant payment processing with full traceability — no double charges, no blind trust of webhooks, no silent failures.

## v1 Requirements

### INFRA: Test Infrastructure

- [x] **INFRA-01**: Spring Boot test context boots with real PostgreSQL via Testcontainers
  - *Outcome: Validated — PostgresContainerConfig; Flyway schema applied on startup*
- [x] **INFRA-02**: Spring Boot test context boots with real Redis via Testcontainers
  - *Outcome: Validated — RedisContainerConfig*
- [x] **INFRA-03**: WireMock stubs for MTN provider endpoints are available in tests
  - *Outcome: Validated — WireMockConfig constants; mtn server registered in @EnableWireMock*
- [x] **INFRA-04**: WireMock stubs for Orange provider endpoints are available in tests
  - *Outcome: Validated — orange server registered in @EnableWireMock*
- [x] **INFRA-05**: Test data is wiped clean before each test (no state bleed between tests)
  - *Outcome: Validated — TestDataCleaner.wipeAll() called in @BeforeEach; preserves fee_rule/fraud_rule/msisdn_prefix_route seed rows*
- [x] **INFRA-06**: Test API keys are injectable without real key-generation overhead
  - *Outcome: Validated — E2ESecurityConfig dual-seed pattern; ON CONFLICT DO NOTHING idempotent*
- [x] **INFRA-07**: Fixed WAT clock available for deterministic Orange timestamp tests
  - *Outcome: Validated — TestClockConfig fixed-clock bean*
- [x] **INFRA-08**: Base class hierarchy enforces orchestration phase order via final template methods
  - *Outcome: Validated — AbstractPayamE2ETest → AbstractPaymentFlowTest → AbstractWebhookFlowTest; runFlow() final*
- [x] **INFRA-09**: Failure flow tests have a separate base class supporting fault injection before executeFlow
  - *Outcome: Validated — AbstractFailureFlowTest extends AbstractPayamE2ETest directly (not AbstractPaymentFlowTest)*

### VERIF: Domain Invariant Verifiers

- [x] **VERIF-01**: DatabaseVerifier asserts transaction row state against expected values
  - *Outcome: Validated — DatabaseVerifier.assertTransactionState() used across all E2E tests*
- [x] **VERIF-02**: HashChainVerifier asserts SHA-256 hash chain integrity for any event sequence
  - *Outcome: Validated — HashChainIntegrityTest*
- [x] **VERIF-03**: InvariantVerifier provides single-call assertions for all critical domain invariants
  - *Outcome: Validated — assertAll() composes multiple invariant checks*
- [x] **VERIF-04**: EventVerifier asserts payment event log entries and ordering
  - *Outcome: Validated — EventVerifier used in payment flow tests*
- [x] **VERIF-05**: LedgerVerifier asserts double-entry ledger balance and entry counts
  - *Outcome: Validated — LedgerDoubleEntryTest; assertLedgerBalanced() in assertAll()*
- [x] **VERIF-06**: ProviderCallVerifier asserts WireMock call counts to provider endpoints
  - *Outcome: Validated — used across fraud-blocked and circuit breaker tests*
- [x] **VERIF-07**: WebhookDeliveryVerifier asserts outbound webhook delivery log entries
  - *Outcome: Validated — OutboundWebhookDeliveryE2ETest*
- [x] **VERIF-08**: TenantIsolationVerifier asserts cross-tenant data isolation
  - *Outcome: Validated — TenantIsolationTest*
- [x] **VERIF-09**: CacheVerifier asserts Redis idempotency key and velocity counter state
  - *Outcome: Validated — IdempotencyNoDoubleChargeTest*
- [x] **VERIF-10**: QueryCountVerifier detects N+1 query regressions
  - *Outcome: Built — created in Phase 19; not consumed in Phase 20-23 (orphaned — tracked in v3-INTEGRATION-CHECK.md)*

### BUILD: Test Data Builders

- [x] **BUILD-01**: TenantBuilder creates test tenants with API keys and webhook secrets deterministically
  - *Outcome: Validated — used in all E2E tests; CreatedTenant exposes rawApiKey*
- [x] **BUILD-02**: ApiKeyBuilder creates test API key entities with deterministic values
  - *Outcome: Built — created in Phase 19; consumed in Phase 28.1 (orphaned for v3 tests)*
- [x] **BUILD-03**: PaymentRequestBuilder constructs payment initiation requests for any scenario
  - *Outcome: Validated — used in payment flow tests*
- [x] **BUILD-04**: MtnWebhookPayloadBuilder constructs MTN webhook callback payloads
  - *Outcome: Validated — used in webhook flow tests*
- [x] **BUILD-05**: OrangeWebhookPayloadBuilder constructs Orange webhook callback payloads
  - *Outcome: Built — created in Phase 19; not consumed in Phase 20-23 (orphaned)*
- [x] **BUILD-06**: FraudSignalBuilder constructs fraud evaluation inputs for rule testing
  - *Outcome: Built — created in Phase 19; not consumed in Phase 20-23 (orphaned)*
- [x] **BUILD-07**: ReconciliationReportBuilder constructs provider reconciliation report data
  - *Outcome: Built — created in Phase 19; not consumed in Phase 20-23 (orphaned)*
- [x] **BUILD-08**: Builders use deterministic UUID seeding for reproducible test scenarios
  - *Outcome: Validated — deterministic UUID seed pattern in TenantBuilder*

### FLOWS-PAY: Payment Flow Tests

- [x] **FLOWS-PAY-01**: MTN full lifecycle (INITIATED→PROCESSING→SUCCESS via webhook) passes all verifiers
  - *Outcome: Validated — MtnPaymentInitiationE2ETest*
- [x] **FLOWS-PAY-02**: Orange full lifecycle with WAT timestamp handling passes all verifiers
  - *Outcome: Validated — OrangePaymentInitiationE2ETest with TestClockConfig*
- [x] **FLOWS-PAY-03**: Polling fallback drives payment to SUCCESS when no webhook arrives
  - *Outcome: Validated — polling fallback test in Phase 20-01; REQUIRES_NEW JDBC backdating pattern*
- [x] **FLOWS-PAY-04**: Orange payToken expiry drives payment to PROCESSING (not FAILED) at polling boundary
  - *Outcome: Validated — OrangePayTokenExpiry; actual code asserts PROCESSING (plan spec corrected)*
- [x] **FLOWS-PAY-05**: Fraud-blocked path produces zero provider calls and zero ledger entries
  - *Outcome: Validated — FraudBlockedPaymentE2ETest*
- [x] **FLOWS-PAY-06**: Duplicate payment request returns same response (idempotency)
  - *Outcome: Validated — PaymentIdempotencyE2ETest; 20-thread race*
- [x] **FLOWS-PAY-07**: Cross-tenant duplicate creates separate transaction (idempotency scoped per tenant)
  - *Outcome: Validated — cross-tenant idempotency assertion in PaymentIdempotencyE2ETest*

### FLOWS-HOOK: Webhook Flow Tests

- [x] **FLOWS-HOOK-01**: MTN PUT webhook triggers correct state transition via double-check
  - *Outcome: Validated — MtnWebhookDoubleCheckE2ETest*
- [x] **FLOWS-HOOK-02**: Orange POST webhook triggers correct state transition via double-check
  - *Outcome: Validated — OrangeWebhookDoubleCheckE2ETest*
- [x] **FLOWS-HOOK-03**: Duplicate webhook delivery is rejected; transaction state unchanged; no duplicate outbox event
  - *Outcome: Validated — WebhookReplayProtectionE2ETest; Redis replay protection*
- [x] **FLOWS-HOOK-04**: Outbound delivery to tenant callback URL includes HMAC-SHA256 signature
  - *Outcome: Validated — OutboundWebhookDeliveryE2ETest signature assertion*
- [x] **FLOWS-HOOK-05**: 5xx from tenant triggers retry with exponential backoff (≥3 attempts)
  - *Outcome: Validated — retry + exponential backoff test in Phase 21-02*
- [x] **FLOWS-HOOK-06**: MTN PUT acceptance (non-standard HTTP method) handled correctly
  - *Outcome: Validated — MTN PUT acceptance test; ApiAdvice HttpRequestMethodNotSupportedException fix*

### FLOWS-FRAUD: Fraud Flow Tests

- [x] **FLOWS-FRAUD-01**: Velocity-blocked payment stops before any provider call is made
  - *Outcome: Validated — FraudVelocityBlockE2ETest; exactly(1) WireMock POST verifier*
- [x] **FLOWS-FRAUD-02**: Fraud evaluation timestamp is recorded before provider HTTP call timestamp on every flow
  - *Outcome: Validated — invariantVerifier.assertFraudEvaluatedBeforeProviderCall()*
- [x] **FLOWS-FRAUD-03**: Allowed payment passes fraud check and proceeds to provider
  - *Outcome: Validated — allowed path in Phase 22-01*

### FLOWS-RECON: Reconciliation Flow Tests

- [x] **FLOWS-RECON-01**: Reconciliation correctly identifies matched provider entries
  - *Outcome: Validated — DailyReconciliationE2ETest matched case*
- [x] **FLOWS-RECON-02**: Reconciliation detects missing entries (transaction in DB but not in provider report)
  - *Outcome: Validated — MISSING_IN_PROVIDER sentinel via ProviderResult(null, null, false, null, null)*
- [x] **FLOWS-RECON-03**: Reconciliation detects mismatched entries (amount/status discrepancy)
  - *Outcome: Validated — DailyReconciliationE2ETest mismatched case*
- [x] **FLOWS-RECON-04**: Reconciliation handles WAT timestamp offset correctly for Orange entries
  - *Outcome: Validated — WAT-offset case in DailyReconciliationE2ETest*

### FLOWS-ADMIN: Admin Flow Tests

- [x] **FLOWS-ADMIN-01**: Admin transaction search returns results scoped to caller's tenant only
  - *Outcome: Validated — TransactionInvestigationE2ETest; tenantId param is Long DB PK*

### INV: Domain Invariant Tests

- [x] **INV-01-TEST**: Hash chain integrity holds for any sequence of payment events
  - *Outcome: Validated — HashChainIntegrityTest*
- [x] **INV-02-TEST**: Ledger double-entry invariant holds for every completed payment
  - *Outcome: Validated — LedgerDoubleEntryTest*
- [x] **INV-03-TEST**: Idempotency prevents double-charge on duplicate requests
  - *Outcome: Validated — IdempotencyNoDoubleChargeTest*
- [x] **INV-04-TEST**: Tenant isolation prevents cross-tenant data access
  - *Outcome: Validated — TenantIsolationTest*
- [x] **INV-05-TEST**: All illegal state machine transitions throw without DB mutation
  - *Outcome: Validated — StateMachineLegalTransitionsTest; 32 illegal transition cases*
- [x] **INV-06-TEST**: Webhook double-check always re-queries provider before state change
  - *Outcome: Validated — WebhookDoubleCheckTest*
- [x] **INV-07-TEST**: Fraud evaluation always occurs before provider HTTP call
  - *Outcome: Validated — FraudBeforeProviderCallTest*
- [x] **INV-08-TEST**: SSRF guard prevents callback URL from pointing to internal addresses
  - *Outcome: Validated — CallbackUrlSsrfGuardTest*
- [x] **INV-09-TEST**: Payment initiation record exists in DB before provider HTTP call is made
  - *Outcome: Validated — InitBeforeProviderCallTest (WireMock RequestListener in try-finally)*
- [x] **INV-10-TEST**: Orange WAT timestamp offset is computed correctly
  - *Outcome: Validated — OrangeTimestampWatTest (plain JUnit 5 unit test; no Spring context)*

### CONC: Concurrency Tests

- [x] **CONC-01**: Concurrent idempotency race (20 threads) produces exactly 1 payment row and 1 provider call
  - *Outcome: Validated — ConcurrentIdempotencyRaceTest*
- [x] **CONC-02**: Webhook/polling race produces exactly 1 SUCCESS row and at least 1 outbound delivery
  - *Outcome: Validated — WebhookPollingRaceTest; event count >= 1 (Hibernate L1 cache can produce 2 PROVIDER_SUCCESS events; financial invariants correct)*
- [x] **CONC-03**: Velocity counter flood does not corrupt per-MSISDN counters
  - *Outcome: Validated — VelocityCounterFloodTest*
- [x] **CONC-04**: API key rotation grace period holds under concurrent rotation requests
  - *Outcome: Validated — ApiKeyRotationGracePeriodTest*

### SM: State Machine Tests

- [x] **SM-01**: All legal MTN payment lifecycle transitions succeed without exception
  - *Outcome: Validated — SM parameterized test MTN path matrix*
- [x] **SM-02**: All legal Orange payment lifecycle transitions succeed without exception
  - *Outcome: Validated — SM parameterized test Orange path matrix*
- [x] **SM-03**: All 32 illegal state transitions throw without DB mutation (MTN)
  - *Outcome: Validated — StateMachineLegalTransitionsTest @MethodSource*
- [x] **SM-04**: All 32 illegal state transitions throw without DB mutation (Orange)
  - *Outcome: Validated — StateMachineLegalTransitionsTest @MethodSource*

### TXN: Transaction Boundary Tests

- [x] **TXN-01**: Transaction rolled back on provider call failure leaves no orphan row
  - *Outcome: Validated — TXN boundary tests Phase 23-03*
- [x] **TXN-02**: Idempotency key reservation scoped correctly to tenant
  - *Outcome: Validated — TXN-02 queries main.idempotency_key by tenant_id*
- [x] **TXN-03**: Event log append is atomic with state transition
  - *Outcome: Validated — TXN boundary tests Phase 23-03*
- [x] **TXN-04**: Idempotency key joins transaction table correctly
  - *Outcome: Validated — TXN-04 joins main.idempotency_key*

### MUT: Mutation Tests

- [x] **MUT-01**: PITest kills all mutations in pure domain classes (OrangeTimeUtil, TransactionStatus, PaymentEventLog)
  - *Outcome: Validated — Phase 23-03; pitest-junit5-plugin 1.2.2; mutationThreshold=90*
- [x] **MUT-02**: PITest kills all mutations across all 6 critical domain classes
  - *Outcome: Validated — Phase 23-05 gap closure; targetClasses expanded; domain unit tests use real production classes*

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| INFRA-01 – INFRA-09 | Phase 18 | ✅ Complete |
| VERIF-01 – VERIF-10 | Phase 19 | ✅ Complete |
| BUILD-01 – BUILD-08 | Phase 19 | ✅ Complete |
| FLOWS-PAY-01 – FLOWS-PAY-07 | Phase 20 | ✅ Complete |
| FLOWS-HOOK-01 – FLOWS-HOOK-06 | Phase 21 | ✅ Complete |
| FLOWS-FRAUD-01 – FLOWS-FRAUD-03 | Phase 22 | ✅ Complete |
| FLOWS-RECON-01 – FLOWS-RECON-04 | Phase 22 | ✅ Complete |
| FLOWS-ADMIN-01 | Phase 22 | ✅ Complete |
| INV-01-TEST – INV-10-TEST | Phase 23 | ✅ Complete |
| CONC-01 – CONC-04 | Phase 23 | ✅ Complete |
| SM-01 – SM-04 | Phase 23 | ✅ Complete |
| TXN-01 – TXN-04 | Phase 23 | ✅ Complete |
| MUT-01 – MUT-02 | Phase 23 | ✅ Complete |

**Coverage:** 74/74 v1 requirements shipped ✓

**Notes:**
- VERIF-10 (QueryCountVerifier) and BUILD-02/05/06/07 built but not consumed in Phase 20-23; available for future regression detection (tracked in v3-INTEGRATION-CHECK.md)

---
*Archived: 2026-03-28 after v3 milestone completion*
