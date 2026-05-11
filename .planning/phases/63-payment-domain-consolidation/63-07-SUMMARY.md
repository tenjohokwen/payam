---
phase: 63-payment-domain-consolidation
plan: "07"
subsystem: payment-ledger
tags: [package-move, refactor, payment, wave-7, phase-63-close, PAY-02]
dependency_graph:
  requires: [63-06]
  provides: [payment.ledger.contract, payment.ledger.contract.exception, payment.ledger.repo, payment.ledger.service]
  affects: [mtn.service, orange.service, platform.admin, payment.core, payment.disbursement, payment.reconciliation, payment.webhook, all E2E and domain tests]
tech_stack:
  added: []
  patterns: [git-mv-rename, sed-package-rewrite, fqn-body-reference-update, two-pass-sed-macOS]
key_files:
  created:
    - src/main/java/com/softropic/payam/payment/ledger/contract/TransactionStatus.java
    - src/main/java/com/softropic/payam/payment/ledger/contract/LedgerFlow.java
    - src/main/java/com/softropic/payam/payment/ledger/contract/LedgerDirection.java
    - src/main/java/com/softropic/payam/payment/ledger/contract/LedgerPosting.java
    - src/main/java/com/softropic/payam/payment/ledger/contract/TransactionEventType.java
    - src/main/java/com/softropic/payam/payment/ledger/contract/CachedResponse.java
    - src/main/java/com/softropic/payam/payment/ledger/contract/exception/IllegalStateTransitionException.java
    - src/main/java/com/softropic/payam/payment/ledger/repo/Transaction.java
    - src/main/java/com/softropic/payam/payment/ledger/repo/TransactionRepository.java
    - src/main/java/com/softropic/payam/payment/ledger/repo/LedgerEntry.java
    - src/main/java/com/softropic/payam/payment/ledger/repo/LedgerEntryRepository.java
    - src/main/java/com/softropic/payam/payment/ledger/repo/IdempotencyKey.java
    - src/main/java/com/softropic/payam/payment/ledger/repo/IdempotencyKeyRepository.java
    - src/main/java/com/softropic/payam/payment/ledger/repo/PaymentEventLog.java
    - src/main/java/com/softropic/payam/payment/ledger/repo/PaymentEventLogRepository.java
    - src/main/java/com/softropic/payam/payment/ledger/service/LedgerService.java
    - src/main/java/com/softropic/payam/payment/ledger/service/IdempotencyService.java
    - src/main/java/com/softropic/payam/payment/ledger/service/EventLogService.java
    - src/main/java/com/softropic/payam/payment/ledger/service/TransactionService.java
  modified:
    - src/main/java/com/softropic/payam/mtn/service/MtnMoMoPort.java
    - src/main/java/com/softropic/payam/mtn/service/MtnStatusMapper.java
    - src/main/java/com/softropic/payam/mtn/service/MtnStatusPollerJob.java
    - src/main/java/com/softropic/payam/orange/service/OrangeMoneyPort.java
    - src/main/java/com/softropic/payam/orange/service/OrangeStatusMapper.java
    - src/main/java/com/softropic/payam/orange/service/OrangeStatusPollerJob.java
    - src/main/java/com/softropic/payam/platform/admin/api/AuditResource.java
    - src/main/java/com/softropic/payam/platform/admin/service/AdminTransactionQueryService.java
    - src/main/java/com/softropic/payam/platform/admin/service/PaymentMetricsService.java
    - src/main/java/com/softropic/payam/payment/core/service/PaymentOrchestrator.java
    - src/main/java/com/softropic/payam/payment/disbursement/ (all 39 files — package decls + transaction import updates)
    - src/main/java/com/softropic/payam/payment/reconciliation/port/MtnReportAdapter.java
    - src/main/java/com/softropic/payam/payment/reconciliation/port/OrangeReportAdapter.java
    - src/main/java/com/softropic/payam/payment/reconciliation/port/ProviderReportPort.java
    - src/main/java/com/softropic/payam/payment/reconciliation/service/ReconciliationProviderRunner.java
    - src/main/java/com/softropic/payam/payment/webhook/contract/OutboundWebhookPayload.java
    - src/main/java/com/softropic/payam/payment/webhook/contract/WebhookEnqueueRequestedEvent.java
    - src/main/java/com/softropic/payam/payment/webhook/contract/WebhookReceivedEvent.java
    - src/main/java/com/softropic/payam/payment/webhook/repo/WebhookDeliveryLog.java
    - src/main/java/com/softropic/payam/payment/webhook/service/WebhookDeliveryService.java
    - src/main/java/com/softropic/payam/payment/webhook/service/WebhookDoubleCheckHandler.java
    - src/main/java/com/softropic/payam/payment/webhook/service/WebhookTransitionService.java
  deleted:
    - src/main/java/com/softropic/payam/transaction/ (entire tree — 19 files)
    - src/test/java/com/softropic/payam/transaction/ (entire tree — 8 files)
