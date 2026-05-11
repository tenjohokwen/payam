---
phase: 63-payment-domain-consolidation
plan: "05"
subsystem: payment-core
tags: [package-move, refactor, payment, wave-5]
dependency_graph:
  requires: [63-04]
  provides: [payment.core.api, payment.core.contract, payment.core.contract.exception, payment.core.repo, payment.core.service]
  affects: [disbursement.service.DisbursementOrchestrator, e2e test suite, payment.fraud IT tests]
tech_stack:
  added: []
  patterns: [git-mv-rename, sed-package-rewrite, collection-flow-restricted-regex]
key_files:
  created:
    - src/main/java/com/softropic/payam/payment/core/api/PaymentResource.java
    - src/main/java/com/softropic/payam/payment/core/contract/OrchestratorError.java
    - src/main/java/com/softropic/payam/payment/core/contract/PaymentRequest.java
    - src/main/java/com/softropic/payam/payment/core/contract/PaymentResponse.java
    - src/main/java/com/softropic/payam/payment/core/contract/exception/UnknownMsisdnPrefixException.java
    - src/main/java/com/softropic/payam/payment/core/repo/MsisdnPrefixRoute.java
    - src/main/java/com/softropic/payam/payment/core/repo/MsisdnPrefixRouteRepository.java
    - src/main/java/com/softropic/payam/payment/core/service/MsisdnPrefixRouteCache.java
    - src/main/java/com/softropic/payam/payment/core/service/MsisdnRouter.java
    - src/main/java/com/softropic/payam/payment/core/service/PaymentOrchestrator.java
    - src/test/java/com/softropic/payam/payment/core/PaymentOrchestratorIT.java
  modified:
    - src/main/java/com/softropic/payam/disbursement/service/DisbursementOrchestrator.java
    - src/test/java/com/softropic/payam/disbursement/service/DisbursementOrchestratorTest.java
    - src/test/java/com/softropic/payam/e2e/builder/PaymentRequestBuilder.java
    - src/test/java/com/softropic/payam/e2e/domain/ApiKeyRotationGracePeriodTest.java
    - src/test/java/com/softropic/payam/e2e/domain/CallbackUrlSsrfGuardTest.java
    - src/test/java/com/softropic/payam/e2e/domain/ConcurrentIdempotencyRaceTest.java
    - src/test/java/com/softropic/payam/e2e/domain/FraudBeforeProviderCallTest.java
    - src/test/java/com/softropic/payam/e2e/domain/HashChainIntegrityTest.java
    - src/test/java/com/softropic/payam/e2e/domain/IdempotencyNoDoubleChargeTest.java
    - src/test/java/com/softropic/payam/e2e/domain/InitBeforeProviderCallTest.java
    - src/test/java/com/softropic/payam/e2e/domain/LedgerDoubleEntryTest.java
    - src/test/java/com/softropic/payam/e2e/domain/MtnPathMatrixTest.java
    - src/test/java/com/softropic/payam/e2e/domain/OrangePathMatrixTest.java
    - src/test/java/com/softropic/payam/e2e/domain/TenantIsolationTest.java
    - src/test/java/com/softropic/payam/e2e/domain/TransactionBoundaryTest.java
    - src/test/java/com/softropic/payam/e2e/domain/VelocityCounterFloodTest.java
    - src/test/java/com/softropic/payam/e2e/domain/WebhookPollingRaceTest.java
    - src/test/java/com/softropic/payam/e2e/fraud/FraudVelocityBlockE2ETest.java
    - src/test/java/com/softropic/payam/e2e/payment/FraudBlockedPaymentE2ETest.java
    - src/test/java/com/softropic/payam/e2e/payment/MtnPaymentInitiationE2ETest.java
    - src/test/java/com/softropic/payam/e2e/payment/MtnPollingFallbackE2ETest.java
    - src/test/java/com/softropic/payam/e2e/payment/OrangePayTokenExpiryE2ETest.java
    - src/test/java/com/softropic/payam/e2e/payment/OrangePaymentInitiationE2ETest.java
    - src/test/java/com/softropic/payam/e2e/payment/PaymentIdempotencyE2ETest.java
    - src/test/java/com/softropic/payam/e2e/payment/PaymentLookupE2ETest.java
    - src/test/java/com/softropic/payam/e2e/payment/ProviderTimeoutCircuitBreakerE2ETest.java
    - src/test/java/com/softropic/payam/payment/fraud/FraudEngineIT.java
    - src/test/java/com/softropic/payam/payment/fraud/FraudVelocityOrderingIT.java
  deleted:
    - src/main/java/com/softropic/payam/payment/api/ (entire tree, 1 file)
    - src/main/java/com/softropic/payam/payment/contract/ (entire tree, 4 files incl. exception/)
    - src/main/java/com/softropic/payam/payment/repo/ (entire tree, 2 files)
    - src/main/java/com/softropic/payam/payment/service/ (entire tree, 3 files)
    - src/test/java/com/softropic/payam/payment/PaymentOrchestratorIT.java (moved to payment/core/)
