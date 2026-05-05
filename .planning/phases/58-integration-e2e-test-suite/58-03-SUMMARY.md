---
phase: 58-integration-e2e-test-suite
plan: "03"
subsystem: disbursement-e2e
tags: [e2e, admin-approval, claim-lifecycle, quartz, reflection, jdbctemplate]
dependency_graph:
  requires:
    - Phase 56 DisbursementAdminApprovalExpiryJob
    - Phase 54 DisbursementTransactionRef DDL + DisbursementRefStatus enum
    - Phase 58-01 claim assertion idiom (raw JDBC on disbursement_transaction_ref via BIGINT PK)
  provides:
    - ADMIN-01 assertion (HTTP POST produces PENDING_ADMIN_APPROVAL with PENDING claims and zero provider calls)
    - ADMIN-03 assertion (DisbursementAdminApprovalExpiryJob ages and expires admin-approval rows)
    - CLAIM-04 assertion (all PENDING claims released to RELEASED on admin-approval expiry)
    - Negative-control test (fresh PENDING_ADMIN_APPROVAL row not touched by expiry job)
  affects:
    - 58-integration-e2e-test-suite (phase gate: mvn verify must pass with both tests green)
tech_stack:
  added: []
  patterns:
    - Standalone E2E IT (no abstract base) matching DisbursementExpiryE2EIT pattern
    - spring.quartz.auto-startup=false prevents background Quartz threads racing direct job invocation
    - executeInternal invoked via reflection (protected on QuartzJobBean; different package)
    - assertClaimStatuses() uses raw JDBC joined on disbursement_id BIGINT PK (not UUID column)
    - DB-side INTERVAL backdating for both aged row (120 min) and fresh-row anchor (2 min — avoids JVM/DB clock skew)
    - No @Transactional on class or test methods (AFTER_COMMIT listeners never fire in rolled-back transactions)
    - No balance/wallet seed required — V32 retired MerchantWalletBalance from orchestrator path (SCHEMA-03)
key_files:
  created:
    - src/test/java/com/softropic/payam/e2e/disbursement/DisbursementAdminApprovalE2EIT.java
  modified: []
decisions:
  - "Fresh-row negative-control test anchors created_date to NOW()-2min via DB-side backdateDisbursement — same JVM/DB clock skew guard as DisbursementExpiryE2EIT.freshPendingConfirmation_isNotExpired. Without the anchor, the application-written timestamp can appear stale to the DB server's NOW(), causing false positive expiry."
  - "Reference prefix for test 2 shortened from 'REF-ADMIN-FRESH-' (16 chars + 36 UUID = 52) to 'REF-FRESH-' (10 chars + 36 UUID = 46) to satisfy @Size(max=50) constraint on DisbursementRequest.reference"
  - "seedTxnsForClaim uses Long id from ThreadLocalRandom (can produce negative values) — String.valueOf(negLong) passed as transactionId works because the orchestrator stores it as VARCHAR"
metrics:
  duration_minutes: 45
  completed_date: "2026-05-05"
  tasks_completed: 1
  files_created: 1
  files_modified: 0
requirements:
  - CLAIM-04
  - ADMIN-01
  - ADMIN-03
---

# Phase 58 Plan 03: DisbursementAdminApprovalE2EIT — HTTP-driven Admin-Approval Lifecycle Summary

HTTP-driven E2E test class for the full PENDING_ADMIN_APPROVAL → expiry job → EXPIRED + claims RELEASED lifecycle, proving ADMIN-01, ADMIN-03, and CLAIM-04 jointly at the HTTP boundary.

## Tasks Completed

| Task | Description | Commit |
|------|-------------|--------|
| 1 | Create DisbursementAdminApprovalE2EIT with 2 @Test methods — HTTP → PENDING_ADMIN_APPROVAL → expiry + claims RELEASED, and negative control (fresh row not expired) | ed69b8d |

## Changes Made

**DisbursementAdminApprovalE2EIT.java** (new file, 411 lines, 2 @Test methods):

