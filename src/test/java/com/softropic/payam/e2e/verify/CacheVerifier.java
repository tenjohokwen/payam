package com.softropic.payam.e2e.verify;

import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VERIF-09: Asserts Redis key presence/absence for idempotency, OAuth2 tokens, and velocity buckets.
 *
 * Redis key patterns (matched exactly to production services):
 *   Idempotency: "idempotency:{tenantId}:{idempotencyKey}"  (IdempotencyService)
 *   MTN token:   "mtn:token:cm"                             (MtnTokenService)
 *   Orange token: "orange:token:cm"                         (OrangeTokenService)
 *   Velocity:    "fraud:velocity:{signalName}:{identifier}" (VelocityCheckService — byte[] key)
 *
 * Note on velocity buckets: Bucket4j stores buckets as opaque byte[] values using LettuceBasedProxyManager.
 * The value format is internal binary — do NOT attempt to read the value via StringRedisTemplate.
 * Only key presence/absence is assertable.
 */
public class CacheVerifier {

    private final StringRedisTemplate redis;

    public CacheVerifier(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Asserts that the idempotency Redis key exists for the given tenant and key.
     * Key pattern: "idempotency:{tenantId}:{idempotencyKey}"
     */
    public void assertIdempotencyKeyPresent(Long tenantId, String idempotencyKey) {
        String key = "idempotency:" + tenantId + ":" + idempotencyKey;
        assertThat(redis.hasKey(key))
            .as("Redis key '%s' must be present", key)
            .isEqualTo(Boolean.TRUE);
    }

    /**
     * Asserts that the idempotency Redis key does NOT exist for the given tenant and key.
     * Key pattern: "idempotency:{tenantId}:{idempotencyKey}"
     */
    public void assertIdempotencyKeyAbsent(Long tenantId, String idempotencyKey) {
        String key = "idempotency:" + tenantId + ":" + idempotencyKey;
        assertThat(redis.hasKey(key))
            .as("Redis key '%s' must be absent", key)
            .isNotEqualTo(Boolean.TRUE);
    }

    /**
     * Asserts the MTN OAuth2 token for Collection is cached in Redis.
     * Key: "mtn:token:coll"
     */
    public void assertMtnCollectionTokenCached() {
        String key = "mtn:token:coll";
        assertThat(redis.hasKey(key))
            .as("Redis key '%s' (MTN Collection OAuth2 token) must be present", key)
            .isEqualTo(Boolean.TRUE);
    }

    /**
     * Asserts the MTN OAuth2 token for Disbursement is cached in Redis.
     * Key: "mtn:token:disb"
     */
    public void assertMtnDisbursementTokenCached() {
        String key = "mtn:token:disb";
        assertThat(redis.hasKey(key))
            .as("Redis key '%s' (MTN Disbursement OAuth2 token) must be present", key)
            .isEqualTo(Boolean.TRUE);
    }

    /**
     * Legacy method for backward compatibility.
     */
    public void assertMtnTokenCached() {
        assertMtnCollectionTokenCached();
    }

    /**
     * Asserts the Orange OAuth2 token is cached in Redis.
     * Key: "orange:token:cm" (from OrangeTokenService.TOKEN_KEY)
     */
    public void assertOrangeTokenCached() {
        String key = "orange:token:cm";
        assertThat(redis.hasKey(key))
            .as("Redis key '%s' (Orange OAuth2 token) must be present", key)
            .isEqualTo(Boolean.TRUE);
    }

    /**
     * Asserts that a Bucket4j velocity bucket key exists in Redis for the given signal and identifier.
     *
     * Key pattern: "fraud:velocity:{signalName}:{identifier}"
     * Example: assertVelocityBucketExists("IP_VELOCITY", "192.168.1.1")
     *
     * The value stored by VelocityCheckService is opaque binary (Bucket4j internal format).
     * This method only checks key presence — do NOT attempt to read the value.
     */
    public void assertVelocityBucketExists(String signalName, String identifier) {
        String key = "fraud:velocity:" + signalName + ":" + identifier;
        assertThat(redis.hasKey(key))
            .as("Redis velocity bucket key '%s' must be present", key)
            .isEqualTo(Boolean.TRUE);
    }

    /**
     * Asserts that the idempotency key for the given tenant does not appear under a different tenant's
     * namespace — cross-context pollution check.
     *
     * @param wrongTenantId  the tenant that must NOT have this key
     * @param idempotencyKey the key to check
     */
    public void assertNoCrossContextPollution(Long wrongTenantId, String idempotencyKey) {
        assertIdempotencyKeyAbsent(wrongTenantId, idempotencyKey);
    }
}
