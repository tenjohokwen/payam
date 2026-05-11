---
phase: 64-provider-infrastructure-encapsulation
plan: "01"
subsystem: payment.provider.mtn
tags: [package-move, mtn, provider, prov-01, v12]
dependency_graph:
  requires: [63-07-PLAN.md — payment domain consolidation complete]
  provides: [payment.provider.mtn — MTN adapter at new location; PROV-01 satisfied]
  affects: [8 external production callers, 22 external test callers, 3 YAML Resilience4j config files]
tech_stack:
  added: []
  patterns:
    - "macOS BSD sed two-pass rewrite: sub-package decls first (ending with dot), root decls second (ending with semicolon)"
    - "git mv for directory-level rename — git detects 90-99% similarity and preserves full history"
    - "Single atomic commit for all 28 moved files + 30 external caller updates + 3 YAML FQN updates + 1 Fee02RegressionTest path fix"
key_files:
  created:
    - src/main/java/com/softropic/payam/payment/provider/mtn/MtnModule.java
    - src/main/java/com/softropic/payam/payment/provider/mtn/config/MtnConfig.java
    - src/main/java/com/softropic/payam/payment/provider/mtn/config/MtnMoMoConfig.java
    - src/main/java/com/softropic/payam/payment/provider/mtn/config/MtnSchedulerConfig.java
    - src/main/java/com/softropic/payam/payment/provider/mtn/contract/MtnCallbackPayload.java
    - src/main/java/com/softropic/payam/payment/provider/mtn/contract/MtnTransactionStatus.java
    - src/main/java/com/softropic/payam/payment/provider/mtn/contract/dto/ (7 DTOs)
    - src/main/java/com/softropic/payam/payment/provider/mtn/contract/exception/MtnAccountInactiveException.java
    - src/main/java/com/softropic/payam/payment/provider/mtn/contract/exception/MtnApiException.java
    - src/main/java/com/softropic/payam/payment/provider/mtn/infrastructure/MtnMoMoClient.java
    - src/main/java/com/softropic/payam/payment/provider/mtn/service/MtnMoMoPort.java
    - src/main/java/com/softropic/payam/payment/provider/mtn/service/MtnStatusMapper.java
    - src/main/java/com/softropic/payam/payment/provider/mtn/service/MtnStatusPollerJob.java
    - src/main/java/com/softropic/payam/payment/provider/mtn/service/MtnTokenService.java
    - src/main/java/com/softropic/payam/payment/provider/mtn/web/MtnCallbackController.java
    - src/main/java/com/softropic/payam/payment/provider/mtn/web/MtnIpWhitelistInterceptor.java
    - src/main/java/com/softropic/payam/payment/provider/mtn/web/MtnWebConfig.java
    - src/test/java/com/softropic/payam/payment/provider/mtn/MtnMoMoPortIT.java
    - src/test/java/com/softropic/payam/payment/provider/mtn/MtnTokenServiceIT.java
    - src/test/java/com/softropic/payam/payment/provider/mtn/service/MtnMoMoPortDisbursementCallbackTest.java
    - src/test/java/com/softropic/payam/payment/provider/mtn/service/MtnStatusPollerJobTimeoutTest.java
    - src/test/java/com/softropic/payam/payment/provider/mtn/web/MtnWebConfigTest.java
  deleted:
    - src/main/java/com/softropic/payam/mtn/ (entire directory tree — 23 files)
    - src/test/java/com/softropic/payam/mtn/ (entire directory tree — 5 files)
  modified:
    - src/main/java/com/softropic/payam/payment/core/service/PaymentOrchestrator.java
    - src/main/java/com/softropic/payam/payment/disbursement/api/MtnDisbursementCallbackController.java
    - src/main/java/com/softropic/payam/payment/disbursement/service/DisbursementCallbackTransitionService.java
    - src/main/java/com/softropic/payam/payment/disbursement/service/DisbursementOrchestrator.java
    - src/main/java/com/softropic/payam/payment/reconciliation/port/MtnReportAdapter.java
    - src/main/java/com/softropic/payam/payment/webhook/service/WebhookDoubleCheckHandler.java
    - src/main/java/com/softropic/payam/payment/webhook/service/WebhookTransitionService.java
    - src/main/java/com/softropic/payam/platform/monitoring/MtnPlatformHealthIndicator.java
    - src/main/resources/application.yaml (line 264 Resilience4j FQN)
    - src/main/resources/application-uat.yaml (line 252 Resilience4j FQN)
    - src/main/resources/application-dev.yaml (line 253 Resilience4j FQN)
    - 22 external test caller files (e2e, reconciliation, webhook, disbursement, core)
    - src/test/java/com/softropic/payam/payment/disbursement/service/Fee02RegressionTest.java (path update)
