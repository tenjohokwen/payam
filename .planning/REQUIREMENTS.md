# Requirements: Payam E2E Test Suite

**Defined:** 2026-03-27
**Core Value:** Provably correct, fraud-resistant, tamper-evident payment processing verified end-to-end
**Source:** `requirements/e2e-test-standard.md`

## v1 Requirements

### INFRA: Test Infrastructure

- [x] **INFRA-01**: `AbstractPayamE2ETest` bootstraps Spring Boot with Testcontainers (PostgreSQL + Redis) and WireMock (MTN + Orange)
- [x] **INFRA-02**: `AbstractPaymentFlowTest` enforces `setupPreconditions / executeFlow / simulateProviderCallback / verifyFinalState` structure
- [x] **INFRA-03**: `AbstractWebhookFlowTest` adds inbound webhook dispatch + double-check verification steps
- [x] **INFRA-04**: `AbstractFailureFlowTest` provides fault-injection hook points
- [x] **INFRA-05**: `PostgresContainerConfig` and `RedisContainerConfig` use Testcontainers with real Flyway schema
- [x] **INFRA-06**: `WireMockConfig` stubs MTN MoMo and Orange Money endpoints
- [x] **INFRA-07**: `TestClockConfig` provides a fixed WAT (UTC+1) clock for deterministic Orange timestamp tests
- [x] **INFRA-08**: `E2ESecurityConfig` injects test API keys without real key generation overhead
- [x] **INFRA-09**: `TestDataCleaner` wipes all payment tables and Redis keys before each test

### VERIF: Verifier Components

- [ ] **VERIF-01**: `DatabaseVerifier` asserts payment row fields, `payment_events` row count, orphan detection, and no duplicate rows per `(tenantId, idempotencyKey)`
- [ ] **VERIF-02**: `HashChainVerifier` validates `SHA-256(prev.hashValue + event.data)` for every event in sequence, genesis seed, and no out-of-order insertion
- [ ] **VERIF-03**: `InvariantVerifier` exposes `assertLedgerBalanced`, `assertNoDoubleCharge`, `assertTenantIsolation`, `assertLegalStateTransition`, `assertWebhookDoubleCheckFired`, `assertFraudEvaluatedBeforeProviderCall`, and `assertAll`
- [ ] **VERIF-04**: `EventVerifier` asserts Spring Modulith outbox event publication count and sequence per `transactionId`
- [ ] **VERIF-05**: `LedgerVerifier` asserts debit = credit balance, entry count = 2 for SUCCESS, and no entry for FAILED/rolled-back flows
- [ ] **VERIF-06**: `ProviderCallVerifier` asserts WireMock call counts, callback URL is Payam-owned (SSRF guard), and no provider call on fraud-blocked flows
- [ ] **VERIF-07**: `WebhookDeliveryVerifier` asserts outbound webhook delivered, HMAC-SHA256 header present, payload matches transaction state, and retry scheduled on 5xx
- [ ] **VERIF-08**: `TenantIsolationVerifier` asserts zero rows returned for wrong `tenantId` across all tables and Redis namespaces
- [ ] **VERIF-09**: `CacheVerifier` asserts idempotency key presence/absence, velocity counter values, MTN OAuth2 token cache, and no cross-context cache pollution
- [ ] **VERIF-10**: `QueryCountVerifier` detects N+1 query regressions in payment listing and admin dashboard endpoints

### BUILD: Test Data Builders

- [ ] **BUILD-01**: `TenantBuilder` creates active tenant rows with configurable callback URL and fee rules; `create(jdbcTemplate)` is the explicit commit
- [ ] **BUILD-02**: `ApiKeyBuilder` creates production or sandbox API keys scoped to a tenant with configurable permissions
- [ ] **BUILD-03**: `PaymentRequestBuilder` builds `PaymentInitiateRequest` for MTN or Orange with MSISDN, amount, currency, idempotency key, and fraud signals
- [ ] **BUILD-04**: `MtnWebhookPayloadBuilder` builds MTN PUT callback payloads with configurable outcome and financial transaction ID
- [ ] **BUILD-05**: `OrangeWebhookPayloadBuilder` builds Orange POST notifUrl payloads with WAT `createtime` (no timezone — must be treated as UTC+1)
- [ ] **BUILD-06**: `FraudSignalBuilder` builds fraud context with source IP, device fingerprint, and pre-seeded velocity counter override
- [ ] **BUILD-07**: `ReconciliationReportBuilder` builds provider reports with matched, missing, and mismatched transaction entries
- [ ] **BUILD-08**: All builders use deterministic fixed UUIDs seeded per test class; no shared mutable state between tests

### FLOWS-PAY: Payment Flow Tests

