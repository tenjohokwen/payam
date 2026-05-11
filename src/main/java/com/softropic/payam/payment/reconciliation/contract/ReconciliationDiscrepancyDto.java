package com.softropic.payam.payment.reconciliation.contract;

import com.softropic.payam.payment.reconciliation.repo.ReconciliationDiscrepancy;

import java.math.BigDecimal;

/**
 * DTO for a single discrepancy row, returned by GET /v1/admin/reconciliation/reports/{id}/discrepancies
 * and embedded in the JSON export.
 */
public record ReconciliationDiscrepancyDto(
        Long id,
        String reportDate,
        String provider,
        String payamTxId,
        String providerRef,
        String payamStatus,
        String providerStatus,
        BigDecimal payamAmount,
        BigDecimal providerAmount,
        String discrepancyType,
        String severity
) {

    public static ReconciliationDiscrepancyDto from(ReconciliationDiscrepancy entity) {
        return new ReconciliationDiscrepancyDto(
                entity.getId(),
                entity.getReportDate() != null ? entity.getReportDate().toString() : null,
                entity.getProvider() != null ? entity.getProvider().name() : null,
                entity.getPayamTxId(),
                entity.getProviderRef(),
                entity.getPayamStatus(),
                entity.getProviderStatus(),
                entity.getPayamAmount(),
                entity.getProviderAmount(),
                entity.getDiscrepancyType() != null ? entity.getDiscrepancyType().name() : null,
                entity.getSeverity() != null ? entity.getSeverity().name() : null
        );
    }
}