decisions:
  - "Fee02RegressionTest.java had hardcoded old path mtn/service/MtnMoMoPort.java — updated to payment/provider/mtn/service/MtnMoMoPort.java (Rule 1 bug fix: static analysis test references obsolete file path)"
  - "Single atomic commit for all 62 file changes — partial commit would leave build uncompilable (consistent with Phase 61/63 pattern)"
  - "macOS BSD sed two-pass approach: sub-package declarations first (ending with dot), root package declarations second (ending with semicolon) — consistent with Phase 63-02 and 63-07 decisions in STATE.md"
metrics:
  duration: "~60 minutes"
  completed: "2026-05-11"
  tasks_completed: 1
  tasks_total: 1
  files_changed: 62
---

# Phase 64 Plan 01: MTN Package Move to payment.provider.mtn Summary

**One-liner:** Relocated flat `mtn/` package (23 prod + 5 test files) into `payment.provider.mtn/` — sub-packages preserved, 30 external callers updated, YAML Resilience4j FQNs updated, mvn verify green, PROV-01 satisfied.

## What Was Done

Relocated the entire `mtn/` package tree to `payment.provider.mtn/` as part of the v12 bounded-context reorganization (Phase 64 PROV-01). This is a pure rename with no behavior changes.

### Files Moved (28 total)

**Production files (23) — src/main/java/com/softropic/payam/payment/provider/mtn/:**

| Sub-package | Files |
|-------------|-------|
| `(root)` | MtnModule.java |
| `config/` | MtnConfig.java, MtnMoMoConfig.java, MtnSchedulerConfig.java |
| `contract/` | MtnCallbackPayload.java, MtnTransactionStatus.java |
| `contract/dto/` | AccountBalanceResponse.java, AccountHolderInfoResponse.java, DisbursementRequest.java, MtnTokenResponse.java, RequestToPayRequest.java, RequestToPayStatusResponse.java, TransferStatusResponse.java |
| `contract/exception/` | MtnAccountInactiveException.java, MtnApiException.java |
| `infrastructure/` | MtnMoMoClient.java |
| `service/` | MtnMoMoPort.java, MtnStatusMapper.java, MtnStatusPollerJob.java, MtnTokenService.java |
| `web/` | MtnCallbackController.java, MtnIpWhitelistInterceptor.java, MtnWebConfig.java |

**Test files (5) — src/test/java/com/softropic/payam/payment/provider/mtn/:**

| Sub-package | Files |
|-------------|-------|
| `(root)` | MtnMoMoPortIT.java, MtnTokenServiceIT.java |
| `service/` | MtnMoMoPortDisbursementCallbackTest.java, MtnStatusPollerJobTimeoutTest.java |
| `web/` | MtnWebConfigTest.java |

### External Callers Updated (30 files)

**Production callers (8):**
- `payment/core/service/PaymentOrchestrator.java` — imports MtnAccountInactiveException + MtnMoMoPort
- `payment/disbursement/api/MtnDisbursementCallbackController.java` — imports MtnCallbackPayload + MtnMoMoPort
- `payment/disbursement/service/DisbursementCallbackTransitionService.java` — imports MtnStatusMapper
- `payment/disbursement/service/DisbursementOrchestrator.java` — imports MtnMoMoPort
- `payment/reconciliation/port/MtnReportAdapter.java` — imports MtnMoMoPort
- `payment/webhook/service/WebhookDoubleCheckHandler.java` — imports MtnMoMoPort
- `payment/webhook/service/WebhookTransitionService.java` — imports MtnStatusMapper
- `platform/monitoring/MtnPlatformHealthIndicator.java` — imports MtnMoMoPort