- [x] **FLOWS-PAY-01**: `MtnPaymentInitiationE2ETest` — MTN happy path: `INITIATED → PROCESSING → SUCCESS` via webhook; all verifiers asserted
- [x] **FLOWS-PAY-02**: `OrangePaymentInitiationE2ETest` — Orange happy path: `INITIATED → AUTH_PENDING → PROCESSING → SUCCESS` via webhook; WAT timestamp verified
- [x] **FLOWS-PAY-03**: MTN polling fallback — payment reaches SUCCESS via Quartz polling when no webhook arrives
- [x] **FLOWS-PAY-04**: Orange payToken expiry — `/pay` returns 401 after TTL; no partial state; idempotency key NOT consumed
- [x] **FLOWS-PAY-05**: `PaymentIdempotencyE2ETest` — all three rounds: new creation, duplicate returns same response, cross-tenant creates separate transaction
- [x] **FLOWS-PAY-06**: Fraud-blocked path — payment with score ≥ threshold → `FAILED` with `FRAUD_BLOCKED`; zero provider calls; zero ledger entries
- [x] **FLOWS-PAY-07**: Provider timeout path — Resilience4j circuit breaker fires; transaction → `FAILED`; circuit OPEN; error propagated to caller

### FLOWS-HOOK: Webhook Flow Tests

- [ ] **FLOWS-HOOK-01**: `MtnWebhookDoubleCheckE2ETest` — MTN PUT webhook received; double-check re-queries provider; transaction transitions to SUCCESS
- [ ] **FLOWS-HOOK-02**: `OrangeWebhookDoubleCheckE2ETest` — Orange POST webhook received; double-check fires; state transition verified
- [ ] **FLOWS-HOOK-03**: Webhook replay protection — duplicate webhook delivery rejected; transaction state unchanged; no duplicate outbox event
- [ ] **FLOWS-HOOK-04**: `OutboundWebhookDeliveryE2ETest` — outbound webhook delivered to tenant callback URL with HMAC-SHA256 signature
- [ ] **FLOWS-HOOK-05**: Outbound webhook retry — 5xx from tenant triggers retry scheduling with exponential backoff (minimum 3 attempts)
- [ ] **FLOWS-HOOK-06**: MTN callback via PUT (not POST) — accepted and processed, not 405 Method Not Allowed

### FLOWS-FRAUD: Fraud Engine Tests

- [ ] **FLOWS-FRAUD-01**: `FraudVelocityBlockE2ETest` — velocity limit exceeded blocks payment before provider call
- [ ] **FLOWS-FRAUD-02**: Allowed path — payment below fraud threshold reaches provider; fraud evaluation timestamp recorded before provider call timestamp
- [ ] **FLOWS-FRAUD-03**: `invariantVerifier.assertFraudEvaluatedBeforeProviderCall` passes on every flow test

### FLOWS-RECON: Reconciliation Tests

- [ ] **FLOWS-RECON-01**: `DailyReconciliationE2ETest` — matched transactions: Payam ledger and provider report agree; no discrepancy flagged
- [ ] **FLOWS-RECON-02**: Missing transaction — in Payam ledger but absent from provider report; flagged as discrepancy
- [ ] **FLOWS-RECON-03**: Mismatched transaction — Payam shows SUCCESS, provider shows FAILED; flagged as discrepancy
- [ ] **FLOWS-RECON-04**: Orange WAT timestamp — `createtime` parsed as UTC+1, not UTC; reconciliation entries correct

### FLOWS-ADMIN: Admin Flow Tests

- [ ] **FLOWS-ADMIN-01**: `TransactionInvestigationE2ETest` — search by `transactionId`, phone number, and `traceId` returns correct results scoped to caller's tenant

### INV: Domain Invariant Tests

- [ ] **INV-01-TEST**: `HashChainIntegrityTest` — every event's `hashValue = SHA256(prev.hashValue + eventData)`; genesis event uses "GENESIS" seed
- [ ] **INV-02-TEST**: `LedgerDoubleEntryTest` — SUCCESS creates balanced debit/credit; FAILED creates no ledger entry; reversed payment remains balanced
- [ ] **INV-03-TEST**: `IdempotencyNoDoubleChargeTest` — `(tenantId, idempotencyKey)` maps to exactly one payment row and one provider call
- [ ] **INV-04-TEST**: `TenantIsolationTest` — no API key can query data from a different tenant across all payment tables
- [ ] **INV-05-TEST**: `StateMachineLegalTransitionsTest` — full transition matrix tested; all illegal cells throw `IllegalStateTransitionException` and leave DB unchanged
- [ ] **INV-06-TEST**: `WebhookDoubleCheckTest` — provider status re-queried at least once before any state change for every inbound webhook
- [ ] **INV-07-TEST**: `FraudBeforeProviderCallTest` — fraud evaluation timestamp < provider HTTP call timestamp on every initiation
- [ ] **INV-08-TEST**: `CallbackUrlSsrfGuardTest` — outbound provider HTTP call always uses Payam-owned callback URL, never tenant-supplied URL
- [ ] **INV-09-TEST**: `InitBeforeProviderCallTest` — INIT row committed to PostgreSQL before WireMock receives the provider HTTP call
- [ ] **INV-10-TEST**: `OrangeTimestampWatTest` — Orange `createtime` parsed with +01:00 offset; UTC parse produces wrong result (mutation caught)

