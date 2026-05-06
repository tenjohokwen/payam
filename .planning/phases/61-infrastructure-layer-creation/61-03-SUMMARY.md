---
phase: 61-infrastructure-layer-creation
plan: 03
subsystem: infra
tags: [java, spring-boot, spring-security, web-filters, package-refactoring, servlet-filters]

# Dependency graph
requires:
  - "61-01: infrastructure.persistence package created"
  - "61-02: infrastructure.config package created"
provides:
  - "infrastructure.web package with 3 web-layer classes: ApiKeyAuthenticationFilter, TenantSecurityConfig, LoggingFilter"
  - "INFRA-02 complete: all web-layer filter infrastructure consolidated under infrastructure.web"
  - "Phase 61 complete: infrastructure.persistence + infrastructure.config + infrastructure.web all populated"
affects:
  - "62-platform-layer-reorganization"
  - "63-payment-domain-consolidation"
  - "64-provider-infrastructure-encapsulation"
  - "65-common-package-redistribution"

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "infrastructure.web as the bounded sub-package for all Spring Security servlet filters and web-layer config"
    - "FilterRegistrationBean(setEnabled=false) preserved verbatim — prevents ApiKeyAuthenticationFilter from auto-registering as global servlet filter"
    - "Package move = package declaration change only (all internal imports remain unchanged)"

key-files:
  created:
    - "src/main/java/com/softropic/payam/infrastructure/web/ApiKeyAuthenticationFilter.java"
    - "src/main/java/com/softropic/payam/infrastructure/web/TenantSecurityConfig.java"
    - "src/main/java/com/softropic/payam/infrastructure/web/LoggingFilter.java"
  modified:
    - "src/main/java/com/softropic/payam/security/config/SecurityConfiguration.java"
  deleted:
    - "src/main/java/com/softropic/payam/tenant/config/ApiKeyAuthenticationFilter.java"
    - "src/main/java/com/softropic/payam/tenant/config/TenantSecurityConfig.java"
    - "src/main/java/com/softropic/payam/security/audit/filter/LoggingFilter.java"

# Key decisions
decisions:
  - "RotatedKeyCleanupSchedulerConfig was explicitly NOT moved — it is a Quartz scheduler config, not web infrastructure; stays in tenant.config until Phase 62"
  - "Pre-existing test failures (33 test classes) on main branch are unrelated to this move; same count before and after — no regressions introduced"
  - "Worktree merged main before executing Plan 03 to pick up Plans 01 and 02 infrastructure.persistence and infrastructure.config packages"

# Metrics
metrics:
  duration: "40 minutes"
  completed: "2026-05-06"
  tasks: 1
  files_changed: 7
---

# Phase 61 Plan 03: Move Web-Layer Infrastructure Classes to infrastructure.web

**One-liner:** Three Spring servlet filters (ApiKeyAuthenticationFilter, TenantSecurityConfig, LoggingFilter) relocated to infrastructure.web with FilterRegistrationBean(setEnabled=false) preserved verbatim and SecurityConfiguration import updated.

## What Was Done

### Task 1: Move three web-layer classes to infrastructure.web and update SecurityConfiguration import

Moved three classes to the new `com.softropic.payam.infrastructure.web` package:

1. `ApiKeyAuthenticationFilter` — from `tenant.config` to `infrastructure.web`. Package declaration updated; all imports unchanged (`security.common.util.TenantContext`, `security.config.AppEndpoints`, `tenant.contract.*`, `tenant.repo.TenantApiKey`, `tenant.service.ApiKeyService`).

2. `TenantSecurityConfig` — from `tenant.config` to `infrastructure.web`. Package declaration updated; all imports unchanged. `FilterRegistrationBean(setEnabled=false)` preserved verbatim — this prevents Spring Boot from auto-registering `ApiKeyAuthenticationFilter` as a global servlet filter which would block admin/account paths. `@Order(1)`, `@Bean`, `@Configuration`, `securityMatcher(tenantPaths)`, and `.addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)` all preserved exactly.

3. `LoggingFilter` — from `security.audit.filter` to `infrastructure.web`. Package declaration updated; imports of `common.Constants` and `security.common.util.TenantContext` unchanged (those classes do not move in Phase 61).

Updated `SecurityConfiguration.java` import line 4: `import com.softropic.payam.security.audit.filter.LoggingFilter` → `import com.softropic.payam.infrastructure.web.LoggingFilter`. No other changes to this file; line 202 `.addFilterBefore(new LoggingFilter(PUBLIC_STATIC_RESOURCES), ...)` unchanged.

### Verification Results

| Check | Result |
|-------|--------|
| `infrastructure.web` file count = 3 | PASS |
| `security.audit.filter` directory empty | PASS |
| `tenant.config` contains only `RotatedKeyCleanupSchedulerConfig.java` | PASS |
| `registration.setEnabled(false)` preserved in TenantSecurityConfig | PASS |
| SecurityConfiguration import updated to `infrastructure.web.LoggingFilter` | PASS |
| No stale imports of moved classes anywhere in `src/` | PASS |
| Compilation (`mvn verify -DskipTests=true`) | PASS |
| No regressions vs pre-existing test baseline | PASS (33 failures on main, 33 failures on worktree — identical set) |

## Deviations from Plan

### Auto-fixed Issues

None — plan executed exactly as written.

### Out-of-Scope Deviations Noted

**Pre-existing test failures (deferred):** 33 test classes fail identically on both `main` branch and this worktree. The failures are all `ApplicationContext failure threshold exceeded` caused by `Cannot resolve reference to bean 'jpaSharedEM_entityManagerFactory'`. This failure exists on `main` before any Phase 61 changes. Root cause is out of scope for INFRA-02 (web filter move). Filed to `deferred-items.md`.

**Merge deviation:** Worktree branch `worktree-agent-ad4ba89d2362a2319` was missing commits from Plans 01 and 02 (these were on `main`). Merged `main` into the worktree branch (fast-forward) before executing Plan 03 to ensure `infrastructure.persistence` and `infrastructure.config` packages were present.

## Phase 61 Completion

Phase 61 "Infrastructure Layer Creation" is now fully complete:

| Plan | Package | Classes Moved | Status |
|------|---------|--------------|--------|
| 61-01 | `infrastructure.persistence` | 8 JPA base classes (AbstractAuditingEntity, etc.) | DONE |
| 61-02 | `infrastructure.config` | 3 config classes (AsyncConfig, DataSourceConfig, ObservabilityConfig) | DONE |
| 61-03 | `infrastructure.web` | 3 filter classes (ApiKeyAuthenticationFilter, TenantSecurityConfig, LoggingFilter) | DONE |

All requirements satisfied: INFRA-02 (web-layer classes in infrastructure.web), INFRA-03 (persistence classes moved), BUILD-01 (compilation clean), BUILD-02 (filter chain semantics: FilterRegistrationBean preserved), BUILD-03 (Spring component-scan picks up infrastructure.web automatically).

## Known Stubs

None.

## Self-Check: PASSED

- `src/main/java/com/softropic/payam/infrastructure/web/ApiKeyAuthenticationFilter.java` — FOUND
- `src/main/java/com/softropic/payam/infrastructure/web/TenantSecurityConfig.java` — FOUND
- `src/main/java/com/softropic/payam/infrastructure/web/LoggingFilter.java` — FOUND
- Commit `c034fbc` — FOUND
