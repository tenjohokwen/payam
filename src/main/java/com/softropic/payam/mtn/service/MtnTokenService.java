package com.softropic.payam.mtn.service;

import com.softropic.payam.mtn.infrastructure.MtnMoMoClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Supplier;

@Service
public class MtnTokenService {

    private static final Logger log = LoggerFactory.getLogger(MtnTokenService.class);
    
    private static final String COLLECTION_TOKEN_KEY = "mtn:token:coll";
    private static final String DISBURSEMENT_TOKEN_KEY = "mtn:token:disb";
    private static final String LOCK_KEY_PREFIX = "mtn:token:lock:";
    
    private static final Duration TTL      = Duration.ofMinutes(55);  // expires_in = 3600 seconds
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);

    private final StringRedisTemplate redis;
    private final MtnMoMoClient mtnMoMoClient;

    public MtnTokenService(StringRedisTemplate redis, MtnMoMoClient mtnMoMoClient) {
        this.redis = redis;
        this.mtnMoMoClient = mtnMoMoClient;
    }

    /** Returns a valid Bearer token for the MTN Collection product. */
    public String getCollectionToken() {
        return getOrFetchToken(COLLECTION_TOKEN_KEY, () -> mtnMoMoClient.fetchCollectionToken().getAccessToken());
    }

    /** Returns a valid Bearer token for the MTN Disbursement product. */
    public String getDisbursementToken() {
        return getOrFetchToken(DISBURSEMENT_TOKEN_KEY, () -> mtnMoMoClient.fetchDisbursementToken().getAccessToken());
    }

    /** Legacy method for backward compatibility - defaults to collection token */
    public String getAccessToken() {
        return getCollectionToken();
    }

    private String getOrFetchToken(String cacheKey, Supplier<String> fetcher) {
        String cached = redis.opsForValue().get(cacheKey);
        if (cached != null) return cached;

        String lockKey = LOCK_KEY_PREFIX + cacheKey;
        // Attempt to acquire soft lock (NX+EX = 10 seconds)
        Boolean locked = redis.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL);
        if (Boolean.TRUE.equals(locked)) {
            try {
                String token = fetcher.get();
                redis.opsForValue().set(cacheKey, token, TTL);
                log.info("Fetched and cached new MTN access token for key: {}", cacheKey);
                return token;
            } finally {
                redis.delete(lockKey);
            }
        } else {
            // Another node is fetching — wait 200ms and retry cache read
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            String retried = redis.opsForValue().get(cacheKey);
            if (retried != null) return retried;
            // Still not available — fetch directly (avoids indefinite wait)
            log.warn("MTN token lock contention for key: {} — fetching token directly", cacheKey);
            return fetcher.get();
        }
    }

    /** Evict both cached tokens */
    public void evict() {
        redis.delete(COLLECTION_TOKEN_KEY);
        redis.delete(DISBURSEMENT_TOKEN_KEY);
        log.info("Evicted all MTN access tokens from cache");
    }
}
