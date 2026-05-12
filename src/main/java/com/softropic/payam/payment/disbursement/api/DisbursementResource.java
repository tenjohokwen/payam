package com.softropic.payam.payment.disbursement.api;

import com.softropic.payam.infrastructure.exception.ResourceNotFoundException;
import com.softropic.payam.payment.disbursement.contract.DisbursementListItem;
import com.softropic.payam.payment.disbursement.contract.DisbursementOrchestratorError;
import com.softropic.payam.payment.disbursement.contract.DisbursementRequest;
import com.softropic.payam.payment.disbursement.contract.DisbursementResponse;
import com.softropic.payam.payment.disbursement.contract.DisbursementStatus;
import com.softropic.payam.payment.disbursement.repo.Disbursement;
import com.softropic.payam.payment.disbursement.repo.DisbursementRepository;
import com.softropic.payam.payment.disbursement.service.DisbursementOrchestrator;
import com.softropic.payam.platform.tenant.contract.TenantPrincipal;

import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * REST controller for disbursement initiation, lookup, listing, and confirmation.
 *
 * <p>All endpoints under /v1/disbursements/** are secured by TenantSecurityConfig (matches /v1/**).
 * Do NOT add any of these endpoints to AppEndpoints.PUBLIC_ENDPOINTS.
 *
 * <p>HTTP status mapping:
 * <ul>
 *   <li>202 Accepted — successful dispatch (PROCESSING), step-up gate (PENDING_CONFIRMATION),
 *       or DISBURSEMENT_ALREADY_PROCESSING replay</li>
 *   <li>404 Not Found — disbursement does not exist OR belongs to a different tenant
 *       (thrown via ResourceNotFoundException, mapped by ApiAdvice)</li>
 *   <li>422 Unprocessable Entity — INSUFFICIENT_BALANCE, RECIPIENT_NOT_FOUND, FRAUD_BLOCK,
 *       DAILY_LIMIT_EXCEEDED, INVALID_STATE, UNKNOWN_MSISDN_PREFIX</li>
 *   <li>429 Too Many Requests — VELOCITY_EXCEEDED</li>
 *   <li>502 Bad Gateway — PROVIDER_ERROR (4xx/5xx from provider)</li>
 *   <li>503 Service Unavailable — PROVIDER_UNAVAILABLE (circuit open)</li>
 * </ul>
 */
@Observed(name = "http.disbursement")
@RestController
public class DisbursementResource {

    private final DisbursementOrchestrator orchestrator;
    private final DisbursementRepository disbursementRepository;

    public DisbursementResource(DisbursementOrchestrator orchestrator,
                                DisbursementRepository disbursementRepository) {
        this.orchestrator = orchestrator;
        this.disbursementRepository = disbursementRepository;
    }

    /** DISB-01: POST /v1/disbursements */
    @PostMapping("/v1/disbursements")
    public ResponseEntity<DisbursementResponse> initiate(
            @RequestBody @Valid DisbursementRequest body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal TenantPrincipal principal) {

        // The DisbursementRequest record's idempotencyKey field is populated from the HTTP header
        // (NOT from the JSON body) — re-construct the record with the header value so the orchestrator
        // sees the canonical key. This avoids leaking the header into the request body contract.
        DisbursementRequest withKey = new DisbursementRequest(
                body.recipientMsisdn(), body.amount(), body.currency(),
                body.reference(), body.description(), body.metadata(),
                body.transactionIds(),
                idempotencyKey);

        DisbursementResponse response = orchestrator.initiate(principal.getTenantId(), withKey);

        if (response.errorCode() == null) {
            return ResponseEntity.accepted().body(response);
        }
        // DISBURSEMENT_ALREADY_PROCESSING is a replay — treat as 202 (idempotent success)
        if (DisbursementOrchestratorError.DISBURSEMENT_ALREADY_PROCESSING
                .getErrorCode().equals(response.errorCode())) {
            return ResponseEntity.accepted().body(response);
        }
        return ResponseEntity.status(resolveHttpStatus(response.errorCode())).body(response);
    }

