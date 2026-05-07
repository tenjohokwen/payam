---
phase: 62-platform-layer-reorganization
plan: "03"
subsystem: platform.admin
tags: [refactor, package-reorganization, plat-05, admin, platform-config]
dependency_graph:
  requires: [62-01, 62-02]
  provides: [platform.admin package with 22 production + 3 test files]
  affects: [admin callers, platform-flat callers, platform.monitoring health indicators]
tech_stack:
  added: []
  patterns: [package-declaration-update, import-sweep, git-mv-for-history-preservation]
key_files:
  created:
    - src/main/java/com/softropic/payam/platform/admin/api/AdminMetricsResource.java
    - src/main/java/com/softropic/payam/platform/admin/api/AdminTransactionResource.java
    - src/main/java/com/softropic/payam/platform/admin/api/AuditResource.java
    - src/main/java/com/softropic/payam/platform/admin/api/ProviderStatusResource.java
    - src/main/java/com/softropic/payam/platform/admin/api/PlatformConfigAdminResource.java
    - src/main/java/com/softropic/payam/platform/admin/config/PayamPlatformProperties.java
    - src/main/java/com/softropic/payam/platform/admin/config/PlatformConfig.java
    - src/main/java/com/softropic/payam/platform/admin/contract/EventLogEntryDto.java
    - src/main/java/com/softropic/payam/platform/admin/contract/HashChainAuditSummaryDto.java
    - src/main/java/com/softropic/payam/platform/admin/contract/HashChainResultDto.java
    - src/main/java/com/softropic/payam/platform/admin/contract/MetricsSnapshotDto.java
    - src/main/java/com/softropic/payam/platform/admin/contract/PinDto.java
    - src/main/java/com/softropic/payam/platform/admin/contract/PlatformConfigDto.java
    - src/main/java/com/softropic/payam/platform/admin/contract/ProviderStatusDto.java
    - src/main/java/com/softropic/payam/platform/admin/contract/TransactionDetailDto.java
    - src/main/java/com/softropic/payam/platform/admin/contract/TransactionSummaryDto.java
    - src/main/java/com/softropic/payam/platform/admin/contract/event/PlatformConfigChangedEvent.java
    - src/main/java/com/softropic/payam/platform/admin/repo/PlatformConfig.java
    - src/main/java/com/softropic/payam/platform/admin/repo/PlatformConfigRepository.java
    - src/main/java/com/softropic/payam/platform/admin/service/AdminTransactionQueryService.java
    - src/main/java/com/softropic/payam/platform/admin/service/PaymentMetricsService.java
    - src/main/java/com/softropic/payam/platform/admin/service/PlatformConfigService.java
    - src/test/java/com/softropic/payam/platform/admin/PlatformConfigAdminResourceIT.java
    - src/test/java/com/softropic/payam/platform/admin/config/PayamPlatformPropertiesTest.java
    - src/test/java/com/softropic/payam/platform/admin/service/PlatformConfigServiceTest.java
  modified:
    - src/main/java/com/softropic/payam/platform/monitoring/MtnPlatformHealthIndicator.java
    - src/main/java/com/softropic/payam/platform/monitoring/OrangePlatformHealthIndicator.java
    - src/main/java/com/softropic/payam/disbursement/api/MtnDisbursementCallbackController.java
    - src/main/java/com/softropic/payam/disbursement/api/OrangeDisbursementCallbackController.java
    - src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java
    - src/main/java/com/softropic/payam/mtn/web/MtnCallbackController.java
    - src/main/java/com/softropic/payam/orange/web/OrangeCallbackController.java
    - src/main/java/com/softropic/payam/orange/service/OrangeMoneyPort.java
    - src/main/java/com/softropic/payam/platform/notification/infrastructure/listener/PlatformConfigEmailListener.java
    - src/main/java/com/softropic/payam/platform/notification/infrastructure/listener/DisbursementOpsAlertEmailListener.java
    - src/main/java/com/softropic/payam/disbursement/config/DisbursementProperties.java
    - src/test/java/com/softropic/payam/disbursement/api/MtnDisbursementCallbackControllerTest.java
    - src/test/java/com/softropic/payam/disbursement/api/OrangeDisbursementCallbackControllerTest.java
    - src/test/java/com/softropic/payam/disbursement/api/DisbursementResourceIT.java
    - src/test/java/com/softropic/payam/disbursement/service/DisbursementIdempotencyRetryIT.java
    - src/test/java/com/softropic/payam/disbursement/service/DisbursementOrchestratorIT.java
    - src/test/java/com/softropic/payam/payment/PaymentOrchestratorIT.java
    - src/test/java/com/softropic/payam/platform/notification/infrastructure/listener/PlatformConfigEmailListenerTest.java
    - src/test/java/com/softropic/payam/platform/notification/infrastructure/listener/DisbursementOpsAlertEmailListenerTest.java
    - src/test/java/com/softropic/payam/e2e/PlatformConfigInitializer.java
    - src/test/java/com/softropic/payam/orange/OrangeMoneyPortIT.java
    - src/test/java/com/softropic/payam/orange/service/OrangeMoneyPortDisbursementCallbackTest.java
