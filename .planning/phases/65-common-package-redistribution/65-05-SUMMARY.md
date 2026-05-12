---
phase: 65-common-package-redistribution
plan: "05"
subsystem: test-infrastructure
tags: [java, spring-boot, package-refactoring, common, cmn-04, test-cleanup, v12-final]

# Dependency graph
requires:
  - phase: 65-common-package-redistribution
    plan: "01"
    provides: "infrastructure.exception, infrastructure.message, infrastructure.config, infrastructure.logging at new paths"
  - phase: 65-common-package-redistribution
    plan: "02"
    provides: "infrastructure.client, infrastructure.threadpool, infrastructure.util, infrastructure.validation at new paths; HttpTestClient.java infrastructure.client.Client import already retargeted"
  - phase: 65-common-package-redistribution
    plan: "03"
    provides: "payment.core.contract for common.payment + common.refund redistribution"
  - phase: 65-common-package-redistribution
    plan: "04"
    provides: "platform.security.contract for Gender/Consumer/Location; production common/ empty shell ready for deletion"
provides:
  - "test/e2e/AdminLogin.java — test login utility at final destination (CMN-04 closure)"
  - "test/e2e/HttpTestClient.java — test HTTP client at final destination with infrastructure.client.Client import (Pitfall 8 honored)"
  - "test/infrastructure/TransactionExceptionSimulator.java — Spring @Component test helper at final destination"
  - "test/infrastructure/config/JacksonTest.java — co-located with CommonConfig in infrastructure.config"
  - "CMN-04 fully satisfied — com.softropic.payam.common package removed from source tree"
  - "v12 milestone (Phases 61-65) complete"
affects:
  - "test/e2e/ — gains AdminLogin.java, HttpTestClient.java"
  - "test/infrastructure/ — gains TransactionExceptionSimulator.java"
  - "test/infrastructure/config/ — gains JacksonTest.java (new directory)"
  - "6 external test-test callers: TransactionInvestigationE2ETest, WebhookDeliveryIT, PlatformConfigAdminResourceIT, SecurityFilterChainIT, SecurityIT, TenantAdminResourceIT"

# Tech stack
tech-stack:
  added: []
  patterns:
    - "macOS sed single-pass per-file pattern (4 distinct destinations)"
    - "Per-file explicit sed loop (avoids macOS shell variable expansion for multiple paths)"
    - "Atomic single commit: all 4 moves + 6 caller updates + empty-dir deletions in one commit"
    - "CMN-04 grep gate: find src -name *.java | xargs grep com.softropic.payam.common | grep -v /platform/security/common/ returns empty"

# Key files
key-files:
  created:
    - path: "src/test/java/com/softropic/payam/e2e/AdminLogin.java"
      note: "Moved from test/common/ — package updated to com.softropic.payam.e2e"
    - path: "src/test/java/com/softropic/payam/e2e/HttpTestClient.java"
      note: "Moved from test/common/ — package updated to com.softropic.payam.e2e; infrastructure.client.Client import was already retargeted by Plan 02 (Pitfall 8)"
    - path: "src/test/java/com/softropic/payam/infrastructure/TransactionExceptionSimulator.java"
      note: "Moved from test/common/ — package updated to com.softropic.payam.infrastructure; @Component annotation preserved"
    - path: "src/test/java/com/softropic/payam/infrastructure/config/JacksonTest.java"
      note: "Moved from test/common/configtest/ — package updated to com.softropic.payam.infrastructure.config; new infrastructure/config/ test directory created"
  deleted:
    - "src/test/java/com/softropic/payam/common/ (entire directory — all 4 files moved)"
    - "src/test/java/com/softropic/payam/common/configtest/ (empty after JacksonTest move)"
    - "src/main/java/com/softropic/payam/common/ (was already absent — removed by Plans 01-04)"
  modified:
    - "src/test/java/com/softropic/payam/e2e/admin/TransactionInvestigationE2ETest.java (AdminLogin import retargeted to e2e)"
    - "src/test/java/com/softropic/payam/payment/webhook/WebhookDeliveryIT.java (AdminLogin import retargeted to e2e)"
    - "src/test/java/com/softropic/payam/platform/admin/PlatformConfigAdminResourceIT.java (AdminLogin import retargeted to e2e)"
    - "src/test/java/com/softropic/payam/platform/security/SecurityFilterChainIT.java (HttpTestClient import retargeted to e2e)"
    - "src/test/java/com/softropic/payam/platform/security/SecurityIT.java (HttpTestClient import retargeted to e2e)"
    - "src/test/java/com/softropic/payam/platform/tenant/TenantAdminResourceIT.java (AdminLogin import retargeted to e2e)"