### CONC: Concurrency Tests

- [ ] **CONC-01**: `ConcurrentIdempotencyRaceTest` — 20 threads, same `(tenantId, idempotencyKey)`, CyclicBarrier release: exactly 1 payment row, 1 provider call, all 20 responses return same `transactionId`
- [ ] **CONC-02**: `WebhookPollingRaceTest` — Thread A: MTN PUT webhook; Thread B: Quartz polling job; both attempt `PROCESSING→SUCCESS`: exactly 1 SUCCESS row, 1 SUCCESS event, 2 ledger entries, 1 outbound webhook delivery
- [ ] **CONC-03**: `VelocityCounterFloodTest` — 100 threads, same source IP: Redis velocity counter = 100; threads exceeding threshold blocked; exactly N (≤ threshold) provider calls made
- [ ] **CONC-04**: `ApiKeyRotationGracePeriodTest` — Thread A uses old key, Thread B uses new key simultaneously during rotation window: both succeed; both attributed to correct tenant

### SM: State Machine Tests

- [ ] **SM-01**: All legal transitions drive payment through expected states via `StateMachineDriver` with hash chain growth asserted at each step
- [ ] **SM-02**: All illegal transitions throw `IllegalStateTransitionException`, leave payment status unchanged, and append no new event
- [ ] **SM-03**: Parameterized test covers full MTN path matrix: success, fraud-blocked, provider timeout, webhook-failed, polling-fallback-success
- [ ] **SM-04**: Parameterized test covers full Orange path matrix: success, payToken expiry, init failure, polling fallback

### TXN: Transaction Boundary Tests

- [ ] **TXN-01**: WireMock transformer confirms INIT row exists in DB at the exact moment the provider HTTP call arrives
- [ ] **TXN-02**: Exception after INIT commit does NOT roll back the INIT row; no provider call made; no ledger entry created
- [ ] **TXN-03**: Spring Modulith event fires after payment row is committed (`AFTER_COMMIT`), not during the transaction
- [ ] **TXN-04**: Redis NX+EX atomic reservation — concurrent requests cannot both see "key absent"

### MUT: Mutation Testing

- [ ] **MUT-01**: PITest configured in `pom.xml` targeting `payment.service`, `payment.domain`, `payment.infrastructure.fraud`, `payment.infrastructure.webhook`, `payment.infrastructure.reconciliation` with `mutationThreshold=90` and `STRONGER` mutators
- [ ] **MUT-02**: Six critical mutations killed: INITIATED→SUCCESS guard, ledger `==` balance check, idempotency tenant scope, fraud blocking `>=` threshold, hash chain `previousHash` inclusion, Orange `+01:00` timestamp offset

## v2 Requirements

*(None defined — all items in the standard are v1)*

## Out of Scope

| Feature | Reason |
|---------|--------|
| Integration tests (unit + service layer) | Covered by existing test suite; this milestone is E2E only |
| Performance / load testing | Not required by standard; separate concern |
| Contract testing (Pact) | Not referenced in standard; deferred |
| Browser / UI E2E (Selenium/Playwright) | Standard is API-level only; admin UI tested via REST endpoints |

## Traceability

Which phases cover which requirements. Updated by create-roadmap.

| Requirement | Phase | Status |
|-------------|-------|--------|
| INFRA-01–09 | Phase 18 | Complete |
| VERIF-01–10 | Phase 19 | Complete |
| BUILD-01–08 | Phase 19 | Complete |
| FLOWS-PAY-01–07 | Phase 20 | Complete |
| FLOWS-HOOK-01–06 | Phase 21 | Pending |
| FLOWS-FRAUD-01–03 | Phase 22 | Pending |
| FLOWS-RECON-01–04 | Phase 22 | Pending |
| FLOWS-ADMIN-01 | Phase 22 | Pending |
| INV-01-TEST–INV-10-TEST | Phase 23 | Pending |
| CONC-01–04 | Phase 23 | Pending |
| SM-01–04 | Phase 23 | Pending |
| TXN-01–04 | Phase 23 | Pending |
| MUT-01–02 | Phase 23 | Pending |

**Coverage:**
- v1 requirements: 64 total
- Mapped to phases: 64
- Unmapped: 0 ✓

---
*Requirements defined: 2026-03-27*
*Source: requirements/e2e-test-standard.md*
