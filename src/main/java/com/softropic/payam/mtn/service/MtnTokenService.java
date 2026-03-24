package com.softropic.payam.mtn.service;

import com.softropic.payam.mtn.infrastructure.MtnMoMoClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class MtnTokenService {

    private static final Logger log = LoggerFactory.getLogger(MtnTokenService.class);
    private static final String TOKEN_KEY = "mtn:token:cm";
    private static final String LOCK_KEY  = "mtn:token:lock";
    private static final Duration TTL      = Duration.ofMinutes(55);  // expires_in = 3600 seconds
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);

    private final StringRedisTemplate redis;
    private final MtnMoMoClient mtnMoMoClient;

    public MtnTokenService(StringRedisTemplate redis, MtnMoMoClient mtnMoMoClient) {
        this.redis = redis;
        this.mtnMoMoClient = mtnMoMoClient;
    }

    /**
     * Returns a valid Bearer token for the MTN Collection product.
     * Returns from Redis cache if still fresh.
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
                String token = mtnMoMoClient.fetchCollectionToken().getAccessToken();
                redis.opsForValue().set(TOKEN_KEY, token, TTL);
                log.info("Fetched and cached new MTN access token");
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
            log.warn("MTN token lock contention — fetching token directly");
            return mtnMoMoClient.fetchCollectionToken().getAccessToken();
        }
    }

    /** Evict the cached token (call on 401 to force refresh on next request) */
    public void evict() {
        redis.delete(TOKEN_KEY);
        log.info("Evicted MTN access token from cache");
    }
}