**Test callers (22):**
- `payment/core/PaymentOrchestratorIT.java` — import + inline FQN body ref at line 173
- `payment/disbursement/api/MtnDisbursementCallbackControllerTest.java`
- `payment/disbursement/service/DisbursementOrchestratorTest.java`
- `payment/reconciliation/ReconciliationJobIT.java`
- `payment/reconciliation/ReconciliationFailedStateIT.java`
- `payment/webhook/service/WebhookDoubleCheckHandlerFlowRoutingTest.java`
- `platform/security/api/AdminLoginResourceTest.java`
- `e2e/builder/MtnWebhookPayloadBuilder.java`
- `e2e/domain/HashChainIntegrityTest.java`
- `e2e/domain/LedgerDoubleEntryTest.java`
- `e2e/domain/MtnPathMatrixTest.java`
- `e2e/domain/TransactionBoundaryTest.java`
- `e2e/domain/WebhookDoubleCheckTest.java`
- `e2e/domain/WebhookPollingRaceTest.java`
- `e2e/payment/MtnPaymentInitiationE2ETest.java`
- `e2e/payment/MtnPollingFallbackE2ETest.java`
- `e2e/reconciliation/DailyReconciliationE2ETest.java`
- `e2e/webhook/MtnPutCallbackAcceptanceE2ETest.java`
- `e2e/webhook/MtnWebhookDoubleCheckE2ETest.java`
- `e2e/webhook/OutboundWebhookDeliveryE2ETest.java`
- `e2e/webhook/WebhookReplayProtectionE2ETest.java`
- `orange/service/OrangeStatusPollerJobTimeoutTest.java` (cross-tree mtn import in orange/ test tree)

### YAML Files Updated (3)

- `src/main/resources/application.yaml` — line 264: Resilience4j `ignoreExceptions` FQN `com.softropic.payam.mtn.contract.exception.MtnAccountInactiveException` → `com.softropic.payam.payment.provider.mtn.contract.exception.MtnAccountInactiveException`
- `src/main/resources/application-uat.yaml` — line 252: same FQN update
- `src/main/resources/application-dev.yaml` — line 253: same FQN update

### Special: PaymentOrchestratorIT.java Inline FQN Body Reference

Line 173 updated from:
```
.ignoreExceptions(com.softropic.payam.mtn.contract.exception.MtnAccountInactiveException.class)
```
to:
```
.ignoreExceptions(com.softropic.payam.payment.provider.mtn.contract.exception.MtnAccountInactiveException.class)
```

This was not just an import — it was an inline FQN expression in the method body (consistent with Phase 63-07 lesson about FQN body references requiring separate sed sweep).

### Preserved Byte-For-Byte

- URL path string literals: `/v1/callbacks/mtn`, `/v1/callbacks/mtn/disbursement`, `/v1/callbacks/mtn/*`
- All Spring annotations: `@Service`, `@Configuration`, `@Component`, `@PutMapping`, `@RequestMapping`
- Quartz annotations: `@DisallowConcurrentExecution`, `@PersistJobDataAfterExecution`, `extends QuartzJobBean`
- MtnWebConfig `@Configuration` + `addPathPatterns` registration for IP whitelist interceptor
- FilterRegistrationBean(setEnabled=false) pattern (unchanged — not present in MtnWebConfig)

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed Fee02RegressionTest.java hardcoded old file path**

- **Found during:** Task 1, Step J (mvn verify run)
- **Issue:** `Fee02RegressionTest.disbursementProviderPortsDoNotCreateTransactionRows` had a hardcoded path `com/softropic/payam/mtn/service/MtnMoMoPort.java` which threw `NoSuchFileException` after the move. This test's purpose is to verify structural properties of provider port files — updating it to the new path is correct behavior.
- **Fix:** Updated `Path mtn = SRC_MAIN_JAVA.resolve("com/softropic/payam/payment/provider/mtn/service/MtnMoMoPort.java");` (was `mtn/service/MtnMoMoPort.java`)
- **Files modified:** `src/test/java/com/softropic/payam/payment/disbursement/service/Fee02RegressionTest.java`
- **Commit:** 867d282 (included in atomic task commit)

## Verification Results

- `find src/main/java/com/softropic/payam/payment/provider/mtn -name '*.java' | wc -l` = **23**
- `find src/test/java/com/softropic/payam/payment/provider/mtn -name '*.java' | wc -l` = **5**
- `test -d src/main/java/com/softropic/payam/mtn` = **false (directory deleted)**
- `test -d src/test/java/com/softropic/payam/mtn` = **false (directory deleted)**
- `grep -rn 'com.softropic.payam.mtn.' src --include='*.java' | wc -l` = **0**
- `grep -rn 'com.softropic.payam.mtn.' src/main/resources/application*.yaml | wc -l` = **0**
- `mvn -q test-compile` = **exits 0**
- `mvn -q verify` = **exits 0**

## Commits

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Move mtn → payment.provider.mtn, update all callers | 867d282 | 62 files (28 moved + 30 external callers updated + 3 YAML + 1 Fee02RegressionTest) |

## Known Stubs

None — this is a pure package rename with no new functionality, no UI data sources, and no placeholder values introduced.

## Self-Check: PASSED
