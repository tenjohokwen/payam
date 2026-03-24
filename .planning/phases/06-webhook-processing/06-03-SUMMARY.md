---
phase: 06-webhook-processing
plan: 03
subsystem: payments
tags: [quartz, hmac-sha256, webhook, rest, jpa, wiremock, spring-data-jpa]

# Dependency graph
requires:
  - phase: 06-01
    provides: OrangeCallbackController, MtnCallbackController, inbound webhook pipeline, RedisDedup
  - phase: 06-02
    provides: WebhookDoubleCheckHandler, WebhookTransitionService, applyFinalTransition with state machine transitions
provides:
  - V9 migration: main.webhook_delivery_log table with external_reference column and retry tracking
  - WebhookDeliveryLog entity (mutable, NOT @Immutable, public setters for delivery state)
  - WebhookDeliveryLogRepository with findPendingForRetry query
  - OutboundWebhookPayload record echoing externalReference to tenant
  - WebhookDeliveryService: enqueue() + immediate first attempt + exponential-backoff retry scheduling
  - WebhookDeliveryJob (QuartzJobBean, every 1 min, picks up nextRetryAt-eligible rows)
  - WebhookSchedulerConfig (Quartz trigger, 1-minute repeat)
  - WebhookConfig: noRetryRestTemplate bean (SimpleClientHttpRequestFactory, no auto-retry)
  - WebhookDeliveryResource: GET /v1/webhooks/deliveries/{transactionId}
  - WebhookDoubleCheckHandler -> WebhookTransitionService now calls webhookDeliveryService.enqueue() after terminal state transition
affects: [07-reporting, 09-phase-hardening]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "enqueue-then-attempt pattern: delivery row inserted first (for durability), then HTTP attempt made inline; nextRetryAt null on INSERT to prevent Quartz race condition on the just-inserted row"
    - "javax.crypto.Mac HmacSHA256 for outbound webhook signing — NOT DigestUtils.sha256Hex (plain SHA-256); signature format: sha256=<64 hex chars> in X-Payam-Signature header"
    - "noRetryRestTemplate (@Qualifier) pattern for webhook HTTP calls — prevents Apache HTTP Client 5 auto-retry from masking failures"
    - "HttpStatusCodeException caught before generic Exception to capture httpStatus from HTTP error responses"

key-files:
  created:
    - src/main/resources/db/migration/V9__webhook_delivery_log.sql
    - src/main/java/com/softropic/payam/webhook/repo/WebhookDeliveryLog.java
    - src/main/java/com/softropic/payam/webhook/repo/WebhookDeliveryLogRepository.java
    - src/main/java/com/softropic/payam/webhook/contract/OutboundWebhookPayload.java
    - src/main/java/com/softropic/payam/webhook/config/WebhookConfig.java
    - src/main/java/com/softropic/payam/webhook/config/WebhookSchedulerConfig.java
    - src/main/java/com/softropic/payam/webhook/service/WebhookDeliveryService.java
    - src/main/java/com/softropic/payam/webhook/service/WebhookDeliveryJob.java
    - src/main/java/com/softropic/payam/webhook/api/WebhookDeliveryResource.java
    - src/test/java/com/softropic/payam/webhook/WebhookDeliveryIT.java
  modified:
    - src/main/java/com/softropic/payam/webhook/service/WebhookTransitionService.java
    - src/test/java/com/softropic/payam/config/TestConfig.java

key-decisions:
  - "enqueue() sets nextRetryAt=null on INSERT — findPendingForRetry uses WHERE nextRetryAt <= :now, so null rows are not matched by Quartz job; prevents race condition between inline first attempt and Quartz pickup of the just-inserted row"
  - "attemptDeliveryInternal() extracted as private method — shared between enqueue() inline delivery and Quartz attemptDelivery() retries; avoids code duplication and @Transactional self-invocation issues"
  - "WebhookConfig.noRetryRestTemplate @Bean in main config — available to WebhookDeliveryService via @Qualifier; TestConfig.restTemplate @Primary to resolve NoUniqueBeanDefinitionException when both beans exist in test context"
  - "HttpStatusCodeException caught before generic Exception in attemptDeliveryInternal — captures httpStatus code from HTTP error responses for queryable failure tracking"
  - "Quartz WebhookDeliveryJob fires every 1 minute (repeatMinutelyForever(1)) — handles retries of failed deliveries; inline first attempt in enqueue() ensures prompt delivery without waiting for Quartz"

