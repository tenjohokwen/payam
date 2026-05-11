package com.softropic.payam.payment.reconciliation.contract;

import com.softropic.payam.payment.reconciliation.repo.ReconciliationReport;

/**
 * DTO for a reconciliation run summary, returned by GET /v1/admin/reconciliation/reports.
 */
public record ReconciliationReportDto(
        Long id,
        String reportDate,
        String provider,
        String runAt,
        int totalChecked,
        int totalMatched,
        int totalDiscrepancies,
        String status
) {

    public static ReconciliationReportDto from(ReconciliationReport entity) {
        return new ReconciliationReportDto(
                entity.getId(),
                entity.getReportDate() != null ? entity.getReportDate().toString() : null,
                entity.getProvider() != null ? entity.getProvider().name() : null,
                entity.getRunAt() != null ? entity.getRunAt().toString() : null,
                entity.getTotalChecked(),
                entity.getTotalMatched(),
                entity.getTotalDiscrepancies(),
                entity.getStatus()
        );
    }
}
