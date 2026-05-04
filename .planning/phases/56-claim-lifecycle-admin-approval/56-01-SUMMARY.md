---
phase: 56-claim-lifecycle-admin-approval
plan: "01"
subsystem: disbursement-claim-infrastructure
tags: [disbursement, claim, repository, service, configuration, events, email, v11]
dependency_graph:
  requires: [55-01, 55-02, 55-03]
  provides:
    - DisbursementTransactionRefRepository.updateRefStatusForDisbursement
    - DisbursementClaimTransitionService.transitionClaims
    - DisbursementProperties (adminApprovalThreshold, adminApprovalTimeoutHours, adminApprovalExpiryCron)
    - DisbursementConfig (@EnableConfigurationProperties)
    - DisbursementAdminApprovalRequiredEvent (record)
    - InsufficientFundsAlertEvent (record)
    - EmailTemplate.DISBURSEMENT_ADMIN_APPROVAL_REQUIRED
    - EmailTemplate.DISBURSEMENT_INSUFFICIENT_FUNDS_ALERT
  affects:
    - Plans 56-02 (admin approval gate wiring)
    - Plans 56-03 (insufficient funds alert + ops email listener)
tech_stack:
  added: []
  patterns:
    - bulk-modifying-jpql-update
    - configurationproperties-enableconfigurationproperties-pair
    - record-event-contract
    - logback-listappender-log-assertion
key_files:
  created:
    - src/main/java/com/softropic/payam/disbursement/service/DisbursementClaimTransitionService.java
    - src/main/java/com/softropic/payam/disbursement/config/DisbursementProperties.java
    - src/main/java/com/softropic/payam/disbursement/config/DisbursementConfig.java
    - src/main/java/com/softropic/payam/disbursement/contract/event/DisbursementAdminApprovalRequiredEvent.java
    - src/main/java/com/softropic/payam/disbursement/contract/event/InsufficientFundsAlertEvent.java
    - src/test/java/com/softropic/payam/disbursement/service/DisbursementClaimTransitionServiceTest.java
  modified:
    - src/main/java/com/softropic/payam/disbursement/repo/DisbursementTransactionRefRepository.java
    - src/main/java/com/softropic/payam/email/contract/EmailTemplate.java
    - src/main/resources/application.yaml
    - src/test/resources/application.properties
decisions:
  - "Log-level fix in DisbursementClaimTransitionServiceTest: logback-test.xml sets root level=WARN; ListAppender captures nothing at INFO unless the specific logger level is explicitly set to INFO before the test — setLevel/restore pattern applied"
  - "adminApprovalExpiryCron added to DisbursementProperties (not in original plan spec) to centralize all three Plan 03 Quartz config keys in one properties class — consistent with Plan 03 dependency declaration"
  - "Merged main branch into worktree before execution — Phase 55 artifacts (TransactionClaimValidationService, DisbursementTransactionRef, DisbursementRefStatus, DisbursementTransactionRefRepository) were on main but not in worktree branch"
metrics:
  duration: "~11 minutes"
  completed_date: "2026-05-04"
  tasks_completed: 4
  files_changed: 10
---

# Phase 56 Plan 01: Claim Lifecycle Infrastructure Summary

Built the shared infrastructure contracts for Phase 56: bulk claim-status repository method, DisbursementClaimTransitionService orchestrator, DisbursementProperties configuration class, two Spring event records, and two EmailTemplate enum entries — all compilable and unit-tested, no call sites wired (Plans 02 and 03 consume these).

## Tasks Completed

| Task | Name | Commit | Key Files |
|------|------|--------|-----------|
| 0 | Verify CLAIM-01 delivered by Phase 55 (no-op) | merge | TransactionClaimValidationService.java (verified) |
| 1+2 | Bulk repo method + DisbursementClaimTransitionService + unit tests | 6ed54ec | DisbursementTransactionRefRepository.java, DisbursementClaimTransitionService.java, DisbursementClaimTransitionServiceTest.java |
| 3 | DisbursementProperties + DisbursementConfig + event records + EmailTemplate + YAML | 31870b9 | DisbursementProperties.java, DisbursementConfig.java, DisbursementAdminApprovalRequiredEvent.java, InsufficientFundsAlertEvent.java, EmailTemplate.java, application.yaml, application.properties |

## CLAIM-01 Closure Note

CLAIM-01 ("PENDING claim rows created atomically at disbursement acceptance") was delivered in Phase 55 by `TransactionClaimValidationService.validateAndClaim()`, which is wired into `DisbursementOrchestrator` Step 7.5. Task 0 confirmed via grep:
- `TransactionClaimValidationService.java` exists at Phase 55 path
- `validateAndClaim` method symbol present
- `DisbursementRefStatus.PENDING` referenced in the service
- `DisbursementOrchestrator` wires `transactionClaimValidationService.validateAndClaim`

No Phase 56 code is required for CLAIM-01.

## New Repository Method

**File:** `src/main/java/com/softropic/payam/disbursement/repo/DisbursementTransactionRefRepository.java`

```java
@Modifying
@Query("UPDATE DisbursementTransactionRef r " +
       "SET r.refStatus = :target " +
       "WHERE r.disbursementId = :disbursementId " +
       "AND r.refStatus = :current")
int updateRefStatusForDisbursement(
    @Param("disbursementId") Long disbursementId,
    @Param("current") DisbursementRefStatus current,
    @Param("target") DisbursementRefStatus target);
```

Single SQL UPDATE — no N+1, no entity load. Returns 0 safely (no claims case handled by callers).

## DisbursementClaimTransitionService API

