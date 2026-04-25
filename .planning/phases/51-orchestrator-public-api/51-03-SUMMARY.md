---
phase: 51-orchestrator-public-api
plan: "03"
subsystem: disbursement
tags: [orchestrator, tdd, integration-test, wiremock, step-up, sec-04]
dependency-graph:
  requires: ["51-01", "51-02"]
  provides: ["DisbursementOrchestrator.initiate", "DisbursementOrchestrator.confirm", "DisbursementService", "DisbursementRepository (extended)"]
  affects: ["51-04"]
tech-stack:
  added: []
  patterns:
    - "NOT @Transactional orchestrator using TransactionTemplate per discrete write (mirrors PaymentOrchestrator)"
    - "SEC-04 step-up gate: amounts > 500,000 XAF -> PENDING_CONFIRMATION; wallet reserved, provider NOT called"
    - "releaseAndFail() helper: release wallet + transition FAILED in separate TransactionTemplate blocks"
    - "WireMock named server portProperties injection to avoid wiremock.server.port ambiguity across multiple servers"
key-files:
  created:
    - src/main/java/com/softropic/payam/disbursement/service/DisbursementService.java
    - src/main/java/com/softropic/payam/disbursement/service/DisbursementOrchestrator.java
    - src/test/java/com/softropic/payam/disbursement/service/DisbursementOrchestratorTest.java
    - src/test/java/com/softropic/payam/disbursement/service/DisbursementOrchestratorIT.java
  modified:
    - src/main/java/com/softropic/payam/disbursement/repo/DisbursementRepository.java
decisions:
  - "DisbursementOrchestrator is NOT @Transactional; uses TransactionTemplate.execute() per write so the provider HTTP call runs outside any DB transaction (P1.1 pattern)"
  - "SEC-04 step-up: wallet IS reserved before PENDING_CONFIRMATION so funds are locked during human confirmation window"
  - "releaseAndFail() ONLY called on FAILED transitions, never EXPIRED (per BAL-03 — EXPIRED holds reservation pending ops resolution)"
  - "WireMock named servers need explicit portProperties on @ConfigureWireMock to avoid wiremock.server.port collision"
metrics:
  duration: "32 minutes"
  tasks_completed: 3
  files_created: 4
  files_modified: 1
  tests_added: 21
  completed_date: "2026-04-25"
---

# Phase 51 Plan 03: DisbursementOrchestrator + DisbursementService Summary

DisbursementOrchestrator with 11-step initiate flow, confirm flow, SEC-04 step-up gate, and 21 passing tests (15 unit + 6 IT against real WireMock/Postgres/Redis).

## Tasks Completed

| Task | Name                                              | Commit  | Result  |
| ---- | ------------------------------------------------- | ------- | ------- |
| 1    | DisbursementRepository extensions + Service       | 372637c | DONE    |
| 2    | DisbursementOrchestrator + 15 unit tests (TDD)    | 8017755 (RED), bec254f (GREEN) | DONE |
| 3    | 6 IT tests (WireMock + Postgres + Redis)          | ab33706 | DONE    |

## What Was Built

### DisbursementRepository (extended)

Four new query methods added to the existing repository:
- `findByDisbursementIdForUpdate` — PESSIMISTIC_WRITE lock query for state transitions
- `findByDisbursementStatusAndCreatedDateBefore` — used by Plan 04's expiry job (SEC-04)
- `findByTenantIdAndDisbursementId` — tenant-scoped lookup enforcing tenant isolation
- `findForTenant` — pageable filtered query for the REST GET list endpoint (Plan 04)

### DisbursementService (new, 103 lines)

Thin DB-write helper with two `@Transactional` methods:
- `create()` — builds and persists the initial Disbursement row with UUID disbursementId
- `transitionToFailed()` — acquires PESSIMISTIC_WRITE lock and applies FAILED transition

### DisbursementOrchestrator (new, 380 lines)

Core orchestration class, NOT @Transactional. Uses `TransactionTemplate.execute()` for each discrete write.

`initiate(tenantId, request)` sequence:
1. Idempotency check (DisbursementIdempotencyService) — return cached response if found
2. MSISDN routing (MsisdnRouter) — resolve MTN or ORANGE
3. Velocity check (DisbursementVelocityService.checkTenantVelocity)
4. Daily MSISDN limit (DisbursementVelocityService.checkMsisdnDailyLimit)
5. Fraud evaluation (DisbursementFraudEvaluationService)
6. Fee evaluation (FeeEvaluationService)
7. Balance reservation (WalletBalanceService.checkAndReserve)
8. Create Disbursement row (DisbursementService.create)
9. Step-up gate — if amount > 500,000 XAF → return PENDING_CONFIRMATION immediately
10. `dispatchToProvider()` shared tail — validateSubscriber + provider call + transition to PROCESSING
11. Store idempotency response

`confirm(tenantId, disbursementId)`:
- Loads disbursement under PESSIMISTIC_WRITE
- Asserts status == PENDING_CONFIRMATION (else returns INVALID_STATE)
- Reconstructs request context and runs `dispatchToProvider()` tail

