---
phase: 04-mtn-momo-adapter
plan: 02
subsystem: payments
tags: [mtn, momo, redis, quartz, spring-security, wiremock, resilience4j]

# Dependency graph
requires:
  - phase: 04-01-mtn-momo-adapter
    provides: MtnMoMoClient, MtnMoMoConfig, DTOs, V7 migration (mtn_financial_tx_id column)
  - phase: 03-orange-money-adapter
    provides: OrangeTokenService/OrangeMoneyPort structural template, OrangeSchedulerConfig pattern
  - phase: 02-transaction-core
    provides: Transaction entity, TransactionRepository, EventLogService, TransactionStatus state machine

provides:
  - MtnTokenService — Redis OAuth2 token cache (mtn:token:cm, NX lock, 55-min TTL)
  - MtnStatusMapper — MTN SUCCESSFUL(single-L)/FAILED/PENDING to internal TransactionStatus
  - MtnMoMoPort — MobileMoneyPort implementation; persistProviderRef(REQUIRES_NEW) before requestToPay
  - MtnStatusPollerJob — Quartz poller for PROCESSING MTN transactions using tx.getProviderRef()
  - MtnSchedulerConfig — Quartz JobDetail + Trigger wired to MtnMoMoConfig poller settings
  - MtnCallbackController — @PutMapping on /v1/callbacks/mtn (HTTP PUT, not POST — P1.4 closed)
  - MtnIpWhitelistInterceptor — CIDR + exact-match IP filter for MTN callbacks (sandbox mode on empty list)
  - MtnWebConfig — WebMvcConfigurer registering IP whitelist interceptor only for /v1/callbacks/mtn
  - AppEndpoints.PUBLIC_ENDPOINTS — updated with /v1/callbacks/mtn
  - Transaction entity — added setProviderRef(), setMtnFinancialTxId(), mtnFinancialTxId field
  - MtnTokenServiceIT — 3 passing integration tests (fetch+cache, cache-hit, evict)
  - MtnMoMoPortIT — 5 passing integration tests (validate, 404/inactive, requestToPay+providerRef, PUT callback, status poll)

affects:
  - 05-payment-orchestrator (routes to MtnMoMoPort.initiateMerchantPayment, getTransactionStatus, validateSubscriber)
  - 06-webhook-processing (expands MtnMoMoPort.processCallback to apply state transitions)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Redis NX-lock token cache: same TOKEN_KEY/LOCK_KEY pattern as OrangeTokenService — mtn:token:cm vs orange:token:cm"
    - "persistProviderRef(REQUIRES_NEW): UUID stored before HTTP call to survive crash (Pitfall 6)"
    - "PUT callback endpoint: @PutMapping prevents 405 silent drop (P1.4)"
    - "IP whitelist interceptor: registered via WebMvcConfigurer.addPathPatterns() for single path"
    - "Test IP whitelist override: mtn.callback-ip-whitelist= (empty) in @TestPropertySource for sandbox mode"
    - "HttpClientException 404 detection: interceptor wraps 4xx before RestTemplate; check getHttpStatusCode().contains(404)"

key-files:
  created:
    - src/main/java/com/softropic/payam/mtn/service/MtnTokenService.java
    - src/main/java/com/softropic/payam/mtn/service/MtnStatusMapper.java
    - src/main/java/com/softropic/payam/mtn/service/MtnMoMoPort.java
    - src/main/java/com/softropic/payam/mtn/service/MtnStatusPollerJob.java
    - src/main/java/com/softropic/payam/mtn/config/MtnSchedulerConfig.java
    - src/main/java/com/softropic/payam/mtn/web/MtnCallbackController.java
    - src/main/java/com/softropic/payam/mtn/web/MtnIpWhitelistInterceptor.java
    - src/main/java/com/softropic/payam/mtn/web/MtnWebConfig.java
    - src/test/java/com/softropic/payam/mtn/MtnTokenServiceIT.java
    - src/test/java/com/softropic/payam/mtn/MtnMoMoPortIT.java
  modified:
    - src/main/java/com/softropic/payam/security/config/AppEndpoints.java
    - src/main/java/com/softropic/payam/transaction/repo/Transaction.java
    - src/main/java/com/softropic/payam/mtn/infrastructure/MtnMoMoClient.java
    - src/test/resources/application.properties

