---
phase: 53-e2e-test-suite
plan: "04"
subsystem: testing
tags: [springboot, quartz, testcontainers, step-up, expiry, disbursement, reflection]

requires:
  - phase: 52-disbursement-callback-integration
    provides: DisbursementExpiryJob and BAL-03 invariant (wallet held on expiry)

provides:
  - HTTP-level E2E test for step-up expiry: HTTP initiation → direct expiry job invocation → EXPIRED terminal state
  - Proof of BAL-03: wallet balance and reservedAmount unchanged after EXPIRED transition
  - Proof: ZERO provider calls made for expired disbursements
  - Proof: GET /v1/disbursements/{id} returns EXPIRED in HTTP response
  - Fix: reflection-based invokeExpiryJob() helper for cross-package access to protected QuartzJobBean.executeInternal()

affects: [53-e2e-test-suite, TEST-03]

tech-stack:
  added: []
  patterns:
    - Reflection-based protected method invocation: getDeclaredMethod + setAccessible(true) for QuartzJobBean subclass
    - DB-relative time anchor: ageDisbursement(id, minutes) sets created_date = NOW() - INTERVAL to avoid JVM/Postgres clock skew
    - ageDisbursement used before expiry job invocation to avoid race between JVM clock and DB NOW()

key-files:
  created:
    - src/test/java/com/softropic/payam/e2e/disbursement/DisbursementExpiryE2EIT.java
  modified: []

key-decisions:
  - "DisbursementExpiryJob.executeInternal() is protected on QuartzJobBean — cross-package test requires reflection via setAccessible(true)"
  - "DB-relative time anchor (ageDisbursement with NOW() - INTERVAL) eliminates JVM/Postgres clock skew for fresh-row expiry guard test"

patterns-established:
  - "ageDisbursement(id, minutes) SQL helper: UPDATE disbursement SET created_date = NOW() - INTERVAL 'N minutes'"
  - "invokeExpiryJob() via reflection: same approach as existing DisbursementExpiryJobIT"

requirements-completed: [TEST-03]

duration: 60min
completed: 2026-04-27
---

# Phase 53 Plan 04: DisbursementExpiryE2EIT Summary

**HTTP-initiated step-up expiry E2E: PENDING_CONFIRMATION → DisbursementExpiryJob (via reflection) → EXPIRED with BAL-03 wallet hold invariant; fresh row guard test using DB-relative time anchor**

## Performance

- **Duration:** 60 min
- **Started:** 2026-04-27T07:45:00Z
- **Completed:** 2026-04-27T08:45:00Z
- **Tasks:** 1
- **Files modified:** 1

## Accomplishments
- Created `DisbursementExpiryE2EIT` with 2 test methods covering the 15-minute step-up expiry lifecycle
- Test 1 (aged): HTTP-initiate large disbursement → age to 16 min via DB SQL → invoke expiry job → EXPIRED; wallet balance/reservedAmount unchanged (BAL-03); zero provider calls; GET returns EXPIRED
- Test 2 (fresh): HTTP-initiate → age to 2 min (within 15-min threshold) → invoke expiry job → still PENDING_CONFIRMATION (not expired)

## Task Commits

1. **Task 1: Create DisbursementExpiryE2EIT** - `47aa3df` (test)

## Files Created/Modified
- `src/test/java/com/softropic/payam/e2e/disbursement/DisbursementExpiryE2EIT.java` - 2-test expiry lifecycle E2E class

## Decisions Made
- Used reflection to call `executeInternal(null)` instead of making it public — avoids changing production API for test convenience
- Added `ageDisbursement(id, 2)` before expiry job in Test 2 (fresh guard) to anchor time to DB-relative NOW() and eliminate JVM/Postgres clock skew

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] DisbursementExpiryJob.executeInternal() is protected — cross-package access denied**
- **Found during:** Task 1 (compilation failure)
- **Issue:** `executeInternal` is protected on `QuartzJobBean`. Test class is in `com.softropic.payam.e2e.disbursement`, not in the same package as the job. Direct call is a compilation error.
- **Fix:** Added `invokeExpiryJob()` helper using Java reflection: `getDeclaredMethod("executeInternal", JobExecutionContext.class)` + `setAccessible(true)` + `invoke(expiryJob, (Object) null)`
- **Files modified:** `DisbursementExpiryE2EIT.java`
- **Verification:** Both tests pass; `DisbursementExpiryJobIT` uses same pattern
- **Committed in:** `47aa3df`

**2. [Rule 1 - Bug] freshPendingConfirmation_isNotExpired test expired a fresh row**
- **Found during:** Task 1 (Test 2 failure)
- **Issue:** Spring `@CreatedDate` stores `Instant.now()` (JVM clock) but Postgres `NOW()` uses DB clock. Even tiny skew or timezone representation difference caused a just-created row to appear older than expected to the expiry query, making it expire immediately.
- **Fix:** Added `ageDisbursement(disbursementId, 2)` before invoking expiry job — explicitly sets `created_date = NOW() - INTERVAL '2 minutes'` (DB-relative), keeping the row within the 15-minute safe window while eliminating clock skew
- **Files modified:** `DisbursementExpiryE2EIT.java`
- **Verification:** Test 2 passes consistently; same pattern used in `DisbursementExpiryJobIT`
- **Committed in:** `47aa3df`

---

**Total deviations:** 2 auto-fixed (2 bugs)
**Impact on plan:** Both fixes necessary for test correctness. No scope creep.

## Issues Encountered
- JVM/Postgres clock skew: Spring `@CreatedDate` (Instant) vs Postgres `NOW()` comparison in expiry query — resolved by DB-relative time anchor

## Next Phase Readiness
- Step-up expiry E2E complete; TEST-03 fully closed (both confirmation and expiry)
- ageDisbursement helper pattern established for future expiry-related tests

---
*Phase: 53-e2e-test-suite*
*Completed: 2026-04-27*
