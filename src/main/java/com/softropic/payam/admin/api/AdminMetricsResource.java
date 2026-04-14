package com.softropic.payam.admin.api;

import com.softropic.payam.admin.service.PaymentMetricsService;
import com.softropic.payam.security.common.util.SecurityConstants;

import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Admin live metrics SSE endpoint.
 *
 * <p>GET /v1/admin/metrics/stream opens an SSE connection and registers the emitter with
 * PaymentMetricsService. Metric snapshots are pushed every 5 seconds by the @Scheduled
 * pushMetrics() method — NOT from this request handler thread — to avoid race conditions.
 *
 * <p>Protected by JWT + ROLE_ADMIN via @PreAuthorize at class level.
 * Excluded from the API-key filter chain by the existing NegatedRequestMatcher in
 * TenantSecurityConfig (covers all /v1/admin/** routes).
 */
@Observed(name = "http.admin.metrics")
@RestController
@RequestMapping("/v1/admin/metrics")
@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
@RequiredArgsConstructor
public class AdminMetricsResource {

    private final PaymentMetricsService metricsService;

    /**
     * Open a Server-Sent Events stream for live payment metrics.
     *
     * <p>Returns a text/event-stream response. The emitter is registered with
     * PaymentMetricsService which pushes MetricsSnapshotDto JSON every 5 seconds.
     * SseEmitter is constructed with Long.MAX_VALUE timeout; the application.yaml setting
     * spring.mvc.async.request-timeout=-1 prevents Tomcat from dropping the connection.
     *
     * @return SseEmitter — Spring writes the text/event-stream response headers and keeps
     *         the connection open until the client disconnects or the emitter completes.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        metricsService.addEmitter(emitter);
        return emitter;
    }
}
