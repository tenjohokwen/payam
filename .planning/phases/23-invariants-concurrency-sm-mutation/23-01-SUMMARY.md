---
phase: 23-invariants-concurrency-sm-mutation
plan: 01
subsystem: testing
tags: [e2e, domain-invariants, junit5, wiremock, testcontainers, hash-chain, ledger, idempotency, state-machine, ssrf, fraud]

# Dependency graph
requires:
  - phase: 19-verifiers-builders
    provides: HashChainVerifier, LedgerVerifier, TenantIsolationVerifier, InvariantVerifier, ProviderCallVerifier, TenantBuilder, PaymentRequestBuilder
  - phase: 18-test-infrastructure
    provides: AbstractPayamE2ETest, WireMockConfig, TestDataCleaner, E2ESecurityConfig
  - phase: 22-fraud-recon-admin-flow-tests
    provides: FraudVelocityBlockE2ETest fraud rule seeding pattern
provides:
  - 10 domain invariant test files in e2e/domain/ package
  - INV-01-TEST through INV-10-TEST coverage for hash chain, ledger, idempotency, tenant isolation, state machine, webhook double-check, fraud ordering, SSRF guard, init-before-provider, Orange WAT offset
affects:
  - 23-02-PLAN (concurrency tests)
  - 23-03-PLAN (mutation tests)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "WireMock RequestListener with try-finally teardown for capturing DB state at provider call time"
    - "Pure unit test (no @SpringBootTest) for OrangeTimeUtil WAT offset assertion"
    - "@MethodSource covering all 32 illegal state transitions with DB-unchanged assertion after each"
    - "assertWebhookDoubleCheckFired via PROVIDER_SUCCESS event in payment_event_log"

key-files:
  created:
    - src/test/java/com/softropic/payam/e2e/domain/HashChainIntegrityTest.java
    - src/test/java/com/softropic/payam/e2e/domain/LedgerDoubleEntryTest.java
    - src/test/java/com/softropic/payam/e2e/domain/IdempotencyNoDoubleChargeTest.java
    - src/test/java/com/softropic/payam/e2e/domain/TenantIsolationTest.java
    - src/test/java/com/softropic/payam/e2e/domain/StateMachineLegalTransitionsTest.java
    - src/test/java/com/softropic/payam/e2e/domain/WebhookDoubleCheckTest.java
    - src/test/java/com/softropic/payam/e2e/domain/FraudBeforeProviderCallTest.java
    - src/test/java/com/softropic/payam/e2e/domain/CallbackUrlSsrfGuardTest.java
    - src/test/java/com/softropic/payam/e2e/domain/InitBeforeProviderCallTest.java
    - src/test/java/com/softropic/payam/e2e/domain/OrangeTimestampWatTest.java
  modified:
    - src/test/java/com/softropic/payam/e2e/payment/PaymentIdempotencyE2ETest.java

key-decisions:
  - "OrangeTimestampWatTest is a plain JUnit 5 unit test with no @SpringBootTest — starts in milliseconds"
  - "StateMachineLegalTransitionsTest @MethodSource covers all 32 illegal transitions; DB-unchanged assertion after each throw"
  - "InitBeforeProviderCallTest uses WireMock RequestListener in try-finally to prevent listener bleed"
  - "FraudBeforeProviderCallTest reuses assertFraudEvaluatedBeforeProviderCall() from InvariantVerifier — PAYMENT_INITIATED event timestamp < provider call timestamp"
  - "PaymentIdempotencyE2ETest required fraud rule seeding to guard against FraudVelocityBlockE2ETest leaving MSISDN_VELOCITY threshold=1 in cache across test ordering"

patterns-established:
  - "Domain invariant tests extend AbstractPayamE2ETest directly (not flow-specific subclass)"
  - "Try-finally listener teardown: addMockServiceRequestListener in try, removeMockServiceRequestListener in finally"
  - "Fraud rule seeding required for all tests driving full HTTP payment flows (BLOCK_THRESHOLD=70 allows normal payments)"

# Metrics
duration: ~19min (from commit timestamps: 01:08 to 01:27, plus test run)
completed: 2026-03-28
---

# Phase 23 Plan 01: Domain Invariant Tests Summary

**10 domain invariant E2E tests (INV-01 through INV-10) proving hash chain integrity, ledger double-entry, idempotency, tenant isolation, state machine legality, webhook double-check, fraud ordering, SSRF guard, init-before-provider, and Orange WAT offset**

## Performance

- **Duration:** ~19 min
- **Started:** 2026-03-28T00:08:00Z (approx)
- **Completed:** 2026-03-28T00:27:04Z
- **Tasks:** 2 of 2
- **Files modified:** 11

## Accomplishments