- `httpInitiatedAdminApproval_expiresViaJob_releasesAllClaims_E2E`: Seeds 3 collection transactions, POSTs 6M XAF disbursement, asserts 202 + PENDING_ADMIN_APPROVAL status. Verifies 3 PENDING claim rows immediately (ADMIN-01), confirms zero WireMock calls to /v1_0/transfer. Backdates row 120 minutes DB-side. Invokes DisbursementAdminApprovalExpiryJob.executeInternal(null) via reflection. Asserts disbursement_status = EXPIRED (ADMIN-03) and all 3 claim rows = RELEASED (CLAIM-04). Final GET confirms HTTP-visible state EXPIRED.
- `freshAdminApproval_isNotExpired_claimsRemainPending`: Seeds 1 transaction, POSTs 6M XAF, asserts PENDING_ADMIN_APPROVAL. Anchors created_date to NOW()-2min via DB-side INTERVAL (guards JVM/DB clock skew). Invokes job. Asserts status remains PENDING_ADMIN_APPROVAL and claim remains PENDING (negative control).
- Helpers: `seedTxnsForClaim`, `postDisbursement`, `getDisbursement`, `backdateDisbursement`, `invokeAdminApprovalExpiryJob`, `assertClaimStatuses`, `parseDisbursementId`, `parseStatus`
- Annotations: @ActiveProfiles + @SpringBootTest(RANDOM_PORT, quartz.auto-startup=false) + @TestPropertySource(admin-approval-timeout-hours=1) + @EnableWireMock(dual MTN+Orange topology)

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fresh-row test expired instead of holding PENDING_ADMIN_APPROVAL**

- **Found during:** Task 1 (second test run — `freshAdminApproval_isNotExpired_claimsRemainPending` failed)
- **Issue:** Without DB-side timestamp anchoring, the JVM-written `created_date` for the fresh row appeared older than 60 minutes to PostgreSQL's `NOW()` due to JVM/DB clock drift in the Testcontainers environment. The plan's blueprint for Test 2 did not include backdateDisbursement — unlike the analogous DisbursementExpiryE2EIT.freshPendingConfirmation_isNotExpired which anchors to 2 minutes.
- **Fix:** Added `backdateDisbursement(disbursementId, 2)` before `invokeAdminApprovalExpiryJob()` in Test 2, matching the exact guard pattern from the reference class.
- **Files modified:** `DisbursementAdminApprovalE2EIT.java`
- **Commit:** ed69b8d (inlined — same commit as task)

**2. [Rule 1 - Bug] Reference field exceeded 50-char @Size constraint in Test 2**

- **Found during:** Task 1 (first test run — 400 Invalid Data on reference field)
- **Issue:** `"REF-ADMIN-FRESH-" + UUID.randomUUID()` = 16 + 36 = 52 chars, exceeding `@Size(max=50)` on `DisbursementRequest.reference`. The plan's context notes this constraint but the blueprint used the oversized prefix.
- **Fix:** Shortened prefix to `"REF-FRESH-"` (10 chars + 36 UUID = 46 chars), within the 50-char limit.
- **Files modified:** `DisbursementAdminApprovalE2EIT.java`
- **Commit:** ed69b8d (inlined — same commit as task)

**3. [Rule - Comment] Wallet references removed from class Javadoc**

- **Found during:** Task 1 (acceptance criteria check: `grep -i "wallet"` returned comments)
- **Issue:** Plan's class-level Javadoc contained `{@code MerchantWalletBalance}` in the "wallet not required" explanation. The acceptance criteria requires zero wallet references.
- **Fix:** Replaced with neutral language referencing V32 Flyway migration and SCHEMA-03.
- **Commit:** ed69b8d (inlined)

## Self-Check

- [x] File `src/test/java/com/softropic/payam/e2e/disbursement/DisbursementAdminApprovalE2EIT.java` exists
- [x] `class DisbursementAdminApprovalE2EIT` present
- [x] `payam.disbursement.admin-approval-timeout-hours=1` in @TestPropertySource
- [x] `spring.quartz.auto-startup=false` in @SpringBootTest properties
- [x] `ADMIN_APPROVAL_AMOUNT = new BigDecimal("6000000")` constant
- [x] `@Autowired DisbursementAdminApprovalExpiryJob adminApprovalExpiryJob` field
- [x] Both @Test methods present
- [x] `assertClaimStatuses(disbursementId, "PENDING", 3)` present
- [x] `assertClaimStatuses(disbursementId, "RELEASED", 3)` present
- [x] `mtnServer.verify(0, postRequestedFor(...))` present (x2)
- [x] `getDeclaredMethod("executeInternal", org.quartz.JobExecutionContext.class)` present
- [x] `NOW() - CAST(? || ' minutes' AS INTERVAL)` present
- [x] No @Transactional on class or test methods
- [x] No wallet references in file
- [x] `mvn verify -Dit.test=DisbursementAdminApprovalE2EIT` exits 0 — Tests run: 2, Failures: 0, Errors: 0
- [x] No production files modified (git diff src/main/java/ is empty)
- [x] Commit ed69b8d exists

## Self-Check: PASSED
