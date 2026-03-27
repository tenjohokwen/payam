package com.softropic.payam.fraud.service;

import com.softropic.payam.fraud.contract.FraudSignal;

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
 * Redis-backed velocity check service using Bucket4j token-bucket algorithm.
 *
 * <p>Each (signal, identifier) pair maps to a unique Redis key. Velocity limits and window
 * durations are loaded from {@link FraudRuleCache}. Buckets survive JVM restarts and work
 * across multiple nodes — unlike in-memory alternatives.
 *
 * <p>Uses {@link LettuceBasedProxyManager} with a raw Lettuce {@link RedisClient} extracted
 * from the Spring {@link LettuceConnectionFactory}.
 */
@Service
public class VelocityCheckService {

    private final LettuceConnectionFactory lettuceConnectionFactory;
    private final FraudRuleCache fraudRuleCache;

    private ProxyManager<byte[]> proxyManager;

    public VelocityCheckService(LettuceConnectionFactory lettuceConnectionFactory,
                                FraudRuleCache fraudRuleCache) {
        this.lettuceConnectionFactory = lettuceConnectionFactory;
        this.fraudRuleCache = fraudRuleCache;
    }

    @PostConstruct
    public void init() {
        // Build a dedicated RedisClient using the host/port from the Spring LettuceConnectionFactory.
        // Using LettuceConnectionFactory.getHostName()/getPort() is safe with @ServiceConnection
        // (Testcontainer) — Spring overwrites these properties at context startup to the container's
        // dynamic port, so this works in both test and production environments.
        //
        // We intentionally create our own RedisClient rather than casting getNativeClient(),
        // because getNativeClient() may return a client configured before @ServiceConnection
        // reconfigures the host/port in test contexts.
        String host = lettuceConnectionFactory.getHostName();
        int port = lettuceConnectionFactory.getPort();
        RedisClient redisClient = RedisClient.create("redis://" + host + ":" + port);
        this.proxyManager = LettuceBasedProxyManager.builderFor(redisClient).build();
    }

    /**
     * Check velocity for the given signal and identifier.
     *
     * <p>Consumes one token from the Redis-backed bucket. Returns {@code true} if the request is
     * allowed (token consumed), {@code false} if the bucket is exhausted (velocity exceeded).
     * If no matching rule is found in the cache, allows the request (fail-open).
     *
     * @param signal     the fraud signal dimension (IP, MSISDN, APP, MSISDN_HOUSEHOLD)
     * @param identifier the key value for this dimension (e.g. IP address, MSISDN string)
     * @return true = allowed, false = velocity limit exceeded
     */
    public boolean checkVelocity(FraudSignal signal, String identifier) {
        return fraudRuleCache.findBySignalName(signal.getSignalName())
                .map(rule -> {
                    BucketConfiguration config = BucketConfiguration.builder()
                            .addLimit(io.github.bucket4j.Bandwidth.builder()
                                    .capacity(rule.getThreshold())
                                    .refillIntervally(rule.getThreshold(), Duration.ofSeconds(rule.getWindowSeconds()))
                                    .build())
                            .build();
                    byte[] key = ("fraud:velocity:" + signal.getSignalName() + ":" + identifier)
                            .getBytes(StandardCharsets.UTF_8);
                    return proxyManager.builder().build(key, () -> config).tryConsume(1);
                })
                .orElse(true); // fail-open: no rule found = allow
    }
}
