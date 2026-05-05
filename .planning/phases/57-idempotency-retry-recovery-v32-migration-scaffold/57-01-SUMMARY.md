---
phase: 57-idempotency-retry-recovery-v32-migration-scaffold
plan: 01
subsystem: payments
tags: [disbursement, idempotency, retry, state-machine, wiremock, testcontainers]

# Dependency graph
requires:
  - phase: 55-transaction-validation-fee-removal
    provides: DisbursementTransactionRef claim lifecycle, DisbursementClaimTransitionService
  - phase: 54-v31-schema-migration
    provides: retry_count column on Disbursement entity (V31), DisbursementRefStatus.RELEASED
provides:
  - DisbursementStatus.FAILED -> INITIATED legal transition (IDEM-02 state machine)
  - DisbursementRetryClassifier component (RETRIABLE vs TERMINAL classification)
  - DisbursementOrchestrator.handleRetry private method (IDEM-01/02/03 routing)
  - DisbursementIdempotencyRetryIT end-to-end integration test (3 IDEM requirements)
affects:
  - phase-58-integration-e2e  # IDEM classification may be expanded after E2E results
  - any phase touching DisbursementOrchestrator constructor (now 15 params)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Conservative retry classification: only PROVIDER_ERROR and PROVIDER_UNAVAILABLE are RETRIABLE; all others (including null) are TERMINAL"
    - "Audit-trail-preserving reactivation: UPDATE existing RELEASED rows to PENDING — no INSERT of new DisbursementTransactionRef rows"
    - "Race guard inside PESSIMISTIC_WRITE lock: re-check status == FAILED before transition"
    - "Idempotency cache-hit routing: FAILED responses route through handleRetry; all other statuses replay verbatim"

key-files:
  created:
    - src/main/java/com/softropic/payam/disbursement/service/DisbursementRetryClassifier.java
    - src/test/java/com/softropic/payam/disbursement/service/DisbursementRetryClassifierTest.java
    - src/test/java/com/softropic/payam/disbursement/service/DisbursementIdempotencyRetryIT.java
  modified:
    - src/main/java/com/softropic/payam/disbursement/contract/DisbursementStatus.java
    - src/main/java/com/softropic/payam/disbursement/service/DisbursementOrchestrator.java
    - src/test/java/com/softropic/payam/disbursement/contract/DisbursementStatusTest.java
    - src/test/java/com/softropic/payam/disbursement/service/DisbursementOrchestratorTest.java

key-decisions:
  - "Conservative classification (Option B): RETRIABLE set contains only PROVIDER_ERROR and PROVIDER_UNAVAILABLE — both are unambiguously transient. All other codes default TERMINAL. Phase 58 E2E will surface any misclassification."
  - "Audit-trail invariant: handleRetry reactivates RELEASED DisbursementTransactionRef rows to PENDING via UPDATE — no new rows inserted. Row count stays at N on retry."
  - "15-parameter DisbursementOrchestrator constructor: added DisbursementRetryClassifier and DisbursementTransactionRefRepository as the 14th and 15th parameters."
  - "IDEM-01 guard probe uses ACTIVE_CLAIM_STATUSES = {PENDING, CLAIMED} to detect conflict before acquiring the PESSIMISTIC_WRITE lock."

patterns-established:
  - "Pattern: handleRetry separates classification (TERMINAL/RETRIABLE), pre-lock validation (IDEM-01), and atomic block (IDEM-02) — three distinct decision points before re-dispatch"
  - "Pattern: DisbursementIdempotencyRetryIT seeds data via direct JdbcTemplate INSERT (not JPA save) to avoid silent transaction failures in test context"

requirements-completed: [IDEM-01, IDEM-02, IDEM-03]

# Metrics
duration: ~90min (including integration test run ~65s)
completed: 2026-05-05
---

# Phase 57 Plan 01: Idempotency Retry Recovery Summary

