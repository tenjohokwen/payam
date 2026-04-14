# Phase 35: Idempotency Correctness — Research

**Researched:** 2026-04-14
**Domain:** Idempotency correctness — Postgres-first write ordering and UPSERT-based concurrent reservation
**Confidence:** HIGH

---

## Summary

Phase 35 fixes two distinct bugs in `IdempotencyService`:

**Bug 1 (IDEM-01 — wrong write ordering in `store()`):** The current `store()` method writes to Redis first, then writes to Postgres. If the Postgres write throws (constraint violation, connection failure, any exception), the Redis key already holds the final serialized response. Any subsequent retry will read the cached value from Redis and reply 202 without ever creating a durable DB record — the payment appears accepted but Postgres has no proof. The fix is: write Postgres first; write Redis only after the DB operation succeeds.

**Bug 2 (IDEM-02 — TOCTOU find+save in `store()`):** The Postgres write in `store()` uses a `findByTenantIdAndIdempotencyKey` followed by either a `delete`+`save` or a plain `save`. Under concurrent load, two threads can both find "absent" and both attempt `save()`, which races on the unique constraint `uq_idempotency_tenant_key`. This causes a `DataIntegrityViolationException` to leak to the caller. The fix is a single native UPSERT (`INSERT ... ON CONFLICT (tenant_id, idempotency_key) DO UPDATE SET ...`), mirroring the existing `reserve()` pattern already present in `IdempotencyKeyRepository`. The `checkAndReserve()` path already handles concurrent first-time writes correctly via Redis `setIfAbsent` (NX+EX) and the Postgres fallback `reserve()` method — only `store()` needs the UPSERT.

**Note on `@Transactional` scope:** `store()` is annotated `@Transactional` but that annotation does not span the Redis write. Redis operations are not part of the JPA transaction. The annotation only applies to the Postgres JPA calls within the method. This is a valid Spring behaviour — `@Transactional` on a method that also performs Redis operations does not make Redis rollback-able; it only demarcates the JPA transaction boundary.

**Primary recommendation:** Rewrite `store()` to (1) perform the Postgres UPSERT via a new `@Modifying @Query` in `IdempotencyKeyRepository`, and (2) write to Redis only if the Postgres operation succeeds. Add unit and integration tests to prove both invariants.

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| IDEM-01 | When a Postgres write fails during idempotency store, Redis does NOT hold a stale value — Postgres is written first, Redis updated only on success | Fixed by reversing the write order in `store()`: Postgres UPSERT first, Redis set second (only on success) |
| IDEM-02 | Concurrent requests with the same idempotency key produce exactly one DB row — a single UPSERT replaces the current TOCTOU find+save pattern | Fixed by replacing `findByTenantIdAndIdempotencyKey` + conditional `delete`/`save` with a single `INSERT ... ON CONFLICT ... DO UPDATE` query in `IdempotencyKeyRepository.upsert()` |
</phase_requirements>

---

## Standard Stack

No new libraries are needed. The fix is pure refactoring within the existing stack.

### Core (already in pom.xml)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Data JPA | via Spring Boot 3.5.11 | `@Modifying @Query` for native UPSERT | Already used in `IdempotencyKeyRepository.reserve()` |
| Spring Data Redis | via Spring Boot 3.5.11 | Redis NX+EX atomic reservation | Already used in `IdempotencyService.checkAndReserve()` |
| spring-boot-starter-data-redis | managed | StringRedisTemplate | Already wired |
| PostgreSQL JDBC | managed | Executes the native UPSERT | Already in use |

**No new installation needed.**

---

## Architecture Patterns

### Current (broken) flow in `store()`

```
store(tenantId, key, status, body)
  1. redis.set(redisKey, json, TTL)          ← Redis written FIRST
  2. repo.findBy...                          ← DB read
  3a. repo.delete(existing); repo.save(...)  ← Two DB writes (TOCTOU race)
  3b. repo.save(...)                         ← Absent-path save (race on constraint)
```

Problems:
- If step 3 throws, Redis holds a stale value
- Two concurrent threads can pass the `findBy` check and both hit step 3b, racing on the unique constraint

### Fixed flow in `store()`

```
store(tenantId, key, status, body)
  1. repo.upsert(tenantId, key, status, body, expiresAt)   ← Postgres FIRST (atomic UPSERT)
  2. redis.set(redisKey, json, TTL)                        ← Redis SECOND (only if step 1 succeeded)
```

