---
phase: 03-orange-money-adapter
plan: "02"
subsystem: payments
tags: [orange-money, quartz, wiremock, resilience4j, circuit-breaker, redis, cam-wat, pessimistic-lock]

# Dependency graph
requires:
  - phase: 03-01
    provides: OrangeMoneyClient, OrangeTokenService, OrangeMoneyConfig, OrangeWebhookPayload, contract DTOs/exceptions, Quartz DDL
  - phase: 02-01
    provides: Transaction entity, TransactionRepository, TransactionStatus state machine, MobileMoneyPort
  - phase: 02-02
    provides: EventLogService (append to hash chain)
provides:
  - OrangeMoneyPort implementing MobileMoneyPort (initiateMerchantPayment, getTransactionStatus, validateSubscriber)
  - OrangeTimeUtil WAT timestamp parsing (Africa/Douala UTC+1)
  - OrangeStatusMapper mapping SUCCESSFULL (double-L) -> SUCCESS, EXPIRED -> FAILED
  - OrangeStatusPollerJob (QuartzJobBean) polling stuck PROCESSING Orange transactions
  - OrangeSchedulerConfig registering job+trigger in Quartz JDBC store
  - Transaction.payToken, payTokenIssuedAt, pollAttempts fields with V6 Flyway migration
  - TransactionRepository.findByTransactionIdForUpdate (PESSIMISTIC_WRITE) and findByTxStatusAndProviderAndLastModifiedDateBefore
  - 11 green IT tests (OrangeMoneyPortIT 8 + OrangeTokenServiceIT 3)
affects:
  - 05-payment-orchestrator (calls OrangeMoneyPort via MobileMoneyPort)
  - 06-webhook-handler (calls OrangeMoneyPort.processWebhook + getTransactionStatus)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Transaction committed before outbound HTTP (P1.1): REQUIRES_NEW propagation for persistPayToken"
    - "PESSIMISTIC_WRITE on state-transition queries (P1.2): prevents webhook+poller race"
    - "payToken expiry check (P1.3): assertPayTokenFresh throws PayTokenExpiredException"
    - "WAT-aware timestamp parsing (P5.1): ALL Orange timestamps via OrangeTimeUtil.parseOrangeTimestamp"
    - "MSISDN country code stripping (P5.5): replaceFirst(^\\+?237,) for national number"
    - "CircuitBreaker without fallback: CallNotPermittedException propagates to orchestration layer"

key-files:
  created:
    - src/main/java/com/softropic/payam/orange/service/OrangeTimeUtil.java
    - src/main/java/com/softropic/payam/orange/service/OrangeStatusMapper.java
    - src/main/java/com/softropic/payam/orange/service/OrangeMoneyPort.java
    - src/main/java/com/softropic/payam/orange/service/OrangeStatusPollerJob.java
    - src/main/java/com/softropic/payam/orange/config/OrangeSchedulerConfig.java
    - src/main/resources/db/migration/V6__transaction_orange_fields.sql
    - src/test/java/com/softropic/payam/orange/OrangeMoneyPortIT.java
    - src/test/java/com/softropic/payam/orange/OrangeTokenServiceIT.java
    - src/test/resources/application.properties
  modified:
    - src/main/java/com/softropic/payam/transaction/repo/Transaction.java
    - src/main/java/com/softropic/payam/transaction/repo/TransactionRepository.java
    - src/main/java/com/softropic/payam/orange/infrastructure/OrangeMoneyClient.java
    - src/main/resources/application.yaml

key-decisions:
  - "CircuitBreaker fallback removed: Resilience4j calls fallback for ALL exceptions (not just circuit-open), swallowing SubscriberInactiveException — removed fallbackMethod so exceptions propagate naturally; CallNotPermittedException reaches Phase 5 orchestrator"
  - "TransactionEventType corrected: plan referenced PAYMENT_CONFIRMED/PAYMENT_FAILED which don't exist; corrected to PROVIDER_SUCCESS/PROVIDER_FAILED per actual enum definition"
  - "FormHttpMessageConverter added to OrangeMoneyClient: AbstractClient.messageConverters() only registers JSON; form-encoded token POST requires explicit FormHttpMessageConverter"
  - "WireMock token URL: orange.token-url overridden in test application.properties as ${orange.base-url}/token so WireMock baseUrlProperties propagates to token endpoint"
  - "Circuit breaker test config: slidingWindowSize=100, failureRateThreshold=90 in test properties prevent premature circuit opening across shared Spring context"
  - "cashout/C2C stubs have no @CircuitBreaker/@Retry: unconditionally-throwing stubs provide no value to circuit-break; fallbacks swallowed UnsupportedOperationException in tests"
  - "ignoreExceptions for SubscriberInactiveException and PayTokenExpiredException added to application.yaml circuit breaker config"

