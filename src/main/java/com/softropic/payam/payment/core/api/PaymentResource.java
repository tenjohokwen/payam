package com.softropic.payam.payment.core.api;

import com.softropic.payam.payment.core.contract.OrchestratorError;
import com.softropic.payam.payment.core.contract.PaymentRequest;
import com.softropic.payam.payment.core.contract.PaymentResponse;
import com.softropic.payam.payment.core.service.PaymentOrchestrator;
import com.softropic.payam.platform.tenant.contract.TenantPrincipal;

import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST controller for payment initiation and lookup.
 *
 * <p>POST /v1/payments is secured by TenantSecurityConfig (matches /v1/** but not /v1/account/**).
 * Do NOT add this endpoint to AppEndpoints.PUBLIC_ENDPOINTS.
 *
 * <p>HTTP status mapping:
 * <ul>
 *   <li>200 OK — successful lookup</li>
 *   <li>202 Accepted — successful provider dispatch or PAYMENT_ALREADY_PROCESSING replay</li>
 *   <li>404 Not Found — payment not found for the tenant</li>
 *   <li>503 Service Unavailable — PROVIDER_UNAVAILABLE (circuit open)</li>
 *   <li>422 Unprocessable Entity — SUBSCRIBER_INACTIVE, UNKNOWN_MSISDN_PREFIX, or FRAUD_BLOCKED</li>
 *   <li>502 Bad Gateway — PROVIDER_ERROR (4xx/5xx from provider)</li>
 * </ul>
 */
@Observed(name = "http.payment")
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

    /**
     * Get payment status by transaction ID.
     */
    @GetMapping("/v1/payments/{transactionId}")
    public ResponseEntity<PaymentResponse> getStatus(
            @PathVariable String transactionId,
            @AuthenticationPrincipal TenantPrincipal principal) {
        return orchestrator.getStatus(principal.getTenantId(), transactionId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /**
     * Get payment status by merchant-supplied external reference.
     */
    @GetMapping("/v1/payments/reference/{externalReference}")
    public ResponseEntity<PaymentResponse> getStatusByReference(
            @PathVariable String externalReference,
            @AuthenticationPrincipal TenantPrincipal principal) {
        return orchestrator.getStatusByReference(principal.getTenantId(), externalReference)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
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
