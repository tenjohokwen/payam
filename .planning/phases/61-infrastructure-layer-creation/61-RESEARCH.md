# Phase 61: Infrastructure Layer Creation - Research

**Researched:** 2026-05-06
**Domain:** Java package refactoring — Spring Boot 3.x, JPA auditing, Spring Security filters
**Confidence:** HIGH

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| INFRA-01 | `config` package (AsyncConfig, DataSourceConfig, ObservabilityConfig) relocated to `infrastructure.config` with all imports updated | 3 production files + Javadoc-only cross-references; import scan complete |
| INFRA-02 | Spring filters, interceptors, and web infrastructure consolidated under `infrastructure.web` | LoggingFilter (security.audit.filter), ApiKeyAuthenticationFilter + TenantSecurityConfig (tenant.config) identified; 1 real import update in SecurityConfiguration |
| INFRA-03 | Shared persistence base classes and configuration consolidated under `infrastructure.persistence` | 8 files in common.persistence; 29 production importers + 4 test importers identified |
</phase_requirements>

## Summary

Phase 61 is the first of five v12 refactoring phases. It creates the `infrastructure` bounded context by moving three groups of existing classes into new sub-packages under `com.softropic.payam.infrastructure`. No new logic is introduced — only package declarations and import statements change.

The phase involves 14 production source files and a small number of import-update sites. The highest-risk element is `common.persistence` redistribution (29 production callers), because every JPA entity in the codebase extends `BaseEntity` or `AbstractAuditingEntity`. The `FilterRegistrationBean(setEnabled=false)` pattern in `TenantSecurityConfig` must be preserved exactly to prevent `ApiKeyAuthenticationFilter` from auto-registering as a servlet filter for all requests.

`@SpringBootApplication` on `PayamApplication` (at `com.softropic.payam`) implicitly scans all sub-packages, so adding `infrastructure.*` sub-packages requires no component-scan configuration changes.

**Primary recommendation:** Move in three discrete, separately-compiled waves: INFRA-03 first (most callers, most risky), then INFRA-01, then INFRA-02. Run `mvn verify` after each wave to catch failures early rather than once at the end.

## Standard Stack

### Core (verified from source tree)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Boot | 3.5.11 | Application framework | Already in use |
| Spring Data JPA | (via Spring Boot BOM) | `@EnableJpaAuditing`, `AbstractAuditingEntity` | Already in use |
| Spring Security | (via Spring Boot BOM) | Filter chain, `FilterRegistrationBean` | Already in use |
| Maven Failsafe | (via Spring Boot BOM) | Integration test execution (`mvn verify`) | Already in use |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Maven Surefire | (via Spring Boot BOM) | Unit test execution | Runs automatically in `mvn verify` |
| PITest | 1.15.3 (`mutation` profile only) | Mutation testing | Not needed for this refactoring phase |

**Installation:** No new dependencies required. This is a pure package reorganization.

## Architecture Patterns

### Recommended Project Structure (after Phase 61)
```
src/main/java/com/softropic/payam
├── infrastructure/
│   ├── config/          # was: config/
│   │   ├── AsyncConfig.java
│   │   ├── DataSourceConfig.java
│   │   └── ObservabilityConfig.java
│   ├── web/             # was: tenant/config/ + security/audit/filter/
│   │   ├── ApiKeyAuthenticationFilter.java
│   │   ├── TenantSecurityConfig.java
│   │   └── LoggingFilter.java
│   └── persistence/     # was: common/persistence/
│       ├── AbstractAuditingEntity.java
│       ├── AuditingDateTimeProvider.java
│       ├── BaseEntity.java
│       ├── DbSchemaChecker.java
│       ├── DbUtil.java
│       ├── EntityStatus.java
│       ├── IdType.java
│       └── RequestIdAuditEntityListener.java
├── config/              # DELETED after this phase
├── common/
│   ├── persistence/     # DELETED after this phase
│   └── ... (other common.* sub-packages remain until Phase 65)
├── tenant/
│   └── config/          # RotatedKeyCleanupSchedulerConfig stays here
│       └── RotatedKeyCleanupSchedulerConfig.java
├── security/
│   └── audit/filter/    # LoggingFilter removed; directory may become empty
└── ... (all other packages unchanged)
```

