package com.softropic.payam.reconciliation.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReconciliationDiscrepancyRepository extends JpaRepository<ReconciliationDiscrepancy, Long> {

    /**
     * Find all discrepancies for a given reconciliation report.
     * Used by admin API (Phase 09-02) to serve discrepancy details.
     */
    List<ReconciliationDiscrepancy> findByReportId(Long reportId);
}
