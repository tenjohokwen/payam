---
phase: 62-platform-layer-reorganization
plan: "05"
subsystem: platform.security
tags: [refactor, package-reorganization, security, PLAT-02]
dependency_graph:
  requires: [62-04]
  provides: [platform.security package with 145 prod + 19 test files]
  affects: [all modules importing security.*, tenant.config filters, admin, common, email, alert, payment, reconciliation, webhook, fee]
tech_stack:
  added: []
  patterns: [git-mv-for-history-preservation, bulk-sed-import-replacement, static-import-update]
key_files:
  created:
    - src/main/java/com/softropic/payam/platform/security/ (145 production files)
    - src/test/java/com/softropic/payam/platform/security/ (19 test files)
  modified:
    - src/main/java/com/softropic/payam/tenant/config/ApiKeyAuthenticationFilter.java
    - src/main/java/com/softropic/payam/tenant/config/TenantSecurityConfig.java
    - src/main/java/com/softropic/payam/admin/api/ (4 files)
    - src/main/java/com/softropic/payam/common/ (4 files)
    - src/main/java/com/softropic/payam/email/infrastructure/listener/ (2 files)
    - src/main/java/com/softropic/payam/platform/ (3 files)
    - plus 15 other prod + 7 test external callers
decisions:
  - Static imports (import static) require separate sed pass from regular imports
  - Bare package declarations (without sub-package) not matched by security. regex — fixed separately
  - git mv preserves rename history for all 165 files
metrics:
  duration_minutes: 38
  tasks_completed: 2
  files_changed: 204
  completed_date: "2026-05-07"
---

# Phase 62 Plan 05: PLAT-02 Security Package Migration Summary

Move all 145 production files and 19 test files from `security/` into `platform/security/`, updating package declarations and all external callers. The infrastructure.web cascade (tenant.config filter imports) updated in the same plan.

## What Was Done

### Task 1: Move security/ to platform/security/ (164 files)

Moved 146 `.java` production files (145 + package-info.java) using `git mv` from `security/` to `platform/security/`, preserving all 8 sub-packages:
- api (3 nested sub-packages: dto, registration)
- audit (4 nested sub-packages: api, filter, listener, repository, service)
- common (2 nested: event, util)
- config (flat)
- contract (3 nested: event, exception, util)
- infrastructure (4 nested: audit, filter, jwt/filter, listener)
- repo (flat)
- service (flat)

Moved 19 test files from `src/test/java/.../security/` to `src/test/java/.../platform/security/`.

For each moved file:
- Updated `package com.softropic.payam.security.*` → `package com.softropic.payam.platform.security.*`
- Updated `import com.softropic.payam.security.*` → `import com.softropic.payam.platform.security.*`
- Updated `import static com.softropic.payam.security.*` → `import static com.softropic.payam.platform.security.*`
- Updated fully-qualified code references (e.g., `Principal.instanceFrom(com.softropic.payam.security.repo.User)`)

Old `security/` directories deleted from both `src/main/java` and `src/test/java`.

**Commit:** `4436464`

### Task 2: Update 29 external callers + fix bare package declarations

Updated all remaining stale `com.softropic.payam.security.*` references across:

**22 production files:**
- `admin/api/` (4 files) — SecurityConstants import
- `alert/api/AlertRuleAdminResource.java` — SecurityConstants
- `common/client/RestRequestInterceptor.java` — SecurityConstants
- `common/consumer/Consumer.java` — security type references
- `common/persistence/AbstractAuditingEntity.java` — security types
- `common/persistence/RequestIdAuditEntityListener.java` — RequestIdProvider
- `common/threadpool/TenantContextTaskDecorator.java` — TenantContext
- `email/infrastructure/listener/` (2 files) — security contract events
- `fee/api/FeeRuleAdminResource.java` — SecurityConstants
- `payment/service/PaymentOrchestrator.java` — security types
- `platform/api/PlatformConfigAdminResource.java` — SecurityConstants
- `platform/config/PlatformConfig.java` — security types
- `platform/service/PlatformConfigService.java` — security types
- `reconciliation/api/ReconciliationResource.java` — SecurityConstants
- `tenant/api/TenantAdminResource.java` — security types
- **`tenant/config/ApiKeyAuthenticationFilter.java` — TenantContext + AppEndpoints (infrastructure.web cascade)**
- **`tenant/config/TenantSecurityConfig.java` — AppEndpoints (infrastructure.web cascade)**
- `webhook/api/WebhookDeliveryResource.java` — SecurityConstants

