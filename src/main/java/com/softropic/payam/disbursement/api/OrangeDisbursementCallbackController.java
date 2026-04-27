package com.softropic.payam.disbursement.api;

import com.softropic.payam.admin.service.PaymentMetricsService;
import com.softropic.payam.orange.contract.OrangeWebhookPayload;
import com.softropic.payam.orange.service.OrangeMoneyPort;

import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Handles Orange Money DISBURSEMENT callbacks (Phase 52, SEC-05).
 *
 * <p>Path: POST /v1/callbacks/orange/disbursement
 *  - X-Notif-Token header forwarded to the port for correlation against payload.notif_token.
 *
 * <p>IP whitelist enforcement is upstream via OrangeIpWhitelistInterceptor (registered for
 * this path in OrangeWebConfig — see Plan 01). Redis replay dedup happens INSIDE
 * OrangeMoneyPort.processDisbursementCallback on namespace "callbacks:dsb:".
 *
 * <p>NOT @Transactional — controller MUST return 200 immediately. State transition and
 * outbound webhook delivery are triggered via @TransactionalEventListener(AFTER_COMMIT)
 * inside the port's TransactionTemplate boundary (Pitfall 1 in 52-RESEARCH).
 *
 * <p>This endpoint is public (no JWT) — see AppEndpoints.PUBLIC_ENDPOINTS.
 */
@Observed(name = "http.orange-disbursement-callback")
@RestController
public class OrangeDisbursementCallbackController {

    private static final Logger log =
        LoggerFactory.getLogger(OrangeDisbursementCallbackController.class);

    private final OrangeMoneyPort orangeMoneyPort;
    private final PaymentMetricsService metricsService;

    public OrangeDisbursementCallbackController(OrangeMoneyPort orangeMoneyPort,
                                                PaymentMetricsService metricsService) {
        this.orangeMoneyPort = orangeMoneyPort;
        this.metricsService = metricsService;
    }

    @PostMapping("/v1/callbacks/orange/disbursement")
    public ResponseEntity<Void> handleDisbursementCallback(
            @RequestBody OrangeWebhookPayload payload,
            @RequestHeader(value = "X-Notif-Token", required = false) String notifToken) {
        metricsService.recordCallbackReceived();
        try {
            orangeMoneyPort.processDisbursementCallback(payload, notifToken);
        } catch (Exception e) {
            log.error("Orange disbursement callback processing failed",
                kv("operation", "webhook_received"),
                kv("provider", "ORANGE"),
                kv("flow", "DISBURSEMENT"),
                kv("status", "ERROR"),
                e);
            metricsService.recordCallbackFailed();
        }
        // Always return 200 so Orange does not retry (Pitfall 1 in 52-RESEARCH)
        return ResponseEntity.ok().build();
    }
}
