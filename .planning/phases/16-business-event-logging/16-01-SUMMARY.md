---
phase: 16-business-event-logging
plan: 01
subsystem: payments
tags: [slf4j, logstash-logback-encoder, structured-logging, kv, loki, fraud, observability]

# Dependency graph
requires:
  - phase: 14-logging-infrastructure
    provides: logstash-logback-encoder with kv() pattern and JSON log pipeline
  - phase: 15-mdc-request-lifecycle
    provides: MDC tenantId (tenantRef UUID) and transactionId populated on every request thread

provides:
  - LOG-BUS-01 initiate_payment structured event on success and all 4 failure branches in PaymentOrchestrator
  - LOG-BUS-05 fraud_evaluation structured event on all 3 return paths in FraudScoringService
  - msisdnLast4() privacy helper in PaymentOrchestrator

affects:
  - 16-02 (transaction state change, webhook, provider adapter logs — same kv() pattern)
  - Loki queries by transactionId, provider, tenantId for payment tracing
  - Grafana dashboards consuming riskScore and blocked fields

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "LOG-BUS pattern: log.info/warn with kv(operation,...) variadic args; all queryable fields as kv(), none in message string"
    - "durationMs timing: long start = System.currentTimeMillis() as first statement of method; durationMs = System.currentTimeMillis() - start at each return path"
    - "MSISDN masking: private static msisdnLast4() returns last 4 digits or **** for null/short"
    - "tenantId field in log events uses TenantContext.get() (UUID tenantRef), not Long DB PK parameter"

key-files:
  created: []
  modified:
    - src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java
    - src/main/java/com/softropic/payam/fraud/service/FraudScoringService.java

key-decisions:
  - "LOG-BUS-01 covers success + 5 log sites (fraud blocked, circuit open, subscriber inactive, HTTP error, generic exception) — 6 total kv() calls with operation=initiate_payment"
  - "Unknown MSISDN prefix path and idempotency replay path intentionally excluded from LOG-BUS-01: no transactionId available on the former, replay is not a new initiation"
  - "LOG-BUS-05 allowed path upgraded from DEBUG to INFO: fraud evaluation results must be visible in production INFO logs for every payment"
  - "start timer declared before velocity checks in evaluate() so durationMs includes Redis lookups, not just scoring"

patterns-established:
  - "Business event log: log.info/warn(human message, kv(operation,...), kv(transactionId,...), kv(durationMs,...), kv(status,...)) — all queryable fields as named kv() args"
  - "Error branch structured log: same kv() set plus kv(errorCode,...) for FAILED status events"
  - "Exception log: log.error() with kv() args AND exception as final positional arg for stack trace"

# Metrics
duration: 6min
completed: 2026-03-27
---

# Phase 16 Plan 01: Business Event Logging (Payment + Fraud) Summary

**kv()-structured initiate_payment and fraud_evaluation events added covering all success and failure paths, enabling Loki queries by transactionId, provider, tenantId, riskScore, and blocked across every payment**

## Performance

- **Duration:** ~6 min
- **Started:** 2026-03-26T23:52:47Z
- **Completed:** 2026-03-27T00:58:02Z
- **Tasks:** 2/2
- **Files modified:** 2

## Accomplishments

- LOG-BUS-01: PaymentOrchestrator.initiate() emits structured `initiate_payment` event on the success path and all 5 failure branches (fraud blocked, circuit open, subscriber inactive, HTTP error, generic exception) with tenantId (UUID), transactionId, provider, msisdn (last 4), durationMs, status, and errorCode on failures
- LOG-BUS-05: FraudScoringService.evaluate() emits structured `fraud_evaluation` event on all 3 return paths — WARN for both block paths, INFO (upgraded from DEBUG) for the allow path — with transactionId, riskScore, blocked, and durationMs
- Private `msisdnLast4()` helper added to PaymentOrchestrator for PII-safe MSISDN masking

## Task Commits

Each task was committed atomically:

1. **Task 1: LOG-BUS-01 — Payment initiation structured log in PaymentOrchestrator** - `022b177` (feat)
2. **Task 2: LOG-BUS-05 — Fraud evaluation structured log in FraudScoringService** - `bebfd4d` (feat)

## Files Created/Modified

- `src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java` — Added kv() import, TenantContext import, start timer, msisdnLast4() helper, and 6 structured log calls replacing 5 old free-text logs
- `src/main/java/com/softropic/payam/fraud/service/FraudScoringService.java` — Added kv() import, start timer, and 3 structured log calls replacing 2 warn + 1 debug

## Decisions Made

- **tenantId field uses TenantContext.get() (UUID tenantRef):** Consistent with MDC decision 15-01. The method receives `Long tenantId` but the log event field uses the UUID string from TenantContext — the Loki-queryable tenant identifier matches the MDC value.
- **Unknown MSISDN prefix and idempotency replay paths excluded:** The unknown prefix path returns before `tx` or `provider` are resolved (no transactionId). The replay path is not a new initiation. Neither is in scope for LOG-BUS-01.
- **Fraud allowed path upgraded DEBUG → INFO:** Every payment passes through fraud evaluation. At DEBUG level these events are invisible in production. INFO makes fraud allow/block decisions traceable for every transaction.
- **start timer before velocity checks:** Declared as the first line of `evaluate()` so durationMs includes all 4 Redis velocity lookups plus scoring computation — the full fraud evaluation cost.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- LOG-BUS-01 and LOG-BUS-05 complete. Every payment is now queryable end-to-end: Loki label filters on `transactionId` will return both the initiate_payment event and the fraud_evaluation event.
- Plan 16-02 covers the remaining 5 log requirements: LOG-BUS-02 (transaction state changes), LOG-BUS-03 (inbound webhooks), LOG-BUS-04 (outbound webhook delivery), LOG-BUS-06 (provider adapter HTTP calls), LOG-BUS-07 (reconciliation run).
- No blockers.

---
*Phase: 16-business-event-logging*
*Completed: 2026-03-27*