**7 test files:**
- `e2e/admin/TransactionInvestigationE2ETest.java`
- `e2e/builder/ApiKeyBuilder.java`
- `platform/PlatformConfigAdminResourceIT.java`
- `platform/service/PlatformConfigServiceTest.java`
- `reconciliation/ReconciliationApiIT.java`
- `tenant/TenantAdminResourceIT.java`
- `webhook/WebhookDeliveryIT.java`

**Infrastructure.web cascade (equivalent):**
- `tenant/config/ApiKeyAuthenticationFilter.java`: `security.common.util.TenantContext` → `platform.security.common.util.TenantContext` AND `security.config.AppEndpoints` → `platform.security.config.AppEndpoints`
- `tenant/config/TenantSecurityConfig.java`: `security.config.AppEndpoints` → `platform.security.config.AppEndpoints`
- FilterRegistrationBean(registration.setEnabled(false)) preserved verbatim

**Bare package declaration fix (deviation):**
- `SecurityIT.java` and `SecurityFilterChainIT.java` had `package com.softropic.payam.security;` (bare, no sub-package) — my initial `security.` pattern didn't match these; fixed separately.
- `package-info.java` also had bare `package com.softropic.payam.security;` — fixed.

**Commit:** `3ba4e8c`

## Verification Results

```
grep -rn "com.softropic.payam.security." src --include="*.java" | wc -l  → 0
mvn -q clean compile                                                        → exit 0
mvn -q test-compile                                                         → exit 0
SecurityIT (9/9 PASS), SecurityFilterChainIT (4/4 PASS)
UserProfileServiceIT (12/12 PASS), SecretServiceIT (3/3 PASS)
AppEndpointsTest (3/3 PASS), RateLimitingServiceTest (3/3 PASS)
```

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Static imports not caught by initial sed pass**
- **Found during:** Task 1 verification
- **Issue:** `sed 's/import com.softropic.payam.security./...'` didn't match `import static com.softropic.payam.security.*` lines; also didn't match fully-qualified code references
- **Fix:** Added separate sed passes for `import static` and fully-qualified `com.softropic.payam.security.` references
- **Files modified:** 30+ files within platform/security (batch operation)
- **Commit:** 4436464 (included in Task 1)

**2. [Rule 1 - Bug] Bare package declarations not updated by dot-suffix regex**
- **Found during:** Task 2 test-compile execution
- **Issue:** `SecurityIT.java`, `SecurityFilterChainIT.java`, and `package-info.java` had `package com.softropic.payam.security;` (no trailing dot), which my `security.` → `platform.security.` replacement didn't match
- **Fix:** Explicit `sed` targeting `^package com.softropic.payam.security;$` pattern
- **Files modified:** 3 files
- **Commit:** 3ba4e8c (included in Task 2)

### Plan Deviation — infrastructure.web files

The plan referenced `src/main/java/com/softropic/payam/infrastructure/web/ApiKeyAuthenticationFilter.java` and `...LoggingFilter.java` and `...TenantSecurityConfig.java`. In this worktree, these files reside under `tenant/config/` (not `infrastructure/web/`). Phase 61's infrastructure move was not yet applied on this branch. The equivalent cascade was applied to `tenant/config/ApiKeyAuthenticationFilter.java` and `tenant/config/TenantSecurityConfig.java` instead.

## Known Stubs

None — this is a pure package reorganization; no business logic introduced.

## Self-Check

Files created and verified:
- `find src/main/java/com/softropic/payam/platform/security -name '*.java' | wc -l` → 146 (145 + package-info.java)
- `find src/test/java/com/softropic/payam/platform/security -name '*.java' | wc -l` → 19
- `test -d src/main/java/com/softropic/payam/security` → false
- `test -d src/test/java/com/softropic/payam/security` → false
- `grep -rn "com.softropic.payam.security." src --include="*.java" | wc -l` → 0

Commits verified:
- 4436464 — Task 1 (git log confirms)
- 3ba4e8c — Task 2 (git log confirms)

## Self-Check: PASSED
