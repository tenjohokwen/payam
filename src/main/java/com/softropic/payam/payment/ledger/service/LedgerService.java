package com.softropic.payam.payment.ledger.service;

import com.softropic.payam.payment.ledger.contract.LedgerDirection;
import com.softropic.payam.payment.ledger.contract.LedgerPosting;
import com.softropic.payam.payment.ledger.repo.LedgerEntry;
import com.softropic.payam.payment.ledger.repo.LedgerEntryRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Double-entry ledger writer.
 *
 * postEntry routes on LedgerPosting.flow():
 *   COLLECTION   -> 2 entries (DEBIT CUSTOMER_WALLET + CREDIT PROVIDER_CLEARING)
 *   DISBURSEMENT -> 3 entries (DEBIT MERCHANT_WALLET gross + CREDIT CUSTOMER_WALLET principal + CREDIT PROVIDER_FEE fee)
 *
 * All account-code strings are private constants here — no external caller references them.
 * All entries in a single call share one entryGroupId, satisfying the V25 balance trigger at commit.
 */
@Service
public class LedgerService {

    private static final String CUSTOMER_WALLET = "CUSTOMER_WALLET";
    private static final String PROVIDER_CLEARING = "PROVIDER_CLEARING";
    private static final String MERCHANT_WALLET = "MERCHANT_WALLET";
    private static final String PROVIDER_FEE = "PROVIDER_FEE";

    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerService(LedgerEntryRepository ledgerEntryRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    /**
     * Posts a balanced double-entry group for a money-moving event.
     * Routes by flow to COLLECTION (2 entries) or DISBURSEMENT (3 entries).
     * All entries in the group share a single entryGroupId so the V25
     * deferrable balance trigger sees them as one unit at commit.
     */
    @Transactional
    public void postEntry(String transactionId, Long tenantId, LedgerPosting posting) {
        List<LedgerEntry> entries = switch (posting.flow()) {
            case COLLECTION   -> buildCollectionEntries(transactionId, tenantId, posting);
            case DISBURSEMENT -> buildDisbursementEntries(transactionId, tenantId, posting);
        };
        ledgerEntryRepository.saveAll(entries);
    }

    private List<LedgerEntry> buildCollectionEntries(String transactionId, Long tenantId, LedgerPosting posting) {
        String groupId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        return List.of(
            entry(transactionId, groupId, tenantId, LedgerDirection.DEBIT,
                  CUSTOMER_WALLET, posting.principal(), posting.currency(), now),
            entry(transactionId, groupId, tenantId, LedgerDirection.CREDIT,
                  PROVIDER_CLEARING, posting.principal(), posting.currency(), now)
        );
    }

    private List<LedgerEntry> buildDisbursementEntries(String transactionId, Long tenantId, LedgerPosting posting) {
        String groupId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        BigDecimal gross = posting.principal().add(posting.fee());
        return List.of(
            entry(transactionId, groupId, tenantId, LedgerDirection.DEBIT,
                  MERCHANT_WALLET, gross, posting.currency(), now),
            entry(transactionId, groupId, tenantId, LedgerDirection.CREDIT,
                  CUSTOMER_WALLET, posting.principal(), posting.currency(), now),
            entry(transactionId, groupId, tenantId, LedgerDirection.CREDIT,
                  PROVIDER_FEE, posting.fee(), posting.currency(), now)
        );
    }

    private LedgerEntry entry(String transactionId, String groupId, Long tenantId,
                              LedgerDirection direction, String accountCode,
                              BigDecimal amount, String currency, Instant now) {
        return LedgerEntry.builder()
                .transactionId(transactionId)
                .entryGroupId(groupId)
                .tenantId(tenantId)
                .direction(direction)
                .accountCode(accountCode)
                .amount(amount)
                .currency(currency)
                .createdDate(now)
                .build();
    }
}
