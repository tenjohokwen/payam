---
phase: 41-pin-schema-encryption-config
plan: 01
subsystem: database
tags: [flyway, jpa, envers, platform-config, pin, encryption, spring-boot]

# Dependency graph
requires:
  - phase: 40-operational-resilience
    provides: stable base after v7 backend hardening
  - phase: 24-platform-configuration
    provides: PlatformConfig entity and V17 base schema

provides:
  - V24 Flyway migration creating platform_config_aud (missing from V20) and adding nullable pin VARCHAR(500) to platform_config
  - PlatformConfig JPA entity mapping for nullable pin column
  - PayamPlatformProperties.getPinEncryptionSecret() bound from payam.platform.pin-encryption-secret
  - All 3 YAML profiles declare PLATFORM_PIN_ENCRYPTION_SECRET env var binding

affects:
  - 42-pin-service (reads PlatformConfig.getPin(), uses PayamPlatformProperties.getPinEncryptionSecret() to construct encryptor)
  - 43-pin-api (calls pin service which depends on entity + config)
  - 44-pin-frontend (drives PIN endpoint calls from the UI)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Envers AUD table created in migration matching V20 tenant_aud pattern: all columns nullable, id+rev PRIMARY KEY, REFERENCES main.revinfo(rev)"
    - "Nullable encrypted-value column pattern: VARCHAR(500) to accommodate AES256 Base64 ciphertext; no default; ciphertext-only comment"
    - "ConfigurationProperties extension: add field + getter + setter to PayamPlatformProperties; YAML binding with ${ENV_VAR:} empty-default pattern"

key-files:
  created:
    - src/main/resources/db/migration/V24__platform_config_pin.sql
    - src/test/java/com/softropic/payam/platform/config/PayamPlatformPropertiesTest.java
  modified:
    - src/main/java/com/softropic/payam/platform/repo/PlatformConfig.java
    - src/main/java/com/softropic/payam/platform/config/PayamPlatformProperties.java
    - src/main/resources/application.yaml
    - src/main/resources/application-dev.yaml
    - src/main/resources/application-uat.yaml

key-decisions:
  - "VARCHAR(500) for pin: AES256 Base64 ciphertext for 4-8 char PIN is ~80-120 chars; 500 is safe with margin"
  - "Nullable pin column: existing ORANGE/MTN rows have pin=NULL until admin sets PIN; no migration of existing data needed"
  - "platform_config_aud created in V24 not V20: V20 was already shipped; idempotent IF NOT EXISTS CREATE in V24 corrects the gap"
  - "Plain unit test for PayamPlatformPropertiesTest: setter injection avoids full Spring context boot; consistent with PlatformConfigServiceTest pattern using MockitoExtension"
  - "Empty default ${PLATFORM_PIN_ENCRYPTION_SECRET:} in YAML: resolves to empty string when env var absent; Phase 42 validates non-blank before constructing encryptor"

patterns-established:
  - "AUD table migration pattern: V24 follows V20 exactly -- all columns nullable, id+rev composite PK, INTEGER rev FK to main.revinfo"
  - "New encrypted column: nullable, VARCHAR(500), COMMENT documents ciphertext-only constraint"

requirements-completed: [PIN-01, PIN-02]

# Metrics
duration: 35min
completed: 2026-04-17
---

# Phase 41 Plan 01: PIN Schema and Encryption Config Summary

**Flyway V24 adds missing platform_config_aud table and nullable pin VARCHAR(500) column; PlatformConfig entity and PayamPlatformProperties wired for AES256 PIN storage foundation**

## Performance

- **Duration:** ~35 min
- **Started:** 2026-04-17T00:00:00Z
- **Completed:** 2026-04-17
- **Tasks:** 2 (+ TDD RED commit)
- **Files modified:** 7

## Accomplishments

- V24 Flyway migration corrects the missing platform_config_aud table (V20 only created tenant + api_key AUD tables) and adds nullable pin VARCHAR(500) to both base and AUD tables
- PlatformConfig entity gets `@Column(name = "pin") private String pin` field -- Lombok `@Getter` generates `getPin()` automatically
- PayamPlatformProperties gains `pinEncryptionSecret` with getter/setter bound from `payam.platform.pin-encryption-secret` via `PLATFORM_PIN_ENCRYPTION_SECRET` env var in all 3 YAML profiles
- PayamPlatformPropertiesTest (TDD) verifies getter/setter binding with zero Spring context overhead

## Task Commits

Each task was committed atomically:

1. **Task 1: V24 Flyway migration** - `6848896` (chore)
2. **Task 2 RED: Failing test** - `c85c320` (test -- TDD RED, compilation fails before implementation)
3. **Task 2 GREEN: Production code + YAML** - `0697204` (feat)

## Files Created/Modified

- `src/main/resources/db/migration/V24__platform_config_pin.sql` - Creates platform_config_aud and adds nullable pin column (PIN-01)
- `src/main/java/com/softropic/payam/platform/repo/PlatformConfig.java` - Added @Column(name="pin") private String pin field
- `src/main/java/com/softropic/payam/platform/config/PayamPlatformProperties.java` - Added pinEncryptionSecret field with getter/setter (PIN-02)
- `src/main/resources/application.yaml` - Added pin-encryption-secret binding
- `src/main/resources/application-dev.yaml` - Added pin-encryption-secret binding
- `src/main/resources/application-uat.yaml` - Added pin-encryption-secret binding
- `src/test/java/com/softropic/payam/platform/config/PayamPlatformPropertiesTest.java` - TDD test for property binding

## Decisions Made

- VARCHAR(500) chosen for ciphertext column -- AES256 Base64 output for 4-8 char PIN is ~80-120 chars; 500 provides headroom
- Nullable pin column -- no existing data to migrate; ORANGE/MTN rows get pin=NULL until admin sets PIN
- platform_config_aud created in V24 using `CREATE TABLE IF NOT EXISTS` -- idempotent, corrects V20 gap
- Plain unit test using setter injection (not @SpringBootTest) -- consistent with existing test pattern; avoids Docker/Testcontainers overhead for a simple property binding test

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

AccountManagementFacadeIT failures observed in full `mvn verify` run -- root cause is Testcontainers Docker startup failure (`postgres:14.18` container timed out) unrelated to Phase 41 changes. Pre-existing environment issue in parallel agent execution. First `mvn verify -q` with our changes exited 0.

## User Setup Required

None - no external service configuration required. The `PLATFORM_PIN_ENCRYPTION_SECRET` env var defaults to empty string; Phase 42 will validate non-blank before constructing the AES256 encryptor.

## Next Phase Readiness

- Phase 42 (PIN service) can now read `PlatformConfig.getPin()` for encrypted PIN storage
- Phase 42 can inject `PayamPlatformProperties` and call `getPinEncryptionSecret()` to construct the jasypt AES256TextEncryptor
- V24 migration will run cleanly on fresh Testcontainers PostgreSQL (creates AUD table, adds nullable column to existing ORANGE/MTN rows)
- No blockers for Phase 42

---
*Phase: 41-pin-schema-encryption-config*
*Completed: 2026-04-17*
