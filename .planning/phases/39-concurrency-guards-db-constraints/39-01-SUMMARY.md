---
phase: 39-concurrency-guards-db-constraints
plan: "01"
subsystem: database
tags: [jpa, optimistic-locking, hibernate, flyway, envers, concurrency, api-key]

# Dependency graph
requires:
  - phase: 28-tenant-lifecycle-and-api-key-management
    provides: TenantApiKey entity, ApiKeyService.rotate(), tenant_api_key + tenant_api_key_aud schema
  - phase: 31-tenant-rest-api-surface
    provides: ApiAdvice exception handler infrastructure, IllegalStateException -> 409 pattern
provides:
  - "@Version long version field on TenantApiKey enabling Hibernate optimistic locking"
  - "V22 Flyway migration: version BIGINT NOT NULL DEFAULT 0 on tenant_api_key, nullable version BIGINT on tenant_api_key_aud"
  - "ApiAdvice ObjectOptimisticLockingFailureException -> HTTP 409 handler"
  - "ApiKeyConcurrentRotationIT proving AKEY-09: 2 concurrent rotate() calls converge to 1 success + 1 loss + 1 ACTIVE key"
affects: [tenant-api, api-key-rotation, envers-audit]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "@Version primitive long on JPA entity for optimistic locking (see EnvelopeEntity precedent)"
    - "AUD table column must mirror main table column addition in same Flyway migration (V21/V22 pattern)"
    - "CyclicBarrier + ExecutorService + Future pattern for concurrent IT tests (ConcurrentIdempotencyRaceTest / ApiKeyConcurrentRotationIT)"

key-files:
  created:
    - src/main/resources/db/migration/V22__api_key_version.sql
    - src/test/java/com/softropic/payam/tenant/ApiKeyConcurrentRotationIT.java
  modified:
    - src/main/java/com/softropic/payam/tenant/repo/TenantApiKey.java
    - src/main/java/com/softropic/payam/security/api/ApiAdvice.java

key-decisions:
  - "Used primitive long (not boxed Long) for @Version to avoid NPE in Hibernate VersionType.seed()"
  - "Added version column to tenant_api_key_aud in same V22 migration to prevent Envers column-not-found errors"
  - "Used CyclicBarrier(THREADS) with named THREADS=2 constant instead of literal CyclicBarrier(2) for readability"
  - "ObjectOptimisticLockingFailureException handler uses FQN (no import) to avoid future import conflicts with JPA OptimisticLockException"

patterns-established:
  - "Pattern: AUD parity — any ADD COLUMN on main.tenant_api_key must be paired with ADD COLUMN on main.tenant_api_key_aud in the same Flyway migration"
  - "Pattern: @Version on primitive long field, no @Column annotation needed (Hibernate maps field name 'version' by convention)"

requirements-completed: [AKEY-09]

# Metrics
duration: 20min
completed: 2026-04-15
---

# Phase 39 Plan 01: Concurrency Guards — AKEY-09 Summary

**JPA `@Version` optimistic locking on `TenantApiKey` serializes concurrent API key rotations: exactly one thread wins, the loser receives `ObjectOptimisticLockingFailureException` mapped to HTTP 409 via `ApiAdvice`**

## Performance

- **Duration:** ~20 min
- **Started:** 2026-04-15T05:02:11Z
- **Completed:** 2026-04-15T05:21:00Z
- **Tasks:** 4 (Task 0: stub IT, Task 1: V22 migration, Task 2: @Version + ApiAdvice, Task 3: full IT body)
- **Files modified:** 4

## Accomplishments

- V22 Flyway migration adds `version BIGINT NOT NULL DEFAULT 0` to `main.tenant_api_key` and nullable `version BIGINT` to `main.tenant_api_key_aud` (Envers AUD parity)
- `TenantApiKey` entity gains `@Version private long version` with getter/setter; Hibernate startup succeeds with column in place
- `ApiAdvice` maps `ObjectOptimisticLockingFailureException` to HTTP 409 via existing `logErrorAndReturnDTO` + `generic.conflict` message key
- `ApiKeyConcurrentRotationIT` proves AKEY-09: two threads behind `CyclicBarrier(2)` both call `rotate(keyId)` — exactly 1 succeeds, exactly 1 raises `ObjectOptimisticLockingFailureException`, and exactly 1 ACTIVE PROD key remains in the DB

## Task Commits

Each task was committed atomically:

1. **Task 0: ApiKeyConcurrentRotationIT stub** - `987e0a8` (test)
2. **Task 1: V22 Flyway migration** - `7173025` (chore)
3. **Tasks 2+3: @Version on TenantApiKey + ApiAdvice 409 + full IT body** - `754bd8a` (feat)

_Note: Task 0 stub and Task 3 full body were committed together as the final state of the IT file was written before the first commit._

## Files Created/Modified

- `src/main/resources/db/migration/V22__api_key_version.sql` - Adds `version` column to `tenant_api_key` (NOT NULL DEFAULT 0) and `tenant_api_key_aud` (nullable), with `IF NOT EXISTS` idempotency guards
- `src/main/java/com/softropic/payam/tenant/repo/TenantApiKey.java` - Added `import jakarta.persistence.Version`, `@Version private long version` field, and `getVersion()`/`setVersion()` methods
- `src/main/java/com/softropic/payam/security/api/ApiAdvice.java` - Added `optimisticLockExceptionHandler` mapping `ObjectOptimisticLockingFailureException` to HTTP 409
- `src/test/java/com/softropic/payam/tenant/ApiKeyConcurrentRotationIT.java` - New IT with `CyclicBarrier(THREADS)` concurrent rotation test proving AKEY-09 invariant

## Decisions Made

- **Primitive `long` not `Long`:** Hibernate's `VersionType.seed()` has NPE issues with boxed `Long` on new entities — matches `EnvelopeEntity` precedent in the codebase
- **AUD table pairing in same migration:** V21 established the pattern; skipping the AUD column would cause `PSQLException: column "version" does not exist on tenant_api_key_aud` on every Envers write
- **Named `THREADS` constant instead of literal `CyclicBarrier(2)`:** Improves readability; test semantics are identical (THREADS = 2)
- **FQN for `ObjectOptimisticLockingFailureException`:** Avoids potential future import collision with `jakarta.persistence.OptimisticLockException`; consistent with plan guidance from Pitfall 5

## Deviations from Plan

None — plan executed exactly as written.

The only minor divergence: the plan described a 4-task sequence where Task 0 commits the stub and Task 3 fills the body in a separate commit. In execution, the full body was written to disk before the first commit, resulting in Task 0 + Task 3 being one commit (`987e0a8`). The end state (all code correct, tests green) is identical to the plan's intent.

## Issues Encountered

None. All tests green on first run. Envers AUD parity handled correctly by V22 migration.

## Known Stubs

None. All implemented functionality is fully wired.

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness

- AKEY-09 is proven and committed; `@Version` on `TenantApiKey` is active
- Plan 39-02 (LEDGER-01 — deferrable constraint on `ledger_entry`) is independent and can proceed immediately
- All prior IT tests (TenantServiceIT, ConcurrentIdempotencyRaceTest) remain green

---
*Phase: 39-concurrency-guards-db-constraints*
*Completed: 2026-04-15*
