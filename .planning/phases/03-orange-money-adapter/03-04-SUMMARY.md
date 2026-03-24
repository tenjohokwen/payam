---
phase: 03-orange-money-adapter
plan: "04"
subsystem: payments
tags: [orange-money, paytoken, poller, quartz, resilience4j, gap-closure]

# Dependency graph
requires:
  - phase: 03-01
    provides: OrangeStatusPollerJob with null-payToken guard and OrangeMoneyPort.assertPayTokenFresh() definition
  - phase: 03-02
    provides: PayTokenExpiredException, OrangeMoneyPort circuit breaker pattern, ignoreExceptions config
  - phase: 03-03
    provides: OrangeWebhookPayload.getCreatetimeAsInstant(), OrangeTimeUtil WAT parsing
provides:
  - assertPayTokenFresh() wired into OrangeStatusPollerJob.pollTransaction() — exactly one production call site
  - PayTokenExpiredException caught in poller (warn log + incrementPollAttempts + return)
  - Javadoc on assertPayTokenFresh() documenting Phase 5 re-init responsibility (ROADMAP SC-4)
  - ROADMAP Phase 3 SC-4 updated: expired payToken detection wired, fresh re-init deferred to Phase 5
affects:
  - 05-payment-orchestration (PaymentOrchestrator re-initiation on expiry — ROADMAP SC-4)
  - 04-mtn-money-adapter (similar freshness guard pattern for MTN tokens)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Freshness guard before polling: assertPayTokenFresh() catch + increment + return — not propagated"
    - "Phase boundary documentation: Javadoc naming cross-phase responsibility caller explicitly"
    - "ROADMAP SC update pattern: replace open criterion with resolved-and-deferred classification"

key-files:
  created: []
  modified:
    - src/main/java/com/softropic/payam/orange/service/OrangeStatusPollerJob.java
    - src/main/java/com/softropic/payam/orange/service/OrangeMoneyPort.java
    - .planning/ROADMAP.md

key-decisions:
  - "assertPayTokenFresh() catch block placed BEFORE max-attempts check — expired token does not count against max retries as a poll attempt from a business perspective; incrementPollAttempts() still tracks the event"
  - "PayTokenExpiredException is NOT re-thrown from pollTransaction() — logged + increment + return; re-initiation requires PaymentCommand context unavailable in the adapter layer (Phase 5 responsibility)"
  - "ROADMAP SC-4 records the architectural boundary: adapter detects expiry, orchestrator owns re-initiation"

patterns-established:
  - "Guard-before-poll pattern: null check → freshness check → max-attempts check → poll call"
  - "Cross-phase Javadoc: name the production caller + name the downstream phase responsible for the follow-up action"

# Metrics
duration: 3min
completed: 2026-03-24
---

# Phase 3 Plan 04: Gap D — payToken Freshness Guard Wired Summary

**assertPayTokenFresh() wired into OrangeStatusPollerJob before each poll attempt; PayTokenExpiredException handled gracefully; ROADMAP SC-4 classifies re-initiation as Phase 5 PaymentOrchestrator responsibility**

## Performance

- **Duration:** ~3 min
- **Started:** 2026-03-24T03:09:12Z
- **Completed:** 2026-03-24T03:12:18Z
- **Tasks:** 2/2
- **Files modified:** 3

## Accomplishments

- assertPayTokenFresh() now has exactly one production call site: OrangeStatusPollerJob.pollTransaction()
- PayTokenExpiredException caught in poller — warn log + incrementPollAttempts() + return (not propagated)
- Javadoc on assertPayTokenFresh() names OrangeStatusPollerJob as caller and documents Phase 5 re-init responsibility
- ROADMAP Phase 3 SC-4 updated to record the architectural classification decision
- OrangeMoneyPortIT: 8/8 tests pass, zero regressions

## Task Commits

Each task was committed atomically:

1. **Task 1: Wire assertPayTokenFresh() into OrangeStatusPollerJob.pollTransaction()** - `9f085db` (feat)
2. **Task 2: Update Javadoc on assertPayTokenFresh() + ROADMAP SC-4 classification** - `acf0b78` (docs)

## Files Created/Modified

- `src/main/java/com/softropic/payam/orange/service/OrangeStatusPollerJob.java` — Added PayTokenExpiredException import; inserted freshness guard block (try/catch) between null-payToken guard and max-attempts check
- `src/main/java/com/softropic/payam/orange/service/OrangeMoneyPort.java` — Expanded assertPayTokenFresh() Javadoc to name OrangeStatusPollerJob as production caller and document Phase 5 re-init responsibility
- `.planning/ROADMAP.md` — Updated Phase 3 SC-4: "Expired payToken is detected before each poll attempt — fresh re-initiation is Phase 5 PaymentOrchestrator responsibility"

## Decisions Made

- assertPayTokenFresh() catch block placed BEFORE the max-attempts check — expired token skips the poll entirely; incrementPollAttempts() still fires so the poller does not loop indefinitely on a transaction with a stale token.
- PayTokenExpiredException is NOT re-thrown from pollTransaction() — the adapter layer does not hold the original PaymentCommand, so re-initiation is architecturally impossible here. Phase 5 PaymentOrchestrator (which does hold PaymentCommand context) owns re-initiation.
- ROADMAP SC-4 updated from an open action item to a resolved classification: expiry detection wired, re-initiation deferred with explicit Phase 5 attribution.

## Deviations from Plan

None — plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness

- Gap D closed: assertPayTokenFresh() has exactly one production call site with graceful exception handling
- Phase 3 complete — all four gap closure plans (03-01 through 03-04) delivered
- Phase 4 (MTN MoMo Adapter) is next; similar freshness guard pattern may apply to MTN OAuth2 tokens
- Phase 5 PaymentOrchestrator must implement ROADMAP SC-4: re-initiation on PayTokenExpiredException using stored PaymentCommand

---
*Phase: 03-orange-money-adapter*
*Completed: 2026-03-24*
