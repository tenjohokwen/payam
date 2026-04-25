package com.softropic.payam.disbursement.service;

import com.softropic.payam.transaction.contract.CachedResponse;
import com.softropic.payam.transaction.repo.IdempotencyKey;
import com.softropic.payam.transaction.repo.IdempotencyKeyRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for DisbursementIdempotencyService.
 *
 * <p>Proves:
 * <ul>
 *   <li>Namespace isolation: Redis keys use "idempotency:dsb:" prefix, NOT "idempotency:"</li>
 *   <li>Postgres-first ordering: repo.upsert() is called before redis.opsForValue().set()</li>
 *   <li>Redis failure tolerance: Redis failures do not corrupt Postgres; service stays operational</li>
 *   <li>Postgres failure propagation: repo.upsert() failures propagate; Redis is never touched</li>
 *   <li>In-flight detection: RESERVED sentinel is returned as CachedResponse(0, "RESERVED")</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DisbursementIdempotencyServiceTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private IdempotencyKeyRepository repo;

    @InjectMocks
    private DisbursementIdempotencyService service;

    private static final Long TENANT_ID = 42L;
    private static final String KEY = "test-idempotency-key-001";

    @BeforeEach
    void setUp() {
        // Stub the chained call so redis.opsForValue() returns our mock
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    // -------------------------------------------------------------------------
    // Test 1: fresh key -> returns empty + reserves under dsb namespace
    // -------------------------------------------------------------------------

    @Test
    void checkAndReserve_keyAbsent_returnsEmptyAndReservesUnderDsbPrefix() {
        // Given: key is absent in Redis (setIfAbsent returns true = was absent)
        when(valueOps.setIfAbsent(anyString(), eq("RESERVED"), eq(Duration.ofHours(24)))).thenReturn(Boolean.TRUE);

        // When
        Optional<CachedResponse> result = service.checkAndReserve(TENANT_ID, KEY);

        // Then: empty optional (new reservation — caller should proceed)
        assertThat(result).isEmpty();

        // AND: the Redis key used the dsb namespace prefix
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).setIfAbsent(keyCaptor.capture(), eq("RESERVED"), eq(Duration.ofHours(24)));
        assertThat(keyCaptor.getValue()).startsWith("idempotency:dsb:");
    }

    // -------------------------------------------------------------------------
    // Test 2: key already stored with real response -> returns CachedResponse
    // -------------------------------------------------------------------------

    @Test
    void checkAndReserve_keyExists_returnsCachedResponse() {
        // Given: key already in Redis (setIfAbsent returns false = was present)
        String storedJson = CachedResponse.toJson(202, "{\"disbursementId\":\"dsb-123\"}");
        when(valueOps.setIfAbsent(anyString(), eq("RESERVED"), any(Duration.class))).thenReturn(Boolean.FALSE);
        when(valueOps.get(anyString())).thenReturn(storedJson);

        // When
        Optional<CachedResponse> result = service.checkAndReserve(TENANT_ID, KEY);

        // Then: cached response returned
        assertThat(result).isPresent();
        assertThat(result.get().httpStatus()).isEqualTo(202);
        assertThat(result.get().responseBody()).isEqualTo("{\"disbursementId\":\"dsb-123\"}");
    }

    // -------------------------------------------------------------------------
    // Test 3: in-flight reservation -> returns sentinel CachedResponse(0, "RESERVED")
    // -------------------------------------------------------------------------

    @Test
    void checkAndReserve_inFlightReservation_returnsReservedSentinel() {
        // Given: key present in Redis with literal "RESERVED" value (another thread is processing)
        when(valueOps.setIfAbsent(anyString(), eq("RESERVED"), any(Duration.class))).thenReturn(Boolean.FALSE);
        when(valueOps.get(anyString())).thenReturn("RESERVED");

        // When
        Optional<CachedResponse> result = service.checkAndReserve(TENANT_ID, KEY);

        // Then: sentinel returned (status=0, body="RESERVED")
        assertThat(result).isPresent();
        assertThat(result.get().httpStatus()).isEqualTo(0);
        assertThat(result.get().responseBody()).isEqualTo("RESERVED");
    }

    // -------------------------------------------------------------------------
    // Test 4: Redis failure -> falls back to Postgres reserve
    // -------------------------------------------------------------------------

    @Test
    void checkAndReserve_redisFailure_fallsBackToPostgresWithDsbNamespace() {
        // Given: Redis throws
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
            .thenThrow(new RuntimeException("Redis connection refused"));
        // And: Postgres reserve succeeds (affectedRows = 1 -> new reservation)
        when(repo.reserve(anyLong(), eq(TENANT_ID), eq(KEY), any(), any())).thenReturn(1);

        // When
        Optional<CachedResponse> result = service.checkAndReserve(TENANT_ID, KEY);

        // Then: empty optional returned (new reservation via Postgres fallback)
        assertThat(result).isEmpty();
        verify(repo).reserve(anyLong(), eq(TENANT_ID), eq(KEY), any(), any());
    }

    // -------------------------------------------------------------------------
    // Test 5: store() -> Postgres called BEFORE Redis (ordering contract IDEM-01)
    // -------------------------------------------------------------------------

    @Test
    void store_writesPostgresFirstThenRedis() {
        // When
        service.store(TENANT_ID, KEY, 202, "{\"disbursementId\":\"dsb-456\"}");

        // Then: InOrder enforces Postgres-first
        InOrder order = inOrder(repo, redis);
        order.verify(repo).upsert(anyLong(), eq(TENANT_ID), eq(KEY), eq(202),
                eq("{\"disbursementId\":\"dsb-456\"}"), any(), any());
        order.verify(redis).opsForValue();
    }

    // -------------------------------------------------------------------------
    // Test 6: Postgres failure -> exception propagates, Redis never touched
    // -------------------------------------------------------------------------

    @Test
    void store_postgresFailure_neverTouchesRedis() {
        // Given: Postgres upsert throws
        doThrow(new RuntimeException("DB error"))
            .when(repo).upsert(anyLong(), anyLong(), anyString(), anyInt(), anyString(), any(), any());

        // When/Then: exception propagates
        assertThatThrownBy(() -> service.store(TENANT_ID, KEY, 202, "{}"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("DB error");

        // AND: Redis was never called for set (opsForValue may not have been called for this path)
        verify(redis, never()).opsForValue();
    }

    // -------------------------------------------------------------------------
    // Test 7: Redis failure on store -> does NOT throw (Postgres is authoritative)
    // -------------------------------------------------------------------------

    @Test
    void store_redisFailure_doesNotThrow() {
        // Given: Postgres succeeds, Redis throws on set
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(Boolean.TRUE); // not called in store
        doThrow(new RuntimeException("Redis down")).when(valueOps).set(anyString(), anyString(), any(Duration.class));

        // When/Then: method completes normally (no exception propagated)
        service.store(TENANT_ID, KEY, 202, "{\"disbursementId\":\"dsb-789\"}");

        // Verify Postgres was still called
        verify(repo).upsert(anyLong(), eq(TENANT_ID), eq(KEY), eq(202),
                eq("{\"disbursementId\":\"dsb-789\"}"), any(), any());
    }

    // -------------------------------------------------------------------------
    // Test 8: KEY_PREFIX is literally "idempotency:dsb:" (not "idempotency:")
    // -------------------------------------------------------------------------

    @Test
    void keyPrefix_isLiterallyIdempotencyDsb() {
        // Given: fresh key
        when(valueOps.setIfAbsent(anyString(), eq("RESERVED"), eq(Duration.ofHours(24)))).thenReturn(Boolean.TRUE);

        // When
        service.checkAndReserve(TENANT_ID, KEY);

        // Then: capture the key and assert it starts with the dsb prefix
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).setIfAbsent(keyCaptor.capture(), eq("RESERVED"), eq(Duration.ofHours(24)));
        String redisKey = keyCaptor.getValue();

        assertThat(redisKey)
            .as("Redis key must use 'idempotency:dsb:' namespace, not 'idempotency:'")
            .startsWith("idempotency:dsb:")
            .doesNotMatch("^idempotency:[^d].*")   // must NOT be "idempotency:<tenantId>:..."
            .contains("idempotency:dsb:" + TENANT_ID + ":" + KEY);
    }
}
