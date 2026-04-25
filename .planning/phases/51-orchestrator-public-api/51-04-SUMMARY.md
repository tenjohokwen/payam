---
phase: 51-orchestrator-public-api
plan: "04"
subsystem: disbursement
tags: [rest-api, quartz, expiry-job, sec-04, bal-03, disb-02, disb-03, disb-04]
dependency-graph:
  requires: [51-03]
  provides: [disbursement-http-layer, disbursement-expiry-job]
  affects: [disbursement-orchestrator, wallet-balance, tenant-isolation]
tech-stack:
  added: []
  patterns:
    - native-sql-for-null-safe-pagination
    - postgres-now-interval-for-timezone-safe-expiry
    - quartz-direct-invocation-in-tests
key-files:
  created:
    - src/main/java/com/softropic/payam/disbursement/api/DisbursementResource.java
    - src/main/java/com/softropic/payam/disbursement/service/DisbursementExpiryJob.java
    - src/main/java/com/softropic/payam/disbursement/config/DisbursementSchedulerConfig.java
    - src/test/java/com/softropic/payam/disbursement/api/DisbursementResourceIT.java
    - src/test/java/com/softropic/payam/disbursement/service/DisbursementExpiryJobIT.java
  modified:
    - src/main/java/com/softropic/payam/disbursement/contract/DisbursementRequest.java
    - src/main/java/com/softropic/payam/disbursement/repo/DisbursementRepository.java
    - src/main/java/com/softropic/payam/disbursement/service/DisbursementExpiryJob.java
decisions:
  - "findForTenant uses native SQL (not JPQL) because PostgreSQL cannot infer the type of null enum parameters in JPQL prepared statements — causes PGSQL-00 type inference error"
  - "findExpiredCandidates uses NOW() - INTERVAL inside SQL (not Instant parameter) because Hibernate 6 maps Instant as TIMESTAMPTZ but disbursement.created_date is TIMESTAMP (no tz) — the type mismatch causes silent 2-hour offset skew on JVM at +02:00"
  - "DisbursementRequest.idempotencyKey has no @NotBlank: @Valid fires on raw JSON body before controller injects header value — HTTP layer enforces presence via @RequestHeader which returns 400 if missing"
  - "DisbursementExpiryJobIT inserts rows using NOW() - INTERVAL DB-side to ensure consistent comparison against the same PostgreSQL clock, avoiding JVM-to-JDBC Timestamp conversion drift"
metrics:
  duration: "~75 minutes (including debugging session continued from previous context)"
  completed: "2026-04-25"
  tasks: 3
  files: 7
---

# Phase 51 Plan 04: REST HTTP Layer + SEC-04 Expiry Job Summary

Wired the disbursement HTTP layer (4 REST endpoints) and the Quartz expiry job that ages unconfirmed step-up disbursements to EXPIRED without releasing wallet balance (BAL-03). 10 integration tests green.

## Tasks Completed

| Task | Description | Commit | Files |
|------|-------------|--------|-------|
| 1 | DisbursementResource — 4 REST endpoints | 482d458 | DisbursementResource.java |
| 2 | DisbursementExpiryJob + DisbursementSchedulerConfig | 70b967e | DisbursementExpiryJob.java, DisbursementSchedulerConfig.java |
| 3 | DisbursementResourceIT (6 tests) + DisbursementExpiryJobIT (4 tests) | 6efbbf9 | DisbursementResourceIT.java, DisbursementExpiryJobIT.java |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Removed @NotBlank from DisbursementRequest.idempotencyKey**
- **Found during:** Task 3 — `DisbursementResourceIT` all POST tests failed with `rejected value [null]`
- **Issue:** `@Valid` validates the raw JSON body before the controller injects the Idempotency-Key header. `idempotencyKey` is always null in the deserialized body, causing `@NotBlank` to fail before the controller can populate the field.
- **Fix:** Removed `@NotBlank` from `idempotencyKey`. The HTTP layer enforces presence via `@RequestHeader("Idempotency-Key")` which Spring returns 400 for if the header is missing. No security regression.
- **Files modified:** `src/main/java/com/softropic/payam/disbursement/contract/DisbursementRequest.java`
- **Commit:** 6efbbf9

