package com.softropic.payam.platform.admin.api;

import com.softropic.payam.platform.admin.contract.HashChainAuditSummaryDto;
import com.softropic.payam.platform.admin.contract.HashChainResultDto;
import com.softropic.payam.security.common.util.SecurityConstants;
import com.softropic.payam.transaction.service.EventLogService;
import com.softropic.payam.transaction.service.EventLogService.AuditPageResult;

import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Admin endpoint for SHA-256 hash chain integrity verification.
 *
 * <p>Exposes the existing {@link EventLogService#verifyChain(String)} method via REST so
 * operators can verify that no payment event records have been tampered with after the fact.
 *
 * <p>Two endpoints:
 * <ul>
 *   <li>GET /v1/admin/audit/hash-chain/{transactionId} — verify one transaction's chain</li>
 *   <li>GET /v1/admin/audit/hash-chain — verify all transactions (may be slow on large logs)</li>
 * </ul>
 */
@Observed(name = "http.admin.audit")
@RestController
@RequestMapping("/v1/admin/audit")
@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
@RequiredArgsConstructor
public class AuditResource {

    private final EventLogService eventLogService;

    /**
     * Verifies the hash chain integrity for a single transaction.
     *
     * @param transactionId internal transaction UUID
     * @return 200 with {@code valid:true} if chain is intact;
     *         200 with {@code valid:false} if the chain has a broken link or the transaction
     *         has no events (also used for not-found — no 404 distinction to avoid leaking
     *         existence information); exception during verification returns 404 with valid:false
     */
    @GetMapping("/hash-chain/{transactionId}")
    public ResponseEntity<HashChainResultDto> verifyChain(@PathVariable String transactionId) {
        try {
            boolean valid = eventLogService.verifyChain(transactionId);
            return ResponseEntity.ok(new HashChainResultDto(transactionId, valid));
        } catch (Exception e) {
            return ResponseEntity.status(404)
                    .body(new HashChainResultDto(transactionId, false));
        }
    }

    /**
     * Verifies hash chains for all transactions in the event log.
     *
     * <p>Processes transactions in pages of 200 so that no single database query or JPA session
     * holds the full dataset in memory.  Each page runs in its own read transaction, allowing the
     * Hibernate L1 cache to be discarded between pages.
     *
     * @param from optional ISO date (e.g. 2026-01-01) — lower bound on event creation date, inclusive
     * @param to   optional ISO date (e.g. 2026-12-31) — upper bound on event creation date, inclusive
     * @return 200 with a summary: total checked, valid count, list of violating transactionIds
     */
    @GetMapping("/hash-chain")
    public ResponseEntity<HashChainAuditSummaryDto> auditAll(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        Instant fromInstant = from != null ? from.atStartOfDay(ZoneOffset.UTC).toInstant() : null;
        Instant toInstant   = to   != null ? to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant() : null;

        int total = 0, validCount = 0;
        List<String> violations = new ArrayList<>();

        int page = 0;
        AuditPageResult result;
        do {
            result = eventLogService.auditPage(fromInstant, toInstant, page++);
            total      += result.total();
            validCount += result.valid();
            violations.addAll(result.violations());
        } while (result.hasMore());

        return ResponseEntity.ok(new HashChainAuditSummaryDto(total, validCount, violations));
    }
}