patterns-established:
  - "outbound-webhook pattern: enqueue() for durability + inline attempt + Quartz retry job for failed rows"
  - "HMAC-SHA256 signing: javax.crypto.Mac with HmacSHA256 algorithm; sign serialized JSON payload; set X-Payam-Signature header as sha256=<hex>"

# Metrics
duration: 25min
completed: 2026-03-24
---

# Phase 6 Plan 03: Outbound Webhook Delivery Summary

**Signed outbound webhook delivery pipeline with javax.crypto.Mac HmacSHA256, Quartz JDBC retry, and GET /v1/webhooks/deliveries/{transactionId} status endpoint**

## Performance

- **Duration:** 25 min
- **Started:** 2026-03-24T09:41:21Z
- **Completed:** 2026-03-24T10:06:51Z
- **Tasks:** 2
- **Files modified:** 12

## Accomplishments
- After SUCCESS/FAILED state transition, tenant webhook URL receives signed HTTP POST with OutboundWebhookPayload (echoes externalReference, correct HMAC-SHA256 algorithm)
- Failed deliveries scheduled for exponential-backoff retry via Quartz JDBC job (survives JVM restart); inline first attempt happens promptly without waiting for Quartz 1-min fire
- GET /v1/webhooks/deliveries/{transactionId} returns delivery log with attempt count, HTTP status, delivered flag, retry schedule

## Task Commits

Each task was committed atomically:

1. **Task 1: V9 migration + WebhookDeliveryLog entity + OutboundWebhookPayload + WebhookDeliveryService** - `7b69313` (feat)
2. **Task 2: WebhookDeliveryJob + WebhookDeliveryResource + wire into DoubleCheckHandler + IT test** - `ebb872f` (feat)

**Plan metadata:** (docs commit follows)

## Files Created/Modified
- `src/main/resources/db/migration/V9__webhook_delivery_log.sql` - webhook_delivery_log table with external_reference, delivered, next_retry_at columns
- `src/main/java/com/softropic/payam/webhook/repo/WebhookDeliveryLog.java` - Mutable JPA entity tracking delivery attempts (NOT @Immutable)
- `src/main/java/com/softropic/payam/webhook/repo/WebhookDeliveryLogRepository.java` - findByTransactionId + findPendingForRetry(now, maxAttempts) JPQL query
- `src/main/java/com/softropic/payam/webhook/contract/OutboundWebhookPayload.java` - Record with transactionId, status, eventType, timestamp, externalReference
- `src/main/java/com/softropic/payam/webhook/config/WebhookConfig.java` - noRetryRestTemplate bean (SimpleClientHttpRequestFactory)
- `src/main/java/com/softropic/payam/webhook/config/WebhookSchedulerConfig.java` - Quartz job + trigger (1 min interval)
- `src/main/java/com/softropic/payam/webhook/service/WebhookDeliveryService.java` - enqueue() + attemptDeliveryInternal() + scheduleRetry() + findPendingDeliveries()
- `src/main/java/com/softropic/payam/webhook/service/WebhookDeliveryJob.java` - QuartzJobBean extension, @Transactional executeInternal
- `src/main/java/com/softropic/payam/webhook/api/WebhookDeliveryResource.java` - GET /v1/webhooks/deliveries/{transactionId}
- `src/main/java/com/softropic/payam/webhook/service/WebhookTransitionService.java` - Added WebhookDeliveryService injection + enqueue() call after terminal transition
- `src/test/java/com/softropic/payam/webhook/WebhookDeliveryIT.java` - 3 IT tests (HMAC verification, retry-on-503, delivery status query)
- `src/test/java/com/softropic/payam/config/TestConfig.java` - Added @Primary to restTemplate bean