**File:** `src/main/java/com/softropic/payam/disbursement/service/DisbursementClaimTransitionService.java`

```java
@Transactional
public int transitionClaims(Long disbursementId,
                            DisbursementRefStatus current,
                            DisbursementRefStatus target)
```

**Transaction propagation:** `@Transactional(REQUIRED)` — joins any existing transaction from the caller. When called from `DisbursementCallbackTransitionService` (REQUIRES_NEW) or `DisbursementOrchestrator` (TransactionTemplate), the disbursement state change and claim transition commit atomically.

**Structured logging:** emits `kv("operation", "dsb_claim_transition")` at INFO level with disbursementId, fromStatus, toStatus, rowsAffected.

## DisbursementProperties Keys + Defaults

**File:** `src/main/java/com/softropic/payam/disbursement/config/DisbursementProperties.java`

| Property | Type | Default | Env var override |
|----------|------|---------|-----------------|
| `payam.disbursement.admin-approval-threshold` | BigDecimal | 5,000,000 | `PAYAM_DISBURSEMENT_ADMIN_APPROVAL_THRESHOLD` |
| `payam.disbursement.admin-approval-timeout-hours` | int | 24 | `PAYAM_DISBURSEMENT_ADMIN_APPROVAL_TIMEOUT_HOURS` |
| `payam.disbursement.admin-approval-expiry-cron` | String | `0 * * * * ?` | `PAYAM_DISBURSEMENT_ADMIN_APPROVAL_EXPIRY_CRON` |

**Test override location:** `src/test/resources/application.properties` (lines added at end). Tests requiring a lower threshold use `@TestPropertySource`.

**Note:** `adminApprovalExpiryCron` is consumed by Plan 03's Quartz scheduler config bean — declared here so YAML binding has all disbursement keys upfront.

## New EmailTemplate Entries

| Enum constant | Subject key |
|---------------|-------------|
| `DISBURSEMENT_ADMIN_APPROVAL_REQUIRED` | `email.disbursement.admin_approval_required.title` |
| `DISBURSEMENT_INSUFFICIENT_FUNDS_ALERT` | `email.disbursement.insufficient_funds_alert.title` |

Plan 03's listener wires the email templates to these entries.

## New Event Record Signatures

**DisbursementAdminApprovalRequiredEvent** (Plan 02 publisher / Plan 03 listener):
```java
public record DisbursementAdminApprovalRequiredEvent(
    String disbursementId, Long tenantId, BigDecimal amount, String currency,
    String recipientMsisdn, String reference, String adminNote, Instant submittedAt)
```

**InsufficientFundsAlertEvent** (Plan 03 publisher/listener):
```java
public record InsufficientFundsAlertEvent(
    String disbursementId, Long tenantId, MobilePaymentProvider provider,
    BigDecimal amount, String currency, String providerErrorCode,
    String providerMessage, Instant failedAt)
```

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed logger level in DisbursementClaimTransitionServiceTest logging test**
- **Found during:** Task 2 GREEN phase
- **Issue:** `logback-test.xml` sets root level to WARN; `ListAppender` captured zero events because INFO-level log was suppressed before reaching the appender
- **Fix:** Added `logbackLogger.setLevel(Level.INFO)` before starting the appender, with restore in `finally` block
- **Files modified:** `src/test/java/com/softropic/payam/disbursement/service/DisbursementClaimTransitionServiceTest.java`
- **Commit:** 6ed54ec

**2. [Rule 3 - Deviation] Added adminApprovalExpiryCron field to DisbursementProperties**
- **Found during:** Task 3 action review
- **Issue:** Plan 03 Quartz config bean requires `admin-approval-expiry-cron` YAML key; plan specified adding it to YAML but not to DisbursementProperties class
- **Fix:** Added `adminApprovalExpiryCron` field with getter/setter to DisbursementProperties so Plan 03 can inject the bean and read it directly
- **Files modified:** `src/main/java/com/softropic/payam/disbursement/config/DisbursementProperties.java`
- **Commit:** 31870b9

**3. [Rule 3 - Blocking] Merged main branch before execution**
- **Found during:** Task 0
- **Issue:** Phase 55 artifacts (TransactionClaimValidationService, DisbursementTransactionRef, DisbursementRefStatus, DisbursementTransactionRefRepository) were on main but absent in this worktree branch
- **Fix:** `git merge main --no-edit` — fast-forward merge brought in all Phase 55 work
- **Commit:** merge commit (already on branch)

## Self-Check: PASSED

Files verified present:
- `src/main/java/com/softropic/payam/disbursement/service/DisbursementClaimTransitionService.java` — FOUND
- `src/main/java/com/softropic/payam/disbursement/config/DisbursementProperties.java` — FOUND
- `src/main/java/com/softropic/payam/disbursement/config/DisbursementConfig.java` — FOUND
- `src/main/java/com/softropic/payam/disbursement/contract/event/DisbursementAdminApprovalRequiredEvent.java` — FOUND
- `src/main/java/com/softropic/payam/disbursement/contract/event/InsufficientFundsAlertEvent.java` — FOUND
- `src/test/java/com/softropic/payam/disbursement/service/DisbursementClaimTransitionServiceTest.java` — FOUND

Commits verified:
- 6ed54ec — feat(56-01): add bulk claim-status update repo method + DisbursementClaimTransitionService — FOUND
- 31870b9 — feat(56-01): add DisbursementProperties, DisbursementConfig, event records, and EmailTemplate entries — FOUND

Build: `mvn compile -q` — PASS
Tests: `DisbursementClaimTransitionServiceTest` 4/4 — PASS
