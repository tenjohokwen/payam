package com.softropic.payam.transaction.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByTransactionId(String transactionId);

    List<LedgerEntry> findByEntryGroupId(String entryGroupId);
}
