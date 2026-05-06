# Phase 62: Platform Layer Reorganization - Research

**Researched:** 2026-05-07
**Domain:** Java package refactoring — Spring Boot 3.x, Spring Security, Quartz scheduler
**Confidence:** HIGH

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| PLAT-01 | `tenant` package relocated to `platform.tenant` with all imports updated | 24 production files; 8 test files; 8 prod callers + 67 test callers identified |
| PLAT-02 | `security` package relocated to `platform.security` with all imports updated | 145 production files; 19 test files; 23 prod callers + 7 test callers identified |
| PLAT-03 | `email` and `alert` packages merged into `platform.notification` with all imports updated | 23 + 7 = 30 production files; 7 + 1 = 8 test files; 7 prod + 8 test callers for email, 0 callers for alert outside itself |
| PLAT-04 | `health` and `ops` packages merged into `platform.monitoring` with all imports updated | 2 + 1 = 3 production files; 0 + 1 = 1 test file; 5 prod callers for platform.*, 0 callers for health/ops outside themselves |
| PLAT-05 | `admin` and `platform` (flat) packages merged into `platform.admin` with all imports updated | 13 + 9 = 22 production files; 3 test files; 5 prod + 2 test callers for admin, 5 prod + 15 test callers for platform |
</phase_requirements>

## Summary

Phase 62 reorganizes five groups of packages into four new `platform.*` sub-packages. No new logic is introduced — only package declarations and import statements change. The phase involves **224 production source files** and **40 test source files** across 8 source packages, merging into 5 target packages (`platform.tenant`, `platform.security`, `platform.notification`, `platform.monitoring`, `platform.admin`).

The `security` package is by far the largest unit of work (145 production + 19 test files) and has the most external callers (23 production + 7 test files outside `security/`). Critically, `security` has deep coupling with `infrastructure.web` — `ApiKeyAuthenticationFilter`, `TenantSecurityConfig`, and `LoggingFilter` (already moved to `infrastructure.web` in Phase 61) import from `security.common.util.TenantContext` and `security.config.AppEndpoints`. When `security` moves to `platform.security`, those three infrastructure.web files must have their imports updated.

The `tenant` package has fewer source files (24 production) but extremely wide test-layer coupling: 67 test files outside `tenant/` import from `tenant.*`. These 67 files span the entire test suite (E2E tests, integration tests, test builders) and use `TenantPrincipal`, `TenantStatus`, `TenantApiKey`, and `ApiKeyService`.

