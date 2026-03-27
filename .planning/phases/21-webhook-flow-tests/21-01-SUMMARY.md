---
phase: 21-webhook-flow-tests
plan: 01
subsystem: testing
tags: [e2e, mtn, orange, webhook, redis, dedup, wiremock, awaitility, double-check]

# Dependency graph
requires:
  - phase: 19-verifiers-builders
    provides: InvariantVerifier, MtnWebhookPayloadBuilder, OrangeWebhookPayloadBuilder, TenantBuilder
  - phase: 18-test-infrastructure
    provides: AbstractWebhookFlowTest, AbstractPayamE2ETest base class with Testcontainers + WireMock
provides:
  - FLOWS-HOOK-01: MTN inbound webhook double-check happy path (MtnWebhookDoubleCheckE2ETest)
  - FLOWS-HOOK-02: Orange inbound webhook double-check happy path (OrangeWebhookDoubleCheckE2ETest)
  - FLOWS-HOOK-03: Redis dedup prevents duplicate outbox event for both MTN and Orange (WebhookReplayProtectionE2ETest)
  - FLOWS-HOOK-06: MTN PUT accepted and processed; POST returns 405 (MtnPutCallbackAcceptanceE2ETest)
affects: [21-02-webhook-flow-tests]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "JDBC seed bypasses payment API: transactionTemplate.execute() inserts PROCESSING row directly — only provider_ref and pay_token must be set correctly for double-check correlation"
    - "Orange correlation by pay_token not transactionId: OrangeMoneyPort.processWebhook() calls findByPayToken(); JDBC insert must set both provider_ref and pay_token columns to payToken"
    - "Replay protection single-test pattern: both webhook calls in one @Test method with no Redis flush between them — @BeforeEach flushDb() runs once at test start only"
    - "Awaitility.await().atMost(5, SECONDS).until(() -> redis.hasKey(dedupKey)) to gate second call on first double-check completion"

key-files:
  created:
    - src/test/java/com/softropic/payam/e2e/webhook/MtnWebhookDoubleCheckE2ETest.java
    - src/test/java/com/softropic/payam/e2e/webhook/OrangeWebhookDoubleCheckE2ETest.java
    - src/test/java/com/softropic/payam/e2e/webhook/WebhookReplayProtectionE2ETest.java
    - src/test/java/com/softropic/payam/e2e/webhook/MtnPutCallbackAcceptanceE2ETest.java
  modified:
    - src/main/java/com/softropic/payam/security/api/ApiAdvice.java

key-decisions:
  - "MTN callback via PUT not POST: MtnCallbackController is @PutMapping; POST returns 405 via ApiAdvice.methodNotSupportedHandler"
  - "MTN status string is SUCCESSFUL (single-L): MtnStatusMapper.toInternal() only recognises single-L; double-L is Orange's spelling"
  - "Orange status string is SUCCESSFULL (double-L): OrangeStatusMapper.toInternal() only recognises double-L"
  - "WebhookReplayProtectionE2ETest extends AbstractPayamE2ETest directly: does not use AbstractWebhookFlowTest 4-phase template because two-call structure requires a flat @Test method"
  - "MtnPutCallbackAcceptanceE2ETest sends POST before PUT: POST is the negative assertion (405); PUT is the positive path (200 + double-check)"

patterns-established:
  - "JDBC seed for double-check tests: no payment API call needed — insert PROCESSING row with correct provider_ref/pay_token, dispatch callback, wait for double-check via Awaitility"
  - "noErrorRestTemplate for non-2xx assertions: disable DefaultResponseErrorHandler to assert 4xx/5xx status codes without Spring throwing exceptions"

# Metrics
duration: 13min
completed: 2026-03-27
---

# Phase 21 Plan 01: Webhook Flow Tests Summary

**Four inbound webhook E2E tests covering MTN double-check, Orange double-check, Redis replay protection for both providers, and MTN HTTP method acceptance — all 5 test methods passing.**

## Performance

- **Duration:** 13 min
- **Started:** 2026-03-27T21:52:26Z
- **Completed:** 2026-03-27T22:05:38Z
- **Tasks:** 2
- **Files modified:** 4 created, 1 modified

## Accomplishments

