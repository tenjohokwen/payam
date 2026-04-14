---
phase: 37-webhook-subsystem-fixes
plan: "02"
subsystem: payments
tags: [spring-events, transactional-event-listener, webhook, after-commit, requires-new]

# Dependency graph
requires:
  - phase: 37-webhook-subsystem-fixes
    provides: "37-01: WEBHOOK-01 N+1 fix completed"
provides:
  - "WebhookEnqueueRequestedEvent record for post-commit enqueue decoupling"
  - "WebhookDeliveryService.onEnqueueRequested AFTER_COMMIT+REQUIRES_NEW listener"
  - "WebhookTransitionService now publishes event instead of direct enqueue call"
  - "WebhookEnqueueListenerIT with rollback-isolation + post-commit delivery assertions"
affects: [webhook-delivery-pipeline, webhook-transition-service, e2e-tests]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "@TransactionalEventListener(AFTER_COMMIT) + @Transactional(REQUIRES_NEW) for post-commit side effects — mirrors Phase 32 email pattern"
    - "Exception swallowing in AFTER_COMMIT listeners — delivery failure must not affect already-committed state transition"

key-files:
  created:
    - src/main/java/com/softropic/payam/webhook/contract/WebhookEnqueueRequestedEvent.java
    - src/test/java/com/softropic/payam/webhook/WebhookEnqueueListenerIT.java
  modified:
    - src/main/java/com/softropic/payam/webhook/service/WebhookDeliveryService.java
    - src/main/java/com/softropic/payam/webhook/service/WebhookTransitionService.java

key-decisions:
  - "[37-02] WebhookDeliveryService hosts the listener method (not a separate bean) — consistent with keeping delivery-related logic co-located; Spring allows a bean to consume events it indirectly triggers via collaborator"
  - "[37-02] webhookDeliveryService field kept in WebhookTransitionService despite no longer calling enqueue directly — preserves collaborator documentation and DI wiring unchanged"

patterns-established:
  - "Post-commit side effects via WebhookEnqueueRequestedEvent: publish in REQUIRES_NEW tx, consume in AFTER_COMMIT REQUIRES_NEW listener with swallowed exception"

requirements-completed:
  - WEBHOOK-02

# Metrics
duration: 16min
completed: 2026-04-14
---

# Phase 37 Plan 02: Webhook Subsystem Fixes — Post-Commit Enqueue Decoupling Summary

**AFTER_COMMIT event-driven webhook enqueue using WebhookEnqueueRequestedEvent, decoupling delivery scheduling from the state-transition REQUIRES_NEW transaction and proving rollback isolation via WebhookEnqueueListenerIT.**

## Performance

- **Duration:** 16 min
- **Started:** 2026-04-14T18:47:31Z
- **Completed:** 2026-04-14T18:55:00Z
- **Tasks:** 2
- **Files modified:** 4 (2 created, 2 modified)

## Accomplishments
- Created `WebhookEnqueueRequestedEvent` record carrying all fields needed by `enqueue()` — no transaction entity reload needed in listener
- Switched `WebhookTransitionService.applyFinalTransition()` from direct `webhookDeliveryService.enqueue()` call to `eventPublisher.publishEvent(new WebhookEnqueueRequestedEvent(...))` — enqueue now fires only after REQUIRES_NEW transaction commits
- Added `WebhookDeliveryService.onEnqueueRequested()` with `@TransactionalEventListener(AFTER_COMMIT) + @Transactional(REQUIRES_NEW)` — exception swallowed (enqueue failure cannot affect already-committed state transition)
- Added `WebhookEnqueueListenerIT` with two tests proving: (a) commit path creates delivery log row, (b) rollback path leaves no delivery log row

## Task Commits

Each task was committed atomically:

1. **Task 1: Create event record, add AFTER_COMMIT listener, switch WebhookTransitionService** - `7e85662` (feat)
2. **Task 2: Add WebhookEnqueueListenerIT** - `867166d` (test)

## Files Created/Modified
- `src/main/java/com/softropic/payam/webhook/contract/WebhookEnqueueRequestedEvent.java` - Immutable record carrying transactionId, tenantId, eventType, status, externalReference, feeAmount
- `src/main/java/com/softropic/payam/webhook/service/WebhookDeliveryService.java` - Added `onEnqueueRequested()` AFTER_COMMIT listener + imports for Propagation, TransactionPhase, TransactionalEventListener, WebhookEnqueueRequestedEvent
- `src/main/java/com/softropic/payam/webhook/service/WebhookTransitionService.java` - Added ApplicationEventPublisher field + constructor param; replaced direct enqueue() call with publishEvent()
- `src/test/java/com/softropic/payam/webhook/WebhookEnqueueListenerIT.java` - Two-test IT: commit path and rollback path

## Decisions Made
- WebhookDeliveryService hosts the listener method (not a separate listener bean) — keeps delivery-related logic co-located; consistent with the established fact that beans can consume events their collaborators indirectly trigger
- `webhookDeliveryService` field retained in WebhookTransitionService even though it no longer calls `enqueue()` directly — preserves the collaborator documentation and existing DI wiring without change

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None. All tests passed first time. WebhookDeliveryIT (3 tests) and WebhookEnqueueListenerIT (2 tests) both green.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- WEBHOOK-02 complete: enqueue is now fully decoupled from the state-transition transaction
- 37-03 (WEBHOOK-03: timeout fix on `noRetryRestTemplate`) can proceed independently
- Existing WebhookDeliveryIT still green — end-to-end delivery flows work with the new event path

---
*Phase: 37-webhook-subsystem-fixes*
*Completed: 2026-04-14*