# Decisions
decisions:
  - "Production common/ was already absent before Plan 05 ran — Plans 01-04 deleted the directory shell when they removed the last files; Plan 05 only needed to confirm absence"
  - "HttpTestClient.java infrastructure.client.Client import was already retargeted by Plan 02 — Pitfall 8 is a no-op in Plan 05; verified by grep returning empty before any sed applied"
  - "JacksonTest.java has @Test commented out — this is the pre-existing state of the file; not a deviation; file is present for future test activation"
  - "6 external callers found (not 5-15 as estimated in plan) — AdminLogin callers: TransactionInvestigationE2ETest, WebhookDeliveryIT, PlatformConfigAdminResourceIT, TenantAdminResourceIT (4); HttpTestClient callers: SecurityFilterChainIT, SecurityIT (2)"

# Metrics
metrics:
  duration: "~10 minutes (mvn verify ~6 minutes)"
  completed: "2026-05-12"
  tasks_completed: 1
  files_created: 4
  files_deleted: 4
  files_modified: 6
  total_files_changed: 10
---

# Phase 65 Plan 05: CMN-04 Closure and v12 Final Gate Summary

**One-liner:** 4 test straggler files relocated from test/common/ to owner destinations; empty common/ shells deleted; CMN-04 grep gate passes (zero `com.softropic.payam.common` references in source tree); `mvn verify` green — v12 architectural reorganization complete.

## Objective Achieved

This final plan of Phase 65 closed out CMN-04 by:
1. Verifying the production `common/` directory was already absent (Plans 01-04 had removed it entirely)
2. Relocating the 4 remaining test straggler files to their owner destinations
3. Deleting the empty test `common/` shell
4. Running the definitive CMN-04 success criteria grep gate (zero results)
5. Running `mvn verify` as the final v12 quality gate (exit 0)

## Files Relocated (4 test files)

| File | Old Location | New Location | Package Change |
|------|-------------|-------------|---------------|
| AdminLogin.java | test/common/ | test/e2e/ | `common` → `e2e` |
| HttpTestClient.java | test/common/ | test/e2e/ | `common` → `e2e` |
| TransactionExceptionSimulator.java | test/common/ | test/infrastructure/ | `common` → `infrastructure` |
| configtest/JacksonTest.java | test/common/configtest/ | test/infrastructure/config/ | `common.configtest` → `infrastructure.config` |

## External Callers Updated (6 files)

| Caller File | Import Updated |
|-------------|---------------|
| e2e/admin/TransactionInvestigationE2ETest.java | `common.AdminLogin` → `e2e.AdminLogin` |
| payment/webhook/WebhookDeliveryIT.java | `common.AdminLogin` → `e2e.AdminLogin` |
| platform/admin/PlatformConfigAdminResourceIT.java | `common.AdminLogin` → `e2e.AdminLogin` |
| platform/security/SecurityFilterChainIT.java | `common.HttpTestClient` → `e2e.HttpTestClient` |
| platform/security/SecurityIT.java | `common.HttpTestClient` → `e2e.HttpTestClient` |
| platform/tenant/TenantAdminResourceIT.java | `common.AdminLogin` → `e2e.AdminLogin` |

## Pitfall Invariants Verified

**Pitfall 2 — platform.security.common.* UNTOUCHED:**
`grep -rn '^package com\.softropic\.payam\.platform\.security\.common' src/main/java` returned 13 declarations — all intact. The `platform/security/common/event/` and `platform/security/common/util/` files were not touched by this plan or any prior plan.

**Pitfall 8 — HttpTestClient.java Client import ALREADY RETARGETED:**
`grep 'com.softropic.payam.common.client.Client' src/test/java/.../HttpTestClient.java` returned empty — Plan 02 had already retargeted the import to `infrastructure.client.Client`. Verified before any sed was applied. The defensive sed in Step D was a no-op.

## CMN-04 Success Criteria

| Check | Command | Result |
|-------|---------|--------|
| Primary grep gate | `find src -name "*.java" \| xargs grep -l "com.softropic.payam.common" \| grep -v "/platform/security/common/"` | 0 files — PASSED |
| Strict dot-notation grep | `grep -rn 'com\.softropic\.payam\.common\.' src --include='*.java' \| grep -v '/platform/security/common/'` | 0 lines — PASSED |
| Directory absence (main + test) | `find src -type d -path '*/payam/common'` | empty — PASSED |
| No stray common/ dirs | `find src -type d -name 'common' \| grep -v 'platform/security/common' \| grep -v 'frontend'` | empty — PASSED |
| Pitfall 2 intact | `grep -rn '^package com\.softropic\.payam\.platform\.security\.common' src/main/java` | 13 declarations — PASSED |

