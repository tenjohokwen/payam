---
phase: 56-claim-lifecycle-admin-approval
plan: "02"
subsystem: disbursement-claim-wiring
tags: [disbursement, claim, orchestrator, callback, admin-approval, insufficient-funds, v11]
dependency_graph:
  requires: [56-01]
  provides:
    - DisbursementOrchestrator (13-param constructor, three-tier threshold gate)
    - DisbursementService.transitionToPendingAdminApproval
    - DisbursementCallbackTransitionService (4-param constructor, claim wiring)
    - InsufficientFundsDetector
  affects:
    - Plan 56-03 (consumes DisbursementAdminApprovalRequiredEvent + InsufficientFundsAlertEvent published here)
tech_stack:
  added: []
  patterns:
    - three-tier-threshold-gate
    - atomic-claim-transition-in-requires-new
    - insufficient-funds-pattern-matching
    - spring-applicationeventpublisher
key_files:
  created:
    - src/main/java/com/softropic/payam/disbursement/service/InsufficientFundsDetector.java
    - src/test/java/com/softropic/payam/disbursement/service/InsufficientFundsDetectorTest.java
    - src/test/java/com/softropic/payam/disbursement/service/DisbursementOrchestratorTest.java (extended)
  modified:
    - src/main/java/com/softropic/payam/disbursement/service/DisbursementOrchestrator.java
    - src/main/java/com/softropic/payam/disbursement/service/DisbursementService.java
    - src/main/java/com/softropic/payam/disbursement/service/DisbursementCallbackTransitionService.java
    - src/test/java/com/softropic/payam/disbursement/service/DisbursementCallbackTransitionServiceTest.java
decisions:
  - "DisbursementOrchestrator constructor grows from 10 to 13 params — DisbursementProperties, DisbursementClaimTransitionService, ApplicationEventPublisher added at the end"
  - "Admin-approval gate evaluated BEFORE step-up gate (Pitfall 2 in 56-RESEARCH) — amount > adminApprovalThreshold routes to PENDING_ADMIN_APPROVAL even if also > STEP_UP_THRESHOLD"
  - "releaseAndFail now locks + transitions + releases claims in a single transactionTemplate.execute — replaces previous disbursementService.transitionToFailed call which committed separately"
  - "DisbursementCallbackTransitionService grows from 2 to 4 params — DisbursementClaimTransitionService, InsufficientFundsDetector added"
  - "CLAIM-05 invariant: callback service only emits SUCCESS or FAILED targets, never EXPIRED — no EXPIRED branch in claim-transition wiring"
metrics:
  duration: "~53 minutes"
  completed_date: "2026-05-04"
  tasks_completed: 3
  files_changed: 7
---

# Phase 56 Plan 02: Claim Transition Wiring Summary

Wired `DisbursementClaimTransitionService` (Plan 01) into the three disbursement state-change paths: `DisbursementOrchestrator` (admin-approval gate + sync-failure release), `DisbursementCallbackTransitionService` (SUCCESS→CLAIMED / FAILED→RELEASED + IF alert), and added `InsufficientFundsDetector` for conservative pattern-matching detection.

## Tasks Completed

| Task | Name | Commit | Key Files |
|------|------|--------|-----------|
| 1 | InsufficientFundsDetector + DisbursementService.transitionToPendingAdminApproval | 7cbe7bf | InsufficientFundsDetector.java, DisbursementService.java, InsufficientFundsDetectorTest.java |
| 2 | Admin-approval branch + claim-release-on-failure in DisbursementOrchestrator | b8e6305 | DisbursementOrchestrator.java, DisbursementOrchestratorTest.java |
| 3 | Claim transitions + IF alert in DisbursementCallbackTransitionService | 5db7237 | DisbursementCallbackTransitionService.java, DisbursementCallbackTransitionServiceTest.java |

## DisbursementOrchestrator Constructor Signature (13 params)

```java
public DisbursementOrchestrator(
    DisbursementIdempotencyService,
    MsisdnRouter,
    DisbursementVelocityService,
    DisbursementFraudEvaluationService,
    DisbursementService,
    DisbursementRepository,
    MtnMoMoPort,
    OrangeMoneyPort,
    TransactionTemplate,
    TransactionClaimValidationService,
    DisbursementProperties,           // new — threshold gate
    DisbursementClaimTransitionService, // new — releaseAndFail claim release
    ApplicationEventPublisher)         // new — admin-approval event
```

