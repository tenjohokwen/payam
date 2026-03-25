package com.softropic.payam.mtn.web;

import com.softropic.payam.admin.service.PaymentMetricsService;
import com.softropic.payam.mtn.contract.MtnCallbackPayload;
import com.softropic.payam.mtn.service.MtnMoMoPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Handles MTN MoMo payment callbacks.
 *
 * CRITICAL: MTN sends HTTP PUT (not POST). A @PostMapping here would return 405 Method Not Allowed
 * and silently drop all callbacks (P1.4 production risk).
 *
 * IP whitelist is enforced upstream by MtnIpWhitelistInterceptor (preHandle).
 * This endpoint is public (no JWT required) — see AppEndpoints.PUBLIC_ENDPOINTS.
 */
@RestController
public class MtnCallbackController {

    private static final Logger log = LoggerFactory.getLogger(MtnCallbackController.class);

    private final MtnMoMoPort mtnMoMoPort;
    private final PaymentMetricsService metricsService;

    public MtnCallbackController(MtnMoMoPort mtnMoMoPort,
                                 PaymentMetricsService metricsService) {
        this.mtnMoMoPort = mtnMoMoPort;
        this.metricsService = metricsService;
    }

    /**
     * Accepts MTN PUT callback. Returns 200 immediately.
     * Correlation: payload.getExternalId() = our transactionId.
     * Phase 6 applies state transition after double-check via getTransactionStatus().
     */
    @PutMapping("/v1/callbacks/mtn")
    public ResponseEntity<Void> handleCallback(
            @RequestBody MtnCallbackPayload payload,
            HttpServletRequest request) {
        // IP whitelist is enforced upstream by MtnIpWhitelistInterceptor (preHandle)
        // Correlation: payload.getExternalId() = our transactionId
        // Phase 6 applies state transition after double-check; this plan logs + stores
        metricsService.recordCallbackReceived();
        try {
            mtnMoMoPort.processCallback(payload);
        } catch (Exception e) {
            log.warn("MTN callback processing failed: {}", e.getMessage());
            metricsService.recordCallbackFailed();
        }
        return ResponseEntity.ok().build();
    }
}