## State of common/ Directories After Plan 05

**Production (`src/main/java/.../common/`):** ABSENT — removed by Plans 01-04.
**Test (`src/test/java/.../common/`):** ABSENT — all 4 test files moved in this plan; `configtest/` and `common/` rmdir'd successfully.

## Phase 65 Requirements Summary

| Req | Plan | Status |
|-----|------|--------|
| CMN-01 | Plan 03 | SATISFIED — common.payment + common.refund → payment.core.contract |
| CMN-02 | Plans 01 + 02 | SATISFIED — infrastructure sub-packages fully populated |
| CMN-03 | Plan 04 | SATISFIED — Gender/Consumer/Location → platform.security.contract; enums → infrastructure.util |
| CMN-04 | This plan (05) | SATISFIED — common package deleted; grep gate passes (zero refs) |
| BUILD-01 | Cross-cutting | SATISFIED — `mvn verify` exits 0 throughout all 5 plans |
| BUILD-02 | Cross-cutting | SATISFIED — REST API, DB schema, Flyway migrations unchanged |
| BUILD-03 | Cross-cutting | SATISFIED — Spring component-scan, Flyway config, security filter verified functional |

## v12 Milestone Status

All 5 phases of v12 are complete:

| Phase | Name | Status |
|-------|------|--------|
| 61 | Infrastructure Layer Creation | COMPLETE |
| 62 | Platform Layer Reorganization | COMPLETE |
| 63 | Payment Domain Consolidation | COMPLETE |
| 64 | Provider Infrastructure Encapsulation | COMPLETE |
| 65 | Common Package Redistribution | COMPLETE (this plan) |

**v12 architectural reorganization is fully shipped.** The codebase now has explicit bounded contexts (`payment`, `platform`, `infrastructure`) with no catch-all `common` package remaining.

## Build Result

- `mvn -q test-compile`: EXIT 0 (compile gate — all imports resolve)
- `mvn -q verify`: EXIT 0 (full integration suite — final v12 quality gate)

## Deviations from Plan

**[No-op deviation] Pitfall 8 already resolved by Plan 02**
- **Found during:** Step A (pre-flight check) — HttpTestClient.java had no stale `common.client.Client` import
- **Issue:** Plan expected to need to retarget the import; Plan 02's sed sweep had already done it
- **Fix:** Step D was a no-op for the primary import; defensive common.* sweep was also clean
- **Impact:** None — correct state achieved regardless

**[No-op deviation] Production common/ directory absent before Plan 05 ran**
- **Found during:** Pre-flight Check 2
- **Issue:** Plan expected to need to `rmdir src/main/java/com/softropic/payam/common`; Plans 01-04 had already removed the directory when the last file was deleted
- **Fix:** Step F confirmed absence; `rmdir` not needed
- **Impact:** None — correct state achieved; no regression

## Prior Plan Work Preserved

All zero-stale-reference checks passed at task start:

| Prior Plan | Pattern | Count |
|-----------|---------|-------|
| Plan 01 | common.{exception,message,config,logging}.* | 0 |
| Plan 02 | common.{client,threadpool,util,validation,dto}.* | 0 |
| Plan 02 root | common.{ClockProvider,Constants,Predicate,TimeGuru,TransactionIdProvider} | 0 |
| Plan 03 | common.{payment,refund}.* | 0 |
| Plan 04 | common.{consumer,enums,Gender} | 0 |
| Phase 64 | {mtn,orange}.* (flat package) | 0 |

## Known Stubs

None — this plan performs pure package relocation. No data sources, no APIs, no UI components.

## Self-Check

Files exist at final destinations:
- `src/test/java/com/softropic/payam/e2e/AdminLogin.java`: FOUND
- `src/test/java/com/softropic/payam/e2e/HttpTestClient.java`: FOUND
- `src/test/java/com/softropic/payam/infrastructure/TransactionExceptionSimulator.java`: FOUND
- `src/test/java/com/softropic/payam/infrastructure/config/JacksonTest.java`: FOUND

Deleted directories absent:
- `src/main/java/com/softropic/payam/common/`: ABSENT (removed by Plans 01-04)
- `src/test/java/com/softropic/payam/common/`: ABSENT (removed in this plan)

CMN-04 primary grep: 0 files — PASSED
CMN-04 directory: no payam/common dir — PASSED
Pitfall 2: 13 platform.security.common declarations intact — PASSED
Pitfall 8: infrastructure.client.Client import in HttpTestClient.java — PASSED

Task commit: b3df7e7 — FOUND

## Self-Check: PASSED