decisions:
  - "macOS sed does not support \\b word boundary — first sed pass with \\b failed silently; fixed with explicit suffix-specific patterns (contract.exception|contract|repo|service|root) matching each package sub-level explicitly"
  - "FQN references in code bodies (not just import statements) also required updating — WebhookDoubleCheckHandler.java used FQNs like com.softropic.payam.transaction.contract.LedgerFlow in code body; sed -e 's|com.softropic.payam.transaction.|...|g' sweep updated all occurrences"
  - "disbursement file package declarations (disbursement.* → payment.disbursement.*) were unstaged from 63-06 git mv — committed together with 63-07 changes since they affect compilation and are logically complete in this wave"
  - "Callers list from Step A grep returned 53 files (27 test + 26 production); plan estimated ~50 — actual count authoritative"
  - "Docker/Testcontainers Ryuk networking failures are pre-existing infrastructure constraint on this machine (identical to waves 1-6 baselines); all failures are Docker-only, zero caused by this package move"
metrics:
  duration_minutes: 98
  completed_date: "2026-05-11"
  tasks_completed: 1
  files_changed: 132
---

# Phase 63 Plan 07: transaction → payment.ledger Package Move — Wave 7 (Phase 63 Close) Summary

**One-liner:** Relocated all 19 production + 8 test files from `com.softropic.payam.transaction.*` to `com.softropic.payam.payment.ledger.*` using git mv (semantic rename: transaction → ledger), updated 53 external callers including FQN references in code bodies; Phase 63 milestone goal achieved — all 7 payment domains now under `payment.*` umbrella with zero flat-package imports remaining.

## Objective

Final wave of Phase 63. Move the `transaction/` package to `payment/ledger/` preserving all sub-packages (contract, contract/exception, repo, service). This is a SEMANTIC RENAME — the package name changes from `transaction` to `ledger` to communicate that this package owns the ledger entries, idempotency tracking, and transaction state machine as a bounded context.

After this plan, PAY-02 is satisfied and Phase 63 milestone goal is met: all seven payment-domain packages (core, ledger, disbursement, fee, reconciliation, fraud, webhook) are consolidated under `payment.*` umbrella with zero flat-package imports remaining.

## Files Moved (19 production + 8 test)

### Production (19 files)

| Sub-package | Files |
|-------------|-------|
| `ledger/contract` | `CachedResponse.java`, `LedgerDirection.java`, `LedgerFlow.java`, `LedgerPosting.java`, `TransactionEventType.java`, `TransactionStatus.java` |
| `ledger/contract/exception` | `IllegalStateTransitionException.java` |
| `ledger/repo` | `IdempotencyKey.java`, `IdempotencyKeyRepository.java`, `LedgerEntry.java`, `LedgerEntryRepository.java`, `PaymentEventLog.java`, `PaymentEventLogRepository.java`, `Transaction.java`, `TransactionRepository.java` |
| `ledger/service` | `EventLogService.java`, `IdempotencyService.java`, `LedgerService.java`, `TransactionService.java` |

### Test (8 files)

| Sub-package | Files |
|-------------|-------|
| `ledger/` (root) | `IdempotencyServiceIT.java`, `LedgerConstraintIT.java`, `LedgerServiceIT.java`, `PaymentEventLogIT.java`, `TransactionStateMachineIT.java` |
| `ledger/contract` | `LedgerFlowTest.java`, `LedgerPostingTest.java` |
| `ledger/repo` | `TransactionFlowTest.java` |

## External Callers Updated

**Authoritative list from Step A grep** — 53 external callers discovered and updated (plan estimated ~50):

### Production callers (26):

| Package | Files | Import count |
|---------|-------|-------------|
| `mtn.service` | `MtnMoMoPort.java`, `MtnStatusMapper.java`, `MtnStatusPollerJob.java` | 6, 1, 5 |
| `orange.service` | `OrangeMoneyPort.java`, `OrangeStatusMapper.java`, `OrangeStatusPollerJob.java` | 6, 1, 5 |
| `platform.admin` | `AuditResource.java`, `AdminTransactionQueryService.java`, `PaymentMetricsService.java` | 2, 4, 2 |
| `payment.core` | `PaymentOrchestrator.java` | 8 |
| `payment.disbursement` | `DisbursementStatus.java`, `DisbursementCallbackTransitionService.java`, `DisbursementIdempotencyService.java`, `DisbursementOrchestrator.java`, `TransactionClaimValidationService.java` | various |
| `payment.reconciliation` | `MtnReportAdapter.java`, `OrangeReportAdapter.java`, `ProviderReportPort.java`, `ReconciliationProviderRunner.java` | 1, 1, 1, 2 |
| `payment.webhook` | `OutboundWebhookPayload.java`, `WebhookEnqueueRequestedEvent.java`, `WebhookReceivedEvent.java`, `WebhookDeliveryLog.java`, `WebhookDeliveryService.java`, `WebhookDoubleCheckHandler.java`, `WebhookTransitionService.java` | various |