## Three-Tier Threshold Gate (Step 6)

```java
BigDecimal adminApprovalThreshold = disbursementProperties.getAdminApprovalThreshold();
boolean adminApproval = request.amount().compareTo(adminApprovalThreshold) > 0;
boolean stepUp = !adminApproval && request.amount().compareTo(STEP_UP_THRESHOLD) > 0;
// Admin-approval takes precedence — checked first (ADMIN-01 + Pitfall 2 in 56-RESEARCH)
```

Admin-approval path (Step 7.6): row created as INITIATED → `transitionToPendingAdminApproval` via transactionTemplate → `DisbursementAdminApprovalRequiredEvent` published → early return with `PENDING_ADMIN_APPROVAL` response (no provider dispatch).

## DisbursementService.transitionToPendingAdminApproval

```java
@Transactional
public void transitionToPendingAdminApproval(String disbursementId, String adminNote)
```

Loads under `PESSIMISTIC_WRITE`, calls `applyTransition(PENDING_ADMIN_APPROVAL)`, sets `adminNote`. Structured log: `kv("operation", "dsb_transition")`.

## DisbursementCallbackTransitionService Constructor Signature (4 params)

```java
public DisbursementCallbackTransitionService(
    DisbursementRepository disbursementRepository,
    ApplicationEventPublisher eventPublisher,
    DisbursementClaimTransitionService claimTransitionService,
    InsufficientFundsDetector insufficientFundsDetector)
```

## SUCCESS/FAILED Claim-Transition Wiring

```java
if (target == DisbursementStatus.SUCCESS) {
    claimTransitionService.transitionClaims(locked.getId(), PENDING, CLAIMED);
} else if (target == DisbursementStatus.FAILED) {
    claimTransitionService.transitionClaims(locked.getId(), PENDING, RELEASED);
    if (insufficientFundsDetector.isInsufficientFunds(result)) {
        eventPublisher.publishEvent(new InsufficientFundsAlertEvent(...)); // ALERT-01
    }
}
// No EXPIRED branch — CLAIM-05 invariant (callback only emits SUCCESS or FAILED)
```

Atomic boundary: `claimTransitionService.transitionClaims` is `@Transactional(REQUIRED)` — joins `DisbursementCallbackTransitionService`'s `REQUIRES_NEW` transaction (Pitfall 4).

## InsufficientFundsDetector Pattern List

Conservative case-insensitive substring matching against `ProviderResult.errorCode()` and `ProviderResult.errorMessage()`:
- `"NOT_ENOUGH_FUNDS"` — MTN convention
- `"INSUFFICIENT_BALANCE"` — Orange convention
- `"INSUFFICIENT_FUNDS"` — generic English fallback

`isInsufficientFunds(null)` and `isInsufficientFunds(result with all-null fields)` return `false` (null-safe).

## releaseAndFail Zero-Claim Graceful Path

`transitionClaims` returning `0` is NOT an error — `releaseAndFail` may be called before any `DisbursementTransactionRef` row exists (claim creation itself threw earlier). The log line `kv("claimsReleased", 0)` is emitted; the transaction commits normally; no exception propagates (CLAIM-05 zero-claim invariant for sync-failure paths).

## ALERT-01 Outcome

`InsufficientFundsAlertEvent` published by `DisbursementCallbackTransitionService` on FAILED + IF signal is consumed by Plan 03's `DisbursementOpsAlertEmailListener`.

## Self-Check: PASSED

Files verified present:
- `src/main/java/com/softropic/payam/disbursement/service/InsufficientFundsDetector.java` — FOUND
- `src/main/java/com/softropic/payam/disbursement/service/DisbursementOrchestrator.java` (modified) — FOUND
- `src/main/java/com/softropic/payam/disbursement/service/DisbursementCallbackTransitionService.java` (modified) — FOUND

Commits verified:
- 7cbe7bf — feat(56-02): create InsufficientFundsDetector + add transitionToPendingAdminApproval — FOUND
- b8e6305 — feat(56-02): add admin-approval branch + claim-release-on-failure to DisbursementOrchestrator — FOUND
- 5db7237 — feat(56-02): wire claim transitions + IF alert into DisbursementCallbackTransitionService — FOUND
