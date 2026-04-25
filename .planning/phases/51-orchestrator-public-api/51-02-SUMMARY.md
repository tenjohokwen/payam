---
phase: 51-orchestrator-public-api
plan: 02
subsystem: payments
tags: [bucket4j, redis, fraud, velocity, disbursement, sec-02, sec-03]

# Dependency graph
requires:
  - phase: 50-schema-balance-infrastructure
    provides: Disbursement entity + DisbursementRepository + DisbursementStatus enum
  - phase: 07-fraud-engine
    provides: FraudDecision record (blocked, riskScore, reason) reused by disbursement path
provides:
  - DisbursementVelocityService with 3 Bucket4j-on-Redis buckets (tenant/min, tenant/hour, msisdn/day)
  - DisbursementFraudEvaluationService with 3 fraud signals (new recipient +15, outlier +30, blocklist +80)
  - VelocityExceededException (maps to HTTP 429)
  - DailyLimitExceededException (maps to HTTP 422)
  - DisbursementIdempotencyService (idempotency:dsb: namespace, Postgres-first ordering)
  - DisbursementRepository extended with countByTenantIdAndRecipientMsisdn + findSuccessfulAmountsForTenant
affects:
  - 51-03-PLAN (Orchestrator calls both services in velocity → fraud → reserve sequence)
  - 51-04-PLAN (REST layer maps VelocityExceededException → 429, DailyLimitExceededException → 422)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Disbursement-specific services in com.softropic.payam.disbursement.service — parallel path, never touch collection FraudScoringService or VelocityCheckService"
    - "protected tryConsume seam in DisbursementVelocityService enables unit-testable Bucket4j without Redis"
    - "fail-open Redis pattern — blocklist check wrapped in try/catch, contributes 0 on exception"
    - "Median computed from sorted ASC list returned by @Query ORDER BY amount ASC"
    - "block threshold strictly > 80 (score == 80 allows) — use compareTo for BigDecimal zero checks"

key-files:
  created:
    - src/main/java/com/softropic/payam/disbursement/service/DisbursementVelocityService.java
    - src/main/java/com/softropic/payam/disbursement/contract/exception/VelocityExceededException.java
    - src/main/java/com/softropic/payam/disbursement/contract/exception/DailyLimitExceededException.java
    - src/main/java/com/softropic/payam/disbursement/service/DisbursementFraudEvaluationService.java
    - src/main/java/com/softropic/payam/disbursement/service/DisbursementIdempotencyService.java
    - src/test/java/com/softropic/payam/disbursement/service/DisbursementVelocityServiceTest.java
    - src/test/java/com/softropic/payam/disbursement/service/DisbursementFraudEvaluationServiceTest.java
  modified:
    - src/main/java/com/softropic/payam/disbursement/repo/DisbursementRepository.java

key-decisions:
  - "DisbursementIdempotencyService created as Rule-3 deviation to unblock compilation of DisbursementIdempotencyServiceTest (committed in plan 51-01) — mirrors IdempotencyService but uses idempotency:dsb: namespace"
  - "Block threshold is strictly > 80: blocklist signal alone (score = 80) allows through; blocklist + any other signal (>80) blocks. Deliberate per SEC-03 spec."
  - "Amount outlier signal skipped when tenant has fewer than 10 SUCCESS rows — fail-open for new tenants avoids false positives on sparse history"
  - "Median computed from repository-sorted list (ORDER BY amount ASC) — no in-service sorting needed"

patterns-established:
  - "disbursement.service namespace parallel to fraud.service — disbursement-specific gates never modify collection-side services"
  - "protected tryConsume seam pattern for testable Bucket4j ProxyManager calls without Redis"
  - "idempotency:dsb: prefix isolates disbursement idempotency keys from collection path idempotency: keys"

requirements-completed: [SEC-02, SEC-03]

# Metrics
duration: ~30min (execution agent portion — fraud service + tests + deviation)
completed: 2026-04-25
---

# Phase 51 Plan 02: Disbursement Velocity and Fraud Gates Summary

**Bucket4j-on-Redis velocity gates (3 buckets: 20/min, 200/hr per tenant; 10/day per MSISDN) and three-signal fraud scorer (new recipient +15, outlier +30, blocklist +80; block when score > 80) implemented as parallel disbursement path — collection services untouched**

## Performance

- **Duration:** ~30 min (fraud service implementation + deviation handling)
- **Started:** 2026-04-25T10:00:00Z
- **Completed:** 2026-04-25T10:32:14Z
- **Tasks:** 3 tasks complete (Tasks 1+2 from prior agent; Task 3 from this execution)
- **Files modified:** 8 files (3 new services, 2 exceptions, 1 idempotency service, 1 repo extension, 2 test files)

## Accomplishments

