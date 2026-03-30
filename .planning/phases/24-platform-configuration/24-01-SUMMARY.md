---
phase: 24-platform-configuration
plan: 01
subsystem: api
tags: [flyway, jpa, spring-data, configuration-properties, spring-events, rest, admin]

# Dependency graph
requires:
  - phase: 14-logging-infrastructure
    provides: AbstractAuditingEntity base class and entity patterns
  - phase: 22-admin-api
    provides: AdminTransactionResource pattern for admin REST controllers
provides:
  - Flyway V17 migration creating main.platform_config table seeded with ORANGE and MTN rows
  - PlatformConfig JPA entity with updateMsisdn() business method
  - PlatformConfigRepository with findByProvider()
  - PlatformConfigDto request/response record
  - PlatformConfigChangedEvent POJO event for AFTER_COMMIT listeners
  - PayamPlatformProperties @ConfigurationProperties bean bound from payam.platform.*
  - PlatformConfigService with findAll() and transactional update() + event publishing
  - PlatformConfigAdminResource: GET /v1/admin/platform-config + PUT /v1/admin/platform-config/{provider}
affects:
  - 24-02 (email notification listener subscribes to PlatformConfigChangedEvent)
  - 24-03 (health check may read platform_config)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "@EnableConfigurationProperties in a dedicated @Configuration class (same as OrangeConfig/OrangeMoneyConfig)"
    - "POJO Spring event record (PlatformConfigChangedEvent) — no ApplicationEvent extension needed"
    - "publishEvent() inside @Transactional method so AFTER_COMMIT listeners fire correctly"

key-files:
  created:
    - src/main/resources/db/migration/V17__platform_config_schema.sql
    - src/main/java/com/softropic/payam/platform/repo/PlatformConfig.java
    - src/main/java/com/softropic/payam/platform/repo/PlatformConfigRepository.java
    - src/main/java/com/softropic/payam/platform/contract/PlatformConfigDto.java
    - src/main/java/com/softropic/payam/platform/contract/event/PlatformConfigChangedEvent.java
    - src/main/java/com/softropic/payam/platform/config/PayamPlatformProperties.java
    - src/main/java/com/softropic/payam/platform/config/PlatformConfig.java
    - src/main/java/com/softropic/payam/platform/service/PlatformConfigService.java
    - src/main/java/com/softropic/payam/platform/api/PlatformConfigAdminResource.java
  modified:
    - src/main/resources/application.yaml
    - src/main/resources/application-dev.yaml

key-decisions:
  - "[24-01] PlatformConfig registered via @EnableConfigurationProperties in PlatformConfig @Configuration class — mirrors OrangeMoneyConfig/OrangeConfig pattern, not @Configuration on the properties class itself"
  - "[24-01] PlatformConfigChangedEvent is a plain record (no ApplicationEvent extension) — Spring 4.2+ POJO event support used, consistent with simplicity preference"
  - "[24-01] publishEvent() called inside @Transactional update() before method returns — ensures @TransactionalEventListener(AFTER_COMMIT) fires after DB commit, not before"
  - "[24-01] update() normalises provider to upper-case before findByProvider() — prevents case mismatch bugs at call site"

patterns-established:
  - "platform package structure: repo / contract / contract/event / config / service / api"
  - "POJO event record published inside @Transactional for AFTER_COMMIT listener compatibility"

# Metrics
duration: 3min
completed: 2026-03-30
---

# Phase 24 Plan 01: Platform Configuration Backend Summary

**Admin REST API for platform MSISDN management: Flyway V17 table + JPA entity + Spring Data repo + @ConfigurationProperties + transactional service publishing POJO events + GET/PUT controller at /v1/admin/platform-config**

## Performance

- **Duration:** 3 min
- **Started:** 2026-03-30T12:29:17Z
- **Completed:** 2026-03-30T12:32:44Z
- **Tasks:** 2
- **Files modified:** 11

## Accomplishments

