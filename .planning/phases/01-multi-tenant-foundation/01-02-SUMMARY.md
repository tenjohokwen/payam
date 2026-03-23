---
phase: 01-multi-tenant-foundation
plan: "02"
subsystem: auth
tags: [spring-security, api-key, filter-chain, threadlocal, flyway, postgresql, testcontainers, async]

# Dependency graph
requires:
  - phase: 01-multi-tenant-foundation
    plan: "01"
    provides: "ApiKeyService.authenticate(), TenantApiKeyRepository with JOIN FETCH k.tenant, Flyway V1 schema"
provides:
  - "@Order(1) SecurityFilterChain scoped to /v1/** (excluding /v1/account/**) with X-Api-Key authentication"
  - "TenantContext: ThreadLocal<String> holder for tenantId propagation across request lifecycle"
  - "TenantPrincipal: UserDetails impl carrying tenantRef + tenantId with ROLE_TENANT authority"
  - "ApiKeyAuthenticationFilter: X-Api-Key extraction, TenantContext.set/clear in try/finally"
  - "TenantContextTaskDecorator: propagates tenantId to @Async threads"
  - "AsyncConfig (com.softropic.payam.config): 'taskExecutor' bean composing MdcDecorator + TenantContextTaskDecorator"
  - "Flyway V2 migration: idempotency_key table with CONSTRAINT uq_idempotency_tenant_key UNIQUE (tenant_id, idempotency_key)"
  - "7-test TenantFilterChainIT suite verifying chain isolation, 401 for missing/invalid keys, idempotency uniqueness"
