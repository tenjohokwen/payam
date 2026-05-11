package com.softropic.payam.payment.ledger.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    Optional<IdempotencyKey> findByTenantIdAndIdempotencyKey(Long tenantId, String idempotencyKey);

    /**
     * Atomically attempts to reserve an idempotency key in the database.
     *
     * @return 1 if newly reserved, 0 if it already existed (conflict).
     */
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

    /**
     * Atomically inserts or updates an idempotency key's cached response.
     *
     * On conflict on the unique constraint (tenant_id, idempotency_key), updates
     * http_status, response_body, and expires_at. Used by IdempotencyService.store()
     * to durably record the final response after successful processing — replaces
     * the previous TOCTOU find+save pattern (IDEM-02).
     *
     * @return 1 if inserted or updated.
     */
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
        @Param("id") Long id,
        @Param("tenantId") Long tenantId,
        @Param("key") String key,
        @Param("httpStatus") int httpStatus,
        @Param("responseBody") String responseBody,
        @Param("expiresAt") Instant expiresAt,
        @Param("now") Instant now);
}
