package com.softropic.payam.e2e.builder;

import com.softropic.payam.common.persistence.DbUtil;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Fluent builder for creating additional API keys on an existing tenant (BUILD-02).
 *
 * <p>Useful for key-rotation and multi-key E2E scenarios. Uses the same hashing algorithm
 * ({@code DigestUtils.sha256Hex}) as {@link com.softropic.payam.security.filter.ApiKeyAuthenticationFilter}
 * so the generated key is accepted by the authentication filter.
 *
 * <p>Usage:
 * <pre>
 *     String rawKey = new ApiKeyBuilder()
 *         .forTenant(tenantId)
 *         .withEnvironment("LIVE")
 *         .create(jdbcTemplate);
 *     // rawKey is the value for the Authorization: ApiKey header
 * </pre>
 */
public class ApiKeyBuilder {

    private Long tenantId;
    private String environment = "LIVE";
    private String keyStatus = "ACTIVE";

    public ApiKeyBuilder forTenant(Long tenantId) {
        this.tenantId = tenantId;
        return this;
    }

    public ApiKeyBuilder withEnvironment(String environment) {
        this.environment = environment;
        return this;
    }

    public ApiKeyBuilder withKeyStatus(String keyStatus) {
        this.keyStatus = keyStatus;
        return this;
    }

    /**
     * Inserts a new API key row for the configured tenant and returns the raw (unhashed) key.
     *
     * <p>The raw key is a random UUID string — opaque credentials are intentionally non-deterministic.
     * The hash stored in the database uses {@code DigestUtils.sha256Hex} matching the production filter.
     * The row ID is generated via {@link DbUtil#generateDbRandom()} (TSID) matching JPA entity generation.
     *
     * @param jdbc JdbcTemplate connected to the test database
     * @return raw API key string for use in {@code Authorization: ApiKey <rawKey>} headers
     */
    public String create(JdbcTemplate jdbc) {
        if (tenantId == null) {
            throw new IllegalStateException("tenantId is required — call forTenant(Long) before create()");
        }
        String rawKey = UUID.randomUUID().toString();
        String keyHash = DigestUtils.sha256Hex(rawKey);
        String keyPrefix = rawKey.substring(0, 8);
        Long id = DbUtil.generateDbRandom();

        jdbc.update(
            "INSERT INTO main.tenant_api_key " +
            "(id, tenant_id, key_hash, key_prefix, key_status, status, environment, created_date) " +
            "VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, NOW())",
            id, tenantId, keyHash, keyPrefix, keyStatus, environment
        );

        return rawKey;
    }
}
