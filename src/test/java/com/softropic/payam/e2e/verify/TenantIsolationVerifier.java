package com.softropic.payam.e2e.verify;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VERIF-08: Asserts zero-row isolation across all tables for a given wrong tenant.
 * Verifies that payment data for one tenant does not leak into another tenant's namespace.
 */
public class TenantIsolationVerifier {

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public TenantIsolationVerifier(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    /**
     * Asserts that no data for the given transactionId/idempotencyKey is visible under wrongTenantId
     * across all payment tables and Redis key namespaces.
     *
     * @param transactionId   the payment transactionId to check
     * @param idempotencyKey  the idempotency key to check
     * @param correctTenantId the tenant that owns this payment (unused in assertions but documents intent)
     * @param wrongTenantId   the tenant that must NOT see any of this data
     */
    public void assertNoDataLeaksToOtherTenant(String transactionId, String idempotencyKey,
                                                Long correctTenantId, Long wrongTenantId) {
        // main.transaction
        Integer txCount = jdbc.queryForObject(
            "SELECT count(*) FROM main.transaction WHERE transaction_id = ? AND tenant_id = ?",
            Integer.class, transactionId, wrongTenantId);
        assertThat(txCount)
            .as("main.transaction rows visible to wrongTenantId=%s for transactionId=%s",
                wrongTenantId, transactionId)
            .isEqualTo(0);

        // main.ledger_entry
        Integer ledgerCount = jdbc.queryForObject(
            "SELECT count(*) FROM main.ledger_entry WHERE transaction_id = ? AND tenant_id = ?",
            Integer.class, transactionId, wrongTenantId);
        assertThat(ledgerCount)
            .as("main.ledger_entry rows visible to wrongTenantId=%s for transactionId=%s",
                wrongTenantId, transactionId)
            .isEqualTo(0);

        // main.idempotency_key
        Integer idempotencyCount = jdbc.queryForObject(
            "SELECT count(*) FROM main.idempotency_key WHERE tenant_id = ? AND idempotency_key = ?",
            Integer.class, wrongTenantId, idempotencyKey);
        assertThat(idempotencyCount)
            .as("main.idempotency_key rows for wrongTenantId=%s idempotencyKey=%s",
                wrongTenantId, idempotencyKey)
            .isEqualTo(0);

        // main.webhook_delivery_log
        Integer webhookCount = jdbc.queryForObject(
            "SELECT count(*) FROM main.webhook_delivery_log WHERE transaction_id = ? AND tenant_id = ?",
            Integer.class, transactionId, wrongTenantId);
        assertThat(webhookCount)
            .as("main.webhook_delivery_log rows visible to wrongTenantId=%s for transactionId=%s",
                wrongTenantId, transactionId)
            .isEqualTo(0);

        // Redis idempotency key namespace
        String wrongRedisKey = "idempotency:" + wrongTenantId + ":" + idempotencyKey;
        Boolean redisKeyExists = redis.hasKey(wrongRedisKey);
        assertThat(redisKeyExists)
            .as("Redis idempotency key %s must not exist for wrongTenantId=%s",
                wrongRedisKey, wrongTenantId)
            .isNotEqualTo(Boolean.TRUE);
    }
}