decisions:
  - "PlatformConfig name collision resolved by sub-package separation: platform.admin.config.PlatformConfig (@Configuration) and platform.admin.repo.PlatformConfig (@Entity) are distinct FQNs with no rename needed"
  - "DisbursementOpsAlertEmailListener stale email.contract.* imports fixed as Rule 3 deviation — pre-existing bug from Phase 62-02 that blocked compilation"
metrics:
  duration: "~20 minutes"
  completed_date: "2026-05-07"
  tasks_completed: 2
  files_changed: 54
---

# Phase 62 Plan 03: Admin + Flat-Platform Consolidation into platform.admin Summary

PLAT-05 requirement satisfied: 13 admin files + 9 flat platform files merged into `platform.admin` sub-package tree. 29 external callers updated. `mvn verify` green with 301 tests, 0 failures.

## What Was Done

Merged two separate source packages (`admin/` and the flat `platform/{api,config,contract,repo,service}/`) into a unified `platform/admin/` package hierarchy, preserving all sub-package structure.

### Task 1: File Moves + Internal Package Declaration Updates

Used `git mv` to preserve file history (97-99% similarity scores). All 22 production files and 3 test files updated with new package declarations. Within-package imports rewritten atomically.

**Files moved from `admin/` to `platform/admin/`:**
- `api/`: AdminMetricsResource, AdminTransactionResource, AuditResource, ProviderStatusResource
- `contract/`: EventLogEntryDto, HashChainAuditSummaryDto, HashChainResultDto, MetricsSnapshotDto, ProviderStatusDto, TransactionDetailDto, TransactionSummaryDto
- `service/`: AdminTransactionQueryService, PaymentMetricsService

**Files moved from flat `platform/` to `platform/admin/`:**
- `api/`: PlatformConfigAdminResource
- `config/`: PayamPlatformProperties, PlatformConfig (@Configuration)
- `contract/`: PinDto, PlatformConfigDto
- `contract/event/`: PlatformConfigChangedEvent
- `repo/`: PlatformConfig (@Entity), PlatformConfigRepository
- `service/`: PlatformConfigService

**Test files moved:**
- `platform/PlatformConfigAdminResourceIT` → `platform/admin/`
- `platform/config/PayamPlatformPropertiesTest` → `platform/admin/config/`
- `platform/service/PlatformConfigServiceTest` → `platform/admin/service/`

Old directories deleted: `admin/` (entire tree), `platform/{api,config,contract,repo,service}/`.
Preserved: `platform/notification/` and `platform/monitoring/` (from Plans 01-02).

### Task 2: External Caller Import Updates + `mvn verify`

Updated all stale imports across 29 files:
- 7 production + 2 test files: `admin.*` → `platform.admin.*`
- 5 production + 15 test files: `platform.{api,config,contract,repo,service}.*` → `platform.admin.*`
- 2 health indicators in `platform.monitoring`: updated from flat `platform.contract.*` and `platform.service.*` to `platform.admin.contract.*` and `platform.admin.service.*`

## PlatformConfig Name Pair

Two classes named `PlatformConfig` now coexist as distinct FQNs:
- `com.softropic.payam.platform.admin.config.PlatformConfig` — Spring `@Configuration` (registers `pinCryptopher` bean)
- `com.softropic.payam.platform.admin.repo.PlatformConfig` — JPA `@Entity` (`platform_config` table)

Sub-package separation (`config/` vs `repo/`) keeps them distinct at both the compiler and Spring bean levels. No rename was needed.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Fixed stale `email.contract.*` imports in DisbursementOpsAlertEmailListener (prod + test)**

- **Found during:** Task 2 — `mvn -q clean compile` failure
- **Issue:** `DisbursementOpsAlertEmailListener.java` and its test still imported `com.softropic.payam.email.contract.{EmailTemplate,Envelope,Recipient}` after Phase 62-02 (commit 7c82d9d was supposed to fix this but left these stale imports)
- **Fix:** Updated to `com.softropic.payam.platform.notification.contract.*` in both prod and test files
- **Files modified:** `platform/notification/infrastructure/listener/DisbursementOpsAlertEmailListener.java`, `...DisbursementOpsAlertEmailListenerTest.java`
- **Commit:** 73ef87b (included in Task 2 commit)

## Verification

```
find src/main/java/com/softropic/payam/platform -maxdepth 1 -type d
  → platform/admin, platform/notification, platform/monitoring (no flat sub-dirs)

grep -rn "com.softropic.payam.admin." src --include="*.java"  → 0 results
grep -rEn 'com.softropic.payam.platform.(api|config|contract|repo|service).' src --include="*.java"  → 0 results

mvn verify: BUILD SUCCESS, 301 tests, 0 failures, 3 skipped
```

## Commits

- `b664369` — Task 1: Move 22 prod + 3 test files, update package declarations
- `73ef87b` — Task 2: Update 29 external caller imports + Rule-3 deviation fix, mvn verify green

## Self-Check: PASSED
