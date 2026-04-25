package com.softropic.payam.disbursement.service;

import com.softropic.payam.disbursement.contract.exception.DailyLimitExceededException;
import com.softropic.payam.disbursement.contract.exception.VelocityExceededException;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;

import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Disbursement-specific velocity gates (SEC-02).
 *
 * <p>Three independent Bucket4j-on-Redis buckets, all under the "disb:velocity:" key prefix:
 * <ul>
 *   <li><b>tenant minute</b> — capacity 20, window 60s; throws VelocityExceededException ("minute") on exhaustion → HTTP 429</li>
 *   <li><b>tenant hour</b>   — capacity 200, window 3600s; throws VelocityExceededException ("hour") on exhaustion → HTTP 429</li>
 *   <li><b>msisdn day</b>    — capacity 10, window 86400s; throws DailyLimitExceededException on exhaustion → HTTP 422</li>
 * </ul>
 *
 * <p>Mirrors VelocityCheckService.init() initialization pattern. Limits are hardcoded per
 * SEC-02 spec — they are NOT loaded from FraudRule (those are collection signals).
 *
 * <p>NOT @Transactional — Redis-only operations; no DB connection held.
 */
@Service
public class DisbursementVelocityService {

    static final int TENANT_MINUTE_CAPACITY = 20;
    static final int TENANT_HOUR_CAPACITY = 200;
    static final int MSISDN_DAY_CAPACITY = 10;

    private final LettuceConnectionFactory lettuceConnectionFactory;
    private ProxyManager<byte[]> proxyManager;

    public DisbursementVelocityService(LettuceConnectionFactory lettuceConnectionFactory) {
        this.lettuceConnectionFactory = lettuceConnectionFactory;
    }

    @PostConstruct
    public void init() {
        // Build a dedicated RedisClient using host/port from Spring LettuceConnectionFactory.
        // Safe with @ServiceConnection (Testcontainer): Spring overwrites properties at context
        // startup so the dynamic container port is used in both test and production environments.
        String host = lettuceConnectionFactory.getHostName();
        int port = lettuceConnectionFactory.getPort();
        RedisClient redisClient = RedisClient.create("redis://" + host + ":" + port);
        this.proxyManager = LettuceBasedProxyManager.builderFor(redisClient).build();
    }

    /**
     * Per-tenant velocity gate. Consumes one token from the minute bucket AND one from the
     * hour bucket. Throws {@link VelocityExceededException} if either is exhausted —
     * minute bucket is checked first.
     *
     * @param tenantId the tenant to check
     * @throws VelocityExceededException if the minute (&gt;20/min) or hour (&gt;200/hr) limit is hit
     */
    public void checkTenantVelocity(Long tenantId) {
        BucketConfiguration minuteCfg = BucketConfiguration.builder()
            .addLimit(Bandwidth.builder()
                .capacity(TENANT_MINUTE_CAPACITY)
                .refillIntervally(TENANT_MINUTE_CAPACITY, Duration.ofSeconds(60))
                .build())
            .build();
        byte[] minuteKey = ("disb:velocity:tenant:minute:" + tenantId).getBytes(StandardCharsets.UTF_8);
        if (!tryConsume(minuteKey, minuteCfg)) {
            throw new VelocityExceededException(
                "Tenant minute velocity exceeded (>" + TENANT_MINUTE_CAPACITY + "/min) for tenant " + tenantId);
        }

        BucketConfiguration hourCfg = BucketConfiguration.builder()
            .addLimit(Bandwidth.builder()
                .capacity(TENANT_HOUR_CAPACITY)
                .refillIntervally(TENANT_HOUR_CAPACITY, Duration.ofSeconds(3600))
                .build())
            .build();
        byte[] hourKey = ("disb:velocity:tenant:hour:" + tenantId).getBytes(StandardCharsets.UTF_8);
        if (!tryConsume(hourKey, hourCfg)) {
            throw new VelocityExceededException(
                "Tenant hour velocity exceeded (>" + TENANT_HOUR_CAPACITY + "/hour) for tenant " + tenantId);
        }
    }

    /**
     * Per-(tenant,MSISDN) daily limit gate. Consumes one token from the day bucket.
     * Throws {@link DailyLimitExceededException} if the bucket is exhausted.
     *
     * @param tenantId        the owning tenant
     * @param recipientMsisdn the recipient MSISDN
     * @throws DailyLimitExceededException if the daily limit (&gt;10/day) is hit
     */
    public void checkMsisdnDailyLimit(Long tenantId, String recipientMsisdn) {
        BucketConfiguration dayCfg = BucketConfiguration.builder()
            .addLimit(Bandwidth.builder()
                .capacity(MSISDN_DAY_CAPACITY)
                .refillIntervally(MSISDN_DAY_CAPACITY, Duration.ofSeconds(86400))
                .build())
            .build();
        byte[] dayKey = ("disb:velocity:msisdn:day:" + tenantId + ":" + recipientMsisdn)
                .getBytes(StandardCharsets.UTF_8);
        if (!tryConsume(dayKey, dayCfg)) {
            throw new DailyLimitExceededException(
                "Daily MSISDN limit exceeded (>" + MSISDN_DAY_CAPACITY + "/day) for "
                + recipientMsisdn + " (tenant " + tenantId + ")");
        }
    }

    /**
     * Test seam: package-protected wrapper around the Bucket4j chained call so unit tests
     * can mock consumption without standing up a real Redis instance. Production behavior
     * is identical — this method just isolates the chained builder call.
     */
    protected boolean tryConsume(byte[] key, BucketConfiguration config) {
        return proxyManager.builder().build(key, () -> config).tryConsume(1);
    }
}