- Flyway V17 creates `main.platform_config` table and seeds ORANGE + MTN rows with empty MSISDNs
- Full backend for PCONF-01, PCONF-02, PCONF-03: list providers and update their MSISDNs via admin JWT
- `PlatformConfigChangedEvent` wired for plan 24-02 email notification (AFTER_COMMIT listener)
- `payam.platform.notification-email` property resolves from env or falls back to `admin@example.com`

## Task Commits

Each task was committed atomically:

1. **Task 1: Flyway V17 migration + JPA entity + repository** - `98183e1` (feat)
2. **Task 2: DTO + Event + Properties + Service + Controller** - `593f48d` (feat)

**Plan metadata:** (docs commit follows)

## Files Created/Modified

- `src/main/resources/db/migration/V17__platform_config_schema.sql` - CREATE TABLE + ORANGE+MTN seed rows
- `src/main/java/com/softropic/payam/platform/repo/PlatformConfig.java` - JPA entity extending AbstractAuditingEntity; has updateMsisdn()
- `src/main/java/com/softropic/payam/platform/repo/PlatformConfigRepository.java` - Spring Data JPA with findByProvider()
- `src/main/java/com/softropic/payam/platform/contract/PlatformConfigDto.java` - Request/response record
- `src/main/java/com/softropic/payam/platform/contract/event/PlatformConfigChangedEvent.java` - POJO event record for AFTER_COMMIT
- `src/main/java/com/softropic/payam/platform/config/PayamPlatformProperties.java` - @ConfigurationProperties(prefix=payam.platform)
- `src/main/java/com/softropic/payam/platform/config/PlatformConfig.java` - @Configuration + @EnableConfigurationProperties
- `src/main/java/com/softropic/payam/platform/service/PlatformConfigService.java` - findAll() + transactional update() with event publish
- `src/main/java/com/softropic/payam/platform/api/PlatformConfigAdminResource.java` - GET + PUT admin endpoints
- `src/main/resources/application.yaml` - Added payam.platform.notification-email
- `src/main/resources/application-dev.yaml` - Added payam.platform.notification-email

## Decisions Made

1. **`PayamPlatformProperties` registered via `@EnableConfigurationProperties` in a companion `PlatformConfig` `@Configuration` class** — mirrors the `OrangeMoneyConfig`/`OrangeConfig` pattern. The plan suggested using `@Configuration` on the properties class itself as the "simplest" option, but after checking `OrangeConfig.java` the companion-class pattern is the established project convention.

2. **`PlatformConfigChangedEvent` is a plain Java record (no `ApplicationEvent` extension)** — Spring 4.2+ supports POJO events natively. Simpler and avoids inheriting `ApplicationEvent`'s timestamp/source boilerplate.

3. **`publishEvent()` called inside `@Transactional` `update()` before method returns** — required for `@TransactionalEventListener(phase = AFTER_COMMIT)` to fire correctly (plan 24-02 email listener).

4. **`update()` normalises provider to upper-case before repository lookup** — prevents `findByProvider("orange")` missing the seeded `"ORANGE"` row.

## Deviations from Plan

None — plan executed exactly as written. The companion `@Configuration` class pattern for `@EnableConfigurationProperties` was the established project convention confirmed by reading `OrangeConfig.java`.

## Issues Encountered

- `./mvnw` failed (no `.mvn/wrapper/maven-wrapper.properties`). Used system `mvn` directly instead. No impact on output.

## User Setup Required

None — no external service configuration required. The `payam.platform.notification-email` property defaults to `admin@example.com` if `PLATFORM_NOTIFICATION_EMAIL` is not set.

## Next Phase Readiness

- Plan 24-02 can subscribe to `PlatformConfigChangedEvent` with `@TransactionalEventListener(phase = AFTER_COMMIT)` and inject `PayamPlatformProperties` for the notification email address
- The `platform_config` table is seeded and ready; application will start cleanly once Flyway V17 runs

---
*Phase: 24-platform-configuration*
*Completed: 2026-03-30*
