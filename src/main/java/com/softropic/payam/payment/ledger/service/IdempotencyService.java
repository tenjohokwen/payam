package com.softropic.payam.payment.ledger.service;

import com.softropic.payam.payment.ledger.contract.CachedResponse;
import com.softropic.payam.payment.ledger.repo.IdempotencyKeyRepository;

import io.hypersistence.tsid.TSID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private static final Duration TTL = Duration.ofHours(24);
    private static final String KEY_PREFIX = "idempotency:";
    private static final String RESERVED = "RESERVED";

    private final StringRedisTemplate redis;
    private final IdempotencyKeyRepository repo;

    public IdempotencyService(StringRedisTemplate redis, IdempotencyKeyRepository repo) {
        this.redis = redis;
        this.repo = repo;
    }

    /**
     * Atomically checks if the idempotency key already exists for this tenant.
     *
     * Returns Optional.empty() when the key was NEWLY reserved (first-time call — caller should proceed).
     * Returns Optional.of(CachedResponse) when the key already exists (duplicate call — caller must return cached response).
     *
     * Uses Redis NX+EX setIfAbsent for atomic reservation. Falls back to PostgreSQL when Redis is unavailable.
     */
    @Transactional
    public Optional<CachedResponse> checkAndReserve(Long tenantId, String idempotencyKey) {
        String redisKey = KEY_PREFIX + tenantId + ":" + idempotencyKey;

        try {
            Boolean wasAbsent = redis.opsForValue().setIfAbsent(redisKey, RESERVED, TTL);

            if (Boolean.FALSE.equals(wasAbsent)) {
                // Key already exists — return cached response if available
                String stored = redis.opsForValue().get(redisKey);
                if (stored != null && !RESERVED.equals(stored)) {
                    return Optional.of(CachedResponse.fromJson(stored));
                }
                // Still RESERVED (in-flight) — return empty with no body yet
                return Optional.of(new CachedResponse(0, RESERVED));
            }

            // Boolean.TRUE or null (key was absent) — successfully reserved
            return Optional.empty();

        } catch (Exception e) {
            log.warn("Redis unavailable for idempotency check",
                kv("operation", "idempotency_check"),
                kv("status", "REDIS_UNAVAILABLE"),
                e);
            return fallbackToPostgres(tenantId, idempotencyKey);
        }
    }

    /**
     * Stores the response for this idempotency key after successful processing.
     *
     * Ordering contract (IDEM-01): Postgres UPSERT executes FIRST. Only if the
     * Postgres write succeeds is Redis updated. A Postgres failure propagates to
     * the caller and Redis is never touched, so a retry falls through to the
     * Postgres fallback path.
     *
     * Concurrency contract (IDEM-02): the Postgres write is a single atomic
     * INSERT ... ON CONFLICT ... DO UPDATE — no TOCTOU find+save race. Concurrent
     * store() calls for the same (tenantId, idempotencyKey) produce exactly one
     * DB row.
     *
     * Redis failure is tolerated: Redis is a performance cache, not the source
     * of truth. On Redis failure the next request falls through to
     * fallbackToPostgres() and serves the durable record.
     */
    @Transactional
    public void store(Long tenantId, String idempotencyKey, int httpStatus, String responseBody) {
        Instant now = Instant.now();

        // Step 1: Postgres FIRST — durable, atomic, concurrency-safe.
        // A failure here propagates; Redis is never touched.
        repo.upsert(
            TSID.fast().toLong(),
            tenantId,
            idempotencyKey,
            httpStatus,
            responseBody,
            now.plus(TTL),
            now
        );

        // Step 2: Redis SECOND — best-effort cache update. Only reached on Postgres success.
        String redisKey = KEY_PREFIX + tenantId + ":" + idempotencyKey;
        try {
            redis.opsForValue().set(redisKey, CachedResponse.toJson(httpStatus, responseBody), TTL);
        } catch (Exception e) {
            log.warn("Redis unavailable for idempotency store",
                kv("operation", "idempotency_store"),
                kv("status", "REDIS_UNAVAILABLE"),
                e);
            // Tolerated — Postgres is authoritative; checkAndReserve() fallback will serve from DB.
        }
    }

    private Optional<CachedResponse> fallbackToPostgres(Long tenantId, String idempotencyKey) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(TTL);
        long tsid = TSID.fast().toLong();

        // Atomically attempt to reserve in the DB.
        // If it succeeds (affectedRows == 1), we are the first thread to use this key.
        int affectedRows = repo.reserve(tsid, tenantId, idempotencyKey, expiresAt, now);

        if (affectedRows == 1) {
            return Optional.empty(); // NEW reservation, proceed
        }

        // If it fails (affectedRows == 0), the key already exists (either finished or in-flight).
        return repo.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey)
                .map(key -> {
                    if (key.getHttpStatus() != null && key.getResponseBody() != null) {
                        return new CachedResponse(key.getHttpStatus(), key.getResponseBody());
                    }
                    // No status/body yet -> another thread is currently processing it (RESERVED)
                    return new CachedResponse(0, RESERVED);
                });
    }

}
