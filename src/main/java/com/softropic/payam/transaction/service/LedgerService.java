package com.softropic.payam.transaction.service;

import com.softropic.payam.transaction.contract.LedgerDirection;
import com.softropic.payam.transaction.repo.LedgerEntry;
import com.softropic.payam.transaction.repo.LedgerEntryRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class LedgerService {

    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerService(LedgerEntryRepository ledgerEntryRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    /**
     * Posts a balanced double-entry pair for a money-moving event.
     * Always inserts exactly 2 rows (DEBIT + CREDIT) sharing the same entryGroupId,
     * within the same @Transactional context.
     */
    @Transactional
    public void postEntry(String transactionId, Long tenantId,
                          BigDecimal amount, String currency) {
        String groupId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        LedgerEntry debit = LedgerEntry.builder()
                .transactionId(transactionId)
                .entryGroupId(groupId)
                .tenantId(tenantId)
                .direction(LedgerDirection.DEBIT)
                .accountCode("CUSTOMER_WALLET")
                .amount(amount)
                .currency(currency)
                .createdDate(now)
                .build();

        LedgerEntry credit = LedgerEntry.builder()
                .transactionId(transactionId)
                .entryGroupId(groupId)
                .tenantId(tenantId)
                .direction(LedgerDirection.CREDIT)
                .accountCode("PROVIDER_CLEARING")
                .amount(amount)
                .currency(currency)
                .createdDate(now)
                .build();

        ledgerEntryRepository.saveAll(List.of(debit, credit));
    }
}
