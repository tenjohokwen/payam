---
phase: 23-invariants-concurrency-sm-mutation
plan: "04"
subsystem: testing
tags: [wiremock, concurrency, race-condition, provider-call-count, conc-02, gap-closure]

# Dependency graph
requires:
  - phase: 23-02
    provides: "WebhookPollingRaceTest with financial invariant assertions (1 SUCCESS row, 2 ledger entries)"
provides:
  - "CONC-02 gap fully closed: mtnServer.verify(1, postRequestedFor) asserts exactly 1 outbound provider POST"
affects:
  - "23-VERIFICATION — CONC-02 must-have now satisfied"

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "WireMock postRequestedFor inside Awaitility.untilAsserted — provider call count verified in retry scope alongside DB assertions"

key-files:
  created: []
  modified:
    - "src/test/java/com/softropic/payam/e2e/domain/WebhookPollingRaceTest.java"

key-decisions:
  - "mtnServer.verify(1, ...) placed inside Awaitility block to keep all post-race assertions in a single retry scope"
  - "Assertion targets POST /v1_0/requesttopay (initiation call), not the GET status polling calls — exactly 1 dispatch regardless of race winner"

patterns-established:
  - "CONC-02 gap closure pattern: WireMock verify inside Awaitility untilAsserted for provider call count in race condition tests"

# Metrics
duration: 4min
completed: 2026-03-28
---

# Phase 23 Plan 04: CONC-02 Gap Closure Summary

**WireMock `mtnServer.verify(1, postRequestedFor(...))` assertion added to WebhookPollingRaceTest inside Awaitility block, proving exactly 1 outbound provider POST regardless of webhook/poller race outcome**

## Performance

- **Duration:** 4 min
- **Started:** 2026-03-28T09:29:35Z
- **Completed:** 2026-03-28T09:34:17Z
- **Tasks:** 1
- **Files modified:** 1

## Accomplishments

- Added `import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor` to `WebhookPollingRaceTest`
- Added `mtnServer.verify(1, postRequestedFor(urlPathEqualTo("/v1_0/requesttopay")))` inside the Awaitility block after the `ledgerCount` assertion
- CONC-02 must-have "1 outbound delivery" is now directly asserted via WireMock call count
- Test passes: BUILD SUCCESS, Tests run: 1, Failures: 0, Errors: 0

## Task Commits

Each task was committed atomically:

1. **Task 1: Add mtnServer.verify(1, postRequestedFor) assertion** - `e7a4218` (feat)

**Plan metadata:** (docs commit follows)

## Files Created/Modified

- `src/test/java/com/softropic/payam/e2e/domain/WebhookPollingRaceTest.java` - Added `postRequestedFor` import and `mtnServer.verify(1, ...)` assertion inside Awaitility block

## Decisions Made

- **mtnServer.verify(1, ...) placement inside Awaitility:** The Awaitility `untilAsserted` block already contained all post-race DB assertions. Adding the WireMock verify here keeps all post-race assertions in a single retry scope, consistent with the pattern in `ConcurrentIdempotencyRaceTest` line 158.
- **Target is POST /v1_0/requesttopay (not GET):** The assertion targets the initiation POST call. The race is between webhook callback and poller status check — both are downstream of the single provider dispatch. Verifying call count=1 on the POST proves no double-dispatch.

## Deviations from Plan

None — plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness

- CONC-02 gap is now fully closed: financial invariants (1 SUCCESS row, 2 ledger entries) + 1 outbound provider POST all asserted
- Phase 23 gap closure plans complete (CONC-02 via 23-04, MUT-02 handled as part of 23-03)
- All phases complete — no blockers

---
*Phase: 23-invariants-concurrency-sm-mutation*
*Completed: 2026-03-28*