patterns-established:
  - "Pattern: Two-phase commit (REQUIRES_NEW) for payToken persistence — INITIATED row committed by TransactionService, payToken update committed separately via persistPayToken"
  - "Pattern: Quartz job as @Component + QuartzJobBean — SpringBeanJobFactory handles DI; @Transactional on executeInternal provides per-poll transaction isolation"

# Metrics
duration: 30min
completed: 2026-03-24
---

# Phase 3 Plan 02: OrangeMoneyPort Service Summary

**Orange Money MP flow with payToken expiry, WAT timestamp parsing, PESSIMISTIC_WRITE state transitions, Quartz status poller, and 11 green WireMock IT tests**

## Performance

- **Duration:** 30 min
- **Started:** 2026-03-24T01:40:09Z
- **Completed:** 2026-03-24T02:10:48Z
- **Tasks:** 2
- **Files modified:** 13 (9 created, 4 modified)

## Accomplishments
- OrangeMoneyPort implements MobileMoneyPort with init->pay flow: subscriber validation, payToken retrieval, /mp/pay call, ProviderResult(pending=true) returned
- OrangeStatusPollerJob polls PROCESSING Orange transactions via Quartz JDBC store; applies applyTransition with PESSIMISTIC_WRITE locks
- V6 Flyway migration adds pay_token, pay_token_issued_at, poll_attempts to main.transaction
- 11 green WireMock IT tests: subscriber active/inactive, MP initiation, inactive exception, status poll, payToken expiry, cashout/C2C stubs

## Task Commits

Each task was committed atomically:

1. **Task 1: OrangeTimeUtil, OrangeStatusMapper, OrangeMoneyPort, V6 migration** - `20b4b68` (feat)
2. **Task 2: OrangeStatusPollerJob, OrangeSchedulerConfig, IT tests** - `88ecf85` (feat)

## Files Created/Modified
- `src/main/java/com/softropic/payam/orange/service/OrangeTimeUtil.java` - WAT (Africa/Douala) timestamp parser (P5.1)
- `src/main/java/com/softropic/payam/orange/service/OrangeStatusMapper.java` - Maps SUCCESSFULL (double-L) to SUCCESS, EXPIRED to FAILED
- `src/main/java/com/softropic/payam/orange/service/OrangeMoneyPort.java` - Core adapter implementing MobileMoneyPort
- `src/main/java/com/softropic/payam/orange/service/OrangeStatusPollerJob.java` - Quartz job polling stuck PROCESSING transactions
- `src/main/java/com/softropic/payam/orange/config/OrangeSchedulerConfig.java` - Quartz job+trigger registration
- `src/main/resources/db/migration/V6__transaction_orange_fields.sql` - pay_token, pay_token_issued_at, poll_attempts columns
- `src/test/java/com/softropic/payam/orange/OrangeMoneyPortIT.java` - 8 WireMock integration tests
- `src/test/java/com/softropic/payam/orange/OrangeTokenServiceIT.java` - 3 Redis token caching tests
- `src/test/resources/application.properties` - WireMock token URL and circuit breaker test overrides
- `src/main/java/com/softropic/payam/transaction/repo/Transaction.java` - Added payToken, payTokenIssuedAt, pollAttempts, incrementPollAttempts()
- `src/main/java/com/softropic/payam/transaction/repo/TransactionRepository.java` - Added findByTransactionIdForUpdate (PESSIMISTIC_WRITE) and findByTxStatusAndProviderAndLastModifiedDateBefore
- `src/main/java/com/softropic/payam/orange/infrastructure/OrangeMoneyClient.java` - Added FormHttpMessageConverter for token endpoint
- `src/main/resources/application.yaml` - Added resilience4j ignoreExceptions for SubscriberInactiveException/PayTokenExpiredException

