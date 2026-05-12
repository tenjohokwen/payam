---
phase: 65-common-package-redistribution
plan: "03"
subsystem: payment
tags: [java, spring-boot, package-refactoring, common, payment-core]

# Dependency graph
requires:
  - phase: 65-common-package-redistribution
    plan: "01"
    provides: "infrastructure.exception, infrastructure.message, infrastructure.config, infrastructure.logging at new paths"
  - phase: 65-common-package-redistribution
    plan: "02"
    provides: "infrastructure.client, infrastructure.threadpool, infrastructure.util, infrastructure.validation at new paths"
  - phase: 64-provider-infrastructure-encapsulation
    provides: "payment.provider.mtn and payment.provider.orange at correct paths"
provides:
  - "payment.core.contract.MobileMoneyPort — hexagonal port interface for mobile money providers"
  - "payment.core.contract.MobilePaymentProvider — enum (MTN, ORANGE) imported by 28+ production files"
  - "payment.core.contract.PaymentCommand — value type passed from orchestrator to provider ports"
  - "payment.core.contract.PaymentMethod — payment method value type"
  - "payment.core.contract.ProviderResult — value type returned from provider ports"
  - "payment.core.contract.SubscriberStatus — value type for subscriber validation responses"
  - "payment.core.contract.ChargeType — enum (was common.refund)"
  - "payment.core.contract.RefundPolicy — @Embeddable (was common.refund)"
  - "payment.core.contract.RefundType — enum (was common.refund)"
  - "common/ now contains only: enums/, consumer/, Gender.java (Plan 04 handles these)"
affects: [65-04, 65-05]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Two-pass sed for package declaration rewrites on macOS BSD (sub-package suffix dot vs root semicolon)"
    - "Broad sed sweep (no import-only anchor) catches both import statements and FQN body references"
    - "Atomic single commit per wave: 9 file moves + 56 caller updates + 3 FQN body refs (6 sites) in one commit"
    - "Step A grep excludes common/payment/, common/refund/, and platform/security/common/ to avoid Pitfall 2"

key-files:
  created:
    - "src/main/java/com/softropic/payam/payment/core/contract/MobileMoneyPort.java (from common.payment)"
    - "src/main/java/com/softropic/payam/payment/core/contract/MobilePaymentProvider.java (from common.payment)"
    - "src/main/java/com/softropic/payam/payment/core/contract/PaymentCommand.java (from common.payment)"
    - "src/main/java/com/softropic/payam/payment/core/contract/PaymentMethod.java (from common.payment)"
    - "src/main/java/com/softropic/payam/payment/core/contract/ProviderResult.java (from common.payment)"
    - "src/main/java/com/softropic/payam/payment/core/contract/SubscriberStatus.java (from common.payment)"
    - "src/main/java/com/softropic/payam/payment/core/contract/ChargeType.java (from common.refund)"
    - "src/main/java/com/softropic/payam/payment/core/contract/RefundPolicy.java (from common.refund)"
    - "src/main/java/com/softropic/payam/payment/core/contract/RefundType.java (from common.refund)"
  deleted:
    - "src/main/java/com/softropic/payam/common/payment/ (entire directory — 6 files)"
    - "src/main/java/com/softropic/payam/common/refund/ (entire directory — 3 files)"
  modified:
    - "30 external production caller files with updated imports"
    - "26 external test caller files with updated imports"
    - "src/main/java/com/softropic/payam/payment/provider/mtn/service/MtnMoMoPort.java (FQN body refs lines 283, 340)"
    - "src/main/java/com/softropic/payam/payment/provider/orange/service/OrangeMoneyPort.java (FQN body refs lines 274, 343)"
    - "src/main/java/com/softropic/payam/payment/disbursement/contract/DisbursementResponse.java (Javadoc @link FQN lines 50, 78)"

key-decisions:
  - "common.refund (zero external callers) placed in payment.core.contract (same destination as common.payment per RESEARCH.md Open Question 1) — zero callers means no compilation risk; consistent with CMN-01 destination mapping"
  - "Step A grep explicitly excludes common/payment/, common/refund/ source files and platform/security/common/ paths (Pitfall 2 guard) — confirmed 0 Pitfall 2 hits"
  - "Broad sed pattern (not import-only anchor) applied to all 56 external callers — catches FQN enum references (MtnMoMoPort, OrangeMoneyPort) and Javadoc @link references (DisbursementResponse) in one pass"
  - "No additional missed callers vs planned list — Step A grep returned exactly 56 files (30 prod + 26 test)"

patterns-established:
  - "Worktree merge before execution: worktree was behind main by Plans 01 and 02 commits; merged via fast-forward before any file operations"
  - "Sed with space-separated file list fails on macOS when filenames have spaces; use for-loop per file instead"