If step 1 throws: Redis is never touched. A retry will fall through to `checkAndReserve()` → Postgres `reserve()` → fresh processing.
If step 2 throws: Redis misses the update. The next retry will fall through to Postgres (fallback path in `checkAndReserve()`) and find the durable record. Redis is eventually consistent and non-authoritative for durability.

### New repository method — `upsert()`

```java
// Source: modeled on existing repo.reserve() in IdempotencyKeyRepository
@Transactional
@Modifying
@Query(value = "INSERT INTO main.idempotency_key " +
               "(id, tenant_id, idempotency_key, http_status, response_body, expires_at, created_date) " +
               "VALUES (:id, :tenantId, :key, :httpStatus, :responseBody, :expiresAt, :now) " +
               "ON CONFLICT (tenant_id, idempotency_key) " +
               "DO UPDATE SET http_status = EXCLUDED.http_status, " +
               "              response_body = EXCLUDED.response_body, " +
               "              expires_at = EXCLUDED.expires_at",
       nativeQuery = true)
int upsert(
    @Param("id")           Long id,
    @Param("tenantId")     Long tenantId,
    @Param("key")          String key,
    @Param("httpStatus")   int httpStatus,
    @Param("responseBody") String responseBody,
    @Param("expiresAt")    Instant expiresAt,
    @Param("now")          Instant now);
```

The `id` parameter uses `TSID.fast().toLong()` (same as `reserve()`). The conflict target is the existing `uq_idempotency_tenant_key` constraint on `(tenant_id, idempotency_key)`.

### `store()` rewrite

```java
@Transactional
public void store(Long tenantId, String idempotencyKey, int httpStatus, String responseBody) {
    String redisKey = KEY_PREFIX + tenantId + ":" + idempotencyKey;
    Instant now = Instant.now();

    // Step 1: Postgres FIRST — durable record
    repo.upsert(
        TSID.fast().toLong(),
        tenantId,
        idempotencyKey,
        httpStatus,
        responseBody,
        now.plus(TTL),
        now
    );

    // Step 2: Redis SECOND — only reached if Postgres succeeded
    try {
        redis.opsForValue().set(redisKey, CachedResponse.toJson(httpStatus, responseBody), TTL);
    } catch (Exception e) {
        log.warn("Redis unavailable for idempotency store",
            kv("operation", "idempotency_store"),
            kv("status", "REDIS_UNAVAILABLE"),
            e);
        // Redis failure is tolerated — Postgres is the authority;
        // the fallback path in checkAndReserve() will serve from Postgres on retry.
    }
}
```

### Anti-Patterns to Avoid

- **Wrapping both Redis and Postgres in a single `try` block:** Makes it impossible to distinguish a Redis failure from a Postgres failure. Keep them in separate try/catch blocks.
- **Using `delete` + `save` to update:** This is the TOCTOU pattern being fixed. Never use find-then-save for concurrent-safe updates — use UPSERT.
- **Putting `@Transactional` on `store()` and expecting it to roll back Redis:** Spring transactions do not span Redis. The annotation only applies to JPA operations.
- **Relying solely on Redis for correctness:** Redis is a performance cache here. Postgres is the source of truth. The fallback path (`fallbackToPostgres`) already enforces this contract.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Concurrent-safe DB insert | Custom locking / pessimistic locks | `INSERT ... ON CONFLICT ... DO UPDATE` | DB handles atomicity at the constraint level; `reserve()` already uses this pattern |
| Redis failure tolerance | Complex retry / circuit-breaker | Simple try/catch with logging | Redis is not authoritative; silence is correct on failure |

---

## Common Pitfalls

### Pitfall 1: `@Transactional` on `store()` does not wrap Redis
**What goes wrong:** Developer assumes `@Transactional` rollback will clean up Redis if Postgres fails.
**Why it happens:** Redis is not a JPA-managed resource; it is outside the transaction.
**How to avoid:** Always place Redis write AFTER the Postgres write completes. Redis write is in its own try/catch.
**Warning signs:** Any code that writes Redis before repo.save/upsert.

