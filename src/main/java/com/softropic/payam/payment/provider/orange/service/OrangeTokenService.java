package com.softropic.payam.payment.provider.orange.service;

import com.softropic.payam.payment.provider.orange.infrastructure.OrangeMoneyClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class OrangeTokenService {

    private static final Logger log = LoggerFactory.getLogger(OrangeTokenService.class);
    private static final String TOKEN_KEY = "orange:token:cm";
    private static final String LOCK_KEY = "orange:token:lock";
    private static final Duration TTL = Duration.ofMinutes(55);
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);

    private final StringRedisTemplate redis;
    private final OrangeMoneyClient orangeMoneyClient;

    public OrangeTokenService(StringRedisTemplate redis, OrangeMoneyClient orangeMoneyClient) {
        this.redis = redis;
        this.orangeMoneyClient = orangeMoneyClient;
    }

    /**
     * Returns a valid Bearer token. Returns from Redis cache if still fresh.
     * On cache miss: acquires a soft NX lock, fetches a new token, stores it with 55-min TTL.
     * Prevents multi-node race: second thread waits 200ms and re-reads.
     */
    public String getAccessToken() {
        String cached = redis.opsForValue().get(TOKEN_KEY);
        if (cached != null) return cached;

        // Attempt to acquire soft lock (NX+EX = 10 seconds)
        Boolean locked = redis.opsForValue().setIfAbsent(LOCK_KEY, "1", LOCK_TTL);
        if (Boolean.TRUE.equals(locked)) {
            try {
                String token = orangeMoneyClient.fetchToken().getAccessToken();
                redis.opsForValue().set(TOKEN_KEY, token, TTL);
                log.info("Fetched and cached new Orange access token");
                return token;
            } finally {
                redis.delete(LOCK_KEY);
            }
        } else {
            // Another node is fetching — wait 200ms and retry cache read
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            String retried = redis.opsForValue().get(TOKEN_KEY);
            if (retried != null) return retried;
            // Still not available — fetch directly (avoids indefinite wait)
            log.warn("Orange token lock contention — fetching token directly");
            return orangeMoneyClient.fetchToken().getAccessToken();
        }
    }

    /** Evict the cached token (call on 401 to force refresh on next request) */
    public void evict() {
        redis.delete(TOKEN_KEY);
        log.info("Evicted Orange access token from cache");
    }
}
