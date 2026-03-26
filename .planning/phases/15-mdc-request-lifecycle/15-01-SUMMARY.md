---
phase: 15-mdc-request-lifecycle
plan: "01"
subsystem: infra
tags: [mdc, logging, structured-logging, logstash-logback-encoder, slf4j, request-lifecycle]

# Dependency graph
requires:
  - phase: 14-logging-infrastructure
    provides: JSON stdout pipeline with LoggingEventCompositeJsonEncoder and MDC flattening via <mdc/> provider
provides:
  - requestId populated in MDC before first log call for every HTTP request
  - tenantId populated in MDC for API-key-authenticated (/v1/payments) requests
  - request_start structured event at INFO (event, operation, method, path, requestId, clientIp)
  - request_end structured event at INFO (event, operation, durationMs, status, httpStatus, tenantId?)
  - request_error structured event at ERROR for 5xx (event, operation, durationMs, errorCode, status=ERROR)
  - Low-cardinality operation name derivation for Loki label queries
affects: [16-business-events, future Loki query patterns using requestId/tenantId correlation]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "MDC.remove() not MDC.clear(): only remove keys you put — preserves traceId/spanId from micrometer-tracing"
    - "StructuredArguments.kv() for every Loki-queryable field — no string interpolation"
    - "Conditional tenantId in request_end: only present for API-key paths (TenantContext.get() != null)"
    - "Low-cardinality operation derivation in deriveOperation() — no UUIDs, amounts, or per-request data"
    - "Async dispatch early-return: isAsyncDispatch(request) check before any MDC writes"

key-files:
  created: []
  modified:
    - src/main/java/com/softropic/payam/security/audit/filter/LoggingFilter.java
    - src/main/java/com/softropic/payam/tenant/config/ApiKeyAuthenticationFilter.java

key-decisions:
  - "tenantRef (String UUID) used as MDC tenantId value — matches TenantContext which also stores tenantRef"
  - "request_end log uses List<Object> args to conditionally include tenantId only when non-null"
  - "Forwarded header read (not X-Forwarded-For) for clientIp to match existing RequestMetadataProvider pattern"
  - "MDC.remove() in finally of LoggingFilter removes only requestId — tenantId cleanup belongs to ApiKeyAuthenticationFilter"

patterns-established:
  - "Filter pair pattern: LoggingFilter owns requestId MDC, ApiKeyAuthenticationFilter owns tenantId MDC"
  - "Lifecycle event trio: request_start before chain, request_end/request_error after chain based on status"
  - "kv() exclusively: all structured fields in lifecycle events use StructuredArguments.kv()"

# Metrics
duration: 3min
completed: 2026-03-26
---

# Phase 15 Plan 01: MDC & Request Lifecycle Summary

**Structured request lifecycle events (request_start/request_end/request_error) with requestId and tenantId MDC population enabling Loki correlation across all log lines in a request thread**

## Performance

- **Duration:** 3 min
- **Started:** 2026-03-26T23:07:59Z
- **Completed:** 2026-03-26T23:11:40Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments
- LoggingFilter fully rewritten: emits three structured lifecycle events using StructuredArguments.kv(), populates requestId in MDC before the first log call, and removes it in finally without touching traceId/spanId
- ApiKeyAuthenticationFilter extended: MDC.put("tenantId", tenantRef) immediately after TenantContext.set(), MDC.remove("tenantId") in finally — completes LOG-MDC-01
- ContentCachingRequestWrapper, ContentCachingResponseWrapper, BodySanitizer, and all header-dump logging removed from LoggingFilter

## Task Commits

Each task was committed atomically:

1. **Task 1: Rewrite LoggingFilter with structured lifecycle events and requestId MDC** - `9f196d9` (feat)
2. **Task 2: Add tenantId MDC population to ApiKeyAuthenticationFilter** - `9a0ebb8` (feat)

**Plan metadata:** _(pending final commit)_

## Files Created/Modified
- `src/main/java/com/softropic/payam/security/audit/filter/LoggingFilter.java` - Full rewrite: request_start/request_end/request_error structured events, requestId MDC, deriveOperation(), resolveClientIp()
- `src/main/java/com/softropic/payam/tenant/config/ApiKeyAuthenticationFilter.java` - Added MDC.put/remove for tenantId alongside existing TenantContext.set/clear

## Decisions Made
- **tenantRef as MDC value:** The plan says "tenantId in MDC" but the available String identifier is `tenantRef` (the UUID string). `tenantId` is a Long (database PK) that is not the same as the Loki-queryable tenant reference. tenantRef matches what TenantContext stores and is the canonical tenant identifier used across the system.
- **Conditional tenantId in request_end:** Build args as `List<Object>`, add tenantId only when `TenantContext.get() != null`. JWT paths will never have tenantId; API-key paths always will. request_error omits tenantId (error path keeps the event minimal).
- **MDC.remove ownership split:** LoggingFilter owns requestId (set/remove in its own try/finally). ApiKeyAuthenticationFilter owns tenantId (set/remove in its own try/finally). The two filters are independent — no circular dependency.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- LOG-MDC-01, LOG-REQ-01, LOG-REQ-02, LOG-REQ-03 all satisfied
- Every HTTP request now produces structured Loki-queryable lifecycle events
- requestId appears in all log lines for a request (MDC set before chain)
- tenantId appears in all log lines for API-key-authenticated requests
- Phase 16 (Business Events) can add txnId and other business MDC keys using the same MDC.put/remove pattern established here

---
*Phase: 15-mdc-request-lifecycle*
*Completed: 2026-03-26*
