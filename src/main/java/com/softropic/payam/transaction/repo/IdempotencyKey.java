package com.softropic.payam.transaction.repo;

import com.softropic.payam.common.persistence.BaseEntity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * JPA entity mapping the main.idempotency_key table (created in V2 migration).
 * Extends BaseEntity only — the V2 DDL has no audit columns (no status, created_by, etc.)
 * It is a simple lookup table keyed by (tenant_id, idempotency_key).
 */
@Entity
@Table(name = "idempotency_key", schema = "main")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class IdempotencyKey extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "created_date", nullable = false, updatable = false)
    private Instant createdDate;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
