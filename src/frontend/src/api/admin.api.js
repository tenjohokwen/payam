import { api } from 'src/boot/axios'

export const adminApi = {
  /**
   * Search transactions with optional filters.
   * Phone number searches use the externalReference param.
   */
  searchTransactions(params = {}) {
    // params: { transactionId, traceId, externalReference, tenantId, page, size }
    return api.get('/v1/admin/transactions', { params })
  },

  /**
   * Get transaction detail with full event timeline.
   */
  getTransactionDetail(transactionId) {
    return api.get(`/v1/admin/transactions/${transactionId}/events`)
  },

  /**
   * List past reconciliation runs (paginated).
   * params: { page, size }
   */
  listReconciliationReports(params = {}) {
    return api.get('/v1/admin/reconciliation/reports', { params })
  },

  /**
   * Get all discrepancy rows for a specific reconciliation run.
   */
  getReconciliationDiscrepancies(reportId) {
    return api.get(`/v1/admin/reconciliation/reports/${reportId}/discrepancies`)
  },

  /**
   * Download the reconciliation report as a CSV or JSON file.
   * format: 'csv' | 'json'
   */
  exportReconciliationReport(reportId, format = 'csv') {
    return api.get(`/v1/admin/reconciliation/reports/${reportId}/export`, {
      params: { format },
      responseType: 'blob',
    })
  },
}
