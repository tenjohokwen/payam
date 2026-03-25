package com.softropic.payam.payment.api;

import com.softropic.payam.payment.contract.OrchestratorError;
import com.softropic.payam.payment.contract.PaymentRequest;
import com.softropic.payam.payment.contract.PaymentResponse;
import com.softropic.payam.payment.service.PaymentOrchestrator;
import com.softropic.payam.tenant.contract.TenantPrincipal;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for payment initiation.
 *
 * <p>POST /v1/payments is secured by TenantSecurityConfig (matches /v1/** but not /v1/account/**).
 * Do NOT add this endpoint to AppEndpoints.PUBLIC_ENDPOINTS.
 *
 * <p>HTTP status mapping:
 * <ul>
 *   <li>202 Accepted — successful provider dispatch or PAYMENT_ALREADY_PROCESSING replay</li>
 *   <li>503 Service Unavailable — PROVIDER_UNAVAILABLE (circuit open)</li>
 *   <li>422 Unprocessable Entity — SUBSCRIBER_INACTIVE, UNKNOWN_MSISDN_PREFIX, or FRAUD_BLOCKED</li>
 *   <li>502 Bad Gateway — PROVIDER_ERROR (4xx/5xx from provider)</li>
 * </ul>
 */
@RestController
public class PaymentResource {

    private final PaymentOrchestrator orchestrator;

    public PaymentResource(PaymentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * Initiate a payment for the authenticated tenant.
     *
     * @param request   validated payment request body
     * @param principal the authenticated tenant from the API key filter chain
     * @return ResponseEntity with PaymentResponse and appropriate HTTP status
     */
    @PostMapping("/v1/payments")
    public ResponseEntity<PaymentResponse> initiatePayment(
            @RequestBody @Valid PaymentRequest request,
            @AuthenticationPrincipal TenantPrincipal principal) {

        PaymentResponse response = orchestrator.initiate(principal.getTenantId(), request);

        if (response.errorCode() == null) {
            // Successful dispatch — 202 Accepted
            return ResponseEntity.accepted().body(response);
        }

        // Error responses: map error code to appropriate HTTP status
        HttpStatus httpStatus = resolveHttpStatus(response.errorCode());

        // PAYMENT_ALREADY_PROCESSING is a replay — treat as 202 (idempotent success)
        if (OrchestratorError.PAYMENT_ALREADY_PROCESSING.getErrorCode().equals(response.errorCode())) {
            return ResponseEntity.accepted().body(response);
        }

        return ResponseEntity.status(httpStatus).body(response);
    }

    private HttpStatus resolveHttpStatus(String errorCode) {
        if (OrchestratorError.PROVIDER_UNAVAILABLE.getErrorCode().equals(errorCode)) {
            return HttpStatus.SERVICE_UNAVAILABLE;   // 503
        }
        if (OrchestratorError.PROVIDER_ERROR.getErrorCode().equals(errorCode)) {
            return HttpStatus.BAD_GATEWAY;           // 502
        }
        // SUBSCRIBER_INACTIVE, UNKNOWN_MSISDN_PREFIX, FRAUD_BLOCKED, and other domain errors → 422
        return HttpStatus.UNPROCESSABLE_ENTITY;      // 422
    }
}
