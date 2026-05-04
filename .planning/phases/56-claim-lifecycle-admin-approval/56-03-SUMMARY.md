---
phase: 56-claim-lifecycle-admin-approval
plan: "03"
subsystem: disbursement-expiry-and-ops-alerts
tags: [disbursement, quartz, expiry, email, listener, claim, admin-approval, v11]
dependency_graph:
  requires: [56-01]
  provides:
    - DisbursementAdminApprovalExpiryJob (CLAIM-04 + ADMIN-03)
    - DisbursementSchedulerConfig (extended with admin-approval expiry beans)
    - DisbursementOpsAlertEmailListener (ADMIN-02 + ALERT-01)
    - DisbursementAdminApprovalExpiryJobIT (end-to-end proof)
  affects:
    - Closes the loop on Plan 01's adminApprovalTimeoutHours (first real consumer)
    - Closes the loop on Plan 02's DisbursementAdminApprovalRequiredEvent + InsufficientFundsAlertEvent (first listeners)
tech_stack:
  added: []
  patterns:
    - quartz-quartzjobbean-disallowconcurrentexecution
    - cron-trigger-property-driven
    - spring-eventlistener-envelope-publish
    - jdbctemplate-seeding-backdating-it
key_files:
  created:
    - src/main/java/com/softropic/payam/disbursement/service/DisbursementAdminApprovalExpiryJob.java
    - src/main/java/com/softropic/payam/email/infrastructure/listener/DisbursementOpsAlertEmailListener.java
    - src/test/java/com/softropic/payam/disbursement/service/DisbursementAdminApprovalExpiryJobIT.java
    - src/test/java/com/softropic/payam/email/infrastructure/listener/DisbursementOpsAlertEmailListenerTest.java
  modified:
    - src/main/java/com/softropic/payam/disbursement/config/DisbursementSchedulerConfig.java
decisions:
  - "Two separate expiry jobs with distinct Quartz identities (Pitfall 6 in 56-RESEARCH) — DisbursementExpiryJob (PENDING_CONFIRMATION, 15-min hardcoded) and DisbursementAdminApprovalExpiryJob (PENDING_ADMIN_APPROVAL, configurable hours) are NOT merged"
  - "Admin-approval expiry cron uses CronScheduleBuilder (not SimpleSchedule) because the timeout window is in hours — administrators may tune cadence to match longer timeouts"
  - "withMisfireHandlingInstructionDoNothing on cron trigger — catch-up scans suppressed to avoid DB floods after downtime"
  - "No WalletBalanceService.release() call — wallet model retired in V31 (SCHEMA-03)"
  - "DisbursementOpsAlertEmailListener uses Spring @EventListener (NOT Quarkus @Observes) — verified against PlatformConfigEmailListener precedent"
metrics:
  duration: "~54 minutes"
  completed_date: "2026-05-04"
  tasks_completed: 4
  files_changed: 5
---

# Phase 56 Plan 03: Expiry Job + Ops Alert Email Listener Summary

Built the two consumers that close the loop on Plans 01 and 02: `DisbursementAdminApprovalExpiryJob` (Quartz job that ages PENDING_ADMIN_APPROVAL disbursements to EXPIRED and atomically releases their PENDING claims), `DisbursementSchedulerConfig` extension (registers the new job with a cron trigger), `DisbursementOpsAlertEmailListener` (Spring @EventListener for both ops notification events), and `DisbursementAdminApprovalExpiryJobIT` (end-to-end IT on real Postgres proving CLAIM-04 + ADMIN-03).

## Tasks Completed

| Task | Name | Commit | Key Files |
|------|------|--------|-----------|
| 1 | DisbursementAdminApprovalExpiryJob | 625d969 | DisbursementAdminApprovalExpiryJob.java |
| 2 | Register in DisbursementSchedulerConfig | 92ce2f4 | DisbursementSchedulerConfig.java |
| 3 | DisbursementOpsAlertEmailListener | 1abd356 | DisbursementOpsAlertEmailListener.java, DisbursementOpsAlertEmailListenerTest.java |
| 4 | DisbursementAdminApprovalExpiryJobIT | 8d16ad7 | DisbursementAdminApprovalExpiryJobIT.java |

## DisbursementAdminApprovalExpiryJob Constructor Signature

```java
public DisbursementAdminApprovalExpiryJob(
    DisbursementRepository disbursementRepository,
    DisbursementClaimTransitionService claimTransitionService,
    DisbursementProperties disbursementProperties,   // Plan 01 — adminApprovalTimeoutHours
    TransactionTemplate transactionTemplate,
    ObservationRegistry observationRegistry)
```

## Age-Source Wiring (closes dead-property gap)

```java
long ageMinutes = disbursementProperties.getAdminApprovalTimeoutHours() * 60L;
// SOLE consumer of Plan 01's adminApprovalTimeoutHours — closes dead-property gap from checker review
List<Disbursement> candidates = disbursementRepository
        .findExpiredCandidates(DisbursementStatus.PENDING_ADMIN_APPROVAL.name(), ageMinutes);
```