**FAILED->INITIATED state machine transition + conservative classifier (PROVIDER_ERROR/PROVIDER_UNAVAILABLE=RETRIABLE) + DisbursementOrchestrator.handleRetry routing RELEASED->PENDING reactivation with audit-trail preservation, end-to-end verified by 3 WireMock+Testcontainers integration tests**

## Performance

- **Duration:** ~90 min
- **Completed:** 2026-05-05
- **Tasks:** 3 (Tasks 1 and 2 pre-committed; Task 3 completed in this session)
- **Files modified:** 7

## Accomplishments
- DisbursementStatus.FAILED now allows one outbound transition: INITIATED (IDEM-02 state machine)
- DisbursementRetryClassifier classifies PROVIDER_ERROR and PROVIDER_UNAVAILABLE as RETRIABLE; everything else (including null) as TERMINAL
- DisbursementOrchestrator.handleRetry implements IDEM-01/02/03: terminal codes return cached response, retriable codes check for active claims then atomically transition + reactivate + re-dispatch
- DisbursementIdempotencyRetryIT proves all three IDEM requirements end-to-end against real Postgres + Redis + WireMock MTN

## Task Commits

1. **Task 1: DisbursementStatus FAILED->INITIATED + DisbursementRetryClassifier** - `4c2ea3b` (feat)
2. **Task 2: DisbursementOrchestrator handleRetry method + unit tests** - `1ee9a54` (feat)
3. **Task 3: DisbursementIdempotencyRetryIT** - `03968b5` (feat)

## Files Created/Modified
- `src/main/java/com/softropic/payam/disbursement/contract/DisbursementStatus.java` - FAILED.allowedTransitions() returns EnumSet.of(INITIATED)
- `src/main/java/com/softropic/payam/disbursement/service/DisbursementRetryClassifier.java` - NEW: @Component classifying error codes
- `src/main/java/com/softropic/payam/disbursement/service/DisbursementOrchestrator.java` - handleRetry method + 15-param constructor + refactored cache-hit branch
- `src/test/java/com/softropic/payam/disbursement/contract/DisbursementStatusTest.java` - failedAllowedTransitions + failedToInitiatedSucceeds replace failedIsTerminal
- `src/test/java/com/softropic/payam/disbursement/service/DisbursementRetryClassifierTest.java` - NEW: 12 tests covering retriable/terminal/null/unknown codes
- `src/test/java/com/softropic/payam/disbursement/service/DisbursementOrchestratorTest.java` - 6 new retry tests + 2 new mock fields
- `src/test/java/com/softropic/payam/disbursement/service/DisbursementIdempotencyRetryIT.java` - NEW: 3 @Test methods (IDEM-01/02/03) against real DB + WireMock

## Decisions Made
- Conservative classification (Option B from RESEARCH Open Question 1): only PROVIDER_ERROR and PROVIDER_UNAVAILABLE are RETRIABLE. Phase 58 E2E tests will surface any misclassification.
- Audit trail preserved: reactivation uses `transitionClaims(id, RELEASED, PENDING)` which runs UPDATE — zero new DisbursementTransactionRef rows are inserted on retry.
- IDEM-01 guard probes ACTIVE_CLAIM_STATUSES = {PENDING, CLAIMED} BEFORE acquiring the PESSIMISTIC_WRITE lock, avoiding unnecessary lock contention.
- 15-parameter DisbursementOrchestrator constructor: DisbursementRetryClassifier (param 14) and DisbursementTransactionRefRepository (param 15) added at the end.

## Deviations from Plan

None — plan executed exactly as written. Unit tests (65 across 3 test classes) and integration tests (3 IDEM tests) all pass.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- IDEM-01, IDEM-02, IDEM-03 requirements complete
- Phase 57 Plan 02 (V32 migration scaffold) runs in parallel (Wave 1, non-overlapping files)
- Phase 58 can use the classifier expansion point: adding new RETRIABLE codes is a one-line change to DisbursementRetryClassifier.RETRIABLE_CODES

---
*Phase: 57-idempotency-retry-recovery-v32-migration-scaffold*
*Completed: 2026-05-05*
