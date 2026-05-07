package com.softropic.payam.orange.web;

import com.softropic.payam.platform.admin.service.PaymentMetricsService;
import com.softropic.payam.orange.contract.OrangeWebhookPayload;
import com.softropic.payam.orange.service.OrangeMoneyPort;

import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static net.logstash.logback.argument.StructuredArguments.kv;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Handles Orange Money inbound payment callbacks.
 *
 * Security layers:
 * 1. IP whitelist — enforced upstream by OrangeIpWhitelistInterceptor (preHandle)
 * 2. Redis dedup — silently suppresses duplicate (payToken + createtime) pairs within 24h TTL
 *
 * CRITICAL (P1.1): No @Transactional — holding a DB connection during this path violates connection pool policy.
 * Return 200 to Orange as fast as possible (Pitfall 5 from research).
 *
 * This endpoint is public (no JWT required) — see AppEndpoints.PUBLIC_ENDPOINTS.
 */
@Observed(name = "http.orange-callback")
@RestController
public class OrangeCallbackController {

    private static final Logger log = LoggerFactory.getLogger(OrangeCallbackController.class);

    private final OrangeMoneyPort orangeMoneyPort;
    private final StringRedisTemplate redis;
    private final PaymentMetricsService metricsService;

    public OrangeCallbackController(OrangeMoneyPort orangeMoneyPort,
                                    StringRedisTemplate redis,
                                    PaymentMetricsService metricsService) {
        this.orangeMoneyPort = orangeMoneyPort;
        this.redis = redis;
        this.metricsService = metricsService;
    }

    /**
     * Accepts Orange POST callback. Returns 200 immediately after dedup and delegation.
     * State transition is Plan 06-02 responsibility (double-check pattern).
     */
    @PostMapping("/v1/callbacks/orange")
    public ResponseEntity<Void> handleCallback(
            @RequestBody OrangeWebhookPayload payload,
            @RequestHeader(value = "X-Notif-Token", required = false) String notifToken) {

        // 1. Dedup check — reject duplicates silently (return 200, do not reprocess)
        //    Key design: payToken + createtime to allow multiple status updates per payment (Pitfall 8)
        String payToken = payload.getPayToken() != null ? payload.getPayToken() : "";
        String createtime = payload.getCreatetime() != null ? payload.getCreatetime() : "unknown";
        String dedupKey = "webhook:orange:" + payToken + ":" + createtime;
        Boolean wasAbsent = redis.opsForValue().setIfAbsent(dedupKey, "SEEN", Duration.ofHours(24));
        if (Boolean.FALSE.equals(wasAbsent)) {
            log.info("Orange webhook duplicate suppressed",
                    kv("operation", "webhook_received"),
                    kv("provider", "ORANGE"),
                    kv("status", "DUPLICATE"));
            return ResponseEntity.ok().build();
        }

        // 2. Delegate to port — correlation + notifToken validation inside processWebhook
        metricsService.recordCallbackReceived();
        try {
            orangeMoneyPort.processWebhook(payload, notifToken);
        } catch (Exception e) {
            log.error("Orange callback processing failed",
                    kv("operation", "webhook_received"),
                    kv("provider", "ORANGE"),
                    kv("status", "ERROR"),
                    e);
            metricsService.recordCallbackFailed();
        }

        // 3. Return 200 immediately — state transition is Plan 06-02 responsibility (double-check pattern)
        return ResponseEntity.ok().build();
    }
}
