---
phase: 16-business-event-logging
plan: 04
subsystem: payments
tags: [slf4j, logstash-logback-encoder, structured-logging, kv, mtn-momo, orange-money, latency, loki]

# Dependency graph
requires:
  - phase: 14-logging-infrastructure
    provides: LoggingEventCompositeJsonEncoder and kv() structured argument support
  - phase: 16-business-event-logging
    provides: LOG-BUS-01/05 patterns established in 16-01 (PaymentOrchestrator + FraudScoringService)
provides:
  - LOG-BUS-06 structured HTTP call latency events for all 7 MTN MoMo adapter methods
  - LOG-BUS-06 structured HTTP call latency events for all 7 Orange Money adapter methods
  - externalService + operation + externalLatencyMs + status fields queryable in Loki
affects:
  - Grafana dashboard phase (provider SLO dashboards can use externalService/operation as labels)
  - Phase 17 code standards (kv() event placement pattern established here)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "LOG-BUS-06 provider latency pattern: long start before makeHttpRequest(); kv() log.info() immediately after; externalLatencyMs = currentTimeMillis() - start"
    - "validateAccountHolder exception safety: start declared inside try block; log omitted on exception path (not a bug — exception propagates for 404/not-found case)"
    - "cashout/c2c direct-return pattern: log before return, using local result variable instead of inline makeHttpRequest()"

key-files:
  created: []
  modified:
    - src/main/java/com/softropic/payam/mtn/infrastructure/MtnMoMoClient.java
    - src/main/java/com/softropic/payam/orange/infrastructure/OrangeMoneyClient.java

key-decisions:
  - "LOG-BUS-06 co-exists with RestRequestInterceptor log: interceptor is for debugging (raw method/url/status/latency strings), kv() event is for Loki querying — both intentional"
  - "externalLatencyMs scope is makeHttpRequest() only: long start declared immediately before the call, log emitted immediately after the call returns, before any null/status checks"
  - "validateAccountHolder exception path omits log: on HttpClientException (404 path), the log.info() line is never reached — this is acceptable; the exception is the observable signal"

patterns-established:
  - "Provider latency pattern: declare start before makeHttpRequest(), log immediately after it returns, before any conditional throws"
  - "Direct-return methods (cashout, c2c): assign result to local variable, log, then return — cannot inline makeHttpRequest() in return statement when log needs the response"

# Metrics
duration: 10min
completed: 2026-03-27
---

# Phase 16 Plan 04: Provider HTTP Call Latency Logging Summary

**LOG-BUS-06 kv() latency events added to all 14 adapter methods (7 MTN MoMo + 7 Orange Money), enabling Loki queries on externalService/operation for provider SLO dashboards**

## Performance

- **Duration:** ~10 min
- **Started:** 2026-03-27T00:00:49Z
- **Completed:** 2026-03-27T00:10:08Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments

- MtnMoMoClient: Logger field + kv() log.info() events in all 7 public methods (fetchCollectionToken, fetchDisbursementToken, requestToPay, getRequestToPayStatus, validateAccountHolder, getBalance, disburse)
- OrangeMoneyClient: Logger field + kv() log.info() events in all 7 public methods (fetchToken, getSubscriberInfo, getMerchantInfo, pay, getPaymentStatus, cashout, c2c)
- Every event emits externalService, operation, externalLatencyMs (around makeHttpRequest() only), and status (SUCCESS/FAILED)

## Task Commits

Each task was committed atomically:

1. **Task 1: LOG-BUS-06 MTN MoMoClient (7 methods)** - `dda97e2` (feat)
2. **Task 2: LOG-BUS-06 OrangeMoneyClient (7 methods)** - `9c12ae0` (feat)

**Plan metadata:** (docs commit — created after this line)

## Files Created/Modified

- `src/main/java/com/softropic/payam/mtn/infrastructure/MtnMoMoClient.java` — Added Logger field, 7 kv() log.info() events with externalService="MTN_MOMO"
- `src/main/java/com/softropic/payam/orange/infrastructure/OrangeMoneyClient.java` — Added Logger field, 7 kv() log.info() events with externalService="ORANGE_MONEY"

## Decisions Made

- **externalLatencyMs measures makeHttpRequest() only:** `long start` declared immediately before the call, log emitted immediately after it returns, before any null/status check that could throw. This ensures latency reflects only the HTTP round-trip.
- **LOG-BUS-06 co-exists with RestRequestInterceptor log:** The interceptor already logs raw method/url/status strings at INFO. The kv() events are additive — they provide structured, Loki-queryable fields. Both are intentional and serve different purposes.
- **validateAccountHolder exception path omits log:** The `start` variable is declared inside the `try` block. When HttpClientException (404) is thrown by the interceptor before makeHttpRequest() returns, the log.info() line is never reached. This is acceptable — the exception is the signal for inactive account, and the interceptor log captures the HTTP detail.
- **cashout/c2c direct-return pattern:** These methods originally returned `makeHttpRequest(...)` inline. To log before returning, the result is assigned to a local variable `result`, logged, then returned. The method signature is unchanged.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- All 14 provider adapter methods now emit LOG-BUS-06 latency events
- Grafana dashboard authors can filter on `externalService="MTN_MOMO"` or `externalService="ORANGE_MONEY"` and group by `operation` to build provider latency panels
- Phase 16 plans 01 and 04 complete; remaining plans (02, 03 per ROADMAP) can proceed

---
*Phase: 16-business-event-logging*
*Completed: 2026-03-27*