**Note on WebhookDoubleCheckHandler:** This file used FQN references (`com.softropic.payam.transaction.contract.LedgerFlow`) in code bodies rather than import statements. The standard import-only sed was insufficient; the broader `sed 's|com.softropic.payam.transaction.|...|g'` sweep updated these correctly.

### Test callers (27):

- `domain/HashChainPreviousHashTest.java`
- `domain/IdempotencyTenantScopeTest.java`
- `domain/LedgerBalanceGuardTest.java`
- `domain/TransactionStatusGuardTest.java`
- `e2e/domain/StateMachineLegalTransitionsTest.java`
- `mtn/MtnMoMoPortIT.java`
- `mtn/service/MtnMoMoPortDisbursementCallbackTest.java`
- `orange/OrangeMoneyPortIT.java`
- `orange/service/OrangeMoneyPortDisbursementCallbackTest.java`
- `payment/core/PaymentOrchestratorIT.java`
- `platform/monitoring/OperationalIT.java`
- `payment/reconciliation/ReconciliationProviderRunnerTest.java`
- `payment/disbursement/contract/DisbursementStatusTest.java`
- `payment/disbursement/webhook/DisbursementWebhookDeliveryIT.java`
- `payment/disbursement/service/TransactionClaimValidationServiceTest.java`
- `payment/disbursement/service/DisbursementClaimConcurrencyIT.java`
- `payment/disbursement/service/DisbursementIdempotencyIT.java`
- `payment/disbursement/service/DisbursementOrchestratorTest.java`
- `payment/disbursement/service/DisbursementOrchestratorIT.java`
- `payment/disbursement/service/DisbursementIdempotencyServiceTest.java`
- `payment/disbursement/service/DisbursementIdempotencyRetryIT.java`
- `payment/disbursement/service/DisbursementCallbackTransitionServiceTest.java`
- `payment/webhook/WebhookDeliveryIT.java`
- `payment/webhook/WebhookDoubleCheckIT.java`
- `payment/webhook/WebhookEnqueueListenerIT.java`
- `payment/webhook/service/WebhookDeliveryServicePayloadTest.java`
- `payment/webhook/service/WebhookDoubleCheckHandlerFlowRoutingTest.java`

## Spring/JPA Annotation Preservation

All Spring and JPA annotations preserved byte-for-byte in moved files:

| File | Annotations verified |
|------|---------------------|
| `Transaction.java` | `@Entity`, `@Table`, `@Enumerated(EnumType.STRING)` on `flow` field |
| `LedgerEntry.java` | `@Entity`, `@Table` |
| `IdempotencyKey.java` | `@Entity`, `@Table` |
| `LedgerService.java` | `@Service` |
| `IdempotencyService.java` | `@Service` |
| `TransactionRepository.java` | `@Lock` annotations and `@Query` strings (unchanged) |
| `IdempotencyKeyRepository.java` | `@Lock`, native upsert `@Query` (unchanged) |

## Phase 63 Closure Assertion

```
grep -rn 'package com\.softropic\.payam\.\(payment\|transaction\|disbursement\|fee\|reconciliation\|fraud\|webhook\);' src --include='*.java' | wc -l
```
Result: **0** — zero remaining flat-umbrella package declarations for all 7 payment-domain packages.

The `payment.*` umbrella now contains all 7 sub-domains:
- `payment.core` (Wave 5 — PAY-01)
- `payment.fee` (Wave 1 — PAY-06)
- `payment.reconciliation` (Wave 2 — PAY-05)
- `payment.fraud` (Wave 3 — PAY-04)
- `payment.webhook` (Wave 4 — PAY-07)
- `payment.disbursement` (Wave 6 — PAY-03)
- `payment.ledger` (Wave 7 — PAY-02) **← this plan**

## Verification Results

