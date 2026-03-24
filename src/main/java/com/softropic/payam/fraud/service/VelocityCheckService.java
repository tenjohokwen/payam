package com.softropic.payam.fraud.service;

import com.softropic.payam.fraud.contract.FraudSignal;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(VelocityCheckService.class);

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
        // Extract the native RedisClient from the Spring LettuceConnectionFactory.
        // LettuceConnectionFactory.getNativeClient() returns AbstractRedisClient; cast to RedisClient.
        // LettuceBasedProxyManager.builderFor(RedisClient) manages its own byte[] connection.
        try {
            RedisClient redisClient = (RedisClient) lettuceConnectionFactory.getNativeClient();
            this.proxyManager = LettuceBasedProxyManager.builderFor(redisClient).build();
            log.info("VelocityCheckService initialized with LettuceBasedProxyManager");
        } catch (ClassCastException e) {
            // Fallback for cluster/sentinel mode: create RedisClient from host/port
            log.warn("Could not cast native client to RedisClient — falling back to standalone client construction", e);
            String host = lettuceConnectionFactory.getHostName();
            int port = lettuceConnectionFactory.getPort();
            RedisClient fallbackClient = RedisClient.create("redis://" + host + ":" + port);
            this.proxyManager = LettuceBasedProxyManager.builderFor(fallbackClient).build();
            log.info("VelocityCheckService initialized with fallback RedisClient at {}:{}", host, port);
        }
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