**2. [Rule 1 - Bug] Replaced JPQL findForTenant with native SQL to fix null enum type inference**
- **Found during:** Task 3 — `DisbursementResourceIT.list_pagination_size1_returnsPagedResult` failed with `ERROR: could not determine data type of parameter $4`
- **Issue:** JPQL `(:status IS NULL OR d.disbursementStatus = :status)` fails in PostgreSQL when `status` is null — the JDBC driver cannot infer the column type from a bare null parameter.
- **Fix:** Replaced with native SQL passing all optional params as `String`. Status passed as `status.name()`, Instants as `Instant.toString()` (ISO-8601). PostgreSQL can unambiguously handle typed varchar/null parameters.
- **Files modified:** `src/main/java/com/softropic/payam/disbursement/repo/DisbursementRepository.java`, `src/main/java/com/softropic/payam/disbursement/api/DisbursementResource.java`
- **Commit:** 6efbbf9

**3. [Rule 1 - Bug] Replaced findByDisbursementStatusAndCreatedDateBefore with NOW()-based native query**
- **Found during:** Task 3 — `DisbursementExpiryJobIT.expiryJob_agedPendingConfirmation_transitionsToExpired` failed with `expected: EXPIRED but was: PENDING_CONFIRMATION`
- **Issue:** Hibernate 6 maps `Instant` parameters as `TIMESTAMP WITH TIME ZONE` (TIMESTAMPTZ). The `disbursement.created_date` column is `TIMESTAMP` (no tz, from V25 migration). Comparing TIMESTAMPTZ against TIMESTAMP via a prepared-statement parameter causes a timezone-offset skew of 2 hours at JVM UTC+2. The 16-minute-old test row appeared newer than the 15-minute threshold to Hibernate.
- **Investigation path:** Added JDBC raw-SQL debug assertions which passed (PostgreSQL saw the row as aged via `NOW() - INTERVAL '15 minutes'`), but the JPA query returned 0 candidates. Confirmed type mismatch.
- **Fix:** New native query `findExpiredCandidates(status, ageMinutes)` computes the threshold entirely in PostgreSQL as `NOW() - CAST(:ageMinutes || ' minutes' AS INTERVAL)`, eliminating all JVM timezone binding. DisbursementExpiryJob updated to pass `EXPIRY_AGE.toMinutes()` instead of an `Instant`. Test inserts use `NOW() - INTERVAL '16 minutes'` DB-side for the same reason.
- **Files modified:** `src/main/java/com/softropic/payam/disbursement/repo/DisbursementRepository.java`, `src/main/java/com/softropic/payam/disbursement/service/DisbursementExpiryJob.java`, `src/test/java/com/softropic/payam/disbursement/service/DisbursementExpiryJobIT.java`
- **Commit:** 6efbbf9

## Verification Results

- `DisbursementResourceIT`: 6/6 tests PASSED
  - `post_happy_path_returns_202_with_disbursement_id`
  - `getById_wrongTenant_returns404` (DISB-02)
  - `getById_unknownId_returns404` (DISB-02)
  - `list_filterByStatus_returnsTenantScopedPage` (DISB-03)
  - `list_pagination_size1_returnsPagedResult` (DISB-03)
  - `confirm_pendingDisbursement_dispatches` (DISB-04)

- `DisbursementExpiryJobIT`: 4/4 tests PASSED
  - `expiryJob_agedPendingConfirmation_transitionsToExpired` (SEC-04 + BAL-03)
  - `expiryJob_freshPendingConfirmation_isNotExpired`
  - `expiryJob_agedProcessingDisbursement_isNotExpired`
  - `expiryJob_emptyResultSet_completesWithoutError`

## Known Stubs

None — all endpoints wire to real implementations with database backing.

## Self-Check: PASSED

All 5 created files confirmed on disk. All 3 task commits confirmed in git log:
- 482d458 (Task 1: DisbursementResource)
- 70b967e (Task 2: DisbursementExpiryJob + DisbursementSchedulerConfig)
- 6efbbf9 (Task 3: DisbursementResourceIT + DisbursementExpiryJobIT)
