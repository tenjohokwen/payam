-- V13: Index on reconciliation_discrepancy(report_id) for fast lookup by report.
-- Used by GET /v1/admin/reconciliation/reports/{reportId}/discrepancies and export endpoints.

CREATE INDEX idx_recon_discrepancy_report_id ON main.reconciliation_discrepancy(report_id);