- Created `src/test/java/com/softropic/payam/e2e/webhook/` package with four test classes
- MtnWebhookDoubleCheckE2ETest (FLOWS-HOOK-01): seeds PROCESSING MTN row via JDBC, dispatches PUT callback, asserts SUCCESS state with balanced ledger and PROVIDER_SUCCESS event after Awaitility wait
- OrangeWebhookDoubleCheckE2ETest (FLOWS-HOOK-02): seeds PROCESSING Orange row with pay_token set, dispatches POST callback correlated by payToken (not transactionId), asserts Orange SUCCESSFULL (double-L) status parsed correctly
- WebhookReplayProtectionE2ETest (FLOWS-HOOK-03): two @Test methods — MTN and Orange — each sending identical callbacks twice; asserts dedup key present in Redis after first call and PROVIDER_*/PROVIDER_FAILED count stays at 1 after second call
- MtnPutCallbackAcceptanceE2ETest (FLOWS-HOOK-06): sends POST first (asserts 405) then PUT (asserts 200) to the same transaction; verifies PUT drives double-check to SUCCESS

## Task Commits

Each task was committed atomically:

1. **Task 1: MtnWebhookDoubleCheckE2ETest and OrangeWebhookDoubleCheckE2ETest** - `5e2c0ec` (feat)
2. **Task 2: WebhookReplayProtectionE2ETest, MtnPutCallbackAcceptanceE2ETest, ApiAdvice bug fix** - `998afec` (feat)

**Plan metadata:** (docs commit — see below)

## Files Created/Modified

- `src/test/java/com/softropic/payam/e2e/webhook/MtnWebhookDoubleCheckE2ETest.java` — FLOWS-HOOK-01: PUT callback with JDBC-seeded PROCESSING row, Awaitility wait for double-check
- `src/test/java/com/softropic/payam/e2e/webhook/OrangeWebhookDoubleCheckE2ETest.java` — FLOWS-HOOK-02: POST callback correlated by payToken, Orange SUCCESSFULL (double-L) stub
- `src/test/java/com/softropic/payam/e2e/webhook/WebhookReplayProtectionE2ETest.java` — FLOWS-HOOK-03: two @Test methods, MTN and Orange replay protection verified via Redis dedup key + event count assertion
- `src/test/java/com/softropic/payam/e2e/webhook/MtnPutCallbackAcceptanceE2ETest.java` — FLOWS-HOOK-06: POST returns 405, PUT returns 200 and processes correctly
- `src/main/java/com/softropic/payam/security/api/ApiAdvice.java` — Bug fix: added HttpRequestMethodNotSupportedException handler mapped to METHOD_NOT_ALLOWED

## Decisions Made

- WebhookReplayProtectionE2ETest extends AbstractPayamE2ETest directly rather than AbstractWebhookFlowTest because the replay scenario requires two callback calls in a single @Test method — the AbstractWebhookFlowTest 4-phase template would structure only one callback dispatch per test
- Both calls in each @Test method of WebhookReplayProtectionE2ETest are in the same method body to guarantee no Redis flush between them (baseSetUp() only runs once before the test)
- noErrorRestTemplate (DefaultResponseErrorHandler that always returns false) used in MtnPutCallbackAcceptanceE2ETest to capture the 405 response without Spring throwing HttpClientErrorException

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] ApiAdvice missing HttpRequestMethodNotSupportedException handler returns 500 instead of 405**

- **Found during:** Task 2 (MtnPutCallbackAcceptanceE2ETest verification)
- **Issue:** `ApiAdvice`'s catch-all `@ExceptionHandler(Throwable.class)` intercepted `HttpRequestMethodNotSupportedException` before Spring's built-in 405 response could be produced, returning 500 INTERNAL_SERVER_ERROR
- **Fix:** Added `@ExceptionHandler(HttpRequestMethodNotSupportedException.class)` handler with `@ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)` to `ApiAdvice`. Import for the exception class was already present (apparently added by formatter)
- **Files modified:** `src/main/java/com/softropic/payam/security/api/ApiAdvice.java`
- **Commit:** `998afec`

## Next Phase Readiness

- Phase 21 plan 01 complete — 4 of 4 inbound webhook tests implemented, 5 of 5 test methods passing
- Phase 21 plan 02 (outbound webhook tests: FLOWS-HOOK-04 HMAC delivery, FLOWS-HOOK-05 retry scheduling) can begin immediately
- OutboundWebhookDeliveryE2ETest requires a third `tenant-wh` WireMock server — must be standalone class (not extending AbstractPayamE2ETest) per RESEARCH pitfall 7

---
*Phase: 21-webhook-flow-tests*
*Completed: 2026-03-27*