key-decisions:
  - "@TestPropertySource(properties = mtn.callback-ip-whitelist=) for PUT callback test — application.yaml has 196.0.0.0/8 which rejects 127.0.0.1 test requests; empty list triggers sandbox mode (accept all)"
  - "MtnMoMoClient.validateAccountHolder() catches HttpClientException (not HttpClientErrorException.NotFound) — RestRequestInterceptor converts all 4xx to HttpClientException before RestTemplate error handling fires"
  - "MtnStatusMapper uses SUCCESSFUL (single-L) vs Orange SUCCESSFULL (double-L)"
  - "MtnStatusPollerJob uses tx.getProviderRef() — MTN has no payToken expiry, no assertPayTokenFresh() needed"
  - "mtn.callback-ip-whitelist in application.yaml pre-populated with 196.0.0.0/8 — test must override to empty"

patterns-established:
  - "MTN PUT callback pattern: @PutMapping NOT @PostMapping — document explicitly for future HTTP method bugs"
  - "IP whitelist interceptor: empty = sandbox mode (log warning, accept all); production must set IP ranges"
  - "Merchant-generated UUID (Pitfall 6): persist providerRef in REQUIRES_NEW BEFORE API call — MTN only pattern"

# Metrics
duration: 11min
completed: 2026-03-24
---

# Phase 4 Plan 02: MTN MoMo Service Layer Summary

**MTN MoMo adapter complete: Redis token cache, port implementation with crash-safe providerRef, Quartz poller, PUT callback with CIDR IP whitelist — all 8 tests pass, Orange unchanged**

## Performance

- **Duration:** 11 min
- **Started:** 2026-03-24T03:40:45Z
- **Completed:** 2026-03-24T03:51:45Z
- **Tasks:** 2
- **Files modified:** 14

## Accomplishments

- Full MobileMoneyPort implementation for MTN: initiateMerchantPayment (with REQUIRES_NEW providerRef persistence before API call), getTransactionStatus, validateSubscriber
- PUT callback controller on /v1/callbacks/mtn with CIDR IP whitelist interceptor — closes P1.4 production risk where @PostMapping would silently drop all callbacks with 405
- 8 integration tests all passing: 3 token cache tests, 5 port tests including PUT callback test

## Task Commits

Each task was committed atomically:

1. **Task 1: Service layer (MtnTokenService, MtnStatusMapper, MtnMoMoPort, MtnStatusPollerJob, MtnSchedulerConfig, AppEndpoints)** - `e564d9f` (feat)
2. **Task 2: Web layer (MtnCallbackController, MtnIpWhitelistInterceptor, MtnWebConfig) and integration tests** - `7baa5b4` (feat)

**Plan metadata:** (see final docs commit)

## Files Created/Modified

- `src/main/java/com/softropic/payam/mtn/service/MtnTokenService.java` — Redis NX-lock token cache (mtn:token:cm, 55-min TTL)
- `src/main/java/com/softropic/payam/mtn/service/MtnStatusMapper.java` — SUCCESSFUL(single-L)/FAILED/PENDING mapping
- `src/main/java/com/softropic/payam/mtn/service/MtnMoMoPort.java` — MobileMoneyPort impl; persistProviderRef REQUIRES_NEW before requestToPay
- `src/main/java/com/softropic/payam/mtn/service/MtnStatusPollerJob.java` — Quartz poller using tx.getProviderRef()
- `src/main/java/com/softropic/payam/mtn/config/MtnSchedulerConfig.java` — Quartz JobDetail + Trigger
- `src/main/java/com/softropic/payam/mtn/web/MtnCallbackController.java` — @PutMapping /v1/callbacks/mtn
- `src/main/java/com/softropic/payam/mtn/web/MtnIpWhitelistInterceptor.java` — CIDR + exact IP whitelist
- `src/main/java/com/softropic/payam/mtn/web/MtnWebConfig.java` — interceptor registered only for /v1/callbacks/mtn
- `src/main/java/com/softropic/payam/security/config/AppEndpoints.java` — added /v1/callbacks/mtn to PUBLIC_ENDPOINTS
- `src/main/java/com/softropic/payam/transaction/repo/Transaction.java` — added setProviderRef(), setMtnFinancialTxId(), mtnFinancialTxId field
- `src/main/java/com/softropic/payam/mtn/infrastructure/MtnMoMoClient.java` — validateAccountHolder now catches HttpClientException (not HttpClientErrorException.NotFound)
- `src/test/java/com/softropic/payam/mtn/MtnTokenServiceIT.java` — 3 integration tests
- `src/test/java/com/softropic/payam/mtn/MtnMoMoPortIT.java` — 5 integration tests
- `src/test/resources/application.properties` — MTN token URL override + circuit breaker tuning