- DisbursementVelocityService enforces 20/min, 200/hr per tenant and 10/day per (tenant,MSISDN) via Bucket4j ProxyManager with distinct Redis key prefixes (disb:velocity:...)
- DisbursementFraudEvaluationService scores 3 independent signals: new recipient MSISDN (+15), amount outlier vs tenant 3x median (+30, skipped for <10 history rows), Redis blocklist MSISDN (+80); blocks when score > 80
- DisbursementRepository extended with 2 read-only query methods (countByTenantIdAndRecipientMsisdn, findSuccessfulAmountsForTenant) for fraud signal computation
- 15 unit tests total: 6 velocity + 9 fraud — all green; collection-side FraudScoringService and VelocityCheckService verified unchanged

## Task Commits

1. **Task 1 (RED): Failing velocity tests** - `cc50778` (test)
2. **Task 1 (GREEN): DisbursementVelocityService + exceptions** - `030b132` (feat)
3. **Task 2: Merge prior agent work** - `f074681` (merge)
4. **Task 3 (RED): Failing fraud evaluation tests** - `fed567e` (test)
5. **Task 3 (GREEN): DisbursementFraudEvaluationService + idempotency service** - `e8743db` (feat)

_TDD: Tasks 1 and 3 each have RED (test) and GREEN (feat) commits._

## Files Created/Modified

- `src/main/java/com/softropic/payam/disbursement/service/DisbursementVelocityService.java` - 3-bucket Bucket4j velocity gate; protected tryConsume seam for unit testability
- `src/main/java/com/softropic/payam/disbursement/contract/exception/VelocityExceededException.java` - RuntimeException → HTTP 429
- `src/main/java/com/softropic/payam/disbursement/contract/exception/DailyLimitExceededException.java` - RuntimeException → HTTP 422
- `src/main/java/com/softropic/payam/disbursement/service/DisbursementFraudEvaluationService.java` - 3-signal fraud scorer with strict > 80 block and Redis fail-open
- `src/main/java/com/softropic/payam/disbursement/service/DisbursementIdempotencyService.java` - idempotency:dsb: namespace, Postgres-first ordering (deviation)
- `src/main/java/com/softropic/payam/disbursement/repo/DisbursementRepository.java` - extended with 2 read-only JPQL/derived queries for fraud signals
- `src/test/java/com/softropic/payam/disbursement/service/DisbursementVelocityServiceTest.java` - 6 unit tests for minute/hour/day buckets + key prefix
- `src/test/java/com/softropic/payam/disbursement/service/DisbursementFraudEvaluationServiceTest.java` - 9 unit tests covering each signal independently + strict > 80 boundary + Redis fail-open

## Decisions Made

- Block threshold is strictly > 80: blocklist signal alone (score = 80) allows; only combined signals (score > 80) block. Preserves deliberate SEC-03 spec semantics.
- Amount outlier signal skipped when fewer than 10 SUCCESS rows exist for the tenant — fail-open avoids penalizing new tenants before their transaction history establishes a reliable baseline.
- Median uses repository's ORDER BY amount ASC sort — no in-service sorting overhead; even-count median computed as average of two middle values with HALF_UP rounding.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Created DisbursementIdempotencyService to unblock test module compilation**
- **Found during:** Task 3 (DisbursementFraudEvaluationService implementation)
- **Issue:** `DisbursementIdempotencyServiceTest.java` (committed as TDD RED in plan 51-01) references `DisbursementIdempotencyService` which did not exist on this branch. Maven test compilation failed for the entire `disbursement.service` package, preventing the fraud test from running.
- **Fix:** Created `DisbursementIdempotencyService` mirroring `IdempotencyService` with the `idempotency:dsb:` namespace prefix. All 7 idempotency tests also pass as a result.
- **Files modified:** `src/main/java/com/softropic/payam/disbursement/service/DisbursementIdempotencyService.java` (created)
- **Verification:** `mvn -q -pl . -Dtest=DisbursementFraudEvaluationServiceTest test` exits 0; compilation clean.
- **Committed in:** `e8743db` (Task 3 feat commit)

---

**Total deviations:** 1 auto-fixed (Rule 3 - Blocking)
**Impact on plan:** Required to unblock Task 3 compilation. DisbursementIdempotencyService is a legitimate plan 51-01 artifact that had its RED test committed before its GREEN implementation — creating it here resolves the compile dependency without scope creep.

## Issues Encountered

- This worktree branch was behind `main` and lacked prior agent's velocity service commits. Resolved by `git merge main` before implementing the fraud service.

## Next Phase Readiness

- Plan 51-03 (Orchestrator) can `@Autowired DisbursementVelocityService`, `DisbursementFraudEvaluationService`, and `DisbursementIdempotencyService` — all three are available as `@Service` beans.
- `VelocityExceededException` and `DailyLimitExceededException` ready for `@ExceptionHandler` mapping in plan 51-04 REST layer.
- DisbursementRepository has the two query methods needed for fraud evaluation (no further repo changes needed for plan 51-03).

---
*Phase: 51-orchestrator-public-api*
*Completed: 2026-04-25*
