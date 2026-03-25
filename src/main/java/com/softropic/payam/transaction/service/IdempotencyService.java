package com.softropic.payam.transaction.service;

import com.softropic.payam.transaction.contract.CachedResponse;
import com.softropic.payam.transaction.repo.IdempotencyKey;
import com.softropic.payam.transaction.repo.IdempotencyKeyRepository;

import io.hypersistence.tsid.TSID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
            log.warn("Redis unavailable for idempotency check — falling back to PostgreSQL. key={}", redisKey, e);
            return fallbackToPostgres(tenantId, idempotencyKey);
        }
    }

    /**
     * Stores the response for this idempotency key after successful processing.
     * Replaces the RESERVED placeholder in Redis and upserts the PostgreSQL record.
     */
    @Transactional
    public void store(Long tenantId, String idempotencyKey, int httpStatus, String responseBody) {
        String redisKey = KEY_PREFIX + tenantId + ":" + idempotencyKey;

        try {
            redis.opsForValue().set(redisKey, CachedResponse.toJson(httpStatus, responseBody), TTL);
        } catch (Exception e) {
            log.warn("Redis unavailable for idempotency store — continuing with PostgreSQL only. key={}", redisKey, e);
        }

        // Upsert PostgreSQL record
        repo.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey)
                .ifPresentOrElse(
                        existing -> {
                            // Update existing record by deleting and re-saving
                            repo.delete(existing);
                            repo.save(buildIdempotencyKey(tenantId, idempotencyKey, httpStatus, responseBody));
                        },
                        () -> repo.save(buildIdempotencyKey(tenantId, idempotencyKey, httpStatus, responseBody))
                );
    }

    private Optional<CachedResponse> fallbackToPostgres(Long tenantId, String idempotencyKey) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(TTL);
        long tsid = TSID.fast().toLong();

        // Atomically attempt to reserve in the DB.
        // If it succeeds (affectedRows == 1), we are the first thread to use this key.
        int affectedRows = repo.reserve(tsid, tenantId, idempotencyKey, expiresAt, now);

        if (affectedRows == 1) {
            log.info("Successfully reserved idempotency key in PostgreSQL: tenantId={}, key={}", tenantId, idempotencyKey);
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

    private IdempotencyKey buildIdempotencyKey(Long tenantId, String idempotencyKey,
                                               int httpStatus, String responseBody) {
        Instant now = Instant.now();
        return IdempotencyKey.builder()
                .tenantId(tenantId)
                .idempotencyKey(idempotencyKey)
                .httpStatus(httpStatus)
                .responseBody(responseBody)
                .createdDate(now)
                .expiresAt(now.plus(TTL))
                .build();
    }
}
