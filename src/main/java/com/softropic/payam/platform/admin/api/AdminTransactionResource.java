package com.softropic.payam.platform.admin.api;

import com.softropic.payam.platform.admin.contract.TransactionDetailDto;
import com.softropic.payam.platform.admin.contract.TransactionSummaryDto;
import com.softropic.payam.platform.admin.service.AdminTransactionQueryService;
import com.softropic.payam.platform.security.common.util.SecurityConstants;

import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin transaction investigation endpoints. All routes require JWT authentication
 * with ROLE_ADMIN or ROLE_LTD_ADMIN (enforced via @PreAuthorize at class level).
 *
 * <p>These endpoints are excluded from the API-key filter chain (TenantSecurityConfig)
 * via NegatedRequestMatcher, so they are handled exclusively by the JWT chain.
 *
 * <p>GET /v1/admin/transactions — paginated search across all tenants
 * GET /v1/admin/transactions/{transactionId}/events — full event timeline for one transaction
 */
@Observed(name = "http.admin.transactions")
@RestController
@RequestMapping("/v1/admin/transactions")
@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
@RequiredArgsConstructor
public class AdminTransactionResource {

    private final AdminTransactionQueryService queryService;

    /**
     * Search transactions with optional filters. All params are optional (null = wildcard).
     *
     * @param transactionId  exact match on internal transaction UUID
     * @param traceId        exact match on caller-supplied trace ID
     * @param externalReference exact match on phone/MSISDN or other external ref
     * @param tenantId       filter to a specific tenant (omit for cross-tenant search)
     * @param page           zero-based page index (default 0)
     * @param size           page size (default 20, max enforced by caller)
     */
    @GetMapping
    public ResponseEntity<Page<TransactionSummaryDto>> search(
            @RequestParam(required = false) String transactionId,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String externalReference,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
            queryService.search(transactionId, traceId, externalReference, tenantId, page, size)
        );
    }

    /**
     * Full detail for a single transaction: summary + event timeline ordered ASC.
     *
     * @param transactionId internal transaction UUID
     * @return 200 with TransactionDetailDto, or 404 if not found
     */
    @GetMapping("/{transactionId}/events")
    public ResponseEntity<TransactionDetailDto> detail(@PathVariable String transactionId) {
        return ResponseEntity.ok(queryService.detail(transactionId));
    }
}
