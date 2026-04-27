---
phase: 52-callbacks-outbound-webhooks
plan: 03
subsystem: payments
tags: [spring-mvc, rest-controller, disbursement, callbacks, mtn, orange, metrics]

# Dependency graph
requires:
  - phase: 52-01
    provides: AppEndpoints PUBLIC_ENDPOINTS registration for /v1/callbacks/mtn/disbursement/* and /v1/callbacks/orange/disbursement; MtnIpWhitelistInterceptor + OrangeIpWhitelistInterceptor path registrations
  - phase: 52-02
    provides: MtnMoMoPort.processDisbursementCallback(payload, providerRef) + OrangeMoneyPort.processDisbursementCallback(payload, notifToken) + DisbursementCallbackTransitionService
provides:
  - "PUT /v1/callbacks/mtn/disbursement/{ref} HTTP entry point via MtnDisbursementCallbackController"
  - "POST /v1/callbacks/orange/disbursement HTTP entry point via OrangeDisbursementCallbackController"
  - "7 unit tests proving both controllers always return 200 OK, never hold @Transactional, and forward all arguments correctly"
affects: [52-04, e2e-tests, disbursement-callback-flow]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Disbursement callback controller mirrors collection controller shape — @Observed + @RestController, no @Transactional, swallow-and-log on exception, always 200"
    - "Disbursement controllers do NOT inject StringRedisTemplate — dedup is centralized in port (callbacks:dsb: namespace), not in controller"

key-files:
  created:
    - src/main/java/com/softropic/payam/disbursement/api/MtnDisbursementCallbackController.java
    - src/main/java/com/softropic/payam/disbursement/api/OrangeDisbursementCallbackController.java
    - src/test/java/com/softropic/payam/disbursement/api/MtnDisbursementCallbackControllerTest.java
    - src/test/java/com/softropic/payam/disbursement/api/OrangeDisbursementCallbackControllerTest.java
  modified: []

key-decisions:
  - "Disbursement callback controllers do NOT inject StringRedisTemplate — dedup centralized in port on callbacks:dsb: namespace (contrast with OrangeCallbackController which deduplicates in the controller)"
  - "Javadoc explains NOT @Transactional rationale inline — keeps non-obvious pitfall visible to future maintainers"

patterns-established:
  - "Thin HTTP shell: controller receives callback, delegates to port, returns 200 regardless — zero business logic in controller layer"
  - "Exception swallowing with recordCallbackFailed() metric: provider must not see processing errors as HTTP failures"

requirements-completed: [SEC-05]

# Metrics
duration: 48min
completed: 2026-04-27
---

# Phase 52 Plan 03: Disbursement Callback Controllers Summary

**Two thin RestController wrappers connecting PUT /v1/callbacks/mtn/disbursement/{ref} and POST /v1/callbacks/orange/disbursement to the port-side processDisbursementCallback methods built in Plan 02 — both always return 200 OK and carry no @Transactional annotation**

## Performance

- **Duration:** 48 min
- **Started:** 2026-04-27T07:07:36Z
- **Completed:** 2026-04-27T07:55:00Z
- **Tasks:** 2 (both TDD)
- **Files modified:** 4 created

## Accomplishments
- MTN disbursement callback controller wired: PUT /v1/callbacks/mtn/disbursement/{ref} extracts `{ref}` as `providerRef` and passes to `MtnMoMoPort.processDisbursementCallback(payload, providerRef)` — IP whitelist and dedup enforced upstream/in-port
- Orange disbursement callback controller wired: POST /v1/callbacks/orange/disbursement forwards `X-Notif-Token` header (nullable) to `OrangeMoneyPort.processDisbursementCallback(payload, notifToken)` — no StringRedisTemplate injection (dedup inside port)
- 7 unit tests (3 MTN + 4 Orange) prove: correct argument forwarding, 200-on-exception, null-token passthrough, and reflection guard confirming no `@Transactional` annotation on class or method
- `mvn verify` green — all existing tests unaffected

## Task Commits

Each task was committed atomically:

1. **Task 1: MtnDisbursementCallbackController + unit test** - `eaf559c` (feat)
2. **Task 2: OrangeDisbursementCallbackController + unit test** - `fe574ce` (feat)

**Plan metadata:** (in final commit)

_Note: Both tasks followed TDD flow — failing test written first, then production class, then verified GREEN._

## Files Created/Modified
- `src/main/java/com/softropic/payam/disbursement/api/MtnDisbursementCallbackController.java` - PUT /v1/callbacks/mtn/disbursement/{ref} → MtnMoMoPort.processDisbursementCallback
- `src/main/java/com/softropic/payam/disbursement/api/OrangeDisbursementCallbackController.java` - POST /v1/callbacks/orange/disbursement → OrangeMoneyPort.processDisbursementCallback
- `src/test/java/com/softropic/payam/disbursement/api/MtnDisbursementCallbackControllerTest.java` - 3 unit tests for MTN disbursement controller
- `src/test/java/com/softropic/payam/disbursement/api/OrangeDisbursementCallbackControllerTest.java` - 4 unit tests for Orange disbursement controller

## Decisions Made
- Disbursement controllers do NOT inject `StringRedisTemplate` — dedup centralized in port layer (`callbacks:dsb:` Redis namespace). This is the key difference from `OrangeCallbackController` (collection) which deduplicates in the controller. Keeping dedup in the port for disbursements means the controller is a pure thin shell.
- Javadoc on both controllers explicitly explains the NOT @Transactional rationale and points to Pitfall 1 in 52-RESEARCH to make the constraint visible to future maintainers.

## Deviations from Plan

None — plan executed exactly as written. Worktree was behind main at start (Plans 01 and 02 existed in main but not in worktree branch); merged main into worktree before implementation. No production code changes were needed beyond the two planned controllers.

## Issues Encountered
- Worktree branch `worktree-agent-ab5a361b2aa782910` was at commit `203c677` (pre-phase-52), missing all Plans 01 and 02 changes. Resolved by merging main (commit `72f4452`) into the worktree branch via fast-forward before beginning implementation. No conflicts occurred.

## Known Stubs

None — both controllers are fully wired to live port methods. No placeholder text, hardcoded returns, or mock data paths.

## User Setup Required
None — no external service configuration required.

## Next Phase Readiness
- Phase 52 Plan 04 (outbound webhook delivery) is unblocked — both inbound disbursement callback controllers are live
- Disbursement callback flow is now end-to-end: IP whitelist → controller → port (dedup + double-check + state transition via DisbursementCallbackTransitionService) → outbound webhook delivery
- `mvn verify` is green; no regressions

---
*Phase: 52-callbacks-outbound-webhooks*
*Completed: 2026-04-27*
