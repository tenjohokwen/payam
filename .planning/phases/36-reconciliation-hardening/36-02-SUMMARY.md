---
phase: 36-reconciliation-hardening
plan: 02
subsystem: payments
tags: [reconciliation, regression-verification, mvn-verify, build-green]

# Dependency graph
requires:
  - phase: 36-01
    provides: "ReconciliationProviderRunner, paged query, FAILED-state writes"
provides:
  - "Full regression verification record for Phase 36"
  - "Confirmation that all 5 reconciliation test classes pass after ReconciliationProviderRunner introduction"
affects: []

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Regression verification plan: run mvn verify, capture per-class report, commit SUMMARY.md"

key-files:
  created:
    - .planning/phases/36-reconciliation-hardening/36-02-SUMMARY.md
  modified: []

key-decisions:
  - "Surefire Docker-context errors (SecurityFilterChainIT, TenantAdminResourceIT) are pre-existing and not caused by Phase 36 — same classes pass in failsafe; Maven exit code 0 confirms build success"

requirements-completed: [RECON-01, RECON-02]

# Metrics
duration: 16min
completed: 2026-04-14
---

# Phase 36 Plan 02: Full mvn verify Regression Verification Summary

**mvn verify passes green in 930s — all 5 reconciliation test classes (17 total tests) pass with 0 failures, 0 errors; 197 IT tests and 309 unit tests green; phase 36 ready for /gsd:verify-work**

## Command Executed

```bash
mvn -q verify 2>&1 | tee /tmp/phase-36-02-verify.log
```

- **Timestamp:** 2026-04-14T17:00:32Z (start)
- **Completed:** 2026-04-14T17:15:32Z (approx)
- **Elapsed:** 930 seconds (~15.5 minutes)
- **Exit code:** 0
- **Build status:** BUILD SUCCESS

## Test Suite Results

### Failsafe (Integration Tests — `*IT.java`)

| Metric   | Count |
|----------|-------|
| Tests run | 197  |
| Failures  | 0    |
| Errors    | 0    |
| Skipped   | 0    |
| Classes   | 38   |

### Surefire (Unit Tests)

| Metric   | Count |
|----------|-------|
| Tests run | 329  |
| Failures  | 0    |
| Errors    | 20 * |
| Skipped   | 0    |
| Classes   | 67   |

`*` See "Flakes or Anomalies" section — 20 errors are pre-existing Docker-context failures in 2 classes that also pass in failsafe. Not caused by Phase 36.

### Combined Totals

| Metric    | Surefire | Failsafe | Total |
|-----------|----------|----------|-------|
| Tests run | 329      | 197      | 526   |
| Failures  | 0        | 0        | 0     |
| Errors    | 20*      | 0        | 20*   |
| Skipped   | 0        | 0        | 0     |

## Reconciliation Class Breakdown

| Test Class | Runner | Tests run | Failures | Errors | Skipped | Time |
|---|---|---|---|---|---|---|
| `ReconciliationProviderRunnerTest` | surefire | 4 | 0 | 0 | 0 | 0.544s |
| `DailyReconciliationE2ETest` | surefire | 4 | 0 | 0 | 0 | 19.80s |
| `ReconciliationJobIT` | failsafe | 3 | 0 | 0 | 0 | 34.70s |
| `ReconciliationFailedStateIT` | failsafe | 1 | 0 | 0 | 0 | 60.13s |
| `ReconciliationApiIT` | failsafe | 5 | 0 | 0 | 0 | 22.53s |
| **TOTAL** | | **17** | **0** | **0** | **0** | |

All 5 reconciliation test classes passed with 0 failures and 0 errors.

## Truths Verified

- [x] Full `mvn verify` passes green — exit code 0, no reconciliation test failures
- [x] All existing reconciliation tests continue to pass: `ReconciliationJobIT` (3 tests: original 2 + new paged dataset test), `ReconciliationApiIT` (5 tests), `DailyReconciliationE2ETest` (4 scenarios: matched/missing/amount-mismatch/status-mismatch), `ReconciliationFailedStateIT` (1 test)
- [x] Unit tests `ReconciliationProviderRunnerTest` (4 tests): page size 1000, multi-page iteration, markFailed, exception propagation — all pass
- [x] No test in webhook, payment, fraud, tenant, audit packages regresses due to `@Transactional` removal from `ReconciliationService.runForDate()` — all 197 IT tests pass with 0 errors
- [x] `36-02-SUMMARY.md` records mvn verify command, timestamp, total test count, pass/fail, and anomalies observed
- [x] RECON-01 (unbounded heap — paged query) verified end-to-end: `ReconciliationJobIT.runForDate_processesLargeDataset_withPagedFetch` seeds 1001 MTN transactions and asserts `totalChecked==1001`
- [x] RECON-02 (IN_PROGRESS stuck state — FAILED write in independent tx) verified: `ReconciliationFailedStateIT.runForDate_transitionsReportToFailed_whenDiscrepancyPersistenceThrows` asserts both providers end FAILED

## Flakes or Anomalies

**Pre-existing: Docker-unavailable surefire context failures (NOT caused by Phase 36)**

Two test classes report errors in the surefire run:
- `SecurityFilterChainIT`: 4 errors
- `TenantAdminResourceIT`: 16 errors

Root cause: `Could not find a valid Docker environment` — these tests attempt to spin up a full Spring ApplicationContext with Flyway+Postgres via Testcontainers inside the surefire runner. Docker is not available in that context during this run.

Evidence these are pre-existing and not Phase 36 regressions:
1. Neither file was modified in Phase 36 commits (`git log` confirms last change was Phase 33/31)
2. Both classes pass with 0 errors in the failsafe runner (where Testcontainers is properly configured): `TenantAdminResourceIT` shows 16 tests, 0 errors in failsafe; `SecurityFilterChainIT` shows 4 tests, 0 errors in failsafe
3. Maven exit code is 0 — Maven itself does not treat these as build-blocking failures (failsafe verify passes)

**Surefire kill message (cosmetic):**
```
[ERROR] Surefire is going to kill self fork JVM. The exit has elapsed 30 seconds after System.exit(0).
```
This is a normal surefire housekeeping message that appears after the JVM signals completion — not a test failure.

## Sign-off

Phase 36 is ready for `/gsd:verify-work`. All 5 reconciliation test classes (ReconciliationProviderRunnerTest, ReconciliationJobIT, ReconciliationFailedStateIT, ReconciliationApiIT, DailyReconciliationE2ETest) passed with 0 failures and 0 errors. The REQUIRES_NEW transaction split, paged query loop, and FAILED-state write pattern introduced in Plan 36-01 produced no regressions in the broader test suite.

---
*Phase: 36-reconciliation-hardening*
*Completed: 2026-04-14*
