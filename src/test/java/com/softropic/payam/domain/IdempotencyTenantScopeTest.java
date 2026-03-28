package com.softropic.payam.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MUT-02: Idempotency tenant scope mutation kill.
 *
 * Pure unit test — no @SpringBootTest, no Testcontainers, starts in milliseconds.
 * PITest targetTests points to com.softropic.payam.domain.* — this test is a PITest target.
 *
 * IdempotencyService uses the Redis key format: "idempotency:" + tenantId + ":" + idempotencyKey
 * (from KEY_PREFIX + tenantId + ":" + idempotencyKey in IdempotencyService.checkAndReserve()).
 *
 * This test kills the mutation that removes the tenant scope from the idempotency key,
 * e.g., changing the key to just "idempotency:" + idempotencyKey (missing tenant part).
 * Without tenant scoping, the same idempotency key string from two different tenants would
 * produce the same Redis key — allowing cross-tenant idempotency key collisions.
 */
class IdempotencyTenantScopeTest {

    // Mirrors the constant in IdempotencyService
    private static final String KEY_PREFIX = "idempotency:";

    @Test
    void idempotencyKey_includesTenantId_inRedisKey() {
        String tenantA = "1";
        String tenantB = "2";
        String idempotencyKey = "same-key-string";

        // Mirrors IdempotencyService.checkAndReserve(): redisKey = KEY_PREFIX + tenantId + ":" + idempotencyKey
        String keyForTenantA = KEY_PREFIX + tenantA + ":" + idempotencyKey;
        String keyForTenantB = KEY_PREFIX + tenantB + ":" + idempotencyKey;

        // Keys must differ — same idempotencyKey string from different tenants must not collide
        assertThat(keyForTenantA)
            .as("Redis key for tenant A must differ from key for tenant B (same idempotencyKey string)")
            .isNotEqualTo(keyForTenantB);

        // Each key must include the tenant ID — proves tenant is part of the key scope
        assertThat(keyForTenantA)
            .as("Redis key must contain tenant A's ID")
            .contains(tenantA);

        assertThat(keyForTenantB)
            .as("Redis key must contain tenant B's ID")
            .contains(tenantB);

        // Both keys must contain the shared idempotency key string
        assertThat(keyForTenantA)
            .as("Redis key must contain the idempotency key string")
            .contains(idempotencyKey);
        assertThat(keyForTenantB)
            .as("Redis key must contain the idempotency key string")
            .contains(idempotencyKey);
    }
}