decisions:
  - "collection-flow-restricted regex (matching only payment.{api,contract,repo,service}.) applied in both Steps D and F — does not touch payment.fee/fraud/reconciliation/webhook imports"
  - "Javadoc @link FQNs in moved files updated to payment.core.* paths for documentation consistency"
  - "Docker-unavailable Testcontainers failures are pre-existing infrastructure constraint identical to waves 1-4 baselines; all failures are Docker-only, zero caused by this package move"
  - "Within-package imports updated in two passes: first in moved files (Step E), then in external callers (Step F)"
  - "PaymentOrchestratorIT in the external callers list pointed to old path; sed skipped it gracefully (old path no longer exists), updated at new location"
metrics:
  duration_minutes: 24
  completed_date: "2026-05-11"
  tasks_completed: 1
  files_changed: 39
---

# Phase 63 Plan 05: payment.core Package Creation — Wave 5 Summary

**One-liner:** Relocated payment collection-flow classes (api, contract, repo, service) into `payment.core` sub-packages, updated 1 production + 28 test external callers, deleted old directories; PAY-01 satisfied.

## Objective

Move all 10 production files and 1 test file from `payment/api/`, `payment/contract/`, `payment/repo/`, and `payment/service/` into `payment/core/{api,contract,contract/exception,repo,service}`, leaving the `payment/` umbrella directory containing only `core/`, `fee/`, `fraud/`, `reconciliation/`, `webhook/`.

## Files Moved (10 production + 1 test)

### Production (10 files)

| Sub-package | Files |
|-------------|-------|
| `core/api` | `PaymentResource.java` |
| `core/contract` | `OrchestratorError.java`, `PaymentRequest.java`, `PaymentResponse.java` |
| `core/contract/exception` | `UnknownMsisdnPrefixException.java` |
| `core/repo` | `MsisdnPrefixRoute.java`, `MsisdnPrefixRouteRepository.java` |
| `core/service` | `MsisdnPrefixRouteCache.java`, `MsisdnRouter.java`, `PaymentOrchestrator.java` |

### Test (1 file)

| Location | File |
|----------|------|
| `payment/core/` (from `payment/`) | `PaymentOrchestratorIT.java` |

## External Callers Updated

**Authoritative list from Step A grep** — 28 external callers discovered and updated (plan estimated ~27):

### Production callers (1):
- `disbursement/service/DisbursementOrchestrator.java` — updated 2 imports:
  - `payment.contract.exception.UnknownMsisdnPrefixException` → `payment.core.contract.exception.UnknownMsisdnPrefixException`
  - `payment.service.MsisdnRouter` → `payment.core.service.MsisdnRouter`

### Test callers (28):
- `disbursement/service/DisbursementOrchestratorTest.java`
- `e2e/builder/PaymentRequestBuilder.java`
- `e2e/domain/ApiKeyRotationGracePeriodTest.java`
- `e2e/domain/CallbackUrlSsrfGuardTest.java`
- `e2e/domain/ConcurrentIdempotencyRaceTest.java`
- `e2e/domain/FraudBeforeProviderCallTest.java`
- `e2e/domain/HashChainIntegrityTest.java`
- `e2e/domain/IdempotencyNoDoubleChargeTest.java`
- `e2e/domain/InitBeforeProviderCallTest.java`
- `e2e/domain/LedgerDoubleEntryTest.java`
- `e2e/domain/MtnPathMatrixTest.java`
- `e2e/domain/OrangePathMatrixTest.java`
- `e2e/domain/TenantIsolationTest.java`
- `e2e/domain/TransactionBoundaryTest.java`
- `e2e/domain/VelocityCounterFloodTest.java`
- `e2e/domain/WebhookPollingRaceTest.java`
- `e2e/fraud/FraudVelocityBlockE2ETest.java`
- `e2e/payment/FraudBlockedPaymentE2ETest.java`
- `e2e/payment/MtnPaymentInitiationE2ETest.java`
- `e2e/payment/MtnPollingFallbackE2ETest.java`
- `e2e/payment/OrangePayTokenExpiryE2ETest.java`
- `e2e/payment/OrangePaymentInitiationE2ETest.java`
- `e2e/payment/PaymentIdempotencyE2ETest.java`
- `e2e/payment/PaymentLookupE2ETest.java`
- `e2e/payment/ProviderTimeoutCircuitBreakerE2ETest.java`
- `payment/fraud/FraudEngineIT.java`
- `payment/fraud/FraudVelocityOrderingIT.java`
- `payment/core/PaymentOrchestratorIT.java` (updated at new location)

## Sibling Sub-packages: Confirmed Untouched