## Decisions Made
- `enqueue()` sets `nextRetryAt=null` on INSERT: `findPendingForRetry` uses `WHERE nextRetryAt <= :now`, so null rows are not matched by Quartz job; prevents race condition between the inline first delivery attempt and Quartz pickup of the just-inserted row
- `attemptDeliveryInternal()` extracted as private shared method: called from both `enqueue()` (first attempt) and public `attemptDelivery()` (Quartz retries); avoids code duplication
- `WebhookConfig.noRetryRestTemplate` is a main application `@Bean`; `TestConfig.restTemplate` marked `@Primary` to resolve `NoUniqueBeanDefinitionException` when both exist in test context (`HttpTestClient` has unqualified `@Autowired RestTemplate`)
- `HttpStatusCodeException` caught before generic `Exception` in `attemptDeliveryInternal` to capture HTTP status code from error responses for delivery log querying

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Added @Primary to TestConfig.restTemplate to resolve NoUniqueBeanDefinitionException**
- **Found during:** Task 2 (WebhookDeliveryIT)
- **Issue:** Adding `noRetryRestTemplate` bean (WebhookConfig) created two `RestTemplate` beans; `HttpTestClient` (in security tests) has unqualified `@Autowired RestTemplate` which Spring cannot resolve unambiguously
- **Fix:** Added `@Primary` to `TestConfig.restTemplate` — it wins unqualified injection; `noRetryRestTemplate` is only injected via `@Qualifier("noRetryRestTemplate")`
- **Files modified:** src/test/java/com/softropic/payam/config/TestConfig.java
- **Verification:** ApplicationContext loads, all tests pass
- **Committed in:** ebb872f (Task 2 commit)

**2. [Rule 1 - Bug] Fixed Quartz race condition: nextRetryAt=null on INSERT**
- **Found during:** Task 2 (WebhookDeliveryIT test debugging)
- **Issue:** `enqueue()` was setting `nextRetryAt=Instant.now()` on INSERT, making the row immediately eligible for Quartz pickup. The Quartz job fired at startup, found the row, called `attemptDelivery()` concurrently with `enqueue()`'s inline delivery — last writer's `nextRetryAt` update won, causing `nextRetryAt=null` (from the entity that was saved by the concurrent Quartz path)
- **Fix:** Set `nextRetryAt=null` on INSERT; `findPendingForRetry` query uses `WHERE nextRetryAt <= :now` which excludes null rows; `nextRetryAt` is only set by `scheduleRetry()` after a failed attempt
- **Files modified:** src/main/java/com/softropic/payam/webhook/service/WebhookDeliveryService.java
- **Verification:** Test 2 (`shouldScheduleRetryWhenTenantEndpointReturns503`) passes with correct `nextRetryAt` and `httpStatus=503`
- **Committed in:** ebb872f (Task 2 commit)

**3. [Rule 2 - Missing Critical] Added HttpStatusCodeException catch to capture HTTP error status**
- **Found during:** Task 2 (WebhookDeliveryIT test debugging)
- **Issue:** Generic `Exception` catch did not capture `httpStatus` from HTTP error responses; delivery log showed null `httpStatus` for 4xx/5xx responses
- **Fix:** Added specific `HttpStatusCodeException` catch before generic `Exception` catch; extracts `e.getStatusCode().value()` for `delivery.setHttpStatus()`
- **Files modified:** src/main/java/com/softropic/payam/webhook/service/WebhookDeliveryService.java
- **Verification:** Test 2 asserts `httpStatus == 503` passes
- **Committed in:** ebb872f (Task 2 commit)

---

**Total deviations:** 3 auto-fixed (1 blocking, 1 bug, 1 missing critical)
**Impact on plan:** All auto-fixes necessary for correctness. No scope creep.

## Issues Encountered
- Quartz job fires at startup and immediately queries `findPendingForRetry`; rows with `nextRetryAt=now()` were being found and processed concurrently with `enqueue()`'s inline attempt. Root cause: standard Quartz behavior with `SimpleScheduleBuilder` and no initial delay. Fixed by ensuring `nextRetryAt` is null on INSERT (only set after failed attempt).

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Phase 6 webhook processing is complete: inbound validation, double-check state machine, and outbound tenant notification all implemented and tested
- Phase 7 can reference `WebhookDeliveryService.enqueue()` signature and `webhook_delivery_log` table
- Quartz JDBC store ensures retry durability across JVM restarts

---
*Phase: 06-webhook-processing*
*Completed: 2026-03-24*
