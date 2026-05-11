package com.softropic.payam.payment.reconciliation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.payam.payment.reconciliation.contract.ReconciliationDiscrepancyDto;
import com.softropic.payam.payment.reconciliation.repo.ReconciliationDiscrepancy;
import com.softropic.payam.payment.reconciliation.repo.ReconciliationReport;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Generates CSV and JSON exports from reconciliation discrepancy rows.
 *
 * <p>Used by ReconciliationResource GET /reports/{id}/export endpoint.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationExportService {

    private static final String CSV_HEADER =
            "reportDate,provider,payamTxId,providerRef,payamStatus,providerStatus,payamAmount,providerAmount,discrepancyType,severity";

    private final ObjectMapper objectMapper;

    /**
     * Generates a UTF-8 CSV byte array for the given report and discrepancies.
     *
     * <p>Header row is always present. Null field values are rendered as empty string.
     * Field values containing commas are wrapped in double quotes.
     */
    public byte[] toCsv(ReconciliationReport report, List<ReconciliationDiscrepancy> discrepancies) {
        StringBuilder sb = new StringBuilder();
        sb.append(CSV_HEADER).append('\n');

        for (ReconciliationDiscrepancy d : discrepancies) {
            sb.append(escape(d.getReportDate() != null ? d.getReportDate().toString() : "")).append(',');
            sb.append(escape(d.getProvider() != null ? d.getProvider().name() : "")).append(',');
            sb.append(escape(d.getPayamTxId())).append(',');
            sb.append(escape(d.getProviderRef())).append(',');
            sb.append(escape(d.getPayamStatus())).append(',');
            sb.append(escape(d.getProviderStatus())).append(',');
            sb.append(escape(d.getPayamAmount() != null ? d.getPayamAmount().toPlainString() : "")).append(',');
            sb.append(escape(d.getProviderAmount() != null ? d.getProviderAmount().toPlainString() : "")).append(',');
            sb.append(escape(d.getDiscrepancyType() != null ? d.getDiscrepancyType().name() : "")).append(',');
            sb.append(escape(d.getSeverity() != null ? d.getSeverity().name() : "")).append('\n');
        }

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Generates a UTF-8 JSON byte array with reportDate, provider, summary, and discrepancies array.
     */
    public byte[] toJson(ReconciliationReport report, List<ReconciliationDiscrepancy> discrepancies) {
        try {
            Map<String, Object> payload = Map.of(
                    "reportDate", report.getReportDate().toString(),
                    "provider", report.getProvider().name(),
                    "summary", Map.of(
                            "totalChecked", report.getTotalChecked(),
                            "totalMatched", report.getTotalMatched(),
                            "totalDiscrepancies", report.getTotalDiscrepancies()
                    ),
                    "discrepancies", discrepancies.stream().map(ReconciliationDiscrepancyDto::from).toList()
            );
            return objectMapper.writeValueAsBytes(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize reconciliation report to JSON", e);
        }
    }

    /**
     * Wraps a field value in double quotes if it contains a comma. Null is rendered as empty string.
     */
    private String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",")) {
            return "\"" + value + "\"";
        }
        return value;
    }
}