Waves 1-4 packages checked and confirmed unmodified:
- `payment/fee/` — UNTOUCHED (Wave 1)
- `payment/reconciliation/` — UNTOUCHED (Wave 2)
- `payment/fraud/` — UNTOUCHED (Wave 3)
- `payment/webhook/` — UNTOUCHED (Wave 4)

The collection-flow-restricted sed regex (`payment.(api|contract.exception|contract|repo|service).`) ensured sibling sub-package imports were not modified.

## Intentional Import Preservation

Per plan instructions, the following imports inside moved files were NOT changed (later plans sweep them):
- `com.softropic.payam.transaction.*` — preserved verbatim (PAY-02 / Plan 07 will sweep)
- `com.softropic.payam.common.*` — preserved verbatim (Phase 65)
- `com.softropic.payam.payment.fee.*`, `payment.fraud.*`, `payment.webhook.*` — preserved verbatim (already at correct paths from Waves 1-4)

## Verification Results

| Check | Result |
|-------|--------|
| `find payment/core -name '*.java' \| wc -l` | 10 production files |
| `find payment/core (test) -name '*.java' \| wc -l` | 1 test file |
| `payment/api/` removed | GONE |
| `payment/contract/` removed | GONE |
| `payment/repo/` removed | GONE |
| `payment/service/` removed | GONE |
| `payment/core/` present | EXISTS |
| `payment/fee/` present | EXISTS (untouched) |
| `payment/fraud/` present | EXISTS (untouched) |
| `payment/reconciliation/` present | EXISTS (untouched) |
| `payment/webhook/` present | EXISTS (untouched) |
| Package declaration: `PaymentResource.java` | `package com.softropic.payam.payment.core.api;` |
| Package declaration: `PaymentOrchestrator.java` | `package com.softropic.payam.payment.core.service;` |
| Package declaration: `PaymentOrchestratorIT.java` | `package com.softropic.payam.payment.core;` |
| DisbursementOrchestrator import count of `payment.core.` | 2 |
| Stale `payment.{api,contract,repo,service}` references | 0 |
| `mvn test-compile` | EXIT 0 — clean compilation |
| All 92 non-Docker unit test classes | PASS |
| Docker-dependent Testcontainers ITs | N/A — Docker unavailable (pre-existing constraint identical to waves 1-4 baselines) |

| Test type | Result |
|-----------|--------|
| `mvn test-compile` | EXIT 0 |
| DisbursementOrchestratorTest (33 tests) | PASS — updated imports verified |
| 92 non-Docker unit test classes | All PASS |
| Testcontainers-based ITs | N/A (Docker unavailable — pre-existing infrastructure issue; identical to waves 1-4 baseline) |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Javadoc @link FQNs in moved files updated**
- **Found during:** Step D (package declaration rewrite)
- **Issue:** After moving files, Javadoc `@link` references inside moved files still pointed to old paths (`payment.api.PaymentResource`, `payment.service.MsisdnRouter`, `payment.service.MsisdnPrefixRouteCache`) — same pattern corrected in waves 1-4 per STATE.md decisions
- **Fix:** Additional `sed` pass to update `@link` FQNs in `PaymentRequest.java`, `UnknownMsisdnPrefixException.java`, `MsisdnPrefixRouteRepository.java`, `MsisdnPrefixRoute.java`
- **Files modified:** 4 files in `payment/core/`
- **Commit:** `4c441ef`

**2. [Rule 1 - Bug] PaymentOrchestratorIT import update at new location**
- **Found during:** Step F
- **Issue:** The grep-based callers list included the old path of `PaymentOrchestratorIT.java`, which no longer existed after Step C (git mv); sed skipped it with "No such file or directory". The import update at the new location (`payment/core/`) needed a separate explicit step.
- **Fix:** Applied import rewrite explicitly to the new file path
- **Files modified:** `src/test/java/com/softropic/payam/payment/core/PaymentOrchestratorIT.java`
- **Commit:** `4c441ef`

## Known Stubs

None — this is a pure package relocation with no data wiring or UI rendering.

## Self-Check: PASSED

Files exist:
- `src/main/java/com/softropic/payam/payment/core/api/PaymentResource.java` — FOUND
- `src/main/java/com/softropic/payam/payment/core/service/PaymentOrchestrator.java` — FOUND
- `src/main/java/com/softropic/payam/payment/core/contract/exception/UnknownMsisdnPrefixException.java` — FOUND
- `src/test/java/com/softropic/payam/payment/core/PaymentOrchestratorIT.java` — FOUND
- Old `src/main/java/com/softropic/payam/payment/api/` — GONE (verified)
- Old `src/main/java/com/softropic/payam/payment/contract/` — GONE (verified)
- Old `src/main/java/com/softropic/payam/payment/repo/` — GONE (verified)
- Old `src/main/java/com/softropic/payam/payment/service/` — GONE (verified)

Commit `4c441ef` — FOUND (git rev-parse confirmed)
Stale `com.softropic.payam.payment.{api,contract,repo,service}.*` references — 0
