---
phase: 20-payment-flow-tests
plan: 02
subsystem: testing
tags: [e2e, idempotency, fraud, circuit-breaker, resilience4j, redis, tenant-isolation, wiremock]

# Dependency graph
requires:
  - phase: 19-verifiers-builders
    provides: InvariantVerifier, TenantIsolationVerifier, CacheVerifier, DeterministicUuidFactory, PaymentRequestBuilder
  - phase: 18-test-infrastructure
    provides: AbstractPayamE2ETest, AbstractFailureFlowTest base classes
  - phase: 20-01-payment-flow-tests
    provides: FLOWS-PAY-01 through FLOWS-PAY-04 test patterns established
provides:
  - FLOWS-PAY-05: PaymentIdempotencyE2ETest — three-round idempotency (new / duplicate / cross-tenant)
  - FLOWS-PAY-06: FraudBlockedPaymentE2ETest — fraud-blocked path with APP_VELOCITY threshold = 0
  - FLOWS-PAY-07: ProviderTimeoutCircuitBreakerE2ETest — circuit breaker forced OPEN, 503 returned
affects: [21-reconciliation-tests]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "noRetryRestTemplate pattern: RestTemplate(SimpleClientHttpRequestFactory) with no-op error handler to prevent Apache HC 503 retry masking circuit breaker state"
    - "FraudRuleCache.refreshRules() must be called after jdbcTemplate.update fraud_rule — DB update alone does not invalidate the in-memory cache"
    - "circuitBreakerRegistry.circuitBreaker(name).transitionToOpenState() for deterministic CB testing without needing to trip via failures"
    - "Three-round idempotency: same key + same tenant = same transactionId; same key + different tenant = new transactionId (TenantIsolationVerifier)"

key-files:
  created:
    - src/test/java/com/softropic/payam/e2e/payment/PaymentIdempotencyE2ETest.java
    - src/test/java/com/softropic/payam/e2e/payment/FraudBlockedPaymentE2ETest.java
    - src/test/java/com/softropic/payam/e2e/payment/ProviderTimeoutCircuitBreakerE2ETest.java
  modified: []

key-decisions:
  - "FraudBlockedPaymentE2ETest: fraud blocked before transaction row written — zero tx count assertable via idempotencyKey+tenantId query; no transactionId available from 422 body"
  - "ProviderTimeoutCircuitBreakerE2ETest: CB open before transaction row written — zero tx count or FAILED status; asserted unconditionally via idempotencyKey+tenantId"
  - "PaymentIdempotencyE2ETest extends AbstractPayamE2ETest directly (not template-method subclass) — three rounds as single @Test to share idempotency key across round 1→2 continuation"
  - "Cross-tenant round asserts TenantIsolationVerifier.assertNoDataLeaksToOtherTenant — confirms idempotency scope is per-tenant, not global"

patterns-established:
  - "noRetryRestTemplate: new RestTemplate(new SimpleClientHttpRequestFactory()) with DefaultResponseErrorHandler that never throws — required for any test asserting 503/4xx from circuit-open path"
  - "Fraud threshold injection: jdbcTemplate.update fraud_rule + fraudRuleCache.refreshRules() — always pair these two"
  - "Circuit breaker state reset: base class AbstractPayamE2ETest.baseSetUp() resets circuit breakers via circuitBreakerRegistry; tests that force OPEN state are isolated per test run"

# Metrics
duration: 45min
completed: 2026-03-27
---

# Phase 20 Plan 02: Payment Flow Tests (Idempotency + Failure Paths) Summary

**Three E2E tests covering three-round idempotency with tenant isolation, fraud-blocked path with zero provider calls, and circuit-breaker-forced-open 503 path using noRetryRestTemplate**

## Performance

- **Duration:** ~45 min
- **Started:** 2026-03-27T19:00:00Z
- **Completed:** 2026-03-27T19:45:00Z
- **Tasks:** 2 (task 1: fraud + circuit breaker, task 2: idempotency)
- **Files modified:** 3

## Accomplishments

- FLOWS-PAY-05: Three-round idempotency test validates same-tenant deduplication (transactionId preserved) and cross-tenant isolation (new transactionId, TenantIsolationVerifier passes)
- FLOWS-PAY-06: Fraud-blocked path verifies 422 response, zero WireMock requestToPay calls, zero transaction rows in DB
- FLOWS-PAY-07: Circuit breaker test verifies 503 response, CB remains OPEN post-test, no transaction row created before CB rejection

## Task Commits

1. **Task 1: FraudBlockedPaymentE2ETest and ProviderTimeoutCircuitBreakerE2ETest** - `fc5804d` (feat)
2. **Task 2: PaymentIdempotencyE2ETest** - `2608c18` (feat)

## Files Created/Modified

- `src/test/java/com/softropic/payam/e2e/payment/PaymentIdempotencyE2ETest.java` - FLOWS-PAY-05: standalone @Test with three sequential idempotency rounds including TenantIsolationVerifier
- `src/test/java/com/softropic/payam/e2e/payment/FraudBlockedPaymentE2ETest.java` - FLOWS-PAY-06: AbstractFailureFlowTest subclass; APP_VELOCITY threshold forced to 0 via jdbcTemplate + cache refresh
- `src/test/java/com/softropic/payam/e2e/payment/ProviderTimeoutCircuitBreakerE2ETest.java` - FLOWS-PAY-07: AbstractFailureFlowTest subclass; CB forced OPEN via registry; noRetryRestTemplate prevents Apache HC retry masking

## Decisions Made

- Used `SimpleClientHttpRequestFactory` for `noRetryRestTemplate` instead of the default `RestTemplate` — Apache HttpClient's retry behavior on 503 masks whether the circuit breaker is actually open
- Fraud test does NOT attempt `invariant.events().assertEventPresent(...)` — when fraud is evaluated before the transaction row is persisted, there is no transactionId and no event log row; 422 FRAUD_BLOCKED response itself is the evidence
- Circuit breaker test asserts zero transaction rows rather than FAILED status — confirmed by reading `PaymentOrchestrator` that the CB rejection precedes transaction DB write

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None - all three tests passed on first run.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- FLOWS-PAY-01 through FLOWS-PAY-07 all pass in the full 7-test suite run together
- Payment flow test coverage is complete for Phase 20 scope
- All major payment lifecycle paths verified: webhook success, polling fallback, payToken expiry, idempotency, fraud blocking, circuit breaking

---
*Phase: 20-payment-flow-tests*
*Completed: 2026-03-27*