requirements-completed: [CMN-01]

# Metrics
duration: ~30min
completed: 2026-05-12
---

# Phase 65 Plan 03: Common Package Redistribution Wave 3 Summary

**Moved 9 payment-domain files from common.payment and common.refund to payment.core.contract — the highest-blast-radius wave in Phase 65, updating 56 external caller files (30 prod + 26 test) plus 3 FQN body references at 6 known line numbers. mvn verify green.**

## Performance

- **Duration:** ~30 min
- **Started:** 2026-05-12T10:00:00Z
- **Completed:** 2026-05-12T10:30:00Z
- **Tasks:** 1 (single atomic task per plan structure)
- **Files modified:** 65 (9 moves + 56 caller updates)

## Accomplishments

- Moved 6 hexagonal port/value-type files from `common.payment` to `payment.core.contract`:
  - `MobileMoneyPort.java` — hexagonal port interface (implemented by MtnMoMoPort, OrangeMoneyPort)
  - `MobilePaymentProvider.java` — enum (MTN, ORANGE) — most-imported type in the codebase
  - `PaymentCommand.java` — value type passed orchestrator → provider
  - `PaymentMethod.java` — payment method value type
  - `ProviderResult.java` — value type returned from provider ports
  - `SubscriberStatus.java` — subscriber validation response value type
- Moved 3 JPA types from `common.refund` to `payment.core.contract`:
  - `ChargeType.java` — enum
  - `RefundPolicy.java` — `@Embeddable` with JPA annotations preserved
  - `RefundType.java` — enum
- Deleted `common/payment/` and `common/refund/` directories (both empty after git mv)
- Updated 30 production + 26 test external caller files with new import paths
- Fixed 3 FQN body references at known line numbers (6 sites total):
  - `MtnMoMoPort.java` lines 283, 340: `com.softropic.payam.common.payment.MobilePaymentProvider.MTN` → `payment.core.contract`
  - `OrangeMoneyPort.java` lines 274, 343: `com.softropic.payam.common.payment.MobilePaymentProvider.ORANGE` → `payment.core.contract`
  - `DisbursementResponse.java` lines 50, 78: Javadoc `{@link com.softropic.payam.common.payment.MobilePaymentProvider}` → `payment.core.contract`
- `common/` now contains only: `enums/`, `consumer/`, `Gender.java` (Plan 04 scope)
- `mvn verify` (BUILD-01 gate) passes green

## Task Commits

1. **Task 1: Move common.payment + common.refund to payment.core.contract, update all callers + 3 FQN body refs** — `2340aed` (refactor)

## External Callers Updated (56 total)

**Production (30 files):**
- `payment/core/repo/MsisdnPrefixRoute.java`
- `payment/core/service/MsisdnPrefixRouteCache.java`
- `payment/core/service/MsisdnRouter.java`
- `payment/core/service/PaymentOrchestrator.java`
- `payment/disbursement/contract/DisbursementResponse.java`
- `payment/disbursement/contract/event/InsufficientFundsAlertEvent.java`
- `payment/disbursement/repo/Disbursement.java`
- `payment/disbursement/service/DisbursementCallbackTransitionService.java`
- `payment/disbursement/service/DisbursementOrchestrator.java`
- `payment/disbursement/service/DisbursementService.java`
- `payment/disbursement/service/InsufficientFundsDetector.java`
- `payment/fraud/service/FraudScoringService.java`
- `payment/ledger/repo/Transaction.java`
- `payment/ledger/repo/TransactionRepository.java`
- `payment/ledger/service/TransactionService.java`
- `payment/provider/mtn/service/MtnMoMoPort.java`
- `payment/provider/mtn/service/MtnStatusPollerJob.java`
- `payment/provider/orange/service/OrangeMoneyPort.java`
- `payment/provider/orange/service/OrangeStatusPollerJob.java`
- `payment/reconciliation/port/MtnReportAdapter.java`
- `payment/reconciliation/port/OrangeReportAdapter.java`
- `payment/reconciliation/port/ProviderReportPort.java`
- `payment/reconciliation/repo/ReconciliationDiscrepancy.java`
- `payment/reconciliation/repo/ReconciliationReport.java`
- `payment/reconciliation/repo/ReconciliationReportRepository.java`
- `payment/reconciliation/service/ReconciliationProviderRunner.java`
- `payment/reconciliation/service/ReconciliationService.java`
- `payment/webhook/contract/WebhookReceivedEvent.java`
- `payment/webhook/service/WebhookDoubleCheckHandler.java`
- `payment/webhook/service/WebhookTransitionService.java`

