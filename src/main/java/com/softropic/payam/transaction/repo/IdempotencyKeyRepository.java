package com.softropic.payam.transaction.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    Optional<IdempotencyKey> findByTenantIdAndIdempotencyKey(Long tenantId, String idempotencyKey);

    /**
     * Atomically attempts to reserve an idempotency key in the database.
     *
     * @return 1 if newly reserved, 0 if it already existed (conflict).
     */
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
}