## Atomic Claim-Release Behavior (CLAIM-04 + ADMIN-03)

Per-candidate inside `transactionTemplate.execute`:
1. `findByDisbursementIdForUpdate` — pessimistic lock
2. Re-check status is still `PENDING_ADMIN_APPROVAL` — race guard
3. `locked.applyTransition(DisbursementStatus.EXPIRED)` — ADMIN-03
4. `claimTransitionService.transitionClaims(locked.getId(), PENDING, RELEASED)` — CLAIM-04
5. Both commit atomically (Pitfall 4 — REQUIRED propagation joins outer transaction)

Zero-claim case: `transitionClaims` returns 0 — NOT an error. Transaction commits normally.

No `WalletBalanceService.release()` call anywhere — SCHEMA-03 retirement enforced.

## New Scheduler Config Beans

```java
// Identity: "disbursement-admin-approval-expiry-job"
@Bean public JobDetail disbursementAdminApprovalExpiryJobDetail()

// Identity: "disbursement-admin-approval-expiry-trigger"  
// Cron: @Value("${payam.disbursement.admin-approval-expiry-cron}") — default "0 * * * * ?"
// MisfireHandling: DoNothing (suppress catch-up scans)
// StartAt: 45s future (staggers from existing 30s job)
@Bean public Trigger disbursementAdminApprovalExpiryTrigger(...)
```

Existing `disbursementExpiryJobDetail` and `disbursementExpiryTrigger` beans untouched (Pitfall 6).

## DisbursementOpsAlertEmailListener — Both @EventListener Methods

```java
@Transactional @EventListener
public void onAdminApprovalRequired(DisbursementAdminApprovalRequiredEvent event)
// → publishes Envelope(EmailTemplate.DISBURSEMENT_ADMIN_APPROVAL_REQUIRED, ...)

@Transactional @EventListener
public void onInsufficientFunds(InsufficientFundsAlertEvent event)
// → publishes Envelope(EmailTemplate.DISBURSEMENT_INSUFFICIENT_FUNDS_ALERT, ...)
```

Recipient property: `@Value("${payam.platform.notification-email}")` — same as `PlatformConfigEmailListener`.

MailManager picks up the Envelope and handles delivery, retry, and AFTER_COMMIT scheduling.

## DisbursementAdminApprovalExpiryJobIT — Three Test Scenarios

| Test | Scenario | Assertion |
|------|----------|-----------|
| `expiresAgedAdminApproval_andReleasesAllClaims` | 3 PENDING claims, backdated 120 min | Disbursement=EXPIRED, all 3 claims=RELEASED |
| `doesNotExpireYoungerThanThreshold_leavesClaimsPending` | 2 PENDING claims, created NOW | Disbursement=PENDING_ADMIN_APPROVAL, claims=PENDING |
| `onExpiry_doesNotTouchAlreadyReleasedClaims` | 3 PENDING + 2 RELEASED, backdated 120 min | Disbursement=EXPIRED, all 5 claims=RELEASED |

Seeding approach: `JdbcTemplate` INSERT + `backdateDisbursement` (PostgreSQL interval arithmetic: `NOW() - CAST(? minutes AS INTERVAL)`). `spring.quartz.auto-startup=false`; `expiryJob.executeInternal(null)` called directly.

Override: `payam.disbursement.admin-approval-timeout-hours=1` — keeps 120-min backdating well within expiry window.

## "Closes the Loop" Framing

- Plan 01 declared `adminApprovalTimeoutHours` with no consumer → Plan 03's job is its first real consumer.
- Plan 02 published `DisbursementAdminApprovalRequiredEvent` and `InsufficientFundsAlertEvent` with no listeners → Plan 03's listener is their first consumer.
- Without Plan 03, both events were dead-published and both properties were dead-declared.

## Self-Check: PASSED

Files verified present:
- `src/main/java/com/softropic/payam/disbursement/service/DisbursementAdminApprovalExpiryJob.java` — FOUND
- `src/main/java/com/softropic/payam/disbursement/config/DisbursementSchedulerConfig.java` (modified) — FOUND
- `src/main/java/com/softropic/payam/email/infrastructure/listener/DisbursementOpsAlertEmailListener.java` — FOUND
- `src/test/java/com/softropic/payam/disbursement/service/DisbursementAdminApprovalExpiryJobIT.java` — FOUND

Commits verified:
- 625d969 — feat(56-03): add DisbursementAdminApprovalExpiryJob — FOUND
- 92ce2f4 — feat(56-03): register DisbursementAdminApprovalExpiryJob in DisbursementSchedulerConfig — FOUND
- 1abd356 — feat(56-03): add DisbursementOpsAlertEmailListener — FOUND
- 8d16ad7 — test(56-03): add DisbursementAdminApprovalExpiryJobIT — FOUND
