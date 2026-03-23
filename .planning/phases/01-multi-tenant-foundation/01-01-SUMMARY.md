---
phase: 01-multi-tenant-foundation
plan: "01"
subsystem: auth
tags: [tenant, api-key, sha256, flyway, jpa, spring-security, postgresql, testcontainers]

# Dependency graph
requires: []
provides:
  - Flyway V1 migration: main.tenant and main.tenant_api_key tables with BIGINT TSID-compatible PKs
  - Tenant JPA entity (AbstractAuditingEntity, @Audited, tenant_status column)
  - TenantApiKey JPA entity (key_hash, key_prefix, key_status, rotated_at, JOIN FETCH query)
  - TenantRepository and TenantApiKeyRepository with isolation-enforced queries
  - ApiKeyService: generateAndStore (SecureRandom+Base64+SHA-256), authenticate (grace period), rotate, revoke
  - TenantService: createTenant returning TenantCreationResult with transient rawKey
  - POST /v1/admin/tenants endpoint returning tenant + raw key shown exactly once
  - TenantStatus enum (ACTIVE, SUSPENDED), ApiKeyStatus enum (ACTIVE, ROTATED, REVOKED)
  - 6-test integration suite verifying provisioning, auth, rotation grace, expiry, isolation
affects:
  - 01-02 (API key security filter chain — consumes ApiKeyService.authenticate())
  - All future phases (every domain entity will carry tenantId for isolation)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Flyway BIGINT PKs: id columns use @Tsid (not sequences) — no BIGSERIAL in DDL"
    - "Column naming to avoid AbstractAuditingEntity conflict: use tenant_status/key_status not status"
    - "API key lifecycle: SecureRandom 32-byte key -> Base64 URL-safe -> SHA-256 hex hash stored"
    - "Rotation grace period: ROTATED status + rotated_at timestamp, query filters 24h window"
    - "JOIN FETCH k.tenant in findValidKeyByHash prevents LazyInitializationException in filter"
    - "TenantCreationResult carries saved TenantApiKey entity directly — never use getApiKeys()"

key-files:
  created:
    - src/main/resources/db/migration/V1__tenant_schema.sql
    - src/main/java/com/softropic/payam/tenant/repo/Tenant.java
    - src/main/java/com/softropic/payam/tenant/repo/TenantRepository.java
    - src/main/java/com/softropic/payam/tenant/repo/TenantApiKey.java
    - src/main/java/com/softropic/payam/tenant/repo/TenantApiKeyRepository.java
    - src/main/java/com/softropic/payam/tenant/contract/TenantStatus.java
    - src/main/java/com/softropic/payam/tenant/contract/ApiKeyStatus.java
    - src/main/java/com/softropic/payam/tenant/contract/TenantDto.java
    - src/main/java/com/softropic/payam/tenant/contract/ApiKeyDto.java
    - src/main/java/com/softropic/payam/tenant/service/ApiKeyService.java
    - src/main/java/com/softropic/payam/tenant/service/TenantService.java
    - src/main/java/com/softropic/payam/tenant/api/TenantAdminResource.java
    - src/test/java/com/softropic/payam/tenant/TenantProvisioningIT.java
  modified:
    - src/main/java/com/softropic/payam/config/DataSourceConfig.java

key-decisions:
  - "ApiKeyStatus is a separate enum (ACTIVE/ROTATED/REVOKED) — not reusing EntityStatus (ACTIVE/INACTIVE/DELETED)"
  - "tenant_status and key_status DDL column names used to avoid clash with AbstractAuditingEntity.status"
  - "Spring Security BadCredentialsException used for invalid/expired key — integrates with Spring Security's auth flow"
  - "EntityNotFoundException used for rotate/revoke when key not found — clean separation from auth failures"
  - "Flyway no-op FlywayMigrationStrategy removed from DataSourceConfig — migrations now run on startup"
  - "JOIN FETCH k.tenant added to findValidKeyByHash to prevent LazyInitializationException in the API key filter"

patterns-established:
  - "Tenant isolation: all repositories accept tenantId as explicit parameter — no implicit context injection"
  - "Raw key transience: rawKey passed up the call stack as plain String, never written to @Column or logged"
  - "Secure key generation: SecureRandom + Base64.getUrlEncoder().withoutPadding() over 32 bytes"
  - "DDL primary keys: BIGINT (no sequence/serial) — TSID assigned by @Tsid annotation in BaseEntity"

# Metrics
duration: 71min
completed: 2026-03-23
---

# Phase 1 Plan 1: Tenant Foundation Summary

**Flyway V1 schema, Tenant/TenantApiKey JPA entities, ApiKeyService with SHA-256 hashing and 24h rotation grace period, POST /v1/admin/tenants admin endpoint, 6 integration tests all green**

## Performance

- **Duration:** 71 min
- **Started:** 2026-03-23T21:37:26Z
- **Completed:** 2026-03-23T22:48:45Z
- **Tasks:** 3
- **Files modified:** 14

## Accomplishments

- Created Flyway V1 migration with `main.tenant` and `main.tenant_api_key` tables using BIGINT (TSID-compatible) PKs
- Implemented `ApiKeyService` with cryptographically secure key generation, SHA-256 hashing, 24-hour rotation grace period, and revocation
- Created `POST /v1/admin/tenants` endpoint that returns raw key exactly once and never persists it
- 6 integration tests cover full tenant lifecycle including provisioning, authentication, rotation (grace window), expiry, and tenant isolation

## Task Commits

Each task was committed atomically:

