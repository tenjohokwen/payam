---
phase: 37-webhook-subsystem-fixes
plan: 03
subsystem: payments
tags: [webhook, resttemplate, timeout, spring, junit5, reflection]

# Dependency graph
requires:
  - phase: 37-webhook-subsystem-fixes
    provides: WebhookConfig.noRetryRestTemplate bean with SimpleClientHttpRequestFactory
provides:
  - WebhookConfig with explicit 5s connect / 10s read timeouts (WEBHOOK-03)
  - WebhookConfigTest unit test verifying timeout values via reflection
affects: [37-webhook-subsystem-fixes]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "SimpleClientHttpRequestFactory timeout configuration: setConnectTimeout/setReadTimeout accept int milliseconds (no Duration overload)"
    - "Unit test reflection pattern for private int fields: getDeclaredField + setAccessible for SimpleClientHttpRequestFactory (no public getters)"

key-files:
  created:
    - src/test/java/com/softropic/payam/webhook/config/WebhookConfigTest.java
  modified:
    - src/main/java/com/softropic/payam/webhook/config/WebhookConfig.java

key-decisions:
  - "CONNECT_TIMEOUT_MS = 5_000 ms, READ_TIMEOUT_MS = 10_000 ms — satisfies WEBHOOK-03 (connect ≤5s, read ≤10s)"
  - "Reflection on private fields connectTimeout/readTimeout in WebhookConfigTest — SimpleClientHttpRequestFactory has no public getters"
  - "Named constants (not inline literals) for timeout values — deliberate change requires updating test explicitly"

patterns-established:
  - "WEBHOOK-03: Explicit timeout constants on SimpleClientHttpRequestFactory before RestTemplate construction"

requirements-completed: [WEBHOOK-03]

# Metrics
duration: 8min
completed: 2026-04-14
---

# Phase 37 Plan 03: WebhookConfig Timeout Fix Summary

**Eliminated infinite Quartz thread hold risk by adding 5s connect / 10s read timeouts to WebhookConfig.noRetryRestTemplate via SimpleClientHttpRequestFactory (WEBHOOK-03)**

## Performance

- **Duration:** 8 min
- **Started:** 2026-04-14T00:00:00Z
- **Completed:** 2026-04-14T00:08:00Z
- **Tasks:** 1 (TDD: RED + GREEN commits)
- **Files modified:** 2

## Accomplishments
- WebhookConfig.noRetryRestTemplate now configures SimpleClientHttpRequestFactory with connectTimeout=5000ms and readTimeout=10000ms before passing to RestTemplate constructor
- Eliminated default-infinite (0/-1) timeouts that allowed a hung tenant endpoint to hold a Quartz delivery thread indefinitely
- WebhookConfigTest unit test (no Spring context) verifies both timeout values via reflection on private int fields

## Task Commits

Each task was committed atomically (TDD flow):

1. **RED — Failing test** - `4923a26` (test)
2. **GREEN — WebhookConfig fix** - `c7bddfd` (feat)

## Files Created/Modified
- `src/main/java/com/softropic/payam/webhook/config/WebhookConfig.java` - Added CONNECT_TIMEOUT_MS=5000 and READ_TIMEOUT_MS=10000 constants; factory.setConnectTimeout/setReadTimeout called before RestTemplate construction
- `src/test/java/com/softropic/payam/webhook/config/WebhookConfigTest.java` - New unit test: instantiates WebhookConfig directly, reflects on private connectTimeout/readTimeout fields of SimpleClientHttpRequestFactory, pins exact values 5000/10000

## Decisions Made
- Using named constants (not inline literals) so any deliberate timeout change forces a corresponding test update — makes intent visible in version history
- Reflection pattern for SimpleClientHttpRequestFactory private fields: `getDeclaredField("connectTimeout").setAccessible(true)` — the only way to assert these values since no public getters exist on this class

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None — straightforward two-file change with TDD cycle.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- WEBHOOK-03 complete: Quartz delivery threads are now bounded to ≤10s per delivery attempt
- Remaining plans in phase 37 (37-01, 37-02) address N+1 query and post-commit enqueue issues independently

---
*Phase: 37-webhook-subsystem-fixes*
*Completed: 2026-04-14*
