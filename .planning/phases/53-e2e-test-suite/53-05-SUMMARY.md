---
phase: 53-e2e-test-suite
plan: "05"
subsystem: testing
tags: [springboot, testcontainers, concurrency, cyclic-barrier, disbursement, bal-01]

requires:
  - phase: 52-disbursement-callback-integration
    provides: Wallet pessimistic write lock (PESSIMISTIC_WRITE) in WalletBalanceService

provides:
  - HTTP-level concurrency race test: 20 simultaneous POST /v1/disbursements → exactly 1 PROCESSING, 19 INSUFFICIENT_BALANCE
  - Proof of BAL-01: no wallet overdraft under concurrent load (balance=0, reservedAmount=PRINCIPAL after race)
  - Proof: exactly 1 MTN /v1_0/transfer call (winner only)
  - Fix: per-thread unique MSISDN to avoid per-MSISDN daily velocity limit (10/day)

affects: [53-e2e-test-suite, TEST-04]

tech-stack:
  added: []
  patterns:
    - CyclicBarrier for synchronized thread start in 20-thread race
    - Per-thread unique MSISDN (mtnMsisdnForThread(i) = +23767{i:02d}00001) to avoid MSISDN daily velocity bucket exhaustion
    - DefaultResponseErrorHandler override to capture 422 without exception

key-files:
  created:
    - src/test/java/com/softropic/payam/e2e/disbursement/DisbursementConcurrencyRaceIT.java
  modified: []

key-decisions:
  - "DisbursementVelocityService has per-MSISDN daily bucket with capacity=10; 20 threads sharing one MSISDN exhausts it after 10 requests causing DAILY_LIMIT_EXCEEDED (HTTP 422) instead of INSUFFICIENT_BALANCE"
  - "Each thread uses a unique MTN MSISDN (+23767XX00001, prefix 67 → MTN) — per-MSISDN velocity bucket is not shared"
  - "Tenant minute bucket (capacity=20) is exactly sufficient for 20 concurrent threads — no issue"

patterns-established:
  - "Concurrency race pattern: CyclicBarrier(20) + ExecutorService.newFixedThreadPool(20) + per-thread RestTemplate + AtomicInteger counters"
  - "Per-thread MSISDN to isolate velocity buckets: mtnMsisdnForThread(threadIndex)"

requirements-completed: [TEST-04]

duration: 50min
completed: 2026-04-27
---

# Phase 53 Plan 05: DisbursementConcurrencyRaceIT Summary

**20-thread HTTP-level race test proving BAL-01 under concurrency: exactly 1 wallet winner, 19 INSUFFICIENT_BALANCE, zero overdraft; fix for per-MSISDN daily velocity limit interference**

## Performance

- **Duration:** 50 min
- **Started:** 2026-04-27T05:00:00Z
- **Completed:** 2026-04-27T07:35:00Z
- **Tasks:** 1
- **Files modified:** 1

## Accomplishments
- Created `DisbursementConcurrencyRaceIT` with 1 test method: 20 concurrent POST /v1/disbursements with wallet covering exactly 1 PRINCIPAL
- Exactly 1 PROCESSING (winner acquires pessimistic write lock), 19 INSUFFICIENT_BALANCE, 0 other
- Wallet verified: balance=0, reservedAmount=PRINCIPAL after race (BAL-01 invariant)
- Exactly 1 MTN /v1_0/transfer call verified via WireMock
- Exactly 1 PROCESSING row in main.disbursement verified via JDBC

## Task Commits

1. **Task 1: Create DisbursementConcurrencyRaceIT** - `43d10d9` (feat)

## Files Created/Modified
- `src/test/java/com/softropic/payam/e2e/disbursement/DisbursementConcurrencyRaceIT.java` - 20-thread race E2E test

## Decisions Made
- Per-thread unique MSISDN to bypass per-MSISDN daily velocity limit (10/day capacity) — all MSISDNs use MTN prefix 67

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Per-MSISDN daily velocity limit caused 10 threads to receive DAILY_LIMIT_EXCEEDED instead of INSUFFICIENT_BALANCE**
- **Found during:** Task 1 (test execution — 9 INSUFFICIENT_BALANCE instead of 19)
- **Issue:** `DisbursementVelocityService` has a per-(tenant,MSISDN) daily bucket with capacity=10. With 20 concurrent threads all using `+237671234567`, after the 10th request the bucket is exhausted → `DailyLimitExceededException` → HTTP 422 with error code `DAILY_LIMIT_EXCEEDED`, not `INSUFFICIENT_BALANCE`. These 10 threads fell into the `other` counter.
- **Fix:** Replaced static `MTN_MSISDN` constant with `mtnMsisdnForThread(threadIndex)` generating `+23767XX00001` (unique per thread); each thread has its own velocity bucket; wallet pessimistic lock remains the sole bottleneck
- **Files modified:** `DisbursementConcurrencyRaceIT.java`
- **Verification:** Test passes with exactly 1 PROCESSING, 19 INSUFFICIENT_BALANCE, 0 other
- **Committed in:** `43d10d9`

---

**Total deviations:** 1 auto-fixed (1 bug)
**Impact on plan:** Fix necessary to isolate the wallet lock as the race condition variable. No scope creep.

## Issues Encountered
- DisbursementVelocityService interaction: per-MSISDN daily bucket (capacity=10) was an invisible constraint not mentioned in the test plan

## Next Phase Readiness
- Concurrency race E2E complete; TEST-04 BAL-01 proven at HTTP layer
- Complements service-layer proof in WalletBalanceConcurrencyIT

---
*Phase: 53-e2e-test-suite*
*Completed: 2026-04-27*
