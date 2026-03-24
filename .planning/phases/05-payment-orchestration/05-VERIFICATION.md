---
phase: 05-payment-orchestration
verified: 2026-03-24T00:00:00Z
status: passed
score: 5/5 must-haves verified
---

# Phase 5 Verification: Payment Orchestration

## Verdict: PASSED

All 5 must-haves verified against codebase. 7 integration tests passing.

---

## Must-Have Verification

### SC-1: POST /v1/payments routes to correct provider by MSISDN prefix
**Status: VERIFIED**

`MsisdnRouter.resolve()` strips `+237`/`237` country code then:
- `startsWith("65") || startsWith("69")` → `MobilePaymentProvider.ORANGE`
- `startsWith("6")` → `MobilePaymentProvider.MTN`
- else → `UnknownMsisdnPrefixException` → 422 UNKNOWN_MSISDN_PREFIX

`PaymentOrchestratorIT` tests 1 (Orange 65X → 202) and 2 (MTN 67X → 202) and 3 (90X → 422) all pass.

Evidence: `MsisdnRouter.java:40-45`, `PaymentOrchestratorIT.java:185-253`

---

### SC-2: No database connections held during provider HTTP calls
**Status: VERIFIED**

`PaymentOrchestrator.initiate()` has NO `@Transactional` annotation. DB operations use
`TransactionTemplate` for discrete atomic boundaries:
1. `idempotencyService.checkAndReserve()` — own transaction
2. `transactionService.initiate()` — own `@Transactional` method
3. `transactionTemplate.execute()` after provider returns — new transaction for state transitions

Provider HTTP is called between (2) and (3), with no open DB connection.

Evidence: `PaymentOrchestrator.java:40-43` (javadoc), no `@Transactional` on class or `initiate()` method

---

### SC-3: Circuit breakers trip on high error rate; subsequent calls fail fast with clear error
**Status: VERIFIED**

`@CircuitBreaker(name = "orange")` on `OrangeMoneyPort.initiateMerchantPayment()` and
`@CircuitBreaker(name = "mtn")` on `MtnMoMoPort.initiateMerchantPayment()`.

`PaymentOrchestrator` catches `CallNotPermittedException` before `HttpClientException` (correct ordering).
`PaymentResource` maps `PROVIDER_UNAVAILABLE` → HTTP 503 SERVICE_UNAVAILABLE.

`PaymentOrchestratorIT.circuit_breaker_trips_after_repeated_failures_returns_503` (Test 7) verifies:
- 10 consecutive MTN failures drive `failureRateThreshold` above 50%
- Circuit transitions to OPEN state (confirmed via `circuitBreakerRegistry`)
- 11th request returns 503 PROVIDER_UNAVAILABLE without calling WireMock

Evidence: `PaymentOrchestrator.java:168-189`, `PaymentResource.java:71-79`, `PaymentOrchestratorIT.java:327-370`

---

### SC-4: Quartz polls pending transactions when no webhook arrives
**Status: VERIFIED**

`OrangeStatusPollerJob` and `MtnStatusPollerJob` (built in Phases 3 and 4) query:
`TransactionRepository.findByTxStatusAndProviderAndLastModifiedDateBefore(PROCESSING, provider, cutoff)`

These jobs automatically pick up PROCESSING transactions regardless of who created them.
`PaymentOrchestrator` sets `txStatus=PROCESSING` and `provider` column on every successful dispatch,
so pollers cover Phase 5-created transactions transparently.

Both jobs extend `QuartzJobBean`, are `@Component`-annotated, and have Quartz schedule entries
in `application.yaml` (`orange.poller` and `mtn.poller` sections with `interval-seconds`).

Evidence: Plan 05-01 Task 3 verification — all grep checks passed. `OrangeStatusPollerJob.java` and
`MtnStatusPollerJob.java` reference `TransactionStatus.PROCESSING` in their repository query.

---

### SC-5: Provider error codes normalized to standardized Payam vocabulary
**Status: VERIFIED**

`OrchestratorError` enum (implements `ErrorCode`) provides 5 standardized codes:
- `PROVIDER_UNAVAILABLE` — circuit open (`CallNotPermittedException`)
- `SUBSCRIBER_INACTIVE` — `SubscriberInactiveException` / `MtnAccountInactiveException`
- `PROVIDER_ERROR` — `HttpClientException` (any 4xx/5xx from provider)
- `UNKNOWN_MSISDN_PREFIX` — unrecognized MSISDN prefix
- `PAYMENT_ALREADY_PROCESSING` — duplicate in-flight idempotency key (RESERVED sentinel)

`PaymentResource` maps these to HTTP status codes: 202, 503, 422, 422, 502.

`PaymentOrchestratorIT` tests 3 (UNKNOWN_MSISDN_PREFIX), 6 (SUBSCRIBER_INACTIVE), 7 (PROVIDER_UNAVAILABLE)
assert correct `errorCode` field values in response body.

Evidence: `OrchestratorError.java`, `PaymentResource.java:71-79`, `PaymentOrchestratorIT.java:240-330`

---

## Integration Test Results

`PaymentOrchestratorIT` — 7 tests, all passing:

| Test | Scenario | Result |
|------|----------|--------|
| 1 | Orange MSISDN (65X) → 202 PROCESSING | PASS |
| 2 | MTN MSISDN (67X) → 202 PROCESSING | PASS |
| 3 | Unknown prefix (90X) → 422 UNKNOWN_MSISDN_PREFIX | PASS |
| 4 | Duplicate idempotency key → 202 (no second provider call) | PASS |
| 5 | Missing X-Api-Key → 401 | PASS |
| 6 | Inactive subscriber (MTN 404) → 422 SUBSCRIBER_INACTIVE | PASS |
| 7 (SC-3) | 10 MTN failures → circuit OPEN → 503 PROVIDER_UNAVAILABLE | PASS |

---

## Deviations

**None.** All plan must_haves implemented exactly as specified.

Two bugs were found and auto-fixed during execution (Rule 1 / Rule 3):
1. `applyFailed()` jsonb quoting — bare string rejected by PostgreSQL; wrapped in JSON quotes
2. `TestRestTemplate` (Apache HTTP Client 5) auto-retries 503 — fixed with `SimpleClientHttpRequestFactory` (`noRetryRestTemplate`) for circuit breaker test

Both fixes are in committed code and do not affect must_have outcomes.