## Decisions Made

- **MtnStatusMapper uses SUCCESSFUL (single-L)** — MTN uses standard spelling; Orange uses "SUCCESSFULL" (double-L). Different constants per provider.
- **MtnStatusPollerJob uses tx.getProviderRef() not tx.getPayToken()** — MTN providerRef is a stable UUID; unlike Orange's payToken it never expires. No assertPayTokenFresh() guard needed.
- **persistProviderRef(REQUIRES_NEW) BEFORE requestToPay** — MTN referenceId is merchant-generated, so it must be stored before the API call to survive crashes (Pitfall 6). Orange's providerRef comes back in the API response so the pattern differs.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] validateAccountHolder 404 handling via HttpClientException not HttpClientErrorException.NotFound**

- **Found during:** Task 2 (MtnMoMoPortIT.account_holder_validation_returns_inactive_for_404_response)
- **Issue:** `MtnMoMoClient.validateAccountHolder()` caught `HttpClientErrorException.NotFound`, but `RestRequestInterceptor.intercept()` converts all 4xx/5xx responses to `HttpClientException` at the interceptor level, before RestTemplate error handling can run. The catch block never fired.
- **Fix:** Changed catch to `HttpClientException` and checked `e.getHttpStatusCode().contains("404")` to detect MTN 404 = inactive account. Retained fallback for `HttpClientErrorException` just in case.
- **Files modified:** `src/main/java/com/softropic/payam/mtn/infrastructure/MtnMoMoClient.java`
- **Verification:** `account_holder_validation_returns_inactive_for_404_response` passes
- **Committed in:** `7baa5b4` (Task 2 commit)

**2. [Rule 1 - Bug] PUT callback test getting 403 from IP whitelist**

- **Found during:** Task 2 (MtnMoMoPortIT.put_callback_endpoint_accepts_put_and_returns_200)
- **Issue:** `application.yaml` pre-populates `mtn.callback-ip-whitelist` with `196.0.0.0/8`. The test sends from `127.0.0.1` which doesn't match that CIDR. The interceptor correctly rejected the request (403). This was a test setup gap, not a production bug.
- **Fix:** Added `mtn.callback-ip-whitelist=` (empty string) to `@TestPropertySource` in `MtnMoMoPortIT` — triggers sandbox mode (accept all IPs, log warning).
- **Files modified:** `src/test/java/com/softropic/payam/mtn/MtnMoMoPortIT.java`
- **Verification:** `put_callback_endpoint_accepts_put_and_returns_200` returns 200
- **Committed in:** `7baa5b4` (Task 2 commit)

---

**Total deviations:** 2 auto-fixed (2 bugs)
**Impact on plan:** Both auto-fixes necessary for tests to pass. Bug 1 is a structural issue with the interceptor pattern that affects all 4xx/5xx error handling in this codebase. Bug 2 is a test setup issue; production behavior is correct.

## Issues Encountered

- The `RestRequestInterceptor` pattern (converts all 4xx/5xx to `HttpClientException` at interceptor level) means any catch on Spring's `HttpClientErrorException` in clients will never fire. This affects `MtnMoMoClient.validateAccountHolder()` and potentially other error-handling code. **Phase 5/6 developers must be aware of this pattern when writing new client error handling.**

## User Setup Required

None - no external service configuration required beyond what was set up in 04-01.

## Next Phase Readiness

- MTN adapter is fully wired: token cache, port, poller, PUT callback, IP whitelist
- `MtnMoMoPort.initiateMerchantPayment()`, `getTransactionStatus()`, `validateSubscriber()` — all ready for Phase 5 PaymentOrchestrator routing
- `MtnMoMoPort.processCallback()` is a stub — logs callback and stores financialTransactionId; Phase 6 wires full state transition
- `MtnStatusPollerJob` and `MtnSchedulerConfig` are in place — poller runs in the Spring context automatically
- Concern: `RestRequestInterceptor` HttpClientException pattern: any new client code catching Spring's `HttpClientErrorException` subtypes will silently not handle errors — must catch `HttpClientException` instead

---
*Phase: 04-mtn-momo-adapter*
*Completed: 2026-03-24*