## Decisions Made
- **CircuitBreaker fallback removed:** Resilience4j calls fallbackMethod for ALL exceptions (not just circuit-open). `SubscriberInactiveException` was being swallowed. Removed `fallbackMethod` — exceptions propagate naturally; `CallNotPermittedException` (circuit open) reaches Phase 5 orchestration layer.
- **TransactionEventType corrected:** Plan referenced `PAYMENT_CONFIRMED`/`PAYMENT_FAILED` which don't exist in the enum. Corrected to `PROVIDER_SUCCESS`/`PROVIDER_FAILED`.
- **FormHttpMessageConverter:** `AbstractClient.messageConverters()` only registers JSON. Orange token endpoint uses `application/x-www-form-urlencoded`; added `FormHttpMessageConverter` in `OrangeMoneyClient` constructor.
- **WireMock token URL:** `@ConfigureWireMock(baseUrlProperties)` overrides properties to `http://localhost:{port}`. Token URL (`orange.token-url`) is a full URL with path, not derivable from `baseUrlProperties` alone. Override in `test/resources/application.properties` as `${orange.base-url}/token`.
- **cashout/C2C no circuit breaker:** Unconditionally-throwing stubs don't need circuit breaking. The circuit breaker fallback was swallowing `UnsupportedOperationException` in tests.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Resilience4j CircuitBreaker fallback intercepted domain exceptions**
- **Found during:** Task 2 (OrangeMoneyPortIT tests)
- **Issue:** `@CircuitBreaker(fallbackMethod = "initiateFallback")` calls the fallback for ALL exceptions thrown by the method, not just when the circuit is open. `SubscriberInactiveException` was being wrapped into `OrangeApiException` by the fallback, causing test `initiate_throws_subscriber_inactive_exception_when_msisdn_inactive` to fail.
- **Fix:** Removed `fallbackMethod` parameter from all `@CircuitBreaker` annotations. Circuit-open now throws `CallNotPermittedException` directly to callers (Phase 5 handles it at orchestration level).
- **Files modified:** `OrangeMoneyPort.java`
- **Verification:** All 11 IT tests pass; `SubscriberInactiveException` propagates correctly.
- **Committed in:** `88ecf85` (Task 2 commit)

**2. [Rule 3 - Blocking] OrangeStatusPollerJob referenced non-existent TransactionEventType constants**
- **Found during:** Task 2 (compiler:testCompile)
- **Issue:** Plan code used `TransactionEventType.PAYMENT_CONFIRMED` and `TransactionEventType.PAYMENT_FAILED` which don't exist. Actual enum values are `PROVIDER_SUCCESS` and `PROVIDER_FAILED`.
- **Fix:** Corrected to `PROVIDER_SUCCESS`/`PROVIDER_FAILED`.
- **Files modified:** `OrangeStatusPollerJob.java`
- **Verification:** `mvn compiler:compile` passes.
- **Committed in:** `88ecf85` (Task 2 commit)

**3. [Rule 1 - Bug] OrangeMoneyClient missing FormHttpMessageConverter for token fetch**
- **Found during:** Task 2 (OrangeTokenServiceIT test failure)
- **Issue:** `AbstractClient.messageConverters()` only registers `MappingJackson2HttpMessageConverter`. The `fetchToken()` method sends `application/x-www-form-urlencoded` body (`MultiValueMap`), causing `No HttpMessageConverter for LinkedMultiValueMap` error.
- **Fix:** Added `FormHttpMessageConverter` to `restTemplate` in `OrangeMoneyClient` constructor.
- **Files modified:** `OrangeMoneyClient.java`
- **Verification:** `getAccessToken_fetches_from_orange_and_caches_in_redis` passes.
- **Committed in:** `88ecf85` (Task 2 commit)

**4. [Rule 3 - Blocking] WireMock did not override orange.token-url**
- **Found during:** Task 2 (OrangeTokenServiceIT, OrangeMoneyPortIT test failures)
- **Issue:** `@ConfigureWireMock(baseUrlProperties = "orange.base-url")` only overrides the base URL. The token URL (`orange.token-url`) pointed to the real Orange API (`https://api-s1.orange.cm/...`), causing live HTTP calls to fail in tests.
- **Fix:** Added `orange.token-url=${orange.base-url}/token` to `src/test/resources/application.properties`, which Spring loads for all tests and resolves after WireMock overrides `orange.base-url`.
- **Files modified:** `src/test/resources/application.properties` (created)
- **Verification:** Token stub `POST /token` matched successfully.
- **Committed in:** `88ecf85` (Task 2 commit)

---

**Total deviations:** 4 auto-fixed (2 bugs, 2 blocking)
**Impact on plan:** All auto-fixes necessary for correctness or compilability. No scope creep. The circuit breaker fallback removal is a production-quality improvement (fallbacks without specific circuit-open detection are anti-patterns in domain exception propagation).

## Issues Encountered
- Circuit breaker + test isolation: The shared Spring context means circuit breaker state persists across tests. Added `slidingWindowSize=100, failureRateThreshold=90` in `test/resources/application.properties` to prevent premature circuit opening during the 11-test run.

## User Setup Required
None - no external service configuration required. All integration tests use WireMock stubs.

## Next Phase Readiness
- Phase 3 complete: OrangeMoneyPort implements MobileMoneyPort and is ready for Phase 5 (PaymentOrchestrator)
- `processWebhook()` hook exists for Phase 6 wiring
- ROADMAP SC-3 deviation documented: cashout/C2C stubbed with `UnsupportedOperationException` pending sandbox field verification; covered by explicit stub tests
- Pre-existing `SecurityFilterChainIT` failure unrelated to Phase 3 (existed before this plan)

---
*Phase: 03-orange-money-adapter*
*Completed: 2026-03-24*
