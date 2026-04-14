package com.softropic.payam.fee.api;

import com.softropic.payam.fee.contract.FeeType;
import com.softropic.payam.fee.repo.FeeRule;
import com.softropic.payam.fee.repo.FeeRuleRepository;
import com.softropic.payam.fee.service.FeeRuleCache;
import com.softropic.payam.security.common.util.SecurityConstants;

import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;


/**
 * Admin API for managing fee rules.
 *
 * <p>All endpoints require JWT authentication with ROLE_ADMIN or ROLE_LTD_ADMIN.
 * Rules are hot-reloaded into {@link FeeRuleCache} after each write, so changes
 * take effect immediately without a JVM restart (OPS-01).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST   /v1/admin/fees         — create a new fee rule</li>
 *   <li>GET    /v1/admin/fees         — list all enabled fee rules</li>
 *   <li>PUT    /v1/admin/fees/{id}    — update an existing fee rule</li>
 * </ul>
 */
@Observed(name = "http.admin.fees")
@RestController
@RequestMapping("/v1/admin/fees")
@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
@RequiredArgsConstructor
public class FeeRuleAdminResource {

    private final FeeRuleRepository feeRuleRepository;
    private final FeeRuleCache feeRuleCache;

    /**
     * Request DTO for creating or updating a fee rule.
     *
     * @param feeType        FEE_FIXED or FEE_PERCENTAGE
     * @param fixedAmount    flat fee amount (used when feeType is FEE_FIXED)
     * @param percentageRate percentage rate as decimal (used when feeType is FEE_PERCENTAGE)
     * @param currency       ISO 4217 currency code (e.g. XAF)
     * @param ruleName       human-readable rule name
     * @param tenantId       null for global rule; non-null for tenant-specific override
     * @param description    optional description
     * @param enabled        whether the rule is active (default true)
     */
    public record FeeRuleRequest(
        String feeType,
        BigDecimal fixedAmount,
        BigDecimal percentageRate,
        String currency,
        String ruleName,
        Long tenantId,
        String description,
        boolean enabled
    ) {}

    /**
     * Create a new fee rule. Returns 201 Created with the persisted rule.
     *
     * @param request fee rule creation request
     * @return 201 with the created FeeRule entity
     */
    @PostMapping
    public ResponseEntity<FeeRule> create(@RequestBody FeeRuleRequest request) {
        FeeRule rule = FeeRule.builder()
                .feeType(FeeType.valueOf(request.feeType()))
                .fixedAmount(request.fixedAmount())
                .percentageRate(request.percentageRate())
                .currency(request.currency())
                .ruleName(request.ruleName())
                .tenantId(request.tenantId())
                .description(request.description())
                .enabled(request.enabled())
                .build();
        FeeRule saved = feeRuleRepository.save(rule);
        feeRuleCache.refresh();
        return ResponseEntity
                .created(URI.create("/v1/admin/fees/" + saved.getId()))
                .body(saved);
    }

    /**
     * List all enabled fee rules.
     *
     * @return 200 with list of enabled FeeRule entities
     */
    @GetMapping
    public ResponseEntity<List<FeeRule>> listEnabled() {
        return ResponseEntity.ok(feeRuleRepository.findAllByEnabledTrue());
    }

    /**
     * Update an existing fee rule. FeeRule is immutable (no public setters), so this
     * uses delete-then-save with a rebuilt entity, then hot-reloads the cache.
     *
     * @param id      the ID of the rule to update
     * @param request updated field values
     * @return 200 with the updated rule, or 404 if not found
     */
    @PutMapping("/{id}")
    public ResponseEntity<FeeRule> update(@PathVariable Long id,
                                          @RequestBody FeeRuleRequest request) {
        FeeRule existing = feeRuleRepository.findById(id)
                .orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }

        // Rebuild entity using SuperBuilder — copy all original fields, override mutable ones
        FeeRule updated = FeeRule.builder()
                .id(existing.getId())
                .tenantId(existing.getTenantId())
                .feeType(existing.getFeeType())
                .currency(existing.getCurrency())
                .description(existing.getDescription())
                // Override with request values
                .ruleName(request.ruleName() != null ? request.ruleName() : existing.getRuleName())
                .fixedAmount(request.fixedAmount() != null ? request.fixedAmount() : existing.getFixedAmount())
                .percentageRate(request.percentageRate() != null ? request.percentageRate() : existing.getPercentageRate())
                .enabled(request.enabled())
                .build();

        feeRuleRepository.delete(existing);
        FeeRule saved = feeRuleRepository.save(updated);
        feeRuleCache.refresh();
        return ResponseEntity.ok(saved);
    }
}