1. **Task 1: Flyway migration + JPA entities + repositories** - `77aaaf4` (feat)
2. **Task 2: ApiKeyService + TenantService + TenantAdminResource** - `7a54f83` (feat)
3. **Task 3: TenantProvisioningIT integration tests** - `4fb6bb0` (test)

## Files Created/Modified

- `src/main/resources/db/migration/V1__tenant_schema.sql` — DDL for tenant and tenant_api_key with BIGINT PKs, indexes
- `src/main/java/com/softropic/payam/tenant/repo/Tenant.java` — JPA entity, tenant_status column avoids status conflict
- `src/main/java/com/softropic/payam/tenant/repo/TenantApiKey.java` — JPA entity with rotatedAt, key_status column
- `src/main/java/com/softropic/payam/tenant/repo/TenantRepository.java` — findByTenantRef, findByTenantRefAndTenantStatus
- `src/main/java/com/softropic/payam/tenant/repo/TenantApiKeyRepository.java` — JOIN FETCH findValidKeyByHash with grace deadline
- `src/main/java/com/softropic/payam/tenant/contract/TenantStatus.java` — ACTIVE, SUSPENDED
- `src/main/java/com/softropic/payam/tenant/contract/ApiKeyStatus.java` — ACTIVE, ROTATED, REVOKED
- `src/main/java/com/softropic/payam/tenant/contract/TenantDto.java` — response record
- `src/main/java/com/softropic/payam/tenant/contract/ApiKeyDto.java` — response record with transient rawKey
- `src/main/java/com/softropic/payam/tenant/service/ApiKeyService.java` — generate/authenticate/rotate/revoke
- `src/main/java/com/softropic/payam/tenant/service/TenantService.java` — createTenant with TenantCreationResult
- `src/main/java/com/softropic/payam/tenant/api/TenantAdminResource.java` — POST /v1/admin/tenants (201 CREATED)
- `src/main/java/com/softropic/payam/config/DataSourceConfig.java` — removed no-op FlywayMigrationStrategy
- `src/test/java/com/softropic/payam/tenant/TenantProvisioningIT.java` — 6 integration tests

## Decisions Made

- **ApiKeyStatus is a separate enum** (ACTIVE/ROTATED/REVOKED) rather than reusing `EntityStatus` (ACTIVE/INACTIVE/DELETED) — API keys have distinct lifecycle states not covered by the generic entity lifecycle
- **tenant_status and key_status DDL columns** instead of `status` — avoids conflict with `AbstractAuditingEntity.status` which already maps to a `status` column
- **`BadCredentialsException` for invalid/expired keys** — uses Spring Security's authentication exception hierarchy, integrates correctly with Spring Security's error handling
- **`JOIN FETCH k.tenant` in `findValidKeyByHash`** — preloads tenant proxy before transaction closes, preventing `LazyInitializationException` when the API key filter accesses `tenantApiKey.getTenant().getTenantRef()` after the authenticate() transaction

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed JDBC timestamp binding in test for expired rotation simulation**

- **Found during:** Task 3 (TenantProvisioningIT.authenticate_rotatedKeyExpired_throws)
- **Issue:** Used `Instant.toString()` as SQL parameter value for `rotated_at` update; PostgreSQL via JDBC rejects ISO-8601 strings for `TIMESTAMP` columns, throwing `BadSqlGrammar`
- **Fix:** Changed to `java.sql.Timestamp.from(Instant)` which JDBC accepts for timestamp columns
- **Files modified:** `src/test/java/com/softropic/payam/tenant/TenantProvisioningIT.java`
- **Verification:** All 6 tests passed after fix
- **Committed in:** `4fb6bb0` (Task 3 commit)

**2. [Rule 3 - Blocking] Enabled Maven resources before running tests**

- **Found during:** Task 3 test run
- **Issue:** `application.yaml` was not in `target/classes` (only `compiler:compile` was run, not `resources:resources`), causing `@Value("${spring.application.name}")` in `TestConfig.postgresContainer()` to fail with `PlaceholderResolutionException`
- **Fix:** Ran `mvn resources:resources resources:testResources` before invoking surefire directly; this is a test execution environment issue, not a code defect
- **Files modified:** None (environment-only fix)
- **Verification:** Application context started successfully, all 6 tests green

---

**Total deviations:** 2 auto-fixed (1 Rule 1 bug, 1 Rule 3 blocking)
**Impact on plan:** Both fixes were necessary for tests to pass. No scope creep. Code quality is identical to plan specification.

## Issues Encountered

- The Maven `surefire:test` goal must be preceded by `resources:resources resources:testResources` when bypassing the standard lifecycle (which is blocked by the frontend plugin). The frontend build (`generate-resources` phase) is broken due to a missing `quasar` module; this is a pre-existing environment issue unrelated to this plan.
- The plan's `./mvnw compile -q` command fails because `.mvn/wrapper/maven-wrapper.properties` is missing from the repository. Used `mvn compiler:compile` directly as equivalent alternative.

## Next Phase Readiness

- `ApiKeyService.authenticate(rawKey)` is fully tested and ready for the `@Order(1)` security filter chain (Plan 01-02)
- `TenantApiKeyRepository.findValidKeyByHash` already uses `JOIN FETCH k.tenant` — the filter can safely access `tenantApiKey.getTenant().getTenantRef()` after the authenticate() transaction closes
- The `TenantStatus` and `ApiKeyStatus` enums are in `tenant.contract` package — import them from there in subsequent plans
- All existing `SecurityFilterChainIT` and `SecurityIT` tests (13 total) remain green — no regression

---
*Phase: 01-multi-tenant-foundation*
*Completed: 2026-03-23*
