package com.softropic.payam.admin.service;

import com.softropic.payam.admin.contract.EventLogEntryDto;
import com.softropic.payam.admin.contract.TransactionDetailDto;
import com.softropic.payam.admin.contract.TransactionSummaryDto;
import com.softropic.payam.transaction.repo.PaymentEventLog;
import com.softropic.payam.transaction.repo.PaymentEventLogRepository;
import com.softropic.payam.transaction.repo.Transaction;
import com.softropic.payam.transaction.repo.TransactionRepository;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-only admin query service for transaction investigation.
 *
 * <p>All methods are read-only (no state transitions). Caller must hold ROLE_ADMIN
 * (enforced at the resource layer via @PreAuthorize).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminTransactionQueryService {

    private final TransactionRepository transactionRepository;
    private final PaymentEventLogRepository eventLogRepository;

    /**
     * Paginated cross-tenant search. All filter parameters are optional (null = wildcard).
     * Results ordered newest-first via ORDER BY createdDate DESC in the JPQL query.
     */
    public Page<TransactionSummaryDto> search(
            String transactionId, String traceId, String externalReference,
            Long tenantId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size); // sort handled by adminSearch query
        return transactionRepository.adminSearch(
            transactionId, traceId, externalReference, tenantId, pageable
        ).map(this::toSummary);
    }

    /**
     * Full transaction detail: summary + event timeline ordered by createdDate ASC.
     *
     * @throws EntityNotFoundException if no transaction matches the given transactionId
     */
    public TransactionDetailDto detail(String transactionId) {
        Transaction tx = transactionRepository.findByTransactionId(transactionId)
            .orElseThrow(() -> new EntityNotFoundException(
                "Transaction not found: " + transactionId));
        List<PaymentEventLog> logs = eventLogRepository
            .findByTransactionIdOrderByCreatedDateAsc(transactionId);
        return new TransactionDetailDto(
            toSummary(tx),
            logs.stream().map(this::toEventEntry).toList()
        );
    }

    private TransactionSummaryDto toSummary(Transaction t) {
        return new TransactionSummaryDto(
            t.getTransactionId(),
            t.getTraceId(),
            t.getExternalReference(),
            t.getTenantId(),
            t.getProvider().name(),
            t.getTxStatus().name(),
            t.getRiskScore(),
            t.getCreatedDate(),
            t.getLastModifiedDate()
        );
    }

    private EventLogEntryDto toEventEntry(PaymentEventLog e) {
        return new EventLogEntryDto(
            e.getEventType().name(),
            e.getStatusFrom() != null ? e.getStatusFrom().name() : null,
            e.getStatusTo().name(),
            e.getActor(),
            e.getMetadata(),
            e.getCreatedDate(),
            e.getEventHash()
        );
    }
}
