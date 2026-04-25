package com.softropic.payam.disbursement.service;

import com.softropic.payam.config.TestConfig;
import com.softropic.payam.config.TestDataCleaner;
import com.softropic.payam.transaction.contract.CachedResponse;
import com.softropic.payam.transaction.service.IdempotencyService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for DisbursementIdempotencyService proving namespace isolation
 * under a real Redis Testcontainer and real PostgreSQL Testcontainer.
 *
 * <p>Verifies SEC-01: disbursement idempotency uses the {@code "idempotency:dsb:"}
 * Redis namespace, which is distinct from the payment idempotency namespace
 * {@code "idempotency:"}, so the same key submitted for a payment AND a disbursement
 * does NOT produce a cache collision.
 */
@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
class DisbursementIdempotencyIT {

    @Autowired
    private DisbursementIdempotencyService dsbIdempotency;

    @Autowired
    private IdempotencyService paymentIdempotency;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private TestDataCleaner testDataCleaner;

    @AfterEach
    void cleanup() {
        // Flush Redis cache (idempotency keys)
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        // Wipe Postgres idempotency_key rows (and all other test data)
        testDataCleaner.wipeAll();
    }

    /**
     * Test 1: namespace_isolation — payment and disbursement services with the same tenant/key
     * reserve INDEPENDENT Redis keys because the namespaces differ.
     *
     * <p>Proves: "idempotency:<tenantId>:<key>" and "idempotency:dsb:<tenantId>:<key>" are
     * distinct Redis keys — a reservation in one namespace does NOT block the other.
     */
    @Test
    void namespace_isolation_payment_and_disbursement_with_same_key_do_not_collide() {
        Long tenantId = 99001L;
        String key = "shared-key-001";

        // Reserve via payment service first
        Optional<CachedResponse> paymentFirst = paymentIdempotency.checkAndReserve(tenantId, key);
        assertThat(paymentFirst)
            .as("Payment idempotency reservation for new key must return empty")
            .isEmpty();

        // Reserve same key via disbursement service — MUST also be empty (different namespace)
        Optional<CachedResponse> dsbFirst = dsbIdempotency.checkAndReserve(tenantId, key);
        assertThat(dsbFirst)
            .as("Disbursement idempotency reservation for same key in dsb namespace must return empty (no collision)")
            .isEmpty();

        // Verify both Redis keys exist with distinct prefixes
        String paymentRedisKey = "idempotency:" + tenantId + ":" + key;
        String dsbRedisKey = "idempotency:dsb:" + tenantId + ":" + key;

        assertThat(redis.opsForValue().get(paymentRedisKey))
            .as("Payment Redis key must exist with RESERVED value")
            .isEqualTo("RESERVED");
        assertThat(redis.opsForValue().get(dsbRedisKey))
            .as("Disbursement Redis key must exist with RESERVED value (separate namespace)")
            .isEqualTo("RESERVED");
    }

    /**
     * Test 2: duplicate_disbursement_key — submitting the same key twice returns the cached
     * response without re-processing.
     *
     * <p>Proves: store() persists the response and checkAndReserve() returns it on the second call.
     */
    @Test
    void duplicate_disbursement_key_returns_cached_response_within_24h() {
        Long tenantId = 99002L;
        String key = "dup-key-002";

        // First call — new reservation
        Optional<CachedResponse> first = dsbIdempotency.checkAndReserve(tenantId, key);
        assertThat(first)
            .as("First reservation must return empty")
            .isEmpty();

        // Store a response
        String responseJson = "{\"disbursementId\":\"dsb-abc\",\"status\":\"PROCESSING\"}";
        dsbIdempotency.store(tenantId, key, 202, responseJson);

        // Second call — must return the cached response
        Optional<CachedResponse> second = dsbIdempotency.checkAndReserve(tenantId, key);
        assertThat(second)
            .as("Second reservation for same key must return cached response")
            .isPresent();
        assertThat(second.get().httpStatus())
            .as("Cached HTTP status must match what was stored")
            .isEqualTo(202);
        assertThat(second.get().responseBody())
            .as("Cached response body must match what was stored")
            .isEqualTo(responseJson);
    }

    /**
     * Test 3: inflight_reservation — when checkAndReserve is called twice without a store(),
     * the second call sees the RESERVED sentinel and returns CachedResponse(0, "RESERVED").
     *
     * <p>Proves: in-flight detection works — the orchestrator can detect that another thread
     * is currently processing the same request.
     */
    @Test
    void inflight_reservation_returns_reserved_sentinel() {
        Long tenantId = 99003L;
        String key = "inflight-key-003";

        // First call — new reservation
        Optional<CachedResponse> first = dsbIdempotency.checkAndReserve(tenantId, key);
        assertThat(first)
            .as("First reservation must return empty")
            .isEmpty();

        // Second call without store() — should see RESERVED sentinel
        Optional<CachedResponse> second = dsbIdempotency.checkAndReserve(tenantId, key);
        assertThat(second)
            .as("Second reservation before store() must return RESERVED sentinel")
            .isPresent();
        assertThat(second.get().responseBody())
            .as("In-flight sentinel body must be 'RESERVED'")
            .isEqualTo("RESERVED");
        assertThat(second.get().httpStatus())
            .as("In-flight sentinel status must be 0")
            .isEqualTo(0);
    }
}
