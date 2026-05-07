package com.softropic.payam.reconciliation.api;

import com.softropic.payam.reconciliation.contract.ReconciliationDiscrepancyDto;
import com.softropic.payam.reconciliation.contract.ReconciliationReportDto;
import com.softropic.payam.reconciliation.repo.ReconciliationDiscrepancy;
import com.softropic.payam.reconciliation.repo.ReconciliationDiscrepancyRepository;
import com.softropic.payam.reconciliation.repo.ReconciliationReport;
import com.softropic.payam.reconciliation.repo.ReconciliationReportRepository;
import com.softropic.payam.reconciliation.service.ReconciliationExportService;
import com.softropic.payam.platform.security.common.util.SecurityConstants;

import io.micrometer.observation.annotation.Observed;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin REST endpoints for reconciliation results.
 *
 * <p>All endpoints require JWT authentication with ROLE_ADMIN or ROLE_LTD_ADMIN.
 * They are excluded from the API-key filter chain via NegatedRequestMatcher in TenantSecurityConfig,
 * so they are handled exclusively by the JWT chain (no further security config changes needed).
 *
 * <p>GET /v1/admin/reconciliation/reports                    — paginated run history
 * GET /v1/admin/reconciliation/reports/{id}/discrepancies   — all discrepancy rows for a run
 * GET /v1/admin/reconciliation/reports/{id}/export          — CSV or JSON file download
 */
@Observed(name = "http.admin.reconciliation")
@RestController
@RequestMapping("/v1/admin/reconciliation")
@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
@RequiredArgsConstructor
public class ReconciliationResource {

    private final ReconciliationReportRepository reportRepository;
    private final ReconciliationDiscrepancyRepository discrepancyRepository;
    private final ReconciliationExportService exportService;

    /**
     * Returns a paginated list of past reconciliation runs, ordered by run_at DESC.
     *
     * @param page zero-based page index (default 0)
     * @param size page size (default 20)
     */
    @GetMapping("/reports")
    public ResponseEntity<Page<ReconciliationReportDto>> listReports(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ReconciliationReport> reports = reportRepository.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "runAt")));
        return ResponseEntity.ok(reports.map(ReconciliationReportDto::from));
    }

    /**
     * Returns all discrepancy rows for a specific reconciliation run.
     *
     * @param reportId ID of the reconciliation_report row
     */
    @GetMapping("/reports/{reportId}/discrepancies")
    public ResponseEntity<List<ReconciliationDiscrepancyDto>> listDiscrepancies(
            @PathVariable Long reportId) {
        List<ReconciliationDiscrepancy> rows = discrepancyRepository.findByReportId(reportId);
        return ResponseEntity.ok(rows.stream().map(ReconciliationDiscrepancyDto::from).toList());
    }

    /**
     * Downloads the reconciliation report as a CSV or JSON file.
     *
     * @param reportId ID of the reconciliation_report row
     * @param format   "csv" (default) or "json"
     */
    @GetMapping("/reports/{reportId}/export")
    public ResponseEntity<byte[]> export(
            @PathVariable Long reportId,
            @RequestParam(defaultValue = "csv") String format) {
        ReconciliationReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new EntityNotFoundException("Report not found: " + reportId));
        List<ReconciliationDiscrepancy> discrepancies = discrepancyRepository.findByReportId(reportId);

        if ("json".equalsIgnoreCase(format)) {
            byte[] data = exportService.toJson(report, discrepancies);
            String filename = "reconciliation-" + report.getReportDate() + "-"
                    + report.getProvider().name().toLowerCase() + ".json";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(data);
        } else {
            byte[] data = exportService.toCsv(report, discrepancies);
            String filename = "reconciliation-" + report.getReportDate() + "-"
                    + report.getProvider().name().toLowerCase() + ".csv";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .body(data);
        }
    }
}