### Pattern 1: Package Declaration + Import Sweep (the only mechanical operation)
**What:** Change `package` declaration in each moved file, then update all `import` statements in callers.
**When to use:** Every file move in this phase.
**Example:**
```java
// BEFORE (in config/AsyncConfig.java)
package com.softropic.payam.config;

// AFTER (in infrastructure/config/AsyncConfig.java)
package com.softropic.payam.infrastructure.config;
```

Callers change correspondingly:
```java
// BEFORE (in common/threadpool/TenantContextTaskDecorator.java Javadoc only — no real import)
// @see com.softropic.payam.config.AsyncConfig  ← Javadoc reference, not a Java import
// No import update needed — Javadoc references do not affect compilation

// BEFORE (in security/config/SecurityConfiguration.java)
import com.softropic.payam.security.audit.filter.LoggingFilter;
// AFTER
import com.softropic.payam.infrastructure.web.LoggingFilter;
```

### Pattern 2: `FilterRegistrationBean(setEnabled=false)` — Must Be Preserved
**What:** `TenantSecurityConfig` disables servlet-container auto-registration of `ApiKeyAuthenticationFilter` so it only runs within the Spring Security filter chain for `/v1/**` paths.
**Why critical:** If this `FilterRegistrationBean` is omitted when the class moves, Spring Boot auto-registers `ApiKeyAuthenticationFilter` as a global servlet filter, causing all requests to go through API key auth — including admin and account paths that use JWT.
**How to preserve:**
```java
// Source: TenantSecurityConfig.java (current) — must survive move to infrastructure.web
@Bean
public FilterRegistrationBean<ApiKeyAuthenticationFilter>
registration(ApiKeyAuthenticationFilter filter) {
    FilterRegistrationBean<ApiKeyAuthenticationFilter> registration =
        new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);   // NEVER remove this line
    return registration;
}
```

### Pattern 3: `@EnableJpaAuditing` Stays with `DataSourceConfig`
**What:** `DataSourceConfig` carries `@EnableJpaAuditing` and `@EnableTransactionManagement`. These module-level annotations must travel with the class to `infrastructure.config.DataSourceConfig`.
**Why:** `@EnableJpaAuditing` activates `AuditingDateTimeProvider` (which moves to `infrastructure.persistence`) and `AuditingEntityListener`. Both must be active; no bean name reference is used (`dateTimeProviderRef` is not set, so Spring resolves by convention).

### Anti-Patterns to Avoid
- **Splitting a paired class from its registrar:** `ApiKeyAuthenticationFilter` and `TenantSecurityConfig` must move together. Leaving one behind causes a compile error (cross-package circular reference that breaks the explicit `@Bean` wiring).
- **Moving RotatedKeyCleanupSchedulerConfig to infrastructure.web:** This is a Quartz scheduler config for tenant key rotation, not a web filter. It stays in `tenant.config` and will move in Phase 62.
- **Moving `security.infrastructure.filter.*` (JWT filters):** `JWTAuthenticationFilter`, `JWTAuthorizationFilter`, `SecondFactorLoginFilter`, `SecurityAdviceFilter`, and `SessionRefreshFilter` are security-domain-specific and move in Phase 62 (`platform.security`), not here.
- **Moving `common.config` (CommonConfig, LongFromStringDeserializer):** These are in `common.config`, not the top-level `config/`. They stay until Phase 65 (CMN-02).
- **Moving `email.config.AsyncConfig`:** This is the email async pool config, not the top-level global `AsyncConfig`. It stays with the `email` package.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Updating all import statements across 29+ files | Manual text search-and-replace script | IDE refactor "Move class" (IntelliJ/Eclipse) | IDEs perform atomic rename across all usages, update package declarations, and handle Javadoc `@see` and `{@link}` references simultaneously |
| Verifying no stale imports remain | Custom grep assertion script | `mvn compile` (fails fast on unresolved symbols) | Compiler is the authoritative import validator |
| Detecting if filter chain still works | Manual HTTP test | `SecurityFilterChainIT`, `TenantFilterChainIT` (existing integration tests in `mvn verify`) | These tests already cover the `ApiKeyAuthenticationFilter` registration and `/v1/**` path scoping |

