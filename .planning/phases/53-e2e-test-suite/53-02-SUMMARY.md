---
phase: 53-e2e-test-suite
plan: "02"
subsystem: testing
tags: [springboot, wiremock, testcontainers, awaitility, orange, disbursement]

requires:
  - phase: 52-disbursement-callback-integration
    provides: Orange disbursement callback controller and OrangeMoneyPort

provides:
  - HTTP-level E2E test for full Orange Money disbursement lifecycle (initiate → callback SUCCESSFULL/insufficient/replay)
  - Production bug fix: OrangeMoneyPort.initiateDisbursement() now extracts payToken from cashout response for callback correlation
  - Proof of insufficient-balance early rejection with ZERO Orange /cashout calls
  - Proof of Orange callback replay deduplication

affects: [53-e2e-test-suite, TEST-02]

tech-stack:
  added: []
  patterns:
    - Orange uses SUCCESSFULL (double-L) status — OrangeStatusMapper maps it to DisbursementStatus.SUCCESS
    - Orange cashout endpoint is /cashout (not /ic2c/pay)
    - Orange callback double-check endpoint: GET /mp/paymentstatus/{payToken}
    - payToken extracted from cashout JSON response body and stored as providerRef for callback correlation

key-files:
  created:
    - src/test/java/com/softropic/payam/e2e/disbursement/OrangeDisbursementE2EIT.java
  modified:
    - src/main/java/com/softropic/payam/orange/service/OrangeMoneyPort.java

key-decisions:
  - "OrangeMoneyPort was returning ProviderResult.success(null, ...) — null providerRef meant no callback could ever be correlated; fixed to extract payToken from cashout response Map"
  - "Stub must return payToken in cashout response: {\"status\":\"SUCCESS\",\"payToken\":\"PAY-TOKEN-OR-TEST\"}"

patterns-established:
  - "Orange disbursement correlation: payToken from cashout response stored as providerRef; callback uses payToken to find disbursement"

requirements-completed: [TEST-02]

duration: 50min
completed: 2026-04-27
---

# Phase 53 Plan 02: OrangeDisbursementE2EIT Summary

**Full HTTP-level Orange Money disbursement lifecycle E2E with production bug fix: payToken extraction from cashout response for callback correlation, plus insufficient-balance and replay tests**

## Performance

- **Duration:** 50 min
- **Started:** 2026-04-27T06:00:00Z
- **Completed:** 2026-04-27T06:50:00Z
- **Tasks:** 1
- **Files modified:** 2

## Accomplishments
- Created `OrangeDisbursementE2EIT` with 3 test methods covering full Orange Money disbursement lifecycle
- Fixed critical production bug: `OrangeMoneyPort` was returning null providerRef, preventing any Orange callback from being correlated
- Test 1: POST /v1/disbursements (Orange MSISDN) → PROCESSING → POST callback SUCCESSFULL → SUCCESS within 10s
- Test 2: Amount > wallet balance → 422 INSUFFICIENT_BALANCE; ZERO /cashout calls confirmed via WireMock
- Test 3: Replayed Orange callback → 200; exactly 1 double-check GET to /mp/paymentstatus/{payToken}

## Task Commits

1. **Task 1: Create OrangeDisbursementE2EIT + fix OrangeMoneyPort** - `82ce726` (test)

## Files Created/Modified
- `src/test/java/com/softropic/payam/e2e/disbursement/OrangeDisbursementE2EIT.java` - 3-test Orange disbursement E2E class
- `src/main/java/com/softropic/payam/orange/service/OrangeMoneyPort.java` - Fixed payToken extraction from cashout response

## Decisions Made
- OrangeMoneyPort production bug fix included in same commit as test (Rule 1 — the test exposed the bug)
- WireMock cashout stub updated to return `{"status":"SUCCESS","payToken":"PAY-TOKEN-OR-TEST"}` to enable correlation

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] OrangeMoneyPort.initiateDisbursement() always returned null providerRef**
- **Found during:** Task 1 (Test 1 execution — callback correlation failed)
- **Issue:** `OrangeMoneyPort.initiateDisbursement()` returned `ProviderResult.success(null, "DISBURSEMENT_SUCCESS")`. The `processDisbursementCallback` method calls `findByProviderRef(payToken)` which found nothing because the stored providerRef was null.
- **Fix:** Added payToken extraction from cashout response Map; returns `ProviderResult.success(payToken, "DISBURSEMENT_SUCCESS")`
- **Files modified:** `src/main/java/com/softropic/payam/orange/service/OrangeMoneyPort.java`
- **Verification:** All 3 Orange E2E tests pass; Orange callback correlation now works end-to-end
- **Committed in:** `82ce726`

---

**Total deviations:** 1 auto-fixed (1 production bug)
**Impact on plan:** Critical correctness fix — Orange disbursement callbacks were entirely non-functional without this. No scope creep.

## Issues Encountered
- Orange cashout stub initially did not return payToken field — test's `stubOrangeSubscriberAndCashout()` was updated to return `{"status":"SUCCESS","payToken":"PAY-TOKEN-OR-TEST"}`

## Next Phase Readiness
- Orange disbursement E2E complete; TEST-02 closed at HTTP layer
- Orange providerRef/payToken correlation now working end-to-end

---
*Phase: 53-e2e-test-suite*
*Completed: 2026-04-27*