### Pitfall 2: `@Modifying` + `@Transactional` on the repository method
**What goes wrong:** Without `@Modifying`, Spring Data JPA will try to return an entity instead of executing the DML.
**Why it happens:** JPA treats `@Query` methods as selects by default.
**How to avoid:** Always annotate native DML methods with both `@Modifying` and `@Transactional` (or rely on the caller's transaction). The existing `reserve()` method uses this pattern correctly.
**Warning signs:** `InvalidDataAccessApiUsageException` at runtime.

### Pitfall 3: Reusing the same TSID across `reserve()` and `upsert()`
**What goes wrong:** If the same ID is produced by `TSID.fast().toLong()` and a row already exists with that ID but a different (tenant_id, idempotency_key), the `ON CONFLICT ON (tenant_id, idempotency_key)` clause will not fire — it will hit the PK constraint instead.
**Why it happens:** TSID collision (extremely rare) or incorrect conflict target.
**How to avoid:** The conflict target must be the composite unique constraint `(tenant_id, idempotency_key)`, not `id`. TSID collision probability is negligible in practice.

### Pitfall 4: Existing `IdempotencyServiceIT` passes because it does not test write ordering
**What goes wrong:** The IT has three tests: new key, store+retrieve, Redis-fallback. None of them test "Postgres fails after Redis write" or "concurrent store" races.
**Why it happens:** The bugs were not caught because the IT exercises happy-path and Redis-failure paths only.
**How to avoid:** Phase 35 must add IT cases that prove both IDEM-01 (Postgres-first ordering) and IDEM-02 (no duplicate row under concurrent `store()`).

### Pitfall 5: ConcurrentIdempotencyRaceTest already passes
**What goes wrong:** The existing `ConcurrentIdempotencyRaceTest` tests `checkAndReserve()` race (20 threads competing to start the same payment), not the `store()` race. The test passes today and will continue to pass after the fix.
**Why it happens:** The race conditions are in `store()`, not in `checkAndReserve()`.
**How to avoid:** The new concurrent test must target `store()` — 20 threads calling `store()` concurrently for the same (tenantId, key), asserting exactly one DB row and no exception leak.

---

## Code Examples

### Existing `reserve()` in IdempotencyKeyRepository — the UPSERT pattern to follow

```java
// Source: src/main/java/com/softropic/payam/transaction/repo/IdempotencyKeyRepository.java
@Transactional
@Modifying
@Query(value = "INSERT INTO main.idempotency_key (id, tenant_id, idempotency_key, expires_at, created_date) " +
               "VALUES (:id, :tenantId, :key, :expiresAt, :now) " +
               "ON CONFLICT (tenant_id, idempotency_key) DO NOTHING", nativeQuery = true)
int reserve(
    @Param("id") Long id,
    @Param("tenantId") Long tenantId,
    @Param("key") String key,
    @Param("expiresAt") Instant expiresAt,
    @Param("now") Instant now);
```

The `upsert()` method is the same pattern with `DO UPDATE SET` instead of `DO NOTHING` and additional `http_status`/`response_body` columns.

### Existing broken `store()` Postgres write — what to replace

```java
// Source: src/main/java/com/softropic/payam/transaction/service/IdempotencyService.java (current)
// Lines 92–100: TOCTOU find+save to DELETE
repo.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey)
        .ifPresentOrElse(
                existing -> {
                    repo.delete(existing);
                    repo.save(buildIdempotencyKey(...));
                },
                () -> repo.save(buildIdempotencyKey(...))
        );
```

This entire block is replaced by a single `repo.upsert(...)` call.

### IT test skeleton for IDEM-01

```java
@Test
void store_postgresFailure_doesNotWriteRedis() {
    // Arrange: inject a repo that throws on upsert
    // Act: call store() — expect exception
    // Assert: Redis key must NOT be present
}
```

### IT test skeleton for IDEM-02

```java
@Test
void store_concurrentSameKey_producesExactlyOneDbRow() throws Exception {
    int THREADS = 20;
    CyclicBarrier barrier = new CyclicBarrier(THREADS);
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);

    for (int i = 0; i < THREADS; i++) {
        pool.submit(() -> {
            barrier.await();
            idempotencyService.store(tenantId, "same-key", 202, "{\"id\":\"x\"}");
        });
    }
    pool.shutdown();
    pool.awaitTermination(30, TimeUnit.SECONDS);

    // Assert: exactly 1 row
    int count = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM main.idempotency_key WHERE tenant_id = ? AND idempotency_key = ?",
        Integer.class, tenantId, "same-key");
    assertThat(count).isEqualTo(1);
}
```

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test 3.5.11 |
| Config file | none (annotation-driven) |
| Quick run command | `mvn verify -pl . -Dtest=IdempotencyServiceIT -Dit.test=IdempotencyServiceIT` |
| Full suite command | `mvn verify` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|--------------|
| IDEM-01 | Redis NOT written when Postgres fails | unit/IT | `mvn verify -Dtest=IdempotencyServiceIT` | Partial — existing IT needs new test method |
| IDEM-02 | Concurrent `store()` produces exactly 1 DB row | IT | `mvn verify -Dtest=IdempotencyServiceIT` | Partial — existing IT needs new test method |
| (E2E regression) | 20-thread flood still produces 1 row and no exception leak | E2E | `mvn verify -Dtest=ConcurrentIdempotencyRaceTest` | ✅ existing |
| (E2E regression) | Three-round idempotency scenario still passes | E2E | `mvn verify -Dtest=PaymentIdempotencyE2ETest` | ✅ existing |

### Sampling Rate

- **Per task commit:** `mvn verify -Dtest=IdempotencyServiceIT`
- **Per wave merge:** `mvn verify`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps

- [ ] `IdempotencyServiceIT` — needs two new test methods (IDEM-01 ordering proof, IDEM-02 concurrent `store()` proof). The class exists; new methods are additions.

---

## Affected Files

| File | Change |
|------|--------|
| `src/main/java/.../transaction/repo/IdempotencyKeyRepository.java` | Add `upsert()` method with `INSERT ... ON CONFLICT ... DO UPDATE` |
| `src/main/java/.../transaction/service/IdempotencyService.java` | Rewrite `store()`: Postgres UPSERT first, Redis second; remove `buildIdempotencyKey()` helper or keep for fallback path only |
| `src/test/java/.../transaction/IdempotencyServiceIT.java` | Add two new test methods proving IDEM-01 and IDEM-02 |

No Flyway migration is required. The `uq_idempotency_tenant_key` unique constraint already exists in `V2__idempotency_key_schema.sql` and the `ON CONFLICT` clause references it.

---

## Open Questions

1. **Should `buildIdempotencyKey()` helper be retained?**
   - What we know: it is only used by `store()` (the two `repo.save()` call sites being removed). It is not called by `fallbackToPostgres()`.
   - What's unclear: Whether future extension might need it.
   - Recommendation: Delete it as dead code after the `store()` rewrite. No external caller references it.

2. **Should `checkAndReserve()` also be updated to ensure Postgres reservation before Redis?**
   - What we know: `checkAndReserve()` writes Redis first (RESERVED marker via `setIfAbsent`), then falls back to Postgres only on Redis failure. The RESERVED marker is not a final response — it is an in-flight lock. If Redis write succeeds but Postgres is never reached, the payment still proceeds and `store()` will write to Postgres.
   - What's unclear: Whether a partial Redis RESERVED + no Postgres reservation creates a problem (Redis expires in 24h; Postgres fallback path handles the case if Redis is unavailable on the next request).
   - Recommendation: Out of scope for Phase 35. The RESERVED path is a locking mechanism, not a response cache. IDEM-01 specifically targets the `store()` response caching path. Do not change `checkAndReserve()` in this phase.

---

## Sources

### Primary (HIGH confidence)

- Direct code inspection of `IdempotencyService.java` (all 139 lines)
- Direct code inspection of `IdempotencyKeyRepository.java` (existing `reserve()` UPSERT pattern)
- Direct code inspection of `IdempotencyServiceIT.java` (existing test coverage)
- Direct code inspection of `ConcurrentIdempotencyRaceTest.java` (existing concurrency test scope)
- `V2__idempotency_key_schema.sql` — confirms existing constraint name `uq_idempotency_tenant_key`
- `PaymentOrchestrator.java` — confirms `store()` is called after successful provider dispatch, outside any containing `@Transactional`

### Secondary (MEDIUM confidence)

- Spring Data JPA `@Modifying` + `@Query` nativeQuery pattern — verified by existing `reserve()` in same repository
- PostgreSQL `ON CONFLICT DO UPDATE` (UPSERT) — stable feature since PostgreSQL 9.5; project uses PostgreSQL (confirmed by pom.xml)

---

## Metadata

**Confidence breakdown:**
- Bug identification: HIGH — direct code reading, no ambiguity
- Fix approach: HIGH — mirrors existing `reserve()` pattern in the same file
- Test approach: HIGH — mirrors existing `IdempotencyServiceIT` and `ConcurrentIdempotencyRaceTest` structure
- Flyway migration needed: HIGH (none required)

**Research date:** 2026-04-14
**Valid until:** 2026-05-14 (stable codebase; no fast-moving dependencies)