**Key insight:** IDE-based "Move" refactoring is safer than sed/grep because IDEs also update Javadoc `{@link}` and `@see` references, which do not affect compilation but would leave misleading documentation if stale.

## Runtime State Inventory

This is a package reorganization — no runtime state is involved.

| Category | Items Found | Action Required |
|----------|-------------|-----------------|
| Stored data | None — package names are not stored in the database | None |
| Live service config | None — package names are not referenced in Flyway migrations or YAML config | None |
| OS-registered state | None | None |
| Secrets/env vars | None — package names do not appear in env vars | None |
| Build artifacts | `target/` directory | `mvn clean` before verifying; stale `.class` files in old package paths will be ignored by Maven but should not be relied on |

## Common Pitfalls

### Pitfall 1: Forgetting Test Import Updates
**What goes wrong:** Production code compiles, but `mvn verify` fails in the integration test phase because test files still import from old package paths.
**Why it happens:** IDEs sometimes only update src/main/java by default. Test sources in src/test/java must also be updated.
**How to avoid:** After moving each group, run `mvn test-compile` before running full `mvn verify`. The 4 test files importing `common.persistence` classes and 1 test file referencing `ApiKeyAuthenticationFilter` must all be updated.
**Warning signs:** Compile failures mentioning `src/test/java` paths with package names like `com.softropic.payam.common.persistence.*` or `com.softropic.payam.security.audit.filter.LoggingFilter`.

### Pitfall 2: Breaking the `FilterRegistrationBean` Pattern
**What goes wrong:** `ApiKeyAuthenticationFilter` auto-registers as a global servlet filter. Every request hits API key auth, blocking admin and account endpoints with 401.
**Why it happens:** Spring Boot auto-registers any bean implementing `Filter`. The `FilterRegistrationBean(setEnabled=false)` in `TenantSecurityConfig` suppresses this. If `TenantSecurityConfig` is not moved along with `ApiKeyAuthenticationFilter`, or if the `registration()` bean method is accidentally omitted, the filter runs globally.
**How to avoid:** Move `ApiKeyAuthenticationFilter` and `TenantSecurityConfig` as a unit. Verify `TenantFilterChainIT` passes after the move.
**Warning signs:** `TenantFilterChainIT` or `SecurityFilterChainIT` failing with 401 on `/v1/admin/**` or `/v1/account/**` paths.

### Pitfall 3: `@EnableJpaAuditing` with No `dateTimeProviderRef` — Implicit Bean Lookup
**What goes wrong:** After moving `DataSourceConfig` to `infrastructure.config` and `AuditingDateTimeProvider` to `infrastructure.persistence`, Spring still finds `AuditingDateTimeProvider` by the bean name `"dateTimeProvider"` (via its `@Component("dateTimeProvider")` annotation). No `dateTimeProviderRef` is configured on `@EnableJpaAuditing`, so the lookup is by-convention. This continues working correctly — but if someone accidentally removes the bean name or changes it during the move, auditing silently breaks (no `createdDate` set on entities).
**How to avoid:** Keep `@Component("dateTimeProvider")` on `AuditingDateTimeProvider` unchanged.
**Warning signs:** `TenantAuditIT` failing with null `createdDate` or `lastModifiedDate` fields.

### Pitfall 4: Partial Move Leaves Old Package Directory with No Files
**What goes wrong:** After moving all files out of `config/` (3 files) or `common/persistence/` (8 files), the old directory is empty. Git removes empty directories automatically. If any downstream import or IDE cache still references the old package path, compile errors appear.
**How to avoid:** Run `mvn clean compile` (not just `mvn compile`) after each wave to ensure stale `.class` files are removed.
**Warning signs:** `ClassNotFoundException` at runtime for a class whose `.class` file exists in an old target directory.