The two merges (`email` + `alert` → `platform.notification`; `health` + `ops` → `platform.monitoring`) are simple. The `alert` package already imports from `email` (AlertNotificationListener uses email's MailManager, Envelope, etc.), so merging them into one package eliminates that cross-package coupling.

`@SpringBootApplication` on `PayamApplication` implicitly scans all sub-packages under `com.softropic.payam`, so adding `platform.*` sub-packages requires zero component-scan configuration changes.

**Primary recommendation:** Execute in five discrete waves (one per PLAT requirement), running `mvn verify` after each wave and committing only when green. Order from smallest external caller impact to largest: PLAT-03 and PLAT-04 first (few callers), then PLAT-05, then PLAT-01, then PLAT-02 last (most callers, most risk).

## Standard Stack

### Core (verified from source tree)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Boot | 3.5.11 | Application framework, component scan | Already in use |
| Spring Security | (via Spring Boot BOM) | SecurityConfiguration, JWT filters | Already in use; security.config lives here |
| Quartz | (via Spring Boot BOM) | RotatedKeyCleanupSchedulerConfig in tenant.config | Already in use; moves with tenant |
| Maven Failsafe + Surefire | (via Spring Boot BOM) | `mvn verify` integration test execution | Already in use |

**Installation:** No new dependencies required. This is a pure package reorganization.

**Version verification:** All versions already confirmed in pom.xml (Spring Boot 3.5.11, Java 17.0.1, Maven 3.9.9).

## Architecture Patterns

### Recommended Project Structure (after Phase 62)
```
src/main/java/com/softropic/payam
├── platform/                            # NEW: platform bounded context
│   ├── tenant/                          # was: tenant/
│   │   ├── api/TenantAdminResource.java
│   │   ├── config/RotatedKeyCleanupSchedulerConfig.java   # moved from tenant.config
│   │   ├── contract/
│   │   ├── repo/
│   │   └── service/
│   ├── security/                        # was: security/
│   │   ├── api/
│   │   ├── audit/
│   │   ├── common/
│   │   ├── config/                      # AppEndpoints, SecurityConfiguration, etc.
│   │   ├── contract/
│   │   ├── infrastructure/              # JWT filters, auth handlers
│   │   ├── repo/
│   │   └── service/
│   ├── notification/                    # was: email/ + alert/ (merged)
│   │   ├── config/
│   │   ├── contract/
│   │   ├── infrastructure/
│   │   ├── repo/
│   │   └── service/
│   ├── monitoring/                      # was: health/ + ops/ (merged)
│   │   ├── MtnPlatformHealthIndicator.java
│   │   ├── OrangePlatformHealthIndicator.java
│   │   └── TlsStartupAssertion.java
│   └── admin/                           # was: admin/ + platform/ (merged)
│       ├── api/
│       ├── config/
│       ├── contract/
│       ├── repo/
│       └── service/
├── infrastructure/                      # ALREADY COMPLETE (Phase 61)
│   ├── config/                          # infrastructure.config (AsyncConfig, etc.)
│   ├── web/                             # infrastructure.web (ApiKeyAuthFilter, etc.)
│   └── persistence/                     # infrastructure.persistence
├── tenant/                              # DELETED after PLAT-01
├── security/                            # DELETED after PLAT-02
├── email/                               # DELETED after PLAT-03
├── alert/                               # DELETED after PLAT-03
├── health/                              # DELETED after PLAT-04
├── ops/                                 # DELETED after PLAT-04
├── admin/                               # DELETED after PLAT-05
└── platform/                            # DELETED (flat pkg) after PLAT-05
```

### Pattern 1: Package Declaration + Import Sweep (the only mechanical operation)
**What:** Change `package` declaration in each moved file, then update all `import` statements in callers.
**When to use:** Every file move in this phase.
**Example:**
```java
// BEFORE
package com.softropic.payam.tenant.service;

// AFTER
package com.softropic.payam.platform.tenant.service;
```

Callers change correspondingly:
```java
// BEFORE (in infrastructure.web.ApiKeyAuthenticationFilter)
import com.softropic.payam.tenant.contract.TenantPrincipal;
import com.softropic.payam.tenant.contract.TenantStatus;
import com.softropic.payam.tenant.repo.TenantApiKey;
import com.softropic.payam.tenant.service.ApiKeyService;

// AFTER
import com.softropic.payam.platform.tenant.contract.TenantPrincipal;
import com.softropic.payam.platform.tenant.contract.TenantStatus;
import com.softropic.payam.platform.tenant.repo.TenantApiKey;
import com.softropic.payam.platform.tenant.service.ApiKeyService;
```

### Pattern 2: Cascade Import Update for infrastructure.web (Critical)
**What:** `infrastructure.web` was moved in Phase 61. Three files in that package import from both `tenant.*` and `security.*`. When those source packages move in Phase 62, `infrastructure.web` import statements must be updated simultaneously.
**Why critical:** `infrastructure.web.ApiKeyAuthenticationFilter` imports from BOTH `security.common.util.TenantContext` AND `security.config.AppEndpoints` AND `tenant.*`. Both of those imports change when PLAT-01 and PLAT-02 execute.

Files in `infrastructure.web` requiring update when security moves:
```java
// infrastructure/web/ApiKeyAuthenticationFilter.java — imports to update
import com.softropic.payam.security.common.util.TenantContext;   // → platform.security.common.util.TenantContext
import com.softropic.payam.security.config.AppEndpoints;          // → platform.security.config.AppEndpoints

// infrastructure/web/LoggingFilter.java — imports to update
import com.softropic.payam.security.common.util.TenantContext;   // → platform.security.common.util.TenantContext

// infrastructure/web/TenantSecurityConfig.java — imports to update
import com.softropic.payam.security.config.AppEndpoints;          // → platform.security.config.AppEndpoints
```

### Pattern 3: SecurityConfiguration References infrastructure.web (No New Change)
**What:** `security.config.SecurityConfiguration` (moving to `platform.security.config`) already imports `infrastructure.web.LoggingFilter` using the new Phase 61 path. That import was updated in Phase 61 and remains correct after Phase 62.
**Why documented:** To confirm no additional update needed for this direction.

### Pattern 4: Merged Package Targets — No Sub-Package Reorganization Within
**What:** When two packages merge into one (e.g., `email/` + `alert/` → `platform.notification/`), the sub-package structure within each source package is preserved as-is at the destination.
**Example:**
```
email/config/    → platform/notification/config/
email/contract/  → platform/notification/contract/
email/infra/     → platform/notification/infrastructure/
email/repo/      → platform/notification/repo/
email/service/   → platform/notification/service/
alert/api/       → platform/notification/api/        (alert adds an api/ sub-package)
alert/contract/  → platform/notification/contract/   (merges with email's contract/)
alert/repo/      → platform/notification/repo/       (merges with email's repo/)
alert/service/   → platform/notification/service/    (merges with email's service/)
```
No file renaming occurs. Sub-packages from both source packages coexist at the destination.

### Pattern 5: Flat Package Merge — Class Name Collision Check Required
**What:** When `admin/` and `platform/` (flat) merge into `platform.admin/`, there is a potential class name collision: `platform.config.PlatformConfig` (Spring `@Configuration` class) and `platform.repo.PlatformConfig` (JPA `@Entity` class) have the same simple name.
**Resolution:** Both already coexist as distinct fully-qualified names within the existing `platform.*` package tree. At the destination `platform.admin.*`, sub-packages (`api/`, `config/`, `contract/`, `repo/`, `service/`) keep them in distinct sub-packages (`platform.admin.config.PlatformConfig` vs `platform.admin.repo.PlatformConfig`). No rename needed; no collision occurs at the compiler level.

### Anti-Patterns to Avoid
- **Splitting a wave mid-file-group:** If only some files in a package are moved and committed, callers will reference a mix of old and new paths. The codebase will be uncompilable. Each wave must move all files in the source package atomically.
- **Forgetting to update infrastructure.web when moving security:** `infrastructure.web` (moved in Phase 61) imports `security.common.util.TenantContext` and `security.config.AppEndpoints`. These must be updated in the same commit as PLAT-02, not a separate one.
- **Moving `platform/` (flat) before `admin/`:** `admin/api/` files (e.g., `AdminMetricsResource`) import from `security.common.util.SecurityConstants`. Moving admin while security is still flat is fine. But moving admin to `platform.admin` while `platform/` (flat) still exists creates two packages called `platform.*` — they must merge together in PLAT-05.
- **Forgetting RotatedKeyCleanupSchedulerConfig:** This Quartz scheduler config is in `tenant/config/RotatedKeyCleanupSchedulerConfig.java`. STATE.md explicitly notes it stays in `tenant.config` until Phase 62. It moves to `platform.tenant.config` in PLAT-01.
- **Test file package declarations:** Test files in `src/test/java/com/softropic/payam/tenant/` have `package com.softropic.payam.tenant;` declarations. When moved to `src/test/java/com/softropic/payam/platform/tenant/`, these declarations must also be updated.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Updating 300+ import lines across 100+ files | Manual text search-and-replace script | IDE refactor "Move class/package" (IntelliJ/Eclipse) | IDEs perform atomic rename across all usages, update package declarations, Javadoc `@see`/`{@link}` references simultaneously |
| Verifying no stale imports remain | Custom grep assertion script | `mvn compile -q` (fails fast on unresolved symbols) | Compiler is the authoritative import validator |
| Detecting if security filter chain still works | Manual HTTP test | Existing `SecurityFilterChainIT`, `TenantFilterChainIT` integration tests via `mvn verify` | These tests already cover the full chain |
| Verifying email delivery still works post-merge | Mock email server setup | `MailManagerIT`, `TenantLifecycleEmailListenerTest` (existing tests) | Already cover email delivery after commit |

**Key insight:** IDE-based "Move" refactoring handles the full file tree atomically. For the large `security` package (145 files), moving the entire package directory in one IDE operation is far safer than manually editing 145 package declarations.

## Runtime State Inventory

This is a package reorganization — package names are not stored anywhere at runtime.

| Category | Items Found | Action Required |
|----------|-------------|-----------------|
| Stored data | None — package names are not stored in the database | None |
| Live service config | None — package names are not referenced in Flyway migrations, YAML config, or application.properties | None |
| OS-registered state | None | None |
| Secrets/env vars | None — package names do not appear in any env var names | None |
| Build artifacts | `target/` directory may contain `.class` files at old package paths | Run `mvn clean` before first wave to eliminate stale compiled classes |

## Common Pitfalls

### Pitfall 1: infrastructure.web Becomes Stale After PLAT-01 or PLAT-02
**What goes wrong:** After moving `tenant` to `platform.tenant` (PLAT-01), `infrastructure.web.ApiKeyAuthenticationFilter` and `infrastructure.web.TenantSecurityConfig` still import `com.softropic.payam.tenant.*`. After moving `security` to `platform.security` (PLAT-02), `ApiKeyAuthenticationFilter`, `LoggingFilter`, and `TenantSecurityConfig` still import `com.softropic.payam.security.*`. Both sets of stale imports cause compile failures.
**Why it happens:** `infrastructure.web` is in a separate package that already moved in Phase 61. IDEs may not automatically update it if the refactor is scoped only to the source package being moved.
**How to avoid:** Include `infrastructure.web` updates explicitly in the PLAT-01 commit (4 tenant import updates) and the PLAT-02 commit (4 security import updates). Run `mvn compile -q` to verify before committing.
**Warning signs:** Compile failure mentioning `infrastructure.web.ApiKeyAuthenticationFilter` or `infrastructure.web.TenantSecurityConfig` with unresolved symbols.

### Pitfall 2: Test Files Have Package Declarations That Must Change
**What goes wrong:** `mvn verify` succeeds on production compile but fails on test compile because test files at `src/test/java/com/softropic/payam/tenant/TenantAdminResourceIT.java` still have `package com.softropic.payam.tenant;` but are physically located under `src/test/java/com/softropic/payam/platform/tenant/`.
**Why it happens:** IDEs may scope "Move" to `src/main/java` only if not explicitly told to include `src/test/java`.
**How to avoid:** Explicitly include `src/test/java` in each package move. Run `mvn test-compile -q` after each wave before running full `mvn verify`.
**Warning signs:** Maven Surefire reporting "class not found" or package mismatch compile errors in test output.

### Pitfall 3: 67 Test Files Reference `tenant.*` — Most Are E2E Tests, Not in the `tenant/` Directory
**What goes wrong:** The 67 test files that import from `tenant.*` are NOT in `src/test/java/.../tenant/`. They are spread across `e2e/`, `disbursement/`, `webhook/`, `fraud/`, and other test packages. These files need their import statements updated (not their physical location), but they stay where they are. Confusing "files that need import updates" with "files to move" causes missed updates.
**How to avoid:** After moving tenant source, run: `grep -rn "com.softropic.payam.tenant\." src/test --include="*.java"` — every line returned is a stale import that must be updated to `platform.tenant`.
**Warning signs:** Compile failures in `e2e/builder/TenantBuilder.java` or any of the 67 affected test files.

### Pitfall 4: `security.config.AppEndpoints` Imported by Multiple Non-Security Files
**What goes wrong:** `AppEndpoints` is in `security.config` and is imported by `infrastructure.web.ApiKeyAuthenticationFilter`, `infrastructure.web.TenantSecurityConfig`. Javadoc-only references also appear in callback controller Javadoc (`MtnDisbursementCallbackController`, `DisbursementResource`, `PaymentResource`). When `security` moves to `platform.security`, `AppEndpoints` moves to `platform.security.config.AppEndpoints`. Only files with real Java import statements (not Javadoc references) cause compile failures if missed.
**How to avoid:** After PLAT-02, run: `grep -rn "com.softropic.payam.security\." src --include="*.java"` — zero results expected.
**Warning signs:** Compile failure mentioning `AppEndpoints` or `TenantContext` with "cannot find symbol."

### Pitfall 5: `platform.config.PlatformConfig` vs `platform.repo.PlatformConfig` Class Name Collision
**What goes wrong:** Two existing classes named `PlatformConfig` currently live in `com.softropic.payam.platform.config` (Spring `@Configuration`) and `com.softropic.payam.platform.repo` (JPA `@Entity`). Both move to `platform.admin.*`. Inside `platform.admin.service.PlatformConfigService`, both are imported via fully-qualified names. The compiler handles this correctly as long as sub-packages are preserved.
**How to avoid:** Verify that `platform.admin.config.PlatformConfig` and `platform.admin.repo.PlatformConfig` have distinct FQNs. Check that any file importing both uses fully-qualified names or distinct import aliases.
**Warning signs:** Compile error about ambiguous import or duplicate class name in `platform.admin.*`.

### Pitfall 6: Quartz Scheduler Bean Name for RotatedKeyCleanupSchedulerConfig
**What goes wrong:** `RotatedKeyCleanupSchedulerConfig` registers a Quartz `JobDetail` bean. Spring resolves beans by type, not package. Moving the class to `platform.tenant.config` does not break the Quartz bean registration — the bean name (determined by `@Bean` method name or explicit qualifier) stays the same. However, if the `@Configuration` class declaration is accidentally duplicated or the class is forgotten during the move, the Quartz job disappears silently.
**How to avoid:** Verify `RotatedKeyCleanupJobIT` passes after PLAT-01. The job was confirmed to stay in `tenant.config` (now becoming `platform.tenant.config`) per STATE.md.
**Warning signs:** `RotatedKeyCleanupJobIT` failing with job-not-found or context load failure.

## Code Examples

### PLAT-01 — Complete Scope of Changes

**Production files (24): change package declaration**
```
tenant/api/TenantAdminResource.java              → platform/tenant/api/TenantAdminResource.java
tenant/config/RotatedKeyCleanupSchedulerConfig.java → platform/tenant/config/RotatedKeyCleanupSchedulerConfig.java
tenant/contract/*.java (10 files)               → platform/tenant/contract/*.java
tenant/repo/*.java (4 files)                    → platform/tenant/repo/*.java
tenant/service/*.java (5 files)                 → platform/tenant/service/*.java
```

**Test files (9): change package declaration + physical location**
```
tenant/ApiKeyConcurrentRotationIT.java → platform/tenant/ApiKeyConcurrentRotationIT.java
tenant/RotatedKeyCleanupJobIT.java     → platform/tenant/RotatedKeyCleanupJobIT.java
... (all 9 test files in tenant/ directory)
```

**Callers needing import updates:**
- 8 production files outside `tenant/` (e.g., `infrastructure.web.ApiKeyAuthenticationFilter`, `webhook.service.WebhookDeliveryJob`)
- 67 test files outside `tenant/` (E2E tests, integration tests, test builders)

### PLAT-02 — Scope Summary (Largest Wave)

**Production files (145): change package declaration**
All of `security/` sub-packages move to `platform/security/`, preserving sub-package structure:
```
security.api.*              → platform.security.api.*
security.audit.*            → platform.security.audit.*
security.common.*           → platform.security.common.*
security.config.*           → platform.security.config.*
security.contract.*         → platform.security.contract.*
security.infrastructure.*   → platform.security.infrastructure.*
security.repo.*             → platform.security.repo.*
security.service.*          → platform.security.service.*
```

**Critical: infrastructure.web caller updates required (same commit as PLAT-02):**
```java
// infrastructure/web/ApiKeyAuthenticationFilter.java
import com.softropic.payam.security.common.util.TenantContext;  // → platform.security.common.util.TenantContext
import com.softropic.payam.security.config.AppEndpoints;         // → platform.security.config.AppEndpoints

// infrastructure/web/LoggingFilter.java
import com.softropic.payam.security.common.util.TenantContext;  // → platform.security.common.util.TenantContext

// infrastructure/web/TenantSecurityConfig.java
import com.softropic.payam.security.config.AppEndpoints;         // → platform.security.config.AppEndpoints
```

**Other prod files with security imports (22 files after PLAT-01 clears 1):**
Including `admin.api.*` (4 files import `SecurityConstants`), `common.client.RestRequestInterceptor`, `platform (flat) files`, `email.*`, etc.

### PLAT-03 — Complete Scope of Changes

**Production files (30): 23 from email/ + 7 from alert/**
```
email/config/*.java (3)                         → platform/notification/config/*.java
email/contract/*.java (5)                       → platform/notification/contract/*.java
email/infrastructure/*.java (all)               → platform/notification/infrastructure/*.java
email/repo/*.java (2)                           → platform/notification/repo/*.java
email/service/*.java (5)                        → platform/notification/service/*.java

alert/api/AlertRuleAdminResource.java           → platform/notification/api/AlertRuleAdminResource.java
alert/contract/AlertFiredEvent.java             → platform/notification/contract/AlertFiredEvent.java
alert/repo/AlertRule.java                       → platform/notification/repo/AlertRule.java
alert/repo/AlertRuleRepository.java             → platform/notification/repo/AlertRuleRepository.java
alert/service/AlertEvaluationService.java       → platform/notification/service/AlertEvaluationService.java
alert/service/AlertNotificationListener.java    → platform/notification/service/AlertNotificationListener.java
alert/service/AlertRuleCache.java               → platform/notification/service/AlertRuleCache.java
```

**Note on AlertNotificationListener:** This class imports from `email.contract.*` and `email.service.MailManager`. After the merge, both source packages land in `platform.notification`, so these cross-package imports become same-package imports and can use short names — but they can also remain as explicit imports with the new path. Either works; consistency with the project style preferred.

**Test files (8): 7 from email/ + 1 from alert/**

**Callers needing import updates:**
- 7 production files (e.g., `alert.service.AlertNotificationListener` imports email — resolved within the merge; `infrastructure.config.AsyncConfig` imports `email.service.*`; `security.api.*` files import email contracts)
- 8 test files (e.g., `TestConfig.java`, `TestMailConfig.java`)

### PLAT-04 — Complete Scope of Changes (Smallest Wave)

**Production files (3):**
```
health/MtnPlatformHealthIndicator.java    → platform/monitoring/MtnPlatformHealthIndicator.java
health/OrangePlatformHealthIndicator.java → platform/monitoring/OrangePlatformHealthIndicator.java
ops/TlsStartupAssertion.java              → platform/monitoring/TlsStartupAssertion.java
```

**Test files (1):**
```
ops/OperationalIT.java → platform/monitoring/OperationalIT.java
```

**Callers needing import updates:**
- 5 production files that import from `platform.*` (flat) reference `PlatformConfigService` which in turn calls health providers — no direct import of `health.*` or `ops.*` from non-platform callers. Zero callers for `health.*` and zero for `ops.*` outside those packages.
- Zero test files import from `health.*` or `ops.*` directly.

Note: `health.MtnPlatformHealthIndicator` and `health.OrangePlatformHealthIndicator` import from `platform.*(flat)` package (e.g., `PlatformConfigService`). Those imports are updated in the same PLAT-04 commit if `platform.*` has not yet moved, OR these files pick up the new `platform.admin.*` path if PLAT-05 executes first.

### PLAT-05 — Complete Scope of Changes

**Production files (22): 13 from admin/ + 9 from platform/(flat)**
```
admin/api/AdminMetricsResource.java        → platform/admin/api/AdminMetricsResource.java
admin/api/AdminTransactionResource.java    → platform/admin/api/AdminTransactionResource.java
admin/api/AuditResource.java               → platform/admin/api/AuditResource.java
admin/api/ProviderStatusResource.java      → platform/admin/api/ProviderStatusResource.java
admin/contract/*.java (5)                  → platform/admin/contract/*.java
admin/service/*.java (2)                   → platform/admin/service/*.java

platform/api/PlatformConfigAdminResource.java → platform/admin/api/PlatformConfigAdminResource.java
platform/config/PayamPlatformProperties.java  → platform/admin/config/PayamPlatformProperties.java
platform/config/PlatformConfig.java           → platform/admin/config/PlatformConfig.java (Spring @Configuration)
platform/contract/PinDto.java                 → platform/admin/contract/PinDto.java
platform/contract/PlatformConfigDto.java      → platform/admin/contract/PlatformConfigDto.java
platform/contract/event/PlatformConfigChangedEvent.java → platform/admin/contract/event/PlatformConfigChangedEvent.java
platform/repo/PlatformConfig.java             → platform/admin/repo/PlatformConfig.java (JPA @Entity)
platform/repo/PlatformConfigRepository.java   → platform/admin/repo/PlatformConfigRepository.java
platform/service/PlatformConfigService.java   → platform/admin/service/PlatformConfigService.java
```

**Test files (3): from platform/(flat)**
```
platform/PlatformConfigAdminResourceIT.java → platform/admin/PlatformConfigAdminResourceIT.java
platform/config/PayamPlatformPropertiesTest.java → platform/admin/config/PayamPlatformPropertiesTest.java
platform/service/PlatformConfigServiceTest.java → platform/admin/service/PlatformConfigServiceTest.java
```

**Callers needing import updates:**
- 5 production files import from `admin.*` (callback controllers, PaymentOrchestrator using `MetricsSnapshotDto`)
- 2 test files import from `admin.*`
- 5 production files import from `platform.*` (flat)
- 15 test files import from `platform.*` (flat)

### Recommended Execution Order (Wave Plan)
```
Wave 1: PLAT-04 (platform.monitoring)
  — 3 production files + 1 test file, zero external callers for health/ops
  — Move 3 prod + 1 test, update 0 external callers
  — Run: mvn verify
  — Commit if green

Wave 2: PLAT-03 (platform.notification)
  — 30 production files + 8 test files, 7 prod + 8 test external callers
  — Move 30 prod + 8 test, update 7 prod + 8 test callers
  — Run: mvn verify
  — Commit if green

Wave 3: PLAT-05 (platform.admin)
  — 22 production files + 3 test files, 7 prod + 17 test external callers
  — Move 22 prod + 3 test, update 7 prod + 17 test callers
  — Run: mvn verify
  — Commit if green

Wave 4: PLAT-01 (platform.tenant)
  — 24 production files + 9 test files, 8 prod + 67 test external callers
  — Move 24 prod + 9 test, update 8 prod + 67 test import sites
  — Include 4 infrastructure.web import updates in this commit
  — Run: mvn verify
  — Commit if green

Wave 5: PLAT-02 (platform.security)
  — 145 production files + 19 test files, 23 prod + 7 test external callers
  — Move 145 prod + 19 test, update 23 prod + 7 test import sites
  — CRITICAL: Include infrastructure.web import updates (4 security.* imports) in this commit
  — Run: mvn verify
  — Commit if green
```

**Alternative wave ordering:** PLAT-04 and PLAT-03 can be parallelized (no dependency between them). PLAT-05, PLAT-01, and PLAT-02 must be sequential (admin imports security; health imports platform flat).

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `@ComponentScan(basePackages=...)` explicit | `@SpringBootApplication` implicit scan | Spring Boot 2.x | New sub-packages under `com.softropic.payam` are auto-discovered; no scan config needed |
| Manual filter registration | `FilterRegistrationBean(setEnabled=false)` | Already established | Preserved in Phase 61; no change needed |

**No deprecated patterns identified** in the classes being moved.

## Open Questions

1. **Should `AlertNotificationListener`'s imports become same-package imports after the email+alert merge?**
   - What we know: `AlertNotificationListener` (in `alert.service`) imports from `email.contract` and `email.service`. After the merge, both live in `platform.notification`, making these effectively same-package imports.
   - What's unclear: Whether to remove the explicit import statements or leave them for clarity.
   - Recommendation: Leave explicit imports as-is (just update the package path). Removing them is a separate cosmetic concern not required by PLAT-03. Less change = less risk.

2. **Can PLAT-04 and PLAT-03 be executed as a single wave?**
   - What we know: `health.*` and `ops.*` have zero cross-package callers outside themselves. `email.*` and `alert.*` have 7 prod + 8 test callers.
   - Recommendation: They can safely be a single plan/commit if desired. Keeping them separate makes the `mvn verify` feedback loop shorter and easier to debug.

3. **Do `OperationalIT` tests move to a `platform.monitoring` test package?**
   - What we know: `OperationalIT` is in `src/test/java/com/softropic/payam/ops/`. After PLAT-04, it becomes `src/test/java/com/softropic/payam/platform/monitoring/OperationalIT.java` with package `com.softropic.payam.platform.monitoring`.
   - The test references `com.softropic.payam.config.TestConfig` (stays in test config — no import change needed).
   - Recommendation: Move the file and update its package declaration. No other changes required.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java 17 | Compilation | Yes | 17.0.1 LTS (Oracle) | — |
| Maven 3.9.9 | `mvn verify` | Yes | 3.9.9 | — |
| Docker 20.10.12 | Integration tests (Testcontainers) | Yes | 20.10.12 | — |
| PostgreSQL (Testcontainers) | Integration tests | Yes (via Docker) | Latest Testcontainers image | — |
| Redis (Testcontainers) | Integration tests | Yes (via Docker) | Latest Testcontainers image | — |

All dependencies available. No blocking items.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 via Spring Boot Test 3.5.11 |
| Config file | `pom.xml` (maven-surefire-plugin + maven-failsafe-plugin) |
| Quick compile check | `mvn test-compile -q` |
| Full suite command | `mvn verify` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| PLAT-01 | `platform.tenant.*` classes present; `GET /v1/admin/tenants` returns paginated list; suspend/reactivate work | integration | `mvn verify` — `TenantAdminResourceIT`, `TenantServiceIT`, `RotatedKeyCleanupJobIT` | Yes (existing, will move) |
| PLAT-02 | `platform.security.*` present; login, token refresh, API key auth all work | integration | `mvn verify` — `SecurityFilterChainIT`, `SecurityIT`, `TenantFilterChainIT` | Yes (existing, will move) |
| PLAT-03 | `platform.notification.*` present; lifecycle emails and alert emails delivered after commit | integration | `mvn verify` — `MailManagerIT`, `TenantLifecycleEmailListenerTest`, `AlertRuleIT` | Yes (existing, will move) |
| PLAT-04 | `platform.monitoring.*` present; `GET /manage/health` returns live provider MSISDN validation | integration | `mvn verify` — `OperationalIT` | Yes (existing, will move) |
| PLAT-05 | `platform.admin.*` present; `PUT /v1/admin/platform-config/{provider}` persists; health reflects updated MSISDN | integration | `mvn verify` — `PlatformConfigAdminResourceIT`, `PlatformConfigServiceTest` | Yes (existing, will move) |
| BUILD-01 | `mvn verify` green after every wave commit | integration | `mvn verify` | Yes (cross-cutting gate) |
| BUILD-02 | No behavioral changes — REST contracts unchanged | integration | `mvn verify` — all existing ITs | Yes |
| BUILD-03 | Spring context starts; health endpoints respond; security filter chain works | integration | `mvn verify` — `OperationalIT`, `SecurityFilterChainIT` | Yes |

### Sampling Rate
- **Per wave commit:** `mvn verify` (full suite — this is a refactor; no shortcuts)
- **Per phase gate:** `mvn verify` green with zero test failures before marking phase complete

### Wave 0 Gaps
None — existing test infrastructure covers all phase requirements. This phase adds no new behavior. All tests already exist and will move with their source packages.

## Sources

### Primary (HIGH confidence)
- Direct source tree inspection — all 8 source packages read directly from `src/main/java/com/softropic/payam/`
- `find` and `grep` scans of entire `src/` tree — file counts, import counts, caller lists verified
- `.planning/phases/61-infrastructure-layer-creation/61-RESEARCH.md` — Phase 61 patterns and decisions
- `.planning/STATE.md` — Key carry-forward decisions (RotatedKeyCleanupSchedulerConfig, FilterRegistrationBean pattern, atomic commit requirement)
- `requirements/architecture.md` — Target package hierarchy spec
- `.planning/REQUIREMENTS.md` — PLAT-01 through PLAT-05 requirement text

### Secondary (MEDIUM confidence)
- `.planning/phases/61-infrastructure-layer-creation/61-VERIFICATION.md` — confirms infrastructure.web import patterns post Phase 61
- `.planning/phases/61-infrastructure-layer-creation/61-03-SUMMARY.md` — confirms exact state of infrastructure.web after Phase 61

### Tertiary (LOW confidence)
- None — all findings are from direct source inspection

## Metadata

**Confidence breakdown:**
- File inventory (what moves, where): HIGH — all source files counted and listed directly
- Caller count (import update scope): HIGH — `grep -rl` scan of entire src tree
- Wave ordering recommendation: HIGH — based on caller counts and dependency analysis
- Collision risk (PlatformConfig name clash): HIGH — verified both classes exist in distinct sub-packages
- infrastructure.web cascade update requirement: HIGH — imports verified in situ

**Research date:** 2026-05-07
**Valid until:** Stable — this is a code-only refactoring; no external dependencies or API versions affect findings.
