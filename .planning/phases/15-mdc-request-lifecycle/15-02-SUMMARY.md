---
phase: 15-mdc-request-lifecycle
plan: "02"
subsystem: payments
tags: [mdc, logging, slf4j, micrometer-tracing, otel]

# Dependency graph
requires:
  - phase: 14-logging-infrastructure
    provides: JSON stdout pipeline with <mdc/> provider flattening MDC keys as top-level JSON fields
  - phase: 15-mdc-request-lifecycle (plan 01)
    provides: requestId and tenantId in MDC for every request thread
provides:
  - camelCase MDC keys transactionId and externalReference in every log line within a payment request thread
  - Constants.TXN_ID_NAME aligned to the canonical "transactionId" MDC key
affects: [16-business-events, future log queries in Loki, TransactionIdProvider consumers]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "MDC key naming: camelCase for all application-owned MDC fields (transactionId, externalReference, requestId, tenantId)"
    - "OTel MDC keys (traceId, spanId) are owned by micrometer-tracing-bridge-otel — never override via manual MDC.put"

key-files:
  created: []
  modified:
    - src/main/java/com/softropic/payam/transaction/service/TransactionService.java
    - src/main/java/com/softropic/payam/common/Constants.java

key-decisions:
  - "Do not call MDC.put(\"traceId\", ...) in application code — micrometer-tracing-bridge-otel injects this automatically via <mdc/> provider; manual put was redundant and used conflicting snake_case key"
  - "TXN_ID_NAME updated from \"txnId\" to \"transactionId\" — old value never matched what TransactionService actually wrote to MDC"
  - "TransactionIdProvider (reads/writes/removes TXN_ID_NAME) now operates on the correct \"transactionId\" key, aligning fallback transaction generation with TransactionService primary writes"

patterns-established:
  - "camelCase MDC contract: application MDC keys must be camelCase; grep for snake_case MDC keys is a valid lint check"
  - "Constants.TXN_ID_NAME as single source of truth for the canonical transaction MDC key"

# Metrics
duration: 6min
completed: 2026-03-26
---

# Phase 15 Plan 02: MDC camelCase Rename Summary

**snake_case MDC keys transaction_id/external_reference renamed to camelCase transactionId/externalReference in TransactionService, with Constants.TXN_ID_NAME updated from "txnId" to "transactionId" for full consistency**

## Performance

- **Duration:** 6 min
- **Started:** 2026-03-26T23:08:01Z
- **Completed:** 2026-03-26T23:13:57Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments
- TransactionService.initiate() now sets MDC with `transactionId` and `externalReference` (camelCase), satisfying LOG-MDC-02
- Removed the manual `MDC.put("trace_id", traceId)` call — micrometer-tracing-bridge-otel injects `traceId` (camelCase) automatically for every active OTel span
- `Constants.TXN_ID_NAME` updated from `"txnId"` to `"transactionId"`, aligning TransactionIdProvider's MDC operations with the key TransactionService actually sets

## Task Commits

Each task was committed atomically:

1. **Task 1: Rename snake_case MDC keys to camelCase in TransactionService** - `35c0494` (feat)
2. **Task 2: Update Constants.TXN_ID_NAME to reflect camelCase canonical key** - `12c26e1` (feat)
3. **Stale comment fixup in TransactionService** - `bb822c9` (style)

**Plan metadata:** _(docs commit follows)_

## Files Created/Modified
- `src/main/java/com/softropic/payam/transaction/service/TransactionService.java` - camelCase MDC keys, removed trace_id put, updated inline comment
- `src/main/java/com/softropic/payam/common/Constants.java` - TXN_ID_NAME "txnId" → "transactionId"

## Decisions Made
- **Remove MDC.put("trace_id", ...):** micrometer-tracing-bridge-otel already writes `traceId` (camelCase) for the active OTel span to MDC via the `<mdc/>` provider configured in Phase 14. The manual put (a) used the wrong snake_case key and (b) was redundant with an active span. The `traceId` local variable is still captured for the Transaction entity's database column — only the MDC.put was removed.
- **TXN_ID_NAME alignment:** The old value `"txnId"` was misaligned — TransactionService was writing `"transaction_id"` (now `"transactionId"`) while TransactionIdProvider was reading/writing `"txnId"`. Both now converge on `"transactionId"`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Stale inline comment referenced old snake_case key name**
- **Found during:** Final verification (grep check 2)
- **Issue:** Comment on line 37 said `// fallback: use transaction_id if no active span` — the `transaction_id` reference was stale after the MDC key rename
- **Fix:** Updated comment to `transactionId` to match renamed key
- **Files modified:** src/main/java/com/softropic/payam/transaction/service/TransactionService.java
- **Verification:** grep for `transaction_id` in file now returns nothing
- **Committed in:** `bb822c9` (style commit)

---

**Total deviations:** 1 auto-fixed (Rule 1 - stale comment)
**Impact on plan:** Cosmetic-only fix. No behavior change. Prevents future confusion about the fallback variable name.

## Issues Encountered
None — both source files matched the plan's documented "current" state exactly.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- LOG-MDC-02 complete: every payment request thread carries `transactionId` and `externalReference` as top-level JSON fields in Loki
- Phase 16 (business events) can reference MDC key `transactionId` directly in log statements without any additional setup
- No blockers

---
*Phase: 15-mdc-request-lifecycle*
*Completed: 2026-03-26*