`releaseAndFail(tenantId, totalAmount, disbursementId)`:
- Calls WalletBalanceService.release() in one TransactionTemplate block
- Calls DisbursementService.transitionToFailed() in another TransactionTemplate block
- Only invoked on FAILED outcomes, never EXPIRED

### DisbursementOrchestratorTest (15 unit tests)

TDD RED-GREEN cycle. Tests cover:
- MTN happy path — initiate returns PROCESSING, idempotency stored
- Orange happy path — routes to OrangeMoneyPort
- Idempotency cached — returns cached DisbursementResponse without processing
- Idempotency in-flight (RESERVED sentinel) — returns PROCESSING_DUPLICATE
- Unknown MSISDN prefix — returns UNKNOWN_MSISDN_PREFIX, wallet never touched
- Velocity limit exceeded — returns VELOCITY_LIMIT_EXCEEDED, wallet never touched
- Daily MSISDN limit exceeded — returns DAILY_MSISDN_LIMIT_EXCEEDED, wallet never touched
- Fraud blocked — returns FRAUD_BLOCKED, wallet never touched
- Insufficient balance — returns INSUFFICIENT_BALANCE, provider never called
- Recipient inactive (MtnMoMo) — releaseAndFail called, returns RECIPIENT_NOT_FOUND
- Provider throws — releaseAndFail called, returns PROVIDER_ERROR
- Step-up gate — returns PENDING_CONFIRMATION, provider never called, wallet IS reserved
- Confirm legal state — PENDING_CONFIRMATION dispatches to provider → PROCESSING
- Confirm illegal state (PROCESSING) — returns INVALID_STATE
- Confirm not found — returns DISBURSEMENT_NOT_FOUND

### DisbursementOrchestratorIT (6 IT tests)

Integration tests against real WireMock + Postgres (Testcontainers) + Redis:
- MTN happy path: disbursement_status = PROCESSING, wallet balance reduced
- Orange happy path: disbursement_status = PROCESSING
- Step-up: 600,000 XAF → PENDING_CONFIRMATION, no MTN transfer call, wallet reduced
- Confirm dispatch: PENDING_CONFIRMATION → PROCESSING via MTN transfer
- Confirm invalid state: PROCESSING disbursement returns INVALID_STATE on confirm
- Insufficient balance: 2,000,000 XAF vs 1,000,000 wallet → INSUFFICIENT_BALANCE, no provider call

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] WireMock named server port injection conflict**
- **Found during:** Task 3 IT test run
- **Issue:** Two `@ConfigureWireMock` servers both default to writing `wiremock.server.port`. The second server (Orange) overwrites the first (MTN), so MTN token URL `${wiremock.server.port}` resolved to the Orange port. Token requests returned 404 from Orange WireMock, causing MtnMoMoPort to throw HttpClientException, triggering releaseAndFail() — disbursements landed in FAILED instead of PROCESSING.
- **Fix:** Added `portProperties = {"wiremock.mtn.port"}` to the MTN `@ConfigureWireMock` and `portProperties = {"wiremock.orange.port"}` to the Orange `@ConfigureWireMock`. Updated `@TestPropertySource` to use `${wiremock.mtn.port}` for MTN token URLs.
- **Files modified:** `DisbursementOrchestratorIT.java`
- **Commit:** ab33706

**2. [Rule 1 - Bug] IT wallet seed insufficient for step-up tests**
- **Found during:** Task 3 — step-up tests failing with FAILED after the port fix resolved MTN tests
- **Issue:** Wallet seeded with 100,000 XAF but step-up tests use 600,000 XAF amount. WalletBalanceService.checkAndReserve() returned INSUFFICIENT_BALANCE, triggering FAILED instead of PENDING_CONFIRMATION.
- **Fix:** Increased wallet seed from 100,000 to 1,000,000 XAF. Updated insufficient_balance test to use 2,000,000 XAF so it still exceeds the wallet. Updated balance assertions to compare against 1,000,000.
- **Files modified:** `DisbursementOrchestratorIT.java`
- **Commit:** ab33706 (same commit, same task)

## Known Stubs

None — all data flows are wired to real repositories and provider ports. The orchestrator integrates all prior plan outputs (idempotency, velocity, fraud, fees, wallet, provider adapters).

## Verification

All 21 tests pass:
```
Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
  DisbursementOrchestratorTest: 15/15 passed
  DisbursementOrchestratorIT:    6/6  passed
```

Requirements fulfilled:
- DISB-01: Wallet balance reserved before disbursement, released on failure
- DISB-04: Disbursement orchestration sequence with idempotency, velocity, fraud, fee, balance, provider
- PROV-01: MTN disbursements call MtnMoMoPort.initiateDisbursement
- PROV-02: Orange disbursements call OrangeMoneyPort.initiateDisbursement
- PROV-03: Recipient validation via port.validateSubscriber before dispatch
- SEC-04: Step-up gate for amounts > 500,000 XAF → PENDING_CONFIRMATION without provider call

## Self-Check: PASSED
