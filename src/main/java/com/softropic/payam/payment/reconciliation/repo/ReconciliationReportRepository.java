package com.softropic.payam.payment.reconciliation.repo;

import com.softropic.payam.payment.core.contract.MobilePaymentProvider;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface ReconciliationReportRepository extends JpaRepository<ReconciliationReport, Long> {

    /**
     * Find a reconciliation report for a specific date and provider combination.
     * Used by ReconciliationService to detect and upsert existing reports.
     */
    Optional<ReconciliationReport> findByReportDateAndProvider(LocalDate reportDate, MobilePaymentProvider provider);
}
