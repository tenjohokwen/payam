package com.softropic.payam.mtn.web;

import com.softropic.payam.mtn.contract.MtnCallbackPayload;
import com.softropic.payam.mtn.service.MtnMoMoPort;

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

    private final MtnMoMoPort mtnMoMoPort;

    public MtnCallbackController(MtnMoMoPort mtnMoMoPort) {
        this.mtnMoMoPort = mtnMoMoPort;
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
        mtnMoMoPort.processCallback(payload);
        return ResponseEntity.ok().build();
    }
}
