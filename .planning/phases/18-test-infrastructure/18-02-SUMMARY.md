---
phase: 18-test-infrastructure
plan: 02
subsystem: testing
tags: [testcontainers, wiremock, spring-boot-test, jdbc, postgresql, redis, clock]

# Dependency graph
requires:
  - phase: 18-test-infrastructure plan 01
    provides: AbstractPayamE2ETest base class hierarchy that imports these config classes

provides:
  - PostgresContainerConfig: @TestConfiguration with @ServiceConnection CustomPostgresContainer (postgres:14.18)
  - RedisContainerConfig: @TestConfiguration with @ServiceConnection GenericContainer (redis:7-alpine)
  - WireMockConfig: non-instantiable utility class with MTN/Orange URL property and token body constants
  - TestClockConfig: @TestConfiguration with @Primary Clock fixed at 2026-01-01T09:00:00Z Africa/Douala (WAT)
  - E2ESecurityConfig: @TestConfiguration seeding main.sec JWT secret at context startup and per-test
  - TestDataCleaner: @Component with FK-safe wipeAll() preserving Flyway seed rows
affects:
  - All phase 18 plans 03+ (concrete E2E test classes extend AbstractPayamE2ETest which depends on all six classes)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "@TestConfiguration split into focused single-responsibility config classes (one per infrastructure concern)"
    - "ApplicationListener<ContextRefreshedEvent> pattern for seeding DB state at context startup"
    - "FK-safe DELETE order in wipeAll() with explicit seed-row preservation via NOT IN clauses"

key-files:
  created:
    - src/test/java/com/softropic/payam/config/PostgresContainerConfig.java
    - src/test/java/com/softropic/payam/config/RedisContainerConfig.java
    - src/test/java/com/softropic/payam/config/WireMockConfig.java
    - src/test/java/com/softropic/payam/config/TestClockConfig.java
    - src/test/java/com/softropic/payam/config/E2ESecurityConfig.java
    - src/test/java/com/softropic/payam/config/TestDataCleaner.java
  modified:
    - src/test/java/com/softropic/payam/e2e/AbstractPayamE2ETest.java

key-decisions:
  - "WireMockConfig removed from @Import in AbstractPayamE2ETest — plain utility class with no Spring annotations cannot be imported as @Configuration"
  - "E2ESecurityConfig uses ApplicationListener<ContextRefreshedEvent> to seed main.sec once at startup AND exposes seedSecurityRow() for per-test re-seed after wipeAll() clears it"
  - "TestDataCleaner preserves fee_rule WHERE id NOT IN (1) and fraud_rule WHERE id NOT IN (1,2,3,4,5) — Flyway seed rows required for all routing and fraud logic"
  - "TestDataCleaner never touches main.msisdn_prefix_route — Flyway V16 seeds it; deleting it breaks all MSISDN routing in subsequent tests"

patterns-established:
  - "Config split pattern: one @TestConfiguration per infrastructure concern (postgres/redis/clock/security) instead of one monolithic TestConfig"
  - "Seed-at-event + re-seed-per-test: ApplicationListener for initial seed, explicit call in baseSetUp() for post-wipe re-seed"

# Metrics
duration: 7min
completed: 2026-03-27
---

# Phase 18 Plan 02: Test Infrastructure Config Classes Summary

**Six focused @TestConfiguration classes (Postgres, Redis, WireMock, Clock, Security, DataCleaner) wiring the AbstractPayamE2ETest environment with real Testcontainers, fixed WAT clock, and FK-safe test isolation.**

## Performance

- **Duration:** ~7 min
- **Started:** 2026-03-27T12:08:19Z
- **Completed:** 2026-03-27T12:15:00Z
- **Tasks:** 2
- **Files modified:** 7

## Accomplishments
- Created six Java config files that AbstractPayamE2ETest's @Import and @Autowired fields now resolve
- E2ESecurityConfig seeds main.sec JWT secret at Spring context startup and per-test re-seed after TestDataCleaner.wipeAll() deletes it
- TestDataCleaner.wipeAll() executes 12 DELETE statements in FK-safe order, preserving Flyway seed rows for fee_rule, fraud_rule, and msisdn_prefix_route
- Fixed AbstractPayamE2ETest: removed WireMockConfig.class from @Import (non-Spring utility class), added E2ESecurityConfig autowire and seedSecurityRow() call in baseSetUp()

## Task Commits

Each task was committed atomically:

1. **Task 1: PostgresContainerConfig, RedisContainerConfig, WireMockConfig, TestClockConfig** - `5fc562e` (feat)
2. **Task 2: E2ESecurityConfig and TestDataCleaner** - `8a11632` (feat)

**Plan metadata:** (docs commit — see below)

## Files Created/Modified
- `src/test/java/com/softropic/payam/config/PostgresContainerConfig.java` — @TestConfiguration with @ServiceConnection CustomPostgresContainer (postgres:14.18 + createSchema.sql)
- `src/test/java/com/softropic/payam/config/RedisContainerConfig.java` — @TestConfiguration with @ServiceConnection GenericContainer (redis:7-alpine:6379)
- `src/test/java/com/softropic/payam/config/WireMockConfig.java` — non-instantiable utility class: MTN/Orange URL property name constants and default token response body constants
- `src/test/java/com/softropic/payam/config/TestClockConfig.java` — @TestConfiguration with @Primary Clock.fixed at 2026-01-01T09:00:00Z in Africa/Douala (WAT)
- `src/test/java/com/softropic/payam/config/E2ESecurityConfig.java` — @TestConfiguration implementing ApplicationListener<ContextRefreshedEvent>; seeds main.sec at startup; exposes public seedSecurityRow()
- `src/test/java/com/softropic/payam/config/TestDataCleaner.java` — @Component; wipeAll() deletes 12 tables FK-safely; preserves seed rows; never touches msisdn_prefix_route
- `src/test/java/com/softropic/payam/e2e/AbstractPayamE2ETest.java` — removed WireMockConfig.class from @Import; added @Autowired E2ESecurityConfig field; added seedSecurityRow() as first call in baseSetUp()

## Decisions Made

- **WireMockConfig removed from @Import:** WireMockConfig is a plain utility class with a private constructor and no Spring annotations. Including it in @Import causes Spring to attempt bean registration on a non-configuration class. Removed from the @Import list; it is accessible as a class-level constant provider only.
- **E2ESecurityConfig dual-seed pattern:** ApplicationListener<ContextRefreshedEvent> seeds main.sec once when the Spring test context boots. AbstractPayamE2ETest.baseSetUp() calls seedSecurityRow() again before each test, because TestDataCleaner.wipeAll() deletes main.sec as part of full isolation. The ON CONFLICT DO NOTHING clause makes both calls idempotent.
- **TestDataCleaner preserves Flyway seed rows:** fee_rule id=1 (global default) and fraud_rule id 1-5 are seeded by Flyway and relied upon by FeeEvaluationService and FraudEvaluationService. Deleting them causes test failures in any test that triggers those services. Preserved via WHERE NOT IN clauses.
- **msisdn_prefix_route not deleted:** Flyway V16 seeds the MSISDN prefix routing table. Every payment initiation resolves the provider via this table; deleting it makes 100% of payment tests fail with UNKNOWN_MSISDN_PREFIX.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Removed WireMockConfig.class from @Import in AbstractPayamE2ETest**
- **Found during:** Task 1 (review of AbstractPayamE2ETest.java from plan 18-01)
- **Issue:** WireMockConfig is a non-instantiable utility class (private constructor, no Spring annotations). @Import on a non-@Configuration class causes Spring to fail when attempting to register it as a bean definition.
- **Fix:** Removed `WireMockConfig.class` from the @Import array in AbstractPayamE2ETest; WireMockConfig remains accessible as a static constant class — no import change needed.
- **Files modified:** src/test/java/com/softropic/payam/e2e/AbstractPayamE2ETest.java
- **Verification:** mvn test-compile produces BUILD SUCCESS with 0 errors
- **Committed in:** 5fc562e (part of Task 1 commit)

---

**Total deviations:** 1 auto-fixed (Rule 1 — bug in plan 18-01 artifact)
**Impact on plan:** Required fix for the E2E context to boot. No scope creep — single-line @Import correction.

## Issues Encountered
None — compile succeeded on first attempt after the @Import correction.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- All six config classes exist and compile cleanly
- AbstractPayamE2ETest correctly imports PostgresContainerConfig, RedisContainerConfig, E2ESecurityConfig, TestClockConfig and wires TestDataCleaner and E2ESecurityConfig
- Plan 18-03 (concrete E2E test classes) can now extend AbstractPayamE2ETest and get a fully wired, isolated test environment

---
*Phase: 18-test-infrastructure*
*Completed: 2026-03-27*
