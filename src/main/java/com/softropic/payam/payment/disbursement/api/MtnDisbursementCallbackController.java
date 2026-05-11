package com.softropic.payam.disbursement.api;

import com.softropic.payam.platform.admin.service.PaymentMetricsService;
import com.softropic.payam.mtn.contract.MtnCallbackPayload;
import com.softropic.payam.mtn.service.MtnMoMoPort;

import io.micrometer.observation.annotation.Observed;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Handles MTN MoMo DISBURSEMENT callbacks (Phase 52, SEC-05).
 *
 * <p>Path: PUT /v1/callbacks/mtn/disbursement/{ref}
 *  - {ref} path variable carries the merchant-generated UUID stored in
 *    Disbursement.providerRef (set by MtnMoMoPort.initiateDisbursement step 3).
 *  - HTTP method MUST be PUT — MTN always uses PUT for callbacks.
 *
 * <p>IP whitelist enforcement is upstream via MtnIpWhitelistInterceptor (registered for this
 * path in MtnWebConfig — see Plan 01). Redis replay dedup happens INSIDE
 * MtnMoMoPort.processDisbursementCallback on namespace "callbacks:dsb:".
 *
 * <p>NOT @Transactional — the controller MUST return 200 immediately so the provider does
 * not retry. The state transition and outbound webhook are kicked off via
 * @TransactionalEventListener(AFTER_COMMIT) inside the port's TransactionTemplate boundary.
 *
 * <p>This endpoint is public (no JWT) — see AppEndpoints.PUBLIC_ENDPOINTS.
 */
@Observed(name = "http.mtn-disbursement-callback")
@RestController
public class MtnDisbursementCallbackController {

    private static final Logger log = LoggerFactory.getLogger(MtnDisbursementCallbackController.class);

    private final MtnMoMoPort mtnMoMoPort;
    private final PaymentMetricsService metricsService;

    public MtnDisbursementCallbackController(MtnMoMoPort mtnMoMoPort,
                                             PaymentMetricsService metricsService) {
        this.mtnMoMoPort = mtnMoMoPort;
        this.metricsService = metricsService;
    }

    @PutMapping("/v1/callbacks/mtn/disbursement/{ref}")
    public ResponseEntity<Void> handleDisbursementCallback(
            @PathVariable("ref") String providerRef,
            @RequestBody MtnCallbackPayload payload,
            HttpServletRequest request) {
        metricsService.recordCallbackReceived();
        try {
            mtnMoMoPort.processDisbursementCallback(payload, providerRef);
        } catch (Exception e) {
            log.error("MTN disbursement callback processing failed",
                kv("operation", "webhook_received"),
                kv("provider", "MTN"),
                kv("flow", "DISBURSEMENT"),
                kv("providerRef", providerRef),
                kv("status", "ERROR"),
                e);
            metricsService.recordCallbackFailed();
        }
        // Always return 200 so the provider does not retry (Pitfall 1 in 52-RESEARCH)
        return ResponseEntity.ok().build();
    }
}
