package com.softropic.payam.tenant.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;


public interface TenantApiKeyRepository extends JpaRepository<TenantApiKey, Long> {

    @Query("""
        SELECT k FROM TenantApiKey k JOIN FETCH k.tenant
        WHERE k.keyHash = :keyHash
          AND (k.keyStatus = 'ACTIVE'
               OR (k.keyStatus = 'ROTATED'
                   AND k.rotatedAt > :graceDeadline))
        """)
    Optional<TenantApiKey> findValidKeyByHash(
        @Param("keyHash") String keyHash,
        @Param("graceDeadline") Instant graceDeadline
    );

    List<TenantApiKey> findAllByTenantId(Long tenantId);
}
