package com.softropic.payam.disbursement.service;

import com.softropic.payam.transaction.contract.CachedResponse;
import com.softropic.payam.transaction.repo.IdempotencyKeyRepository;

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

/**
 * Disbursement-specific idempotency service (DISB-01).
 *
 * <p>Mirrors {@code IdempotencyService} (collection path) but uses the dedicated Redis key
 * namespace {@code "idempotency:dsb:<tenantId>:<key>"} to ensure disbursement and collection
 * idempotency keys are fully isolated — a disbursement retry cannot collide with a collection
 * reservation for the same caller-supplied key string.
 *
 * <p>Postgres-first ordering contract (IDEM-01): {@code repo.upsert()} is always called before
 * any Redis write. A Redis failure on {@code store()} is tolerated — Postgres is authoritative.
 *
 * <p>NOT modified from collection {@code IdempotencyService} other than the key prefix.
 */
@Service
public class DisbursementIdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(DisbursementIdempotencyService.class);

    private static final Duration TTL = Duration.ofHours(24);
    /** Namespace prefix isolates disbursement idempotency keys from collection path. */
    static final String KEY_PREFIX = "idempotency:dsb:";
    private static final String RESERVED = "RESERVED";

    private final StringRedisTemplate redis;
    private final IdempotencyKeyRepository repo;

    public DisbursementIdempotencyService(StringRedisTemplate redis, IdempotencyKeyRepository repo) {
        this.redis = redis;
        this.repo = repo;
    }

    /**
     * Atomically checks if the idempotency key already exists for this tenant.
     *
     * <p>Returns {@link Optional#empty()} when the key was NEWLY reserved (first-time call —
     * caller should proceed with the disbursement).
     * Returns {@link Optional#of(CachedResponse)} when the key already exists (duplicate call —
     * caller must return cached response without re-initiating the disbursement).
     *
     * <p>Uses Redis NX+EX {@code setIfAbsent} for atomic reservation. Falls back to PostgreSQL
     * when Redis is unavailable.
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
                // Still RESERVED (in-flight) — return sentinel
                return Optional.of(new CachedResponse(0, RESERVED));
            }

            // Boolean.TRUE or null (key was absent) — successfully reserved
            return Optional.empty();

        } catch (Exception e) {
            log.warn("Redis unavailable for disbursement idempotency check",
                kv("operation", "dsb_idempotency_check"),
                kv("status", "REDIS_UNAVAILABLE"),
                e);
            return fallbackToPostgres(tenantId, idempotencyKey);
        }
    }

    /**
     * Stores the response for this idempotency key after successful disbursement processing.
     *
     * <p>Postgres UPSERT executes FIRST (IDEM-01). Redis update follows on success. Redis
     * failures are tolerated — Postgres is authoritative; a subsequent request falls through
     * to the Postgres fallback path.
     */
    @Transactional
    public void store(Long tenantId, String idempotencyKey, int httpStatus, String responseBody) {
        Instant now = Instant.now();

        // Step 1: Postgres FIRST — durable, atomic, concurrency-safe.
        repo.upsert(
            TSID.fast().toLong(),
            tenantId,
            idempotencyKey,
            httpStatus,
            responseBody,
            now.plus(TTL),
            now
        );

        // Step 2: Redis SECOND — best-effort cache update.
        String redisKey = KEY_PREFIX + tenantId + ":" + idempotencyKey;
        try {
            redis.opsForValue().set(redisKey, CachedResponse.toJson(httpStatus, responseBody), TTL);
        } catch (Exception e) {
            log.warn("Redis unavailable for disbursement idempotency store",
                kv("operation", "dsb_idempotency_store"),
                kv("status", "REDIS_UNAVAILABLE"),
                e);
            // Tolerated — Postgres is authoritative.
        }
    }

    private Optional<CachedResponse> fallbackToPostgres(Long tenantId, String idempotencyKey) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(TTL);
        long tsid = TSID.fast().toLong();

        int affectedRows = repo.reserve(tsid, tenantId, idempotencyKey, expiresAt, now);

        if (affectedRows == 1) {
            return Optional.empty(); // NEW reservation, proceed
        }

        return repo.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey)
                .map(key -> {
                    if (key.getHttpStatus() != null && key.getResponseBody() != null) {
                        return new CachedResponse(key.getHttpStatus(), key.getResponseBody());
                    }
                    return new CachedResponse(0, RESERVED);
                });
    }
}
