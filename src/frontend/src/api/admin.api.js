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
}
