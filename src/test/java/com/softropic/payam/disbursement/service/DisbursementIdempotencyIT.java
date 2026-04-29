package com.softropic.payam.disbursement.service;

import com.softropic.payam.config.TestConfig;
import com.softropic.payam.config.TestDataCleaner;
import com.softropic.payam.tenant.contract.ApiKeyEnvironment;
import com.softropic.payam.tenant.service.TenantService;
import com.softropic.payam.transaction.contract.CachedResponse;
import com.softropic.payam.transaction.service.IdempotencyService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

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
    private TenantService tenantService;

    @Autowired
    private TestDataCleaner testDataCleaner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long tenantId;

    @BeforeEach
    void setUp() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute(
                "INSERT INTO main.sec (id, created_by, created_date, last_modified_by, last_modified_date, " +
                "request_id, session_id, status, bus_id, value, version) " +
                "VALUES ('659287191260154475','SYSTEM_ACCOUNT','2024-12-24 06:51:55.357352'," +
                "'SYSTEM_ACCOUNT','2024-12-24 06:51:55.357352'," +
                "'bed78f34-3e09-4fa8-81db-32326a528cca', null, 'ACTIVE', 'jot'," +
                "'loiI8oT2C1tWecrNXPDjN8fveYEU8rD6nb1k1NbVy92rwdd4/KO+aHhXh3A5zjsT5eSFL/xI+9Rqyj4RI6QCiFywn5nZLIwHGPNEY0F9lnDnGGmVjv/9rO5fgGt83+cxNDyGoCePaVEpBd7xHxyDdfpAoLxQs8mhKGqcEsh09Q+26qEiEm/a9bgDSbSQ0sX00VHBLd35OLmvN+ydjEluYxBTa6KzGb2CQ6Ttg4ZaELmbZOWpEjQ1Z7BbbYiXmWyaY+2HnkyhONoGbUpvVKl1c4e9IlQzeUYkekbUbADIm2LNK9Nhfv5/L5esvFrdVOUcUpLk/y8UT9f5xOMLFJ4Ct6s0eTKvNqYkSz2DFRI8Ip4p/ns6gA4V/1MUf9GeqPUWLiOa28Vw15+R8ycUMqb8NZHOP1oj9RunhSwA7EY84bZL3+yePc3n1b8ne8xzaYVEdK1WBu3J6s2AoBaOL/JLWfu8MuxXI+ub', 'v1') " +
                "ON CONFLICT DO NOTHING");
            return null;
        });
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Dsb Idempotency Test Corp", ApiKeyEnvironment.PROD);
        tenantId = result.tenant().getId();
    }

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
