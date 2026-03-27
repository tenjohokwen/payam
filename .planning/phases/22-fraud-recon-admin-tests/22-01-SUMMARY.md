---
phase: 22-fraud-recon-admin-tests
plan: 01
subsystem: testing
tags: [junit5, spring-boot-test, wiremock, fraud, velocity-check, e2e, redis]

# Dependency graph
requires:
  - phase: 18-test-infrastructure
    provides: AbstractFailureFlowTest base class with four-phase failure injection template
  - phase: 19-verifiers-builders
    provides: InvariantVerifier.assertFraudEvaluatedBeforeProviderCall, TenantBuilder, PaymentRequestBuilder
  - phase: 20-payment-flow-tests
    provides: Established fraud rule seeding and transactionTemplate+refreshRules() pattern
provides:
  - FraudVelocityBlockE2ETest covering FLOWS-FRAUD-01, FLOWS-FRAUD-02, FLOWS-FRAUD-03
  - E2E proof that fraud engine blocks payment before any provider HTTP call (zero WireMock hits for blocked path)
  - E2E proof that allowed path writes PAYMENT_INITIATED event (assertFraudEvaluatedBeforeProviderCall)
affects:
  - 22-02 (reconciliation tests — same TestDataCleaner and Redis flush patterns)
  - 22-03 (admin tests — same AbstractPayamE2ETest infrastructure)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - FraudVelocityBlockE2ETest extends AbstractFailureFlowTest — same hierarchy as FraudBlockedPaymentE2ETest
    - Fraud rule seeding via transactionTemplate.execute() + ON CONFLICT (id) DO UPDATE + fraudRuleCache.refreshRules() outside lambda
    - noRetryRestTemplate pattern (SimpleClientHttpRequestFactory + no-op DefaultResponseErrorHandler) for 4xx assertion
    - Two-request pattern in executeFlow() — first request allowed, second blocked, blockedResponse field captured for verifyFailureHandled()

key-files:
  created:
    - src/test/java/com/softropic/payam/e2e/fraud/FraudVelocityBlockE2ETest.java
  modified: []

key-decisions:
  - "FraudVelocityBlockE2ETest.seedFraudRule() is a local private helper (not inherited from AbstractFailureFlowTest) — same pattern as FraudBlockedPaymentE2ETest which also defines it locally"
  - "blockedResponse stored as ResponseEntity<PaymentResponse> field on class — allows verifyFailureHandled() to assert status and errorCode separately from executeFlow()"
  - "allowedTransactionId captured from 202 response body in executeFlow() — used by both PAYMENT_INITIATED event count assertion (FLOWS-FRAUD-02) and assertFraudEvaluatedBeforeProviderCall (FLOWS-FRAUD-03)"
  - "mtnServer.verify(exactly(1), postRequestedFor(urlEqualTo(...))) — exactly(1) confirms only the allowed path reached the provider; blocked path added zero provider calls"

patterns-established:
  - "Two-request E2E pattern: executeFlow() sends both allowed and blocked requests, stores blockedResponse as field, allowedTransactionId extracted for later invariant assertions"
  - "Failure-flow fast-fail guard: if first (allowed) request does not return 202, throw AssertionError immediately with diagnostic message rather than propagating NullPointerException"

# Metrics
duration: 8min
completed: 2026-03-27
---

# Phase 22 Plan 01: FraudVelocityBlockE2ETest Summary

**E2E fraud velocity block test proving the fraud engine intercepts before any provider HTTP call: blocked path returns 422 FRAUD_BLOCKED with zero WireMock hits; allowed path records PAYMENT_INITIATED event and passes assertFraudEvaluatedBeforeProviderCall.**

## Performance

- **Duration:** 8 min
- **Started:** 2026-03-27T22:38:00Z
- **Completed:** 2026-03-27T22:46:10Z
- **Tasks:** 1
- **Files modified:** 1 created

## Accomplishments

- Created `src/test/java/com/softropic/payam/e2e/fraud/` package and `FraudVelocityBlockE2ETest.java`
- FLOWS-FRAUD-01: WireMock `verify(exactly(1), postRequestedFor(...))` confirms blocked request generates zero additional provider calls
- FLOWS-FRAUD-02: `payment_event_log` query confirms PAYMENT_INITIATED event count = 1 for `allowedTransactionId`
- FLOWS-FRAUD-03: `invariant.assertFraudEvaluatedBeforeProviderCall(allowedTransactionId)` passes without AssertionError
- Both `FraudVelocityBlockE2ETest` and `FraudBlockedPaymentE2ETest` pass in the same JVM run (2 tests, 0 failures)

## Task Commits

Each task was committed atomically:

1. **Task 1: FraudVelocityBlockE2ETest** - `a2285af` (feat)

**Plan metadata:** (docs commit — see below)

## Files Created/Modified

- `src/test/java/com/softropic/payam/e2e/fraud/FraudVelocityBlockE2ETest.java` - FLOWS-FRAUD-01/02/03 E2E test extending AbstractFailureFlowTest; seeds fraud rules, lowers MSISDN_VELOCITY threshold to 1, sends two requests (allowed then blocked), verifies 422 + zero provider calls + PAYMENT_INITIATED event

## Decisions Made

- `seedFraudRule()` defined as a local private helper rather than inherited — `AbstractFailureFlowTest` does not expose this helper; `FraudBlockedPaymentE2ETest` also defines it locally. Consistent with existing pattern.
- `blockedResponse` stored as a `ResponseEntity<PaymentResponse>` field on the class so `verifyFailureHandled()` can access both the status code and deserialized body independently.
- `allowedTransactionId` extracted in `executeFlow()` and asserted non-null before `verifyFailureHandled()` runs — ensures diagnostic failure message points to correct phase if the allowed request fails unexpectedly.
- Used `exactly(1)` WireMock verifier for the POST count assertion — more precise than `moreThanOrEqualTo(1)` since we want to prove strictly one call.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None. Test compiled, ran, and passed on first attempt. Both standalone (`FraudVelocityBlockE2ETest` alone) and combined (`FraudBlockedPaymentE2ETest,FraudVelocityBlockE2ETest`) runs pass cleanly.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- `FraudVelocityBlockE2ETest` is complete and passing — fraud E2E coverage for FLOWS-FRAUD-01/02/03 is done
- Phase 22 plan 02 (DailyReconciliationE2ETest) can proceed immediately — extends AbstractPayamE2ETest directly with @MockBean ports, same infrastructure
- No blockers or concerns

---
*Phase: 22-fraud-recon-admin-tests*
*Completed: 2026-03-27*
