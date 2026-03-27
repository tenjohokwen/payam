---
phase: 16-business-event-logging
plan: 03
subsystem: payments
tags: [logstash, structured-logging, webhook, mtn, orange, kv]

# Dependency graph
requires:
  - phase: 16-01
    provides: "LOG-BUS-01/05 payment initiation and fraud scoring kv() patterns established"
  - phase: 16-02
    provides: "LOG-BUS-02/06 payment completion and status poller kv() patterns established"
provides:
  - "LOG-BUS-03: MtnMoMoPort.processCallback() emits kv webhook_received event (provider=MTN)"
  - "LOG-BUS-03: OrangeMoneyPort.processWebhook() emits kv webhook_received event (provider=ORANGE) inside present-branch where txId is available"
  - "LOG-BUS-04: WebhookDeliveryService.attemptDeliveryInternal() emits kv webhook_delivery on all 4 outcome paths with durationMs timer"
affects: [16-04-code-standards, future-observability]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "kv() structured log pattern applied to inbound webhook receipt (both providers)"
    - "durationMs timer placed before try block to cover all HTTP outcome paths"
    - "txId-gated log placement: Orange webhook_received log inside present-branch lambda where txId is resolved, not at method entry"
    - "httpStatus=-1 convention for network errors with no HTTP response"
    - "tenant.getTenantRef() for tenantId field (UUID string, consistent with MDC decision 15-01)"

key-files:
  created: []
  modified:
    - "src/main/java/com/softropic/payam/mtn/service/MtnMoMoPort.java"
    - "src/main/java/com/softropic/payam/orange/service/OrangeMoneyPort.java"
    - "src/main/java/com/softropic/payam/webhook/service/WebhookDeliveryService.java"

key-decisions:
  - "[16-03] Orange webhook_received log placed inside present-branch lambda (not at processWebhook entry): txId is only available after transactionRepository.findByPayToken() resolves; log emitted there to include transactionId in structured event"
  - "[16-03] MTN externalReference field uses payload.getFinancialTransactionId() (may be null at callback time): null is valid JSON, Loki omits null fields — no null check needed in log call"
  - "[16-03] deliveryStart timer declared before try block in attemptDeliveryInternal: covers all 4 outcome paths (success, non-2xx, HttpStatusCodeException, generic Exception)"
  - "[16-03] httpStatus=-1 for network errors (generic Exception catch): signals no HTTP response received, consistent with delivery.setHttpStatus() only called on actual responses"

patterns-established:
  - "txId-gated log: when txId is only available inside a lambda, emit the structured log inside that lambda — do not defer or hoist"
  - "Duration timer before try: place deliveryStart (or equivalent) before the try block to ensure all catch branches can compute durationMs"

# Metrics
duration: 8min
completed: 2026-03-27
---

# Phase 16 Plan 03: Webhook Receipt and Delivery Business Event Logs Summary

**kv() structured logs for inbound MTN/Orange webhook receipt (LOG-BUS-03) and outbound webhook delivery with HTTP duration timer (LOG-BUS-04), completing the provider feedback loop in Loki**

## Performance

- **Duration:** 8 min
- **Started:** 2026-03-27T00:00:00Z
- **Completed:** 2026-03-27T00:08:00Z
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments

- MtnMoMoPort.processCallback() now emits `kv("operation", "webhook_received")` with provider=MTN, transactionId (payload.getExternalId()), externalReference (financialTransactionId, may be null), and providerStatus — replacing the prior string-interpolation log
- OrangeMoneyPort.processWebhook() now emits `kv("operation", "webhook_received")` with provider=ORANGE, transactionId, externalReference (tx.getExternalReference()), and providerStatus — log is inside the present-branch lambda where txId is resolved; the old top-level log.info was removed
- WebhookDeliveryService.attemptDeliveryInternal() now emits `kv("operation", "webhook_delivery")` on all 4 outcome paths with transactionId, tenantId (UUID), durationMs, httpStatus, status (SUCCESS/FAILED), and retryCount — replacing all 4 prior string-interpolation calls; deliveryStart timer added before the try block

## Task Commits

Each task was committed atomically:

1. **Task 1: LOG-BUS-03 — Inbound webhook receipt logs in MtnMoMoPort and OrangeMoneyPort** - `f330f41` (feat)
2. **Task 2: LOG-BUS-04 — Outbound webhook delivery structured log in WebhookDeliveryService** - `9475a49` (feat)

**Plan metadata:** (docs commit below)

## Files Created/Modified

- `src/main/java/com/softropic/payam/mtn/service/MtnMoMoPort.java` — Added kv import; replaced string-interpolation processCallback log with webhook_received structured event
- `src/main/java/com/softropic/payam/orange/service/OrangeMoneyPort.java` — Added kv import; removed top-level log.info; added webhook_received structured event inside present-branch lambda
- `src/main/java/com/softropic/payam/webhook/service/WebhookDeliveryService.java` — Added kv import; added deliveryStart timer; replaced 4 log calls with webhook_delivery structured events

## Decisions Made

- **Orange webhook_received inside lambda:** The existing code had a top-level `log.info("Orange webhook received: ...")` at processWebhook entry, before the repository lookup. This was replaced by a structured kv() log inside the present-branch lambda — txId is only available there, and transactionId is a required field of the event. The not-found WARN in the else-branch remains unchanged.
- **MTN externalReference = financialTransactionId (nullable):** payload.getFinancialTransactionId() may be null at callback time (checked separately for persistence). Passed as-is — null JSON fields are omitted by Loki, and no conditional logging is needed.
- **deliveryStart before try block:** Declared at the top of the HTTP delivery section so all 4 catch paths (success, non-2xx, HttpStatusCodeException, generic Exception) can compute durationMs without requiring duplication.
- **httpStatus=-1 for network errors:** The generic Exception catch has no HTTP response; -1 is used as a sentinel value consistent with the existing pattern where `delivery.setHttpStatus()` is only called on actual HTTP responses.
- **tenantId = tenant.getTenantRef() (UUID):** Consistent with MDC decision 15-01 and LOG-BUS-01 decision. The `delivery.getTenantId()` Long PK is not used for logging.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None. The `.mvnw` wrapper was missing `.mvn/wrapper/` directory, so the system `mvn` was used instead — same Maven version, same outcome.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- LOG-BUS-03 and LOG-BUS-04 complete — all 6 business event log operations (LOG-BUS-01 through LOG-BUS-06) are now instrumented
- Phase 16 execution plans (01, 02, 03) are complete; if plan 16-02 exists and is done, phase 16 is complete
- Loki can now query the full provider feedback loop: payment initiation → fraud evaluation → provider polling → inbound webhook receipt → outbound webhook delivery

---
*Phase: 16-business-event-logging*
*Completed: 2026-03-27*
