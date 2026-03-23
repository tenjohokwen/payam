---
phase: 02-transaction-core
plan: "03"
subsystem: payments
tags: [idempotency, redis, double-entry-ledger, flyway, jpa, testcontainers, jackson, setIfAbsent]

# Dependency graph
requires:
  - phase: 02-transaction-core
    provides: Transaction entity + TransactionRepository (FK target for ledger_entry), spring-boot-starter-data-redis + commons-pool2 + testcontainers base already added
  - phase: 01-multi-tenant-foundation
    provides: V2 idempotency_key DDL, BaseEntity (@Tsid IDs), Tenant FK target, TestConfig testcontainers pattern
provides:
  - V4 Flyway migration creating main.ledger_entry (double-entry accounting table with CHECK constraints)
  - LedgerDirection enum (DEBIT/CREDIT)
  - LedgerEntry @Immutable JPA entity (append-only, @Builder, @Tsid id, no AbstractAuditingEntity)
  - LedgerEntryRepository with findByTransactionId + findByEntryGroupId
  - LedgerService.postEntry() inserting atomic DEBIT+CREDIT pair sharing entryGroupId
  - CachedResponse record with Jackson toJson/fromJson (static ObjectMapper)
  - IdempotencyKey JPA entity extending BaseEntity (V2 DDL has no audit columns)
  - IdempotencyKeyRepository with findByTenantIdAndIdempotencyKey
  - IdempotencyService with Redis NX+EX setIfAbsent (atomic reservation) + PostgreSQL fallback on exception
  - Redis GenericContainer in TestConfig with @ServiceConnection(name="redis")
  - 5 integration tests: 3 IdempotencyServiceIT + 2 LedgerServiceIT
affects:
  - 02-02-orange-adapter (can now call LedgerService.postEntry() and IdempotencyService after payment)
  - 02-04-mtn-adapter (same)
  - 03-payment-orchestration (idempotency and ledger are required before orchestration layer)
  - All future provider adapters needing idempotency or audit trail

# Tech tracking
tech-stack:
  added:
    - Redis GenericContainer testcontainer (redis:7-alpine) — added to TestConfig
    - Spring Data Redis StringRedisTemplate (already in classpath via 02-01; first functional use here)
  patterns:
    - Redis NX+EX setIfAbsent pattern for atomic idempotency reservation (single call, no check-then-set race)
    - PostgreSQL fallback catch-block pattern: catch Exception on Redis → query IdempotencyKeyRepository
    - Double-entry ledger pattern: always save DEBIT+CREDIT pair in same @Transactional with shared entryGroupId
    - @Immutable entity for append-only records (LedgerEntry) — Hibernate refuses dirty-check updates
    - ReflectionTestUtils injection pattern for testing service with broken dependency (broken StringRedisTemplate)

key-files:
  created:
    - src/main/resources/db/migration/V4__ledger_schema.sql
    - src/main/java/com/softropic/payam/transaction/contract/LedgerDirection.java
    - src/main/java/com/softropic/payam/transaction/contract/CachedResponse.java
    - src/main/java/com/softropic/payam/transaction/repo/LedgerEntry.java
    - src/main/java/com/softropic/payam/transaction/repo/LedgerEntryRepository.java
    - src/main/java/com/softropic/payam/transaction/repo/IdempotencyKey.java
    - src/main/java/com/softropic/payam/transaction/repo/IdempotencyKeyRepository.java
    - src/main/java/com/softropic/payam/transaction/service/LedgerService.java
    - src/main/java/com/softropic/payam/transaction/service/IdempotencyService.java
    - src/test/java/com/softropic/payam/transaction/IdempotencyServiceIT.java
    - src/test/java/com/softropic/payam/transaction/LedgerServiceIT.java
  modified:
    - src/test/java/com/softropic/payam/config/TestConfig.java (added Redis GenericContainer bean)

key-decisions:
  - "IdempotencyKey extends BaseEntity only (not AbstractAuditingEntity) — V2 DDL has only id/tenant_id/idempotency_key/response_body/http_status/created_date/expires_at; no status/created_by/audit columns"
  - "LedgerEntry uses @Builder (not @SuperBuilder) — no superclass; @Immutable prevents dirty-check updates"
  - "IdempotencyService.store() delete-then-save for upsert — IdempotencyKey has no public setters; delete+save is cleaner than reflection-based mutation for a low-frequency operation"
  - "Test 3 (Redis fallback) uses ReflectionTestUtils.getField + new IdempotencyService(brokenRedis, realRepo) — avoids @MockBean which would require a separate Spring context; tests real fallback path with broken factory"
  - "@ServiceConnection(name='redis') required on GenericContainer — Spring Boot cannot infer service type from untyped GenericContainer without the name attribute (unlike PostgreSQLContainer which has a typed class)"

patterns-established:
  - "Atomic Redis NX+EX reservation: setIfAbsent(key, 'RESERVED', TTL) — returns Boolean.FALSE if key already exists; single call prevents two concurrent callers both proceeding"
  - "Catch-all Redis fallback: catch Exception around Redis calls → log warn → query PostgreSQL; never let Redis failure block the request"
  - "Double-entry invariant: LedgerService.postEntry() always calls saveAll(List.of(debit, credit)) — exactly 2 rows, same entryGroupId, same @Transactional, sum debit - credit == 0"
  - "Append-only entity: @Immutable on LedgerEntry — Hibernate will not flush dirty checks; amount/direction are immutable after insert"