| Check | Result |
|-------|--------|
| `find payment/ledger -name '*.java' \| wc -l` (production) | 19 |
| `find payment/ledger -name '*.java' \| wc -l` (test) | 8 |
| `test -d transaction/` (main) | GONE |
| `test -d transaction/` (test) | GONE |
| `head -1 TransactionStatus.java` | `package com.softropic.payam.payment.ledger.contract;` |
| `head -1 Transaction.java` | `package com.softropic.payam.payment.ledger.repo;` |
| `head -1 LedgerService.java` | `package com.softropic.payam.payment.ledger.service;` |
| `head -1 IllegalStateTransitionException.java` | `package com.softropic.payam.payment.ledger.contract.exception;` |
| `head -1 IdempotencyServiceIT.java` | `package com.softropic.payam.payment.ledger;` |
| `head -1 LedgerFlowTest.java` | `package com.softropic.payam.payment.ledger.contract;` |
| `head -1 TransactionFlowTest.java` | `package com.softropic.payam.payment.ledger.repo;` |
| Stale `com.softropic.payam.transaction.*` refs outside ledger/ | 0 |
| Stale `package com.softropic.payam.transaction.*` decls | 0 |
| Phase 63 flat-umbrella assertion (all 7 domains) | 0 |
| `mvn test-compile` | EXIT 0 — clean compilation |
| Unit tests (excluding IT/E2E) | 388 PASS, 0 FAIL |
| IT/E2E tests (Testcontainers) | N/A — Docker networking broken on this machine (pre-existing, same constraint as waves 1-6) |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] First sed pass for package declarations silently failed on macOS**
- **Found during:** Step C
- **Issue:** The sed command `sed -i '' -e 's|^package com\.softropic\.payam\.transaction\b|...|'` used `\b` word boundary which is not supported in macOS's BSD sed — the substitution ran without error but produced no change
- **Fix:** Replaced with explicit suffix-specific patterns: `s|...transaction\.contract\.exception;|...|`, `s|...transaction\.contract;|...|`, `s|...transaction\.repo;|...|`, `s|...transaction\.service;|...|`, `s|...transaction;|...|` — one pattern per sub-package variant
- **Files modified:** All 27 moved files
- **Commit:** `03e76b0`

**2. [Rule 2 - FQN coverage] FQN references in code bodies also required updating**
- **Found during:** Step H verification grep
- **Issue:** 16 occurrences of `com.softropic.payam.transaction.*` remained after the import-only sed sweep — these were FQN references inside code bodies (not import lines), including `com.softropic.payam.transaction.contract.LedgerFlow` in `WebhookDoubleCheckHandler.java` method bodies, FQN method parameter in `ProviderReportPort.java`, and a Javadoc `{@link}` in `DisbursementIdempotencyService.java`
- **Fix:** Additional sed pass targeting all occurrences (not just imports): `sed -i '' -e 's|com\.softropic\.payam\.transaction\.|com.softropic.payam.payment.ledger.|g' "$f"` on each remaining file
- **Files modified:** `WebhookDoubleCheckHandler.java`, `MtnMoMoPort.java`, `OrangeMoneyPort.java`, `MtnStatusPollerJob.java`, `OrangeStatusPollerJob.java`, `MtnReportAdapter.java`, `OrangeReportAdapter.java`, `ProviderReportPort.java`, `DisbursementIdempotencyService.java`, `PaymentOrchestratorIT.java`, `DisbursementClaimConcurrencyIT.java`
- **Commit:** `03e76b0`

**3. [Rule 1 - Bug] Disbursement file package declarations from incomplete 63-06 commit**
- **Found during:** git status check before committing
- **Issue:** The 63-06 `git mv` moved disbursement files but the package declaration updates were not staged/committed in the 63-06 refactor commit. The working tree had 39 disbursement files with correct `payment.disbursement.*` package declarations that weren't yet committed to HEAD
- **Fix:** Staged and committed the disbursement file content changes together with the 63-07 wave, since they affect compilation and are logically complete (this wave's sed also updated transaction imports in those same files)
- **Files modified:** All 39 files in `payment/disbursement/` + 28 in test tree
- **Commit:** `03e76b0`

## Known Stubs

None — this is a pure package relocation with no data wiring or UI rendering.

## Self-Check: PASSED

Files exist:
- `src/main/java/com/softropic/payam/payment/ledger/contract/TransactionStatus.java` — FOUND
- `src/main/java/com/softropic/payam/payment/ledger/repo/Transaction.java` — FOUND
- `src/main/java/com/softropic/payam/payment/ledger/service/LedgerService.java` — FOUND
- `src/main/java/com/softropic/payam/payment/ledger/service/IdempotencyService.java` — FOUND
- `src/main/java/com/softropic/payam/payment/ledger/contract/exception/IllegalStateTransitionException.java` — FOUND
- `src/test/java/com/softropic/payam/payment/ledger/LedgerServiceIT.java` — FOUND
- `src/test/java/com/softropic/payam/payment/ledger/contract/LedgerFlowTest.java` — FOUND
- Old `src/main/java/com/softropic/payam/transaction/` — GONE (verified)
- Old `src/test/java/com/softropic/payam/transaction/` — GONE (verified)

Commit `03e76b0` — FOUND (git rev-parse confirmed)
Stale `com.softropic.payam.transaction.*` references — 0
Phase 63 flat-umbrella closure — 0 remaining flat declarations