### Pitfall 5: `@Configuration("tenantAsyncConfig")` Bean Name Conflict
**What goes wrong:** `config.AsyncConfig` is annotated `@Configuration("tenantAsyncConfig")` specifically to avoid collision with `email.config.AsyncConfig`. Moving the class to a new package does not change the bean name — the `"tenantAsyncConfig"` qualifier is on the class, not the package. No action needed, but the distinction must be understood.
**How to avoid:** Do NOT strip or change the `@Configuration("tenantAsyncConfig")` qualifier when moving `AsyncConfig`.
**Warning signs:** Spring context failing with `BeanDefinitionOverrideException` mentioning `asyncConfig` (would happen if the qualifier were removed, causing collision with email's `AsyncConfig`).

## Code Examples

### Production Files: Full Scope of Changes

**INFRA-01 — 3 files change package declaration, ~1 import update:**
```
src/main/java/com/softropic/payam/
  config/AsyncConfig.java           → infrastructure/config/AsyncConfig.java
  config/DataSourceConfig.java      → infrastructure/config/DataSourceConfig.java
  config/ObservabilityConfig.java   → infrastructure/config/ObservabilityConfig.java
```
Callers needing import updates:
- None (no production files `import com.softropic.payam.config.AsyncConfig/DataSourceConfig/ObservabilityConfig`)
- The Javadoc `@see com.softropic.payam.config.AsyncConfig` in `TenantContextTaskDecorator.java` is a Javadoc-only reference; it does not affect compilation but should be updated for documentation accuracy.

**INFRA-02 — 3 files change package declaration, 1 import update:**
```
src/main/java/com/softropic/payam/
  tenant/config/ApiKeyAuthenticationFilter.java → infrastructure/web/ApiKeyAuthenticationFilter.java
  tenant/config/TenantSecurityConfig.java       → infrastructure/web/TenantSecurityConfig.java
  security/audit/filter/LoggingFilter.java      → infrastructure/web/LoggingFilter.java
```
Callers needing import updates:
- `security/config/SecurityConfiguration.java` — `import com.softropic.payam.security.audit.filter.LoggingFilter;` → `import com.softropic.payam.infrastructure.web.LoggingFilter;`

**INFRA-03 — 8 files change package declaration, 29 production + 4 test import updates:**
```
src/main/java/com/softropic/payam/
  common/persistence/AbstractAuditingEntity.java     → infrastructure/persistence/AbstractAuditingEntity.java
  common/persistence/AuditingDateTimeProvider.java   → infrastructure/persistence/AuditingDateTimeProvider.java
  common/persistence/BaseEntity.java                 → infrastructure/persistence/BaseEntity.java
  common/persistence/DbSchemaChecker.java            → infrastructure/persistence/DbSchemaChecker.java
  common/persistence/DbUtil.java                     → infrastructure/persistence/DbUtil.java
  common/persistence/EntityStatus.java               → infrastructure/persistence/EntityStatus.java
  common/persistence/IdType.java                     → infrastructure/persistence/IdType.java
  common/persistence/RequestIdAuditEntityListener.java → infrastructure/persistence/RequestIdAuditEntityListener.java
```
Callers: every JPA entity class (`Transaction`, `Tenant`, `TenantApiKey`, `Disbursement`, `FeeRule`, etc.) and 4 test files must update `import com.softropic.payam.common.persistence.*` to `import com.softropic.payam.infrastructure.persistence.*`.

### Recommended Execution Order (Wave Plan)
```
Wave 1: INFRA-03 (infrastructure.persistence)
  — Most callers (29 prod + 4 test), highest risk of compile failures
  — Move 8 files, update imports in all callers
  — Run: mvn verify
  — Commit if green

Wave 2: INFRA-01 (infrastructure.config)
  — Fewest callers (0 real production imports, 1 Javadoc)
  — Move 3 files
  — Run: mvn verify
  — Commit if green

Wave 3: INFRA-02 (infrastructure.web)
  — Medium risk (FilterRegistrationBean pattern, 1 real import update)
  — Move 3 files, update 1 import in SecurityConfiguration
  — Run: mvn verify
  — Commit if green
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `@ComponentScan(basePackages=...)` explicit | `@SpringBootApplication` implicit scan | Spring Boot 1.x → 2.x | New sub-packages under the application's root package are auto-discovered; no scan config needed |
| Manual filter registration in `web.xml` | `FilterRegistrationBean` in Java config | Spring Boot 1.x | `FilterRegistrationBean(setEnabled=false)` is the correct way to register a filter only within a specific `SecurityFilterChain` |

**No deprecated patterns identified** in the classes being moved.

## Open Questions

1. **Should `RotatedKeyCleanupSchedulerConfig` also move to `infrastructure.web`?**
   - What we know: It is in `tenant.config`, the same package as `TenantSecurityConfig`. It is a Quartz scheduler config, not a web filter.
   - What's clear: The INFRA-02 requirement is for "Spring filters, interceptors, and web-layer infrastructure." A Quartz scheduler is not web-layer infrastructure.
   - Recommendation: Leave `RotatedKeyCleanupSchedulerConfig` in `tenant.config`; it will move with the full `tenant` package in Phase 62.

2. **Should `LoggingFilter`-related integration tests (`SecurityFilterChainIT`) need updating after Phase 61?**
   - What we know: `SecurityFilterChainIT` tests the JWT chain via HTTP; `LoggingFilter` is an internal concern that does not affect HTTP response codes or bodies tested by the ITs.
   - Recommendation: No test logic changes expected — only the import in `SecurityConfiguration` changes. The ITs test behavior, not class locations.

## Environment Availability

Step 2.6: All required tools are available — no external dependencies beyond project's own build.

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java 17 | Compilation | Yes | 17.0.1 LTS | — |
| Maven 3.9.9 | `mvn verify` | Yes | 3.9.9 | — |
| PostgreSQL (Testcontainers) | Integration tests | Yes (Docker) | via Testcontainers | — |
| Redis (Testcontainers) | Integration tests | Yes (Docker) | via Testcontainers | — |

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
| INFRA-01 | All `config.*` classes in `infrastructure.config`, no `config.*` prod imports | compile | `mvn compile -q` | N/A (compile gate) |
| INFRA-02 | `infrastructure.web` classes present; `ApiKeyAuthenticationFilter` only runs for `/v1/**` non-admin/account | integration | `mvn verify` — `TenantFilterChainIT` + `SecurityFilterChainIT` | Yes (existing) |
| INFRA-03 | All `infrastructure.persistence` classes present; `@MappedSuperclass` chain intact | integration | `mvn verify` — `TenantAuditIT` + any IT using a JPA entity | Yes (existing) |
| BUILD-01 | `mvn verify` green | integration | `mvn verify` | Yes (existing) |
| BUILD-02 | No behavior change — REST contracts unchanged | integration | `mvn verify` — all existing ITs | Yes (existing) |
| BUILD-03 | Spring context starts; health endpoints respond | integration | `mvn verify` — `OperationalIT` + actuator health check | Yes (existing) |

### Sampling Rate
- **Per wave commit:** `mvn verify` (full suite — this is a refactor, not feature work; no shortcuts)
- **Per phase gate:** `mvn verify` green with zero test failures

### Wave 0 Gaps
None — existing test infrastructure covers all phase requirements. No new test files need to be created for Phase 61. The phase adds no new behavior; correctness is verified by the existing unit and integration test suite remaining green.

## Sources

### Primary (HIGH confidence)
- Source tree inspection — `/src/main/java/com/softropic/payam/config/`, `/common/persistence/`, `/tenant/config/`, `/security/audit/filter/` — all files read directly
- `PayamApplication.java` — confirms `@SpringBootApplication` implicit component scan from `com.softropic.payam`
- `TenantSecurityConfig.java` — confirms `FilterRegistrationBean(setEnabled=false)` pattern
- `DataSourceConfig.java` — confirms `@EnableJpaAuditing` is on this class
- `SecurityConfiguration.java` — confirms `LoggingFilter` import location and filter registration

### Secondary (MEDIUM confidence)
- `requirements/architecture.md` — the target package hierarchy diagram is the authoritative design spec
- `.planning/REQUIREMENTS.md` — INFRA-01/02/03 requirement text and Phase 61 success criteria
- `.planning/STATE.md` — `FilterRegistrationBean(setEnabled=false)` carry-forward note explicitly documented

### Tertiary (LOW confidence)
- None — all findings are from direct source inspection

## Metadata

**Confidence breakdown:**
- File inventory (what moves): HIGH — all source files read directly
- Caller count (import update scope): HIGH — `grep` scan of entire src tree
- `FilterRegistrationBean` preservation: HIGH — pattern documented in STATE.md and verified in source
- Wave ordering recommendation: MEDIUM — based on caller count and risk reasoning, not empirical data

**Research date:** 2026-05-06
**Valid until:** Stable — this is a code-only refactoring; no external dependencies or API versions affect findings.
