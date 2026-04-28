---
phase: 53-e2e-test-suite
plan: "06"
subsystem: testing
tags: [springboot, testcontainers, fraud, redis, idempotency, cyclic-barrier, disbursement]

requires:
  - phase: 52-disbursement-callback-integration
    provides: DisbursementFraudEvaluationService with Redis blocklist check and NEW_RECIPIENT signal

provides:
  - HTTP-level E2E test for fraud block: blocklist+new-recipient combo (score 95 > 80) → FRAUD_BLOCK
  - Proof: ZERO provider calls and ZERO persisted rows when FRAUD_BLOCK fires
  - HTTP-level E2E test for idempotency race: 20 concurrent threads with same Idempotency-Key → exactly 1 row, all 20 receive 202
  - Proof of idempotency cache guard: winner writes 1 row, others served from cache, all 20 return HTTP 202

affects: [53-e2e-test-suite, TEST-01, TEST-04]

tech-stack:
  added: []
  patterns:
    - Redis SET seeding: redis.opsForSet().add(FRAUD_BLOCKLIST_KEY, msisdn) before test
    - Fraud scoring: BLOCKLIST_WEIGHT=80 + NEW_RECIPIENT_WEIGHT=15 = 95 > BLOCK_THRESHOLD=80
    - Idempotency race: all threads use SAME Idempotency-Key; winner locks NX, others get cached response
    - All 20 idempotency race threads receive HTTP 202 (not just the winner — cached response is also 202)

key-files:
  created:
    - src/test/java/com/softropic/payam/e2e/disbursement/DisbursementFraudBlockE2EIT.java
  modified: []

key-decisions:
  - "Fraud score of exactly 80 does NOT block (threshold is strictly greater) — blocklist alone (80) is insufficient; must combine with NEW_RECIPIENT (+15) = 95 to trigger FRAUD_BLOCK"
  - "Idempotency race: all 20 threads use SAME Idempotency-Key with SAME MSISDN and amount (different reference — reference not used for idempotency)"

patterns-established:
  - "Fraud block test: fresh tenant ensures MSISDN is NEW_RECIPIENT; Redis SET seeded before request"
  - "Idempotency race: shared IDEM key + large enough wallet (1,000,000 XAF) so balance is never the constraint"

requirements-completed: [TEST-01, TEST-04]

duration: 35min
completed: 2026-04-27
---

# Phase 53 Plan 06: DisbursementFraudBlockE2EIT Summary

**HTTP-level fraud block E2E (blocklist+new-recipient → FRAUD_BLOCK, zero provider calls, zero rows) and idempotency race E2E (20 threads/same key → exactly 1 row, all 20 receive 202)**

## Performance

- **Duration:** 35 min
- **Started:** 2026-04-27T07:40:00Z
- **Completed:** 2026-04-27T08:15:00Z
- **Tasks:** 1
- **Files modified:** 1

## Accomplishments
- Created `DisbursementFraudBlockE2EIT` with 2 test methods
- Test 1 (fraud block): FRAUD_MSISDN seeded on Redis blocklist + fresh tenant (NEW_RECIPIENT) → score 95 > 80 → HTTP 422 FRAUD_BLOCK; zero MTN transfers; zero disbursement rows persisted
- Test 2 (idempotency race): 20 concurrent threads with same `SHARED_IDEM` key → all 20 receive HTTP 202; exactly 1 disbursement row; exactly 1 MTN /v1_0/transfer call

## Task Commits

1. **Task 1: Create DisbursementFraudBlockE2EIT** - `41424ae` (feat)

## Files Created/Modified
- `src/test/java/com/softropic/payam/e2e/disbursement/DisbursementFraudBlockE2EIT.java` - 2-test fraud block + idempotency race E2E class

## Decisions Made
- Wallet seeded at 1,000,000 XAF (20 × 5,000 XAF) for idempotency race so insufficient balance is never a factor — only idempotency is the gating mechanism
- FRAUD_MSISDN and CLEAN_MSISDN kept distinct to isolate fraud test from idempotency race

## Deviations from Plan

None — plan executed exactly as written.

## Issues Encountered
None — both tests passed on first run.

## Next Phase Readiness
- All 6 E2E test plans complete; Phase 53 closes all TEST-01, TEST-02, TEST-03, TEST-04 requirements at the HTTP layer
- v10 Client Disbursement API milestone complete

---
*Phase: 53-e2e-test-suite*
*Completed: 2026-04-27*
