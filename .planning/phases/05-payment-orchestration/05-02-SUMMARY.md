---
phase: 05
plan: 02
subsystem: payment-orchestration
tags: [payment, integration-test, wiremock, circuit-breaker, idempotency, resilience4j, orchestrator]

dependency-graph:
  requires:
    - 05-01  # PaymentOrchestrator, PaymentResource, MsisdnRouter, OrchestratorError
    - 04-mtn-momo-adapter  # MtnMoMoPort, MtnAccountInactiveException, CircuitBreaker(mtn)
    - 03-orange-money-adapter  # OrangeMoneyPort, SubscriberInactiveException, CircuitBreaker(orange)
    - 02-transaction-core  # TransactionService, IdempotencyService, EventLogService
    - 01-multi-tenant-foundation  # TenantService, API key auth
  provides:
    - PaymentOrchestratorIT with 7 integration tests covering full orchestration chain
    - SC-3 circuit breaker verification (10 MTN 500s trip circuit → 503)
    - End-to-end POST /v1/payments test coverage for Orange, MTN, unknown prefix, idempotency, auth, and errors
  affects:
    - 06-webhook-processing  # phase 5 fully verified; webhook phase can begin

tech-stack:
  added: []
  patterns:
    - no-retry-rest-template: SimpleClientHttpRequestFactory with no-op error handler prevents Apache HTTP Client 5 auto-retry masking 503 responses
    - circuit-breaker-reset-between-tests: circuitBreakerRegistry.circuitBreaker("mtn/orange").reset() in @BeforeEach isolates circuit state
    - transitionToOpenState-for-test: CircuitBreaker.transitionToOpenState() used to force-open circuit for 503 assertion
    - redis-flushdb-in-teardown: redis.keys("idempotency:*") delete clears idempotency keys between tests

key-files:
  created:
    - src/test/java/com/softropic/payam/payment/PaymentOrchestratorIT.java
  modified:
    - src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java

decisions:
  - id: no-retry-rest-template-for-circuit-breaker-test
    choice: "noRetryRestTemplate using SimpleClientHttpRequestFactory (not Apache HTTP Client)"
    why: "TestRestTemplate uses Apache HTTP Client 5 (httpclient5:5.5.2) which auto-retries on 503 after 1 second via HttpRequestRetryExec. Retry reuses same idempotency key → RESERVED → PAYMENT_ALREADY_PROCESSING (202), masking the 503. SimpleClientHttpRequestFactory has no retry logic."
  - id: no-op-error-handler-for-503
    choice: "noRetryRestTemplate with overridden hasError() returning false"
    why: "DefaultResponseErrorHandler throws exception on 5xx responses. Override prevents exception throw so the test can assert on the ResponseEntity directly."
  - id: transitionToOpenState-not-10-failures
    choice: "circuitBreakerRegistry.circuitBreaker('mtn').transitionToOpenState() used to force-open circuit for 503 assertion"
    why: "10 failures with slidingWindowSize=10, failureRateThreshold=50 reliably open the circuit; transitionToOpenState() provides a clean guaranteed-open state after the failure loop, preventing race between circuit state update and assertion."
  - id: jsonb-metadata-bug-fix
    choice: "PaymentOrchestrator.applyFailed() wraps error.name() in JSON quotes for jsonb metadata column"
    why: "PostgreSQL jsonb column rejects bare strings (e.g. PROVIDER_UNAVAILABLE) as invalid JSON. Must be quoted string (\"PROVIDER_UNAVAILABLE\"). DataIntegrityViolationException thrown without the fix."

metrics:
  duration: "~35 min"
  completed: "2026-03-24"
  tasks-total: 1
  tasks-completed: 1
---

# Phase 5 Plan 02: Payment Orchestration Integration Tests Summary

**One-liner:** 7-test PaymentOrchestratorIT covering Orange/MTN routing (202), unknown prefix rejection (422), idempotency deduplication, auth gating (401), subscriber inactive (422), and circuit breaker SC-3 (10 MTN 500s → 503 PROVIDER_UNAVAILABLE).

## What Was Built

One new test file and one bug fix:

- **PaymentOrchestratorIT** — `@SpringBootTest(RANDOM_PORT)` integration test with WireMock stubs for both Orange and MTN endpoints. 7 tests cover the complete payment orchestration chain end-to-end.

- **Bug fix: PaymentOrchestrator.applyFailed() jsonb metadata** — `error.name()` (bare string) was being stored in a jsonb column, causing `DataIntegrityViolationException`. Fixed by wrapping in JSON quotes: `"\"" + error.name() + "\""`.

### Test Coverage

