package com.softropic.payam.payment.disbursement.service;

import com.softropic.payam.payment.disbursement.contract.exception.DailyLimitExceededException;
import com.softropic.payam.payment.disbursement.contract.exception.VelocityExceededException;

import io.github.bucket4j.BucketConfiguration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * Unit tests for DisbursementVelocityService using a package-protected seam (tryConsume).
 * No real Redis needed — the spy intercepts the single Bucket4j chained call.
 */
@ExtendWith(MockitoExtension.class)
class DisbursementVelocityServiceTest {

    private DisbursementVelocityService service;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factoryMock = mock(LettuceConnectionFactory.class);
        lenient().when(factoryMock.getHostName()).thenReturn("localhost");
        lenient().when(factoryMock.getPort()).thenReturn(6379);

        // Use spy so we can override the protected tryConsume seam
        service = spy(new DisbursementVelocityService(factoryMock));
    }

    // ─────────────────────────────── checkTenantVelocity ───────────────────────────────

    @Test
    void checkTenantVelocity_below20Minute_doesNotThrow() {
        // Both buckets allow through
        doReturn(true).when(service).tryConsume(any(byte[].class), any(BucketConfiguration.class));

        assertThatCode(() -> service.checkTenantVelocity(1L))
                .doesNotThrowAnyException();
    }

    @Test
    void checkTenantVelocity_minuteBucketExhausted_throwsVelocityExceeded() {
        // First call (minute bucket) returns false — exhausted
        doReturn(false).when(service).tryConsume(any(byte[].class), any(BucketConfiguration.class));

        assertThatThrownBy(() -> service.checkTenantVelocity(1L))
                .isInstanceOf(VelocityExceededException.class)
                .hasMessageContaining("minute");
    }

    @Test
    void checkTenantVelocity_hourBucketExhausted_throwsVelocityExceeded() {
        // Minute bucket allows (true on first call), hour bucket exhausted (false on second call)
        doReturn(true)   // minute bucket
            .doReturn(false) // hour bucket
            .when(service).tryConsume(any(byte[].class), any(BucketConfiguration.class));

        assertThatThrownBy(() -> service.checkTenantVelocity(1L))
                .isInstanceOf(VelocityExceededException.class)
                .hasMessageContaining("hour");
    }

    // ─────────────────────────────── checkMsisdnDailyLimit ─────────────────────────────

    @Test
    void checkMsisdnDailyLimit_below10_doesNotThrow() {
        doReturn(true).when(service).tryConsume(any(byte[].class), any(BucketConfiguration.class));

        assertThatCode(() -> service.checkMsisdnDailyLimit(1L, "+237690000001"))
                .doesNotThrowAnyException();
    }

    @Test
    void checkMsisdnDailyLimit_dayBucketExhausted_throwsDailyLimitExceeded() {
        doReturn(false).when(service).tryConsume(any(byte[].class), any(BucketConfiguration.class));

        assertThatThrownBy(() -> service.checkMsisdnDailyLimit(1L, "+237690000001"))
                .isInstanceOf(DailyLimitExceededException.class);
    }

    // ─────────────────────────── Key prefix correctness ────────────────────────────────

    @Test
    void bucket_keys_use_disb_velocity_prefix() {
        ArgumentCaptor<byte[]> keyCaptor = ArgumentCaptor.forClass(byte[].class);
        doReturn(true).when(service).tryConsume(keyCaptor.capture(), any(BucketConfiguration.class));

        // Call both methods to produce multiple keys
        service.checkTenantVelocity(42L);
        service.checkMsisdnDailyLimit(42L, "+237690000042");

        // Every captured key must start with "disb:velocity:"
        for (byte[] key : keyCaptor.getAllValues()) {
            String keyStr = new String(key, StandardCharsets.UTF_8);
            assertThat(keyStr).startsWith("disb:velocity:");
        }
    }
}