# Metrics
duration: 5min
completed: 2026-03-24
---

# Phase 2 Plan 3: Idempotency + Ledger Summary

**Redis NX+EX idempotency store with PostgreSQL fallback + double-entry DEBIT/CREDIT ledger with @Immutable append-only entity, 5 ITs green**

## Performance

- **Duration:** 5 min
- **Started:** 2026-03-23T22:59:15Z
- **Completed:** 2026-03-24T00:04:13Z
- **Tasks:** 2
- **Files modified:** 12

## Accomplishments
- V4 Flyway migration creating `main.ledger_entry` with `amount > 0` CHECK, `direction IN ('DEBIT','CREDIT')` CHECK, and 3 indexes
- LedgerEntry `@Immutable` JPA entity (append-only, @Builder, @Tsid ID) with LedgerService.postEntry() saving atomic DEBIT+CREDIT pair sharing the same `entryGroupId` in one `@Transactional` call
- IdempotencyService using Redis `setIfAbsent` (atomic NX+EX) as primary store with PostgreSQL `idempotency_key` table as exception-triggered fallback — no check-then-set race condition possible
- CachedResponse Java record with static Jackson ObjectMapper for JSON serialization/deserialization
- Redis GenericContainer added to TestConfig with `@ServiceConnection(name="redis")` — enables Redis in all integration tests
- 5 integration tests green: 3 IdempotencyServiceIT + 2 LedgerServiceIT; full 132-test suite passes with zero regressions

## Task Commits

Each task was committed atomically:

1. **Task 1: V4 ledger schema + LedgerEntry entity + LedgerService** - `81eb618` (feat)
2. **Task 2: IdempotencyService + Redis Testcontainer + integration tests** - `c1e7e76` (feat)

**Plan metadata:** (docs commit follows)

## Files Created/Modified
- `src/main/resources/db/migration/V4__ledger_schema.sql` - DDL for main.ledger_entry with CHECK constraints and 3 indexes
- `src/main/java/com/softropic/payam/transaction/contract/LedgerDirection.java` - DEBIT/CREDIT enum
- `src/main/java/com/softropic/payam/transaction/contract/CachedResponse.java` - record with toJson/fromJson using static Jackson ObjectMapper
- `src/main/java/com/softropic/payam/transaction/repo/LedgerEntry.java` - @Immutable @Builder entity with @Tsid id
- `src/main/java/com/softropic/payam/transaction/repo/LedgerEntryRepository.java` - JpaRepository with findByTransactionId + findByEntryGroupId
- `src/main/java/com/softropic/payam/transaction/repo/IdempotencyKey.java` - JPA entity extending BaseEntity (matches V2 DDL columns exactly)
- `src/main/java/com/softropic/payam/transaction/repo/IdempotencyKeyRepository.java` - JpaRepository with findByTenantIdAndIdempotencyKey
- `src/main/java/com/softropic/payam/transaction/service/LedgerService.java` - postEntry() saves DEBIT+CREDIT in same @Transactional
- `src/main/java/com/softropic/payam/transaction/service/IdempotencyService.java` - checkAndReserve() + store() with Redis NX+EX + Postgres fallback
- `src/test/java/com/softropic/payam/config/TestConfig.java` - added Redis GenericContainer @ServiceConnection(name="redis")
- `src/test/java/com/softropic/payam/transaction/IdempotencyServiceIT.java` - 3 ITs covering new-key reservation, store+retrieve, Redis fallback to Postgres
- `src/test/java/com/softropic/payam/transaction/LedgerServiceIT.java` - 2 ITs covering 2-row DEBIT+CREDIT insert and net-zero balance

## Decisions Made
- **IdempotencyKey extends BaseEntity only:** The V2 DDL has no `status`, `created_by`, `last_modified_by`, or other AbstractAuditingEntity columns. Using AbstractAuditingEntity would cause schema-validation failure on startup.
- **LedgerEntry uses @Builder not @SuperBuilder:** No superclass — @Builder is sufficient. @Immutable prevents Hibernate dirty checks on fields that must not be updated after insert.
- **IdempotencyService.store() delete-then-save for upsert:** IdempotencyKey has no public setters. Delete-then-save is a clean upsert for a table where updates are rare (only when a response needs to be recorded after reservation).
- **Test 3 uses ReflectionTestUtils + manual IdempotencyService construction:** Creates a broken StringRedisTemplate with a mock connection factory that throws RedisConnectionFailureException. Extracts real repo via reflection and constructs a fresh IdempotencyService with broken Redis but real Postgres. Tests the full fallback path without a separate Spring Boot context or @MockBean.

## Deviations from Plan

None — plan executed exactly as written. All specified files created, all specified behaviors implemented, all 5 ITs pass.

## Issues Encountered
None — tests passed on first run. Docker pulled redis:7-alpine for first-time use (expected).

## User Setup Required
None — no external service configuration required for this plan.

## Next Phase Readiness
- IdempotencyService ready for use by provider adapters (02-02 Orange, 02-04 MTN) before any payment initiation
- LedgerService ready for use by payment orchestration after successful payment confirmation
- Redis Testcontainer now active in all integration tests — future IT classes using Redis will work automatically
- No blockers for Wave 2 plans (02-02, 02-04) or Phase 3 orchestration

---
*Phase: 02-transaction-core*
*Completed: 2026-03-24*
