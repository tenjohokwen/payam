package com.softropic.payam.transaction;

import com.softropic.payam.config.TestConfig;
import com.softropic.payam.tenant.service.TenantService;
import com.softropic.payam.transaction.contract.CachedResponse;
import com.softropic.payam.transaction.repo.IdempotencyKey;
import com.softropic.payam.transaction.repo.IdempotencyKeyRepository;
import com.softropic.payam.transaction.service.IdempotencyService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"ledger.database.spy=true", "enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
class IdempotencyServiceIT {

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long tenantId;

    @BeforeEach
    void setUp() {
        // Seed JWT secret required by SecurityAdviceFilter
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

        // Create tenant for all tests
        TenantService.TenantCreationResult tenantResult =
            tenantService.createTenant("Idempotency Test Corp", "LIVE");
        tenantId = tenantResult.tenant().getId();

        // Flush Redis keys for this tenant to ensure clean state
        var keys = stringRedisTemplate.keys("idempotency:" + tenantId + ":*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM main.idempotency_key");
            jdbcTemplate.execute("DELETE FROM main.tenant_api_key");
            jdbcTemplate.execute("DELETE FROM main.tenant");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
        // Clean up all test Redis keys
        var keys = stringRedisTemplate.keys("idempotency:*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    // -------------------------------------------------------------------------
    // Test 1: New key — returns empty Optional and sets RESERVED in Redis
    // -------------------------------------------------------------------------
    @Test
    void checkAndReserve_newKey_returnsEmpty_andSetsReservedInRedis() {
        Optional<CachedResponse> result = idempotencyService.checkAndReserve(tenantId, "key-001");

        assertThat(result).isEmpty();

        String redisValue = stringRedisTemplate.opsForValue().get("idempotency:" + tenantId + ":key-001");
        assertThat(redisValue).isEqualTo("RESERVED");
    }

    // -------------------------------------------------------------------------
    // Test 2: store then checkAndReserve returns the cached response
    // -------------------------------------------------------------------------
    @Test
    void store_thenCheckAndReserve_returnsCachedResponse() {
        // First call — reserves the key
        idempotencyService.checkAndReserve(tenantId, "key-002");

        // Store the actual response
        idempotencyService.store(tenantId, "key-002", 201, "{\"id\":\"abc\"}");

        // Second call — should return the cached response
        Optional<CachedResponse> result = idempotencyService.checkAndReserve(tenantId, "key-002");

        assertThat(result).isPresent();
        assertThat(result.get().httpStatus()).isEqualTo(201);
        assertThat(result.get().responseBody()).isEqualTo("{\"id\":\"abc\"}");
    }

    // -------------------------------------------------------------------------
    // Test 3: Redis failure — falls back to PostgreSQL
    // -------------------------------------------------------------------------
    @Test
    void checkAndReserve_whenRedisFails_fallsBackToPostgres() {
        // Pre-seed an idempotency_key row directly in PostgreSQL via the JPA repository
        transactionTemplate.execute(status -> {
            Instant now = Instant.now();
            IdempotencyKey keyRow = IdempotencyKey.builder()
                .tenantId(tenantId)
                .idempotencyKey("key-003")
                .responseBody("{\"id\":\"xyz\"}")
                .httpStatus(200)
                .createdDate(now)
                .expiresAt(now.plusSeconds(86400))
                .build();
            idempotencyKeyRepository.save(keyRow);
            return null;
        });

        // Build a broken RedisConnectionFactory that simulates Redis being down
        RedisConnectionFactory brokenFactory = mock(RedisConnectionFactory.class);
        when(brokenFactory.getConnection())
            .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("Redis unavailable"));

        StringRedisTemplate brokenRedis = new StringRedisTemplate(brokenFactory);

        // Extract the real repository from the autowired service via reflection
        IdempotencyKeyRepository realRepo =
            (IdempotencyKeyRepository) ReflectionTestUtils.getField(idempotencyService, "repo");

        // Create a fresh service instance with broken Redis but real Postgres repo
        IdempotencyService serviceWithBrokenRedis = new IdempotencyService(brokenRedis, realRepo);

        Optional<CachedResponse> result = serviceWithBrokenRedis.checkAndReserve(tenantId, "key-003");

        assertThat(result).isPresent();
        assertThat(result.get().httpStatus()).isEqualTo(200);
        assertThat(result.get().responseBody()).isEqualTo("{\"id\":\"xyz\"}");
    }
}
