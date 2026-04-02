package com.softropic.payam.admin.api;

import com.softropic.payam.admin.contract.HashChainAuditSummaryDto;
import com.softropic.payam.admin.contract.HashChainResultDto;
import com.softropic.payam.security.common.util.SecurityConstants;
import com.softropic.payam.transaction.repo.PaymentEventLogRepository;
import com.softropic.payam.transaction.service.EventLogService;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
@RestController
@RequestMapping("/v1/admin/audit")
@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
@RequiredArgsConstructor
public class AuditResource {

    private final EventLogService eventLogService;
    private final PaymentEventLogRepository eventLogRepository;

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
     * <p>NOTE: This iterates all distinct transaction IDs and verifies each chain.
     * On large event logs this operation can be slow — consider adding date windowing
     * for production use (the {@code from} and {@code to} parameters are accepted but
     * not yet applied in this Phase 10 implementation).
     *
     * @param from optional ISO date string (e.g. 2026-01-01) — reserved for future filtering
     * @param to   optional ISO date string (e.g. 2026-12-31) — reserved for future filtering
     * @return 200 with a summary: total checked, valid count, list of violating transactionIds
     */
    @GetMapping("/hash-chain")
    @Transactional(readOnly = true)
    public ResponseEntity<HashChainAuditSummaryDto> auditAll(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {

        //TODO this method will cause memory issues when there are many transactions
        List<String> transactionIds = eventLogRepository.findAllDistinctTransactionIds();
        List<String> violations = new ArrayList<>();
        int validCount = 0;

        for (String txId : transactionIds) {
            try {
                if (eventLogService.verifyChain(txId)) {
                    validCount++;
                } else {
                    violations.add(txId);
                }
            } catch (Exception e) {
                violations.add(txId);
            }
        }

        return ResponseEntity.ok(new HashChainAuditSummaryDto(
                transactionIds.size(), validCount, violations));
    }
}