**Test (26 files):**
- `domain/FraudThresholdGuardTest.java`
- `e2e/domain/StateMachineLegalTransitionsTest.java`
- `e2e/reconciliation/DailyReconciliationE2ETest.java`
- `payment/disbursement/api/MtnDisbursementCallbackControllerIT.java`
- `payment/disbursement/api/OrangeDisbursementCallbackControllerIT.java`
- `payment/disbursement/repo/DisbursementRepositoryIT.java`
- `payment/disbursement/service/DisbursementCallbackTransitionServiceTest.java`
- `payment/disbursement/service/DisbursementClaimConcurrencyIT.java`
- `payment/disbursement/service/DisbursementOrchestratorIT.java`
- `payment/disbursement/service/DisbursementOrchestratorTest.java`
- `payment/disbursement/service/InsufficientFundsDetectorTest.java`
- `payment/disbursement/service/TransactionClaimValidationServiceTest.java`
- `payment/disbursement/webhook/DisbursementWebhookDeliveryIT.java`
- `payment/fraud/FraudScoringServiceIT.java`
- `payment/ledger/LedgerServiceIT.java`
- `payment/ledger/TransactionStateMachineIT.java`
- `payment/ledger/repo/TransactionFlowTest.java`
- `payment/provider/mtn/MtnMoMoPortIT.java`
- `payment/provider/mtn/service/MtnMoMoPortDisbursementCallbackTest.java`
- `payment/provider/orange/OrangeMoneyPortIT.java`
- `payment/provider/orange/service/OrangeMoneyPortDisbursementCallbackTest.java`
- `payment/reconciliation/ReconciliationFailedStateIT.java`
- `payment/reconciliation/ReconciliationJobIT.java`
- `payment/reconciliation/ReconciliationProviderRunnerTest.java`
- `payment/webhook/service/WebhookDoubleCheckHandlerFlowRoutingTest.java`
- `platform/notification/infrastructure/listener/DisbursementOpsAlertEmailListenerTest.java`

## Invariants Verified

- **Pitfall 2 (platform.security.common):** 13 files in `platform/security/common/` confirmed untouched — package declarations still `com.softropic.payam.platform.security.common.*`
- **JPA annotations preserved:** RefundPolicy still has `@Embeddable`, `@Column`, `@Enumerated`; Transaction still has `@Entity`, `@Table`
- **Enum values preserved:** MobilePaymentProvider enum has MTN and ORANGE values
- **Hexagonal port interface preserved:** MobileMoneyPort `interface` declaration intact
- **Plan 01 work preserved:** zero `common.{exception,message,config,logging}.*` references
- **Plan 02 work preserved:** zero `common.{client,threadpool,util,validation,dto}.*` references
- **Phase 64 work preserved:** zero flat `com.softropic.payam.{mtn,orange}.*` references
- **Plan 04-05 targets preserved:** `common/consumer/`, `common/enums/`, `common/Gender.java` all intact

## Decisions Made

- `common.refund` (zero external callers) placed in `payment.core.contract` per RESEARCH.md Open Question 1 resolution — consistent with CMN-01 destination mapping, zero callers means no risk from this placement choice.
- Broad sed pattern (not import-only) applied to all 56 external callers in a single pass — same pattern catches FQN enum references in method bodies (MtnMoMoPort, OrangeMoneyPort) and Javadoc `@link` references (DisbursementResponse) without a separate FQN sweep step.

## Deviations from Plan

None — plan executed exactly as written. The caller count matched the planned ~56 (exact: 56). No extra callers discovered. All 3 FQN body references updated at their specified line numbers (lines 283/340 in MtnMoMoPort, 274/343 in OrangeMoneyPort, 50/78 in DisbursementResponse).

## Issues Encountered

- Worktree was 12 commits behind `main` (Plans 01-02 and earlier Phase 64/65 planning work not merged). Resolved via `git merge main` fast-forward before any file operations.
- macOS sed fails silently when a space-separated file list is passed as a shell variable to xargs/sed — files with spaces in path components get concatenated. Resolved by switching to a per-file for-loop (same pattern as Phases 63-64).

## Known Stubs

None — pure refactoring plan, no data stubs introduced.

## Next Phase Readiness

- `payment.core.contract` now contains all 9 moved types plus the 3 pre-existing files (`OrchestratorError`, `PaymentRequest`, `PaymentResponse`, `exception/`)
- `common/` contains only: `enums/`, `consumer/`, `Gender.java`
- Plan 04 ready: move `common.consumer`, `common.enums`, `common.Gender` to owning domain packages
- Plan 05 ready to execute after Plan 04: final verification that `common` package is empty and can be deleted

---
*Phase: 65-common-package-redistribution*
*Completed: 2026-05-12*