    /** DISB-02: GET /v1/disbursements/{disbursementId} — tenant-scoped (404 if wrong tenant). */
    @GetMapping("/v1/disbursements/{disbursementId}")
    public ResponseEntity<DisbursementListItem> getById(
            @PathVariable String disbursementId,
            @AuthenticationPrincipal TenantPrincipal principal) {

        Disbursement dsb = disbursementRepository
                .findByTenantIdAndDisbursementId(principal.getTenantId(), disbursementId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Disbursement not found: " + disbursementId, "disbursement"));

        return ResponseEntity.ok(toListItem(dsb));
    }

    /** DISB-03: GET /v1/disbursements?status=...&from=...&to=...&page=0&size=20 — paginated. */
    @GetMapping("/v1/disbursements")
    public ResponseEntity<Page<DisbursementListItem>> list(
            @RequestParam(required = false) DisbursementStatus status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal TenantPrincipal principal) {

        // Cap page size to prevent abuse — same convention as AdminTransactionResource (max 100).
        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(0, page);
        Pageable pageable = PageRequest.of(safePage, safeSize);

        // Pass all optional filters as nullable Strings to avoid PostgreSQL "could not determine
        // data type" errors when null Instant/enum params are bound in native prepared statements.
        String statusName = status != null ? status.name() : null;
        String fromStr = from != null ? from.toString() : null;
        String toStr = to != null ? to.toString() : null;
        Page<Disbursement> rows = disbursementRepository
                .findForTenant(principal.getTenantId(), statusName, fromStr, toStr, pageable);

        Page<DisbursementListItem> mapped = rows.map(this::toListItem);
        return ResponseEntity.ok(mapped);
    }

    /** DISB-04: POST /v1/disbursements/{disbursementId}/confirm — only PENDING_CONFIRMATION. */
    @PostMapping("/v1/disbursements/{disbursementId}/confirm")
    public ResponseEntity<DisbursementResponse> confirm(
            @PathVariable String disbursementId,
            @AuthenticationPrincipal TenantPrincipal principal) {

        DisbursementResponse response = orchestrator.confirm(principal.getTenantId(), disbursementId);

        if (response.errorCode() == null) {
            return ResponseEntity.accepted().body(response);
        }
        return ResponseEntity.status(resolveHttpStatus(response.errorCode())).body(response);
    }

    private DisbursementListItem toListItem(Disbursement d) {
        // FEE-01: disbursements carry no fee — wallet model retired in v11 (SCHEMA-03)
        BigDecimal fee = BigDecimal.ZERO;
        Instant completedAt = (d.getDisbursementStatus() == DisbursementStatus.SUCCESS
                            || d.getDisbursementStatus() == DisbursementStatus.FAILED
                            || d.getDisbursementStatus() == DisbursementStatus.EXPIRED)
                ? d.getLastModifiedDate()
                : null;
        return new DisbursementListItem(
                d.getDisbursementId(),
                d.getDisbursementStatus().name(),
                d.getRecipientMsisdn(),
                d.getAmount(),
                fee,
                d.getCurrency(),
                d.getReference(),
                d.getProvider() != null ? d.getProvider().name() : null,
                d.getProviderRef(),
                d.getCreatedDate(),
                completedAt
        );
    }

    private HttpStatus resolveHttpStatus(String errorCode) {
        if (DisbursementOrchestratorError.PROVIDER_UNAVAILABLE.getErrorCode().equals(errorCode)) {
            return HttpStatus.SERVICE_UNAVAILABLE;   // 503
        }
        if (DisbursementOrchestratorError.PROVIDER_ERROR.getErrorCode().equals(errorCode)) {
            return HttpStatus.BAD_GATEWAY;           // 502
        }
        if (DisbursementOrchestratorError.VELOCITY_EXCEEDED.getErrorCode().equals(errorCode)) {
            return HttpStatus.TOO_MANY_REQUESTS;     // 429
        }
        // INSUFFICIENT_BALANCE, RECIPIENT_NOT_FOUND, FRAUD_BLOCK, DAILY_LIMIT_EXCEEDED,
        // INVALID_STATE, UNKNOWN_MSISDN_PREFIX
        return HttpStatus.UNPROCESSABLE_ENTITY;      // 422
    }
}
