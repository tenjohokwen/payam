---
phase: 35-idempotency-correctness
plan: 01
subsystem: payments
tags: [idempotency, postgres, redis, upsert, concurrency, spring-data-jpa]

# Dependency graph
requires:
  - phase: 05-payment-orchestration
    provides: IdempotencyService and IdempotencyKeyRepository infrastructure used here
provides:
  - IdempotencyKeyRepository.upsert() — atomic INSERT ... ON CONFLICT ... DO UPDATE on (tenant_id, idempotency_key)
  - IdempotencyService.store() rewritten with Postgres-first ordering and UPSERT semantics
  - Two new IdempotencyServiceIT tests proving IDEM-01 (no stale Redis on Postgres failure) and IDEM-02 (exactly one DB row under 20-thread concurrent store())
affects: [payment-orchestration, idempotency, e2e-tests]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Postgres-first write ordering: durable store BEFORE cache update; cache failure tolerated in isolated try/catch"
    - "Native UPSERT for concurrent-safe row updates: INSERT ... ON CONFLICT ... DO UPDATE replaces find+save TOCTOU"

key-files:
  created: []
  modified:
    - src/main/java/com/softropic/payam/transaction/repo/IdempotencyKeyRepository.java
    - src/main/java/com/softropic/payam/transaction/service/IdempotencyService.java
    - src/test/java/com/softropic/payam/transaction/IdempotencyServiceIT.java

key-decisions:
  - "Conflict target uses column-list form (tenant_id, idempotency_key) not ON CONFLICT ON CONSTRAINT — consistent with existing reserve() and avoids constraint-name coupling"
  - "Redis write in its own try/catch separate from Postgres call — Postgres exception must propagate; Redis failure must be silenced"
  - "buildIdempotencyKey() helper deleted as dead code — sole callers were the two repo.save() call sites in store(), both removed"

patterns-established:
  - "Postgres-first ordering: any method that writes both Postgres and Redis must write Postgres first, Redis second in isolated try/catch"
  - "Use native UPSERT (@Modifying @Query nativeQuery=true) for concurrent-safe update-or-insert; never use find+conditional-save in high-concurrency paths"

requirements-completed: [IDEM-01, IDEM-02]

# Metrics
duration: 17min
completed: 2026-04-14
---

# Phase 35 Plan 01: Idempotency Correctness Summary

**Postgres-first UPSERT in IdempotencyService.store(): atomic INSERT ... ON CONFLICT ... DO UPDATE eliminates TOCTOU race (IDEM-02) and Postgres-failure-after-Redis-write stale cache bug (IDEM-01)**

## Performance

- **Duration:** 17 min
- **Started:** 2026-04-14T13:05:09Z
- **Completed:** 2026-04-14T13:22:21Z
- **Tasks:** 3
- **Files modified:** 3

## Accomplishments
- Added `IdempotencyKeyRepository.upsert()` native method with `INSERT ... ON CONFLICT (tenant_id, idempotency_key) DO UPDATE SET http_status, response_body, expires_at` — mirrors existing `reserve()` pattern
- Rewrote `IdempotencyService.store()` to write Postgres via `upsert()` first, then Redis in isolated try/catch — a Postgres failure now propagates before Redis is ever touched
- Deleted dead `buildIdempotencyKey()` private helper (its only callers, `repo.delete()+repo.save()`, were removed) and the now-unused `IdempotencyKey` import
- Added two new `IdempotencyServiceIT` tests: `storeDoesNotWriteRedisWhenPostgresFails` (IDEM-01, mock repo throws, assert Redis null) and `concurrentStoreCalls_ProduceExactlyOneDbRow` (IDEM-02, 20-thread CyclicBarrier flood, assert count=1 and no exceptions)
- All 5 `IdempotencyServiceIT` tests pass (3 pre-existing + 2 new)

## Task Commits

Each task was committed atomically:

1. **Task 1: Add upsert() to IdempotencyKeyRepository** - `b98cdc2` (feat)
2. **Task 2: Add IDEM-01 and IDEM-02 failing tests** - `5fe5fb2` (test)
3. **Task 3: Rewrite store() — Postgres-first ordering** - `f939d03` (feat)

## Files Created/Modified
- `src/main/java/com/softropic/payam/transaction/repo/IdempotencyKeyRepository.java` — Added `upsert()` method after `reserve()`
- `src/main/java/com/softropic/payam/transaction/service/IdempotencyService.java` — Rewrote `store()`, removed `buildIdempotencyKey()` helper and `IdempotencyKey` import
- `src/test/java/com/softropic/payam/transaction/IdempotencyServiceIT.java` — Added two new test methods + required imports

## Decisions Made
- **Conflict target uses column-list form:** `ON CONFLICT (tenant_id, idempotency_key)` not `ON CONFLICT ON CONSTRAINT uq_idempotency_tenant_key`. Column-list form is consistent with the existing `reserve()` method and avoids coupling to the constraint name string.
- **Separate try/catch for Redis:** The Postgres call is intentionally outside any try/catch so failures propagate to the caller. Redis is wrapped in its own try/catch because Redis failure is tolerable — the next retry falls through `checkAndReserve()` to the Postgres fallback path.
- **buildIdempotencyKey() deleted:** It was only used by the two `repo.save()` call sites in `store()`, both removed. No other file in `src/main/java` referenced it.

## Deviations from Plan

None — plan executed exactly as written.

## Issues Encountered

None. The upsert SQL compiled cleanly. The test used `Mockito.mock()` for the IDEM-01 test (brokenRepo) — this is consistent with the existing IDEM-03 test that similarly mocks `RedisConnectionFactory`. The `IdempotencyKey` import removal was noted in the plan as conditional ("if the helper was the sole user") — it was, and was removed.

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness

- `IdempotencyKeyRepository.upsert()` is available for any future callers that need to durably cache a response
- Postgres-first write ordering pattern is established and documented for reuse
- Phase 35 plan 02 can proceed independently

---
*Phase: 35-idempotency-correctness*
*Completed: 2026-04-14*
