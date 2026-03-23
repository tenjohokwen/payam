package com.softropic.payam.transaction.service;

import com.softropic.payam.transaction.contract.CachedResponse;
import com.softropic.payam.transaction.repo.IdempotencyKey;
import com.softropic.payam.transaction.repo.IdempotencyKeyRepository;

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
                            // Update existing record — direct JPQL or save via loaded entity
                            // Since IdempotencyKey has no public setters, save a rebuilt version is not ideal.
                            // For upsert, we delete and re-save with updated values.
                            repo.delete(existing);
                            repo.save(buildIdempotencyKey(tenantId, idempotencyKey, httpStatus, responseBody));
                        },
                        () -> repo.save(buildIdempotencyKey(tenantId, idempotencyKey, httpStatus, responseBody))
                );
    }

    private Optional<CachedResponse> fallbackToPostgres(Long tenantId, String idempotencyKey) {
        return repo.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey)
                .filter(key -> key.getHttpStatus() != null && key.getResponseBody() != null)
                .map(key -> new CachedResponse(key.getHttpStatus(), key.getResponseBody()));
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