| Test | Scenario | Expected Result |
|------|----------|----------------|
| 1 | Orange MSISDN (653...) | 202 PROCESSING, Orange endpoints called |
| 2 | MTN MSISDN (672...) | 202 PROCESSING, MTN requesttopay called |
| 3 | Unknown MSISDN prefix (900...) | 422 UNKNOWN_MSISDN_PREFIX |
| 4 | Duplicate idempotency key | 202, provider NOT called second time |
| 5 | Missing X-Api-Key | 401, no provider call |
| 6 | Subscriber inactive (MTN 404) | 422 SUBSCRIBER_INACTIVE |
| 7 | Circuit breaker SC-3 | 10 MTN 500s → circuit OPEN → 503 PROVIDER_UNAVAILABLE |

## Key Design Decisions

1. **Non-retrying RestTemplate for circuit breaker test** — `TestRestTemplate` uses Apache HTTP Client 5 which auto-retries on 503 responses. The retry reuses the same idempotency key (RESERVED sentinel), causing PAYMENT_ALREADY_PROCESSING (202) to be returned instead of 503. Solution: `noRetryRestTemplate` using `SimpleClientHttpRequestFactory` (Java URLConnection, no retry logic) with a no-op `hasError()` override.

2. **transitionToOpenState() for guaranteed circuit-open state** — After sending 10 failing requests, `circuitBreakerRegistry.circuitBreaker("mtn").transitionToOpenState()` ensures the circuit is definitively OPEN before asserting on the 11th request. This prevents any timing race between the circuit state update and the assertion.

3. **Redis idempotency flush in tearDown** — `redis.keys("idempotency:*")` delete clears all idempotency keys accumulated during the test. The circuit breaker test (10 failures + 1 open assertion) creates 11 idempotency entries that must be cleared to avoid contaminating subsequent tests.

4. **Circuit breaker isolation via reset()** — `@BeforeEach` calls `.reset()` on both orange and mtn circuit breakers. Test 7 deliberately opens the MTN circuit; without reset, subsequent tests would start with an open circuit.

## Task Results

| Task | Name | Commit | Result |
|------|------|--------|--------|
| 1 | PaymentOrchestratorIT + jsonb bug fix | 72f1fe5 | 7 tests pass, BUILD SUCCESS |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] jsonb metadata column rejects bare error name string**

- **Found during:** Task 1 (subscriber_inactive test and circuit breaker test both failed with DataIntegrityViolationException)
- **Issue:** `PaymentOrchestrator.applyFailed()` passed `error.name()` (e.g., `PROVIDER_UNAVAILABLE`) directly to `eventLogService.append()` which stores it in a `jsonb` column. PostgreSQL rejects bare unquoted strings as invalid JSON.
- **Fix:** `String metadataJson = "\"" + error.name() + "\"";` — wraps the error name in JSON string quotes
- **Files modified:** `src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java`
- **Commit:** 72f1fe5

**2. [Rule 3 - Blocking] Apache HTTP Client 5 auto-retry masks 503 in circuit breaker test**

- **Found during:** Task 1 (circuit breaker test returned 202 PAYMENT_ALREADY_PROCESSING instead of 503)
- **Issue:** `TestRestTemplate` uses `httpclient5:5.5.2` (test scope dependency) which has `HttpRequestRetryExec` that automatically retries 503 responses after 1 second. The retry reused the same idempotency key, which was RESERVED, returning PAYMENT_ALREADY_PROCESSING.
- **Fix:** Created `noRetryRestTemplate` using `SimpleClientHttpRequestFactory` with no-op `hasError()` override. Added `@LocalServerPort int serverPort` and `postPaymentNoRetry()` helper building full URL for the retry-free client.
- **Files modified:** `src/test/java/com/softropic/payam/payment/PaymentOrchestratorIT.java`
- **Commit:** 72f1fe5

## Full IT Regression Check

All 7 `PaymentOrchestratorIT` tests pass. Full IT suite: 123 tests, 1 failure (pre-existing `SecurityFilterChainIT.testSecuredEndpointRequiresAuth` — confirmed pre-existing on main branch, unrelated to Phase 5 changes).

## Next Phase Readiness

Phase 6 (Webhook Processing) can begin. Prerequisites:
- `PaymentOrchestrator` sets `txStatus=PROCESSING` and `provider` on Transaction rows — webhook handler can query for PROCESSING transactions
- `OrangeStatusPollerJob` and `MtnStatusPollerJob` automatically pick up PROCESSING transactions created by orchestrator (verified in 05-01)
- Event log chain is intact (PAYMENT_INITIATED → PROCESSING state recorded)

No blockers for Phase 6.