- Created 10 test files in `e2e/domain/` proving all 10 domain invariants at the E2E level
- StateMachineLegalTransitionsTest covers all 32 illegal transitions via @MethodSource, asserting DB is unchanged after every throw
- OrangeTimestampWatTest is a pure JUnit 5 unit test (no Spring context) asserting the WAT-vs-UTC 3600s difference
- InitBeforeProviderCallTest uses a WireMock RequestListener to capture the DB INITIATED row count at the exact moment the provider HTTP call arrives, proving the INIT row exists before the provider is contacted

## Task Commits

Each task was committed atomically:

1. **Task 1: INV-01 through INV-05** - `fae4bc8` (test)
2. **Task 2: INV-06 through INV-10** - `78b087d` (test)

## Files Created/Modified

- `e2e/domain/HashChainIntegrityTest.java` — INV-01: genesis GENESIS check + full chain validation via HashChainVerifier
- `e2e/domain/LedgerDoubleEntryTest.java` — INV-02: success posts balanced DEBIT+CREDIT; failed posts zero ledger entries
- `e2e/domain/IdempotencyNoDoubleChargeTest.java` — INV-03: same key + same tenant = 1 row; same key + different tenants = distinct rows
- `e2e/domain/TenantIsolationTest.java` — INV-04: cross-tenant DB isolation check across all payment tables and Redis
- `e2e/domain/StateMachineLegalTransitionsTest.java` — INV-05: 32 illegal transition cases via @MethodSource, DB-unchanged assertion after each; legal chain INITIATED→SUCCESS passes
- `e2e/domain/WebhookDoubleCheckTest.java` — INV-06: asserts PROVIDER_SUCCESS event in payment_event_log after MTN PUT callback, proving provider re-query before state change
- `e2e/domain/FraudBeforeProviderCallTest.java` — INV-07: PAYMENT_INITIATED event timestamp is before provider call timestamp
- `e2e/domain/CallbackUrlSsrfGuardTest.java` — INV-08: callbackUrl in MTN requesttopay body is Payam-owned, not the tenant-supplied evil.example.com
- `e2e/domain/InitBeforeProviderCallTest.java` — INV-09: WireMock RequestListener captures INITIATED row count >= 1 at provider call time
- `e2e/domain/OrangeTimestampWatTest.java` — INV-10/MUT-02: pure unit test asserting WAT parse differs from UTC parse by exactly 3600s
- `e2e/payment/PaymentIdempotencyE2ETest.java` — added fraud rule seeding to prevent cross-test contamination

## Decisions Made

- OrangeTimestampWatTest uses no @SpringBootTest — pure JUnit 5. The WAT offset is a pure computation; Spring context overhead is unnecessary and would slow the suite.
- StateMachineLegalTransitionsTest covers all 32 illegal transitions from the RESEARCH.md transition matrix via @MethodSource. Each case queries the DB after the expected throw to confirm the row status is unchanged.
- InitBeforeProviderCallTest uses try-finally to remove the WireMock RequestListener regardless of assertion outcome, preventing listener bleed into subsequent tests sharing the same Spring context.
- FraudBeforeProviderCallTest asserts invariant using the existing `assertFraudEvaluatedBeforeProviderCall()` method on InvariantVerifier, which checks the PAYMENT_INITIATED event timestamp (fraud recorded) precedes the first provider HTTP call timestamp.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] PaymentIdempotencyE2ETest missing fraud rule seeding causing cross-test ordering failures**

- **Found during:** Task 2 (INV-06 through INV-10)
- **Issue:** FraudVelocityBlockE2ETest leaves MSISDN_VELOCITY threshold=1 in the FraudRuleCache when run before PaymentIdempotencyE2ETest. Without fraud rule seeding in PaymentIdempotencyE2ETest, payments were being blocked by the stale cache state, causing the idempotency test to fail with FRAUD_BLOCKED instead of 202.
- **Fix:** Added fraud rule seeding (5 rules, BLOCK_THRESHOLD=70) + `fraudRuleCache.refreshRules()` to PaymentIdempotencyE2ETest's setup, consistent with the pattern used in domain invariant tests.
- **Files modified:** `src/test/java/com/softropic/payam/e2e/payment/PaymentIdempotencyE2ETest.java`
- **Verification:** Full suite runs 46 tests, 0 failures, regardless of test execution order.
- **Committed in:** `78b087d` (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (Rule 1 - Bug)
**Impact on plan:** Fix was necessary for test suite determinism across execution order. No scope creep.

## Issues Encountered

None beyond the auto-fixed idempotency test ordering issue above.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- All 10 domain invariant tests are in place and passing
- e2e/domain/ package is established as the home for invariant-level tests
- Fraud rule seeding pattern (5 rules, BLOCK_THRESHOLD=70 + cache refresh) is now applied consistently across all tests driving full HTTP payment flows
- Ready for Phase 23 Plan 02 (concurrency tests) and Plan 03 (mutation tests)

---
*Phase: 23-invariants-concurrency-sm-mutation*
*Completed: 2026-03-28*