affects:
  - Phase 2 (idempotency enforcement — consumes idempotency_key table and TenantContext)
  - All future phases with @Async methods (use 'taskExecutor' bean to get tenant propagation)
  - All /v1/** payment handlers (read tenantId via TenantContext.get())

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Filter chain isolation: @Order(1) chain with AndRequestMatcher(/v1/** AND NOT /v1/account/**) — JWT chain retains full control of user account paths"
    - "Filter bean pattern: ApiKeyAuthenticationFilter NOT @Component, defined as @Bean in TenantSecurityConfig to prevent servlet container auto-registration"
    - "shouldNotFilter() bypass: filter explicitly skips /v1/account/** and PUBLIC_ENDPOINTS paths"
    - "TenantContext.clear() uses ThreadLocal.remove() not set(null) — prevents memory leak in pooled threads"
    - "Async decorator composition: MdcDecorator wraps task first, then TenantContextTaskDecorator wraps the MDC-wrapped task"
    - "Two AsyncConfig classes: email.config.AsyncConfig ('sendMailPool') and config.AsyncConfig ('taskExecutor', named 'tenantAsyncConfig' via @Configuration to avoid ConflictingBeanDefinitionException in Spring 6.2+)"

key-files:
  created:
    - src/main/java/com/softropic/payam/security/common/util/TenantContext.java
    - src/main/java/com/softropic/payam/tenant/contract/TenantPrincipal.java
    - src/main/java/com/softropic/payam/tenant/config/ApiKeyAuthenticationFilter.java
    - src/main/java/com/softropic/payam/tenant/config/TenantSecurityConfig.java
    - src/main/java/com/softropic/payam/common/threadpool/TenantContextTaskDecorator.java
    - src/main/java/com/softropic/payam/config/AsyncConfig.java
    - src/main/resources/db/migration/V2__idempotency_key_schema.sql
    - src/test/java/com/softropic/payam/tenant/TenantFilterChainIT.java
  modified: []

key-decisions:
  - "securityMatcher excludes /v1/account/**: AND-NOT matcher ensures JWT chain handles all user account management — preserves SecurityFilterChainIT (4 tests) and SecurityIT (9 tests) completely"
  - "ApiKeyAuthenticationFilter NOT @Component: prevents auto-registration with servlet container; defined as @Bean in TenantSecurityConfig; shouldNotFilter() skips account/public paths as defence-in-depth"
  - "@Configuration('tenantAsyncConfig') on new AsyncConfig: Spring 6.2+ ConflictingBeanDefinitionException with email.config.AsyncConfig (both named 'asyncConfig' by default); explicit name resolves it"
  - "TenantFilterChainIT loads JWT secret in @BeforeEach: SecurityAdviceFilter.addSecretToThread() runs on every request and throws SecException KEY_NOT_FOUND if main.sec row absent; test must pre-seed it"

patterns-established:
  - "TenantContext read: TenantContext.get() returns tenantRef (UUID string) during any /v1/** request processed by the API key chain"
  - "New filter registration: do not use @Component for security filters; define as @Bean in config class to prevent double-registration"
  - "Test JWT secret: any IT test that triggers SecurityAdviceFilter (i.e., any web request) must have JWT secret row in main.sec — either via @Sql or manual @BeforeEach insert"

# Metrics
duration: 78min
completed: 2026-03-23
---

# Phase 1 Plan 2: API Key Filter Chain Summary

**@Order(1) API key security filter chain, TenantContext ThreadLocal, async tenant propagation, and Flyway V2 idempotency_key schema — all 19 tests green with zero JWT chain regressions**

## Performance

- **Duration:** 78 min
- **Started:** 2026-03-23T21:52:52Z
- **Completed:** 2026-03-23T23:11:06Z
- **Tasks:** 3
- **Files modified:** 8 created

## Accomplishments

- Wired `@Order(1)` SecurityFilterChain that authenticates `/v1/**` payment/admin requests via `X-Api-Key` without touching the JWT chain
- Introduced `TenantContext` ThreadLocal that is set before request handling and cleared (via `remove()`) in a `finally` block, preventing thread-pool leakage between requests
- Created `TenantContextTaskDecorator` composable with `MdcDecorator` in the new `taskExecutor` bean, propagating tenantId to all `@Async` tasks
- Delivered Flyway V2 migration with idempotency_key table whose composite UNIQUE constraint makes cross-tenant key collision structurally impossible at the DB level
- 7 integration tests cover all filter chain scenarios including chain isolation, 401 paths, idempotency uniqueness, and thread context clearing

## Task Commits

Each task was committed atomically:

1. **Task 1: TenantContext, TenantPrincipal, ApiKeyAuthenticationFilter** - `7c389d9` (feat)
2. **Task 2: TenantSecurityConfig, TenantContextTaskDecorator, AsyncConfig, V2 migration** - `ab067e0` (feat)
3. **Task 3: TenantFilterChainIT integration tests** - `aa0bae5` (test)

## Files Created/Modified

- `src/main/java/com/softropic/payam/security/common/util/TenantContext.java` — ThreadLocal<String> with set/get/clear using remove()
- `src/main/java/com/softropic/payam/tenant/contract/TenantPrincipal.java` — UserDetails carrying tenantRef + tenantId, ROLE_TENANT authority
- `src/main/java/com/softropic/payam/tenant/config/ApiKeyAuthenticationFilter.java` — X-Api-Key filter, shouldNotFilter() bypass for account paths
- `src/main/java/com/softropic/payam/tenant/config/TenantSecurityConfig.java` — @Order(1) chain with AndRequestMatcher excluding /v1/account/**
- `src/main/java/com/softropic/payam/common/threadpool/TenantContextTaskDecorator.java` — TaskDecorator for async tenantId propagation
- `src/main/java/com/softropic/payam/config/AsyncConfig.java` — 'taskExecutor' bean, @Configuration("tenantAsyncConfig")
- `src/main/resources/db/migration/V2__idempotency_key_schema.sql` — idempotency_key with uq_idempotency_tenant_key UNIQUE constraint
- `src/test/java/com/softropic/payam/tenant/TenantFilterChainIT.java` — 7 integration tests

## Decisions Made

- **securityMatcher excludes `/v1/account/**`**: The plan spec said `securityMatcher("/v1/**")` broadly, but the existing `SecurityFilterChainIT` tests (JWT-based account management) break when the API key chain intercepts `/v1/account/`. Solution: use `AndRequestMatcher(AntPath("/v1/**"), Negated(AntPath("/v1/account/**")))`. This keeps `/v1/account/` exclusively in the JWT chain while all payment/admin paths go through the API key chain.
- **Filter as `@Bean` not `@Component`**: `@Component` registers the filter with the servlet container AND in the security chain (double registration). Non-`@Component` filter defined via `@Bean` in `TenantSecurityConfig` runs ONLY within the `@Order(1)` chain. This is the correct pattern for security-chain-scoped filters.
- **`@Configuration("tenantAsyncConfig")`**: Spring 6.2 no longer allows two beans with the same default class-derived name (`asyncConfig`) from different packages. Explicit name on the new class resolves the `ConflictingBeanDefinitionException` without renaming either class.
- **`@BeforeEach` JWT secret in tests**: `SecurityAdviceFilter` calls `jwtSecretService.addSecretToThread()` for every request (it's a `@Component`). The JWT secret lives in `main.sec`. Tests that exercise the web layer must pre-seed this row or every request returns a wrapped 401 from the `SecException`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] securityMatcher changed from `/v1/**` to exclude `/v1/account/**`**

- **Found during:** Task 2 (TenantSecurityConfig), confirmed in Task 2 SecurityFilterChainIT run
- **Issue:** Plan specified `securityMatcher("/v1/**")` broadly. With this matcher, the `@Order(1)` chain intercepts `/v1/account/` paths (user account management). Even with `permitAll()` for those paths, the `ApiKeyAuthenticationFilter` still runs and returns 401 before Spring Security's authorization phase. The JWT chain never processes these requests.
- **Fix:** Changed `securityMatcher` to use `AndRequestMatcher(AntPath("/v1/**"), Negated(AntPath("/v1/account/**")))`. The `permitAll()` and `shouldNotFilter()` approaches were tried first and failed (filter runs before authorization decision regardless of permitAll).
- **Files modified:** `TenantSecurityConfig.java`, `ApiKeyAuthenticationFilter.java`
- **Verification:** SecurityFilterChainIT (4 tests) + SecurityIT (9 tests) all green after fix
- **Committed in:** `ab067e0` (Task 2 commit)

**2. [Rule 3 - Blocking] ConflictingBeanDefinitionException from duplicate AsyncConfig class name**

- **Found during:** Task 2 test run (ApplicationContext failed to load)
- **Issue:** Spring 6.2+ raises `ConflictingBeanDefinitionException` when two `@Configuration` classes in different packages share the same default bean name (`asyncConfig`). Earlier Spring versions allowed this.
- **Fix:** Added `@Configuration("tenantAsyncConfig")` to the new AsyncConfig to give it a unique bean name.
- **Files modified:** `src/main/java/com/softropic/payam/config/AsyncConfig.java`
- **Verification:** ApplicationContext started, all tests green
- **Committed in:** `ab067e0` (Task 2 commit)

**3. [Rule 3 - Blocking] TenantFilterChainIT failing with 401 from SecException KEY_NOT_FOUND**

- **Found during:** Task 3 initial test run (3 of 7 tests failed)
- **Issue:** `SecurityAdviceFilter` (a `@Component`) calls `jwtSecretService.addSecretToThread()` for every request. The JWT secret is fetched from `main.sec` table. `TenantFilterChainIT` test DB starts fresh (Flyway only) with no secret row. Result: every HTTP request through the test throws `SecException` which the exception handler maps to 401.
- **Fix:** Added `@BeforeEach setUp()` that inserts the JWT secret row (same value as `sql/secData.sql`), and `@AfterEach tearDown()` that deletes from `main.sec`.
- **Files modified:** `TenantFilterChainIT.java`
- **Verification:** All 7 TenantFilterChainIT tests pass
- **Committed in:** `aa0bae5` (Task 3 commit)

---

**Total deviations:** 3 auto-fixed (1 Rule 1 bug, 2 Rule 3 blocking)
**Impact on plan:** All fixes were necessary for correct operation and test greenness. No scope creep. The core deliverables match the plan spec exactly.

## Issues Encountered

- `securityMatcher("/v1/**")` is a common Spring Security pattern that works for pure API key systems, but breaks in hybrid systems where some `/v1/**` paths (account management) are JWT-auth territory. The `AndRequestMatcher` + `NegatedRequestMatcher` pattern is the correct Spring Security 6 solution.
- The `SecurityAdviceFilter @Component` double-registration pattern (registered in servlet container AND added to security chain) means all `@Component` servlet filters run for ALL requests regardless of which security filter chain matches. Any new IT test that makes web requests must account for this.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- `TenantContext.get()` is available in all `/v1/**` request handlers (except `/v1/account/**`) that pass the API key filter — downstream phases read tenantId from here
- `idempotency_key` table exists with cross-tenant collision prevention at DB level — Phase 2 adds runtime lookup logic
- `taskExecutor` bean propagates tenantId to `@Async` tasks — use `@Async("taskExecutor")` to get both MDC and tenant context in async work
- All 19 existing tests remain green — no regression in JWT chain or public endpoint behaviour

---
*Phase: 01-multi-tenant-foundation*
*Completed: 2026-03-23*
