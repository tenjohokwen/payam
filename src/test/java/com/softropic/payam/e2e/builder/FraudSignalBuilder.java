package com.softropic.payam.e2e.builder;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fluent builder for fraud context test data (BUILD-06).
 *
 * <p>Produces a {@code Map<String, String>} with {@code sourceIp} and {@code deviceFingerprint}
 * keys. Tests apply the deviceFingerprint to {@link PaymentRequestBuilder} and the sourceIp
 * to the HTTP request header (e.g., X-Forwarded-For).
 *
 * <p>Also supports pre-seeding velocity counter state by deleting Bucket4j Redis keys
 * via {@link #resetVelocityCounters(StringRedisTemplate)} — call this in {@code @BeforeEach}
 * to ensure a fresh bucket at the configured limit regardless of prior test state.
 *
 * <p>Usage:
 * <pre>
 *     FraudSignalBuilder fraud = new FraudSignalBuilder()
 *         .withSourceIp("10.0.0.1")
 *         .withVelocityCounterReset("IP_VELOCITY", "10.0.0.1");
 *
 *     fraud.resetVelocityCounters(redis);  // in @BeforeEach
 *     Map<String, String> context = fraud.build();
 * </pre>
 */
public class FraudSignalBuilder {

    private String sourceIp = "192.168.1.1";
    private String deviceFingerprint = "test-device-fp-001";
    private final List<String[]> velocityResets = new ArrayList<>();

    public FraudSignalBuilder withSourceIp(String sourceIp) {
        this.sourceIp = sourceIp;
        return this;
    }

    public FraudSignalBuilder withDeviceFingerprint(String deviceFingerprint) {
        this.deviceFingerprint = deviceFingerprint;
        return this;
    }

    /**
     * Registers a velocity counter reset for the given (signalName, identifier) pair.
     * The reset is applied by calling {@link #resetVelocityCounters(StringRedisTemplate)}.
     *
     * <p>Signal names are the string values of {@code FraudSignal} enum entries
     * (e.g., "IP_VELOCITY", "MSISDN_VELOCITY", "APP_VELOCITY", "MSISDN_HOUSEHOLD").
     */
    public FraudSignalBuilder withVelocityCounterReset(String signalName, String identifier) {
        velocityResets.add(new String[]{signalName, identifier});
        return this;
    }

    /**
     * Deletes Bucket4j velocity counter keys from Redis for all registered resets.
     *
     * <p>Key format: {@code fraud:velocity:{signalName}:{identifier}}
     * This forces Bucket4j to create a fresh bucket at the configured limit on the next check.
     *
     * <p>Typically called in {@code @BeforeEach} after {@code redis.execute(redisCommands -> {
     * redisCommands.flushdb(); return null; })}, though {@code flushDb} already clears all keys
     * including velocity buckets. This method is useful for targeted resets mid-test.
     */
    public void resetVelocityCounters(StringRedisTemplate redis) {
        for (String[] entry : velocityResets) {
            String key = "fraud:velocity:" + entry[0] + ":" + entry[1];
            redis.delete(key);
        }
    }

    /**
     * Builds and returns a fraud context map with {@code sourceIp} and
     * {@code deviceFingerprint} keys. Tests use the deviceFingerprint with
     * {@link PaymentRequestBuilder#withDeviceFingerprint(String)} and the sourceIp
     * in the HTTP header (e.g., {@code X-Forwarded-For}).
     */
    public Map<String, String> build() {
        Map<String, String> context = new LinkedHashMap<>();
        context.put("sourceIp", sourceIp);
        context.put("deviceFingerprint", deviceFingerprint);
        return context;
    }
}
