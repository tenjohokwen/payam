package com.softropic.payam.platform.admin.contract;

import java.util.List;

/**
 * Summary of a full hash-chain audit across all transactions in the event log.
 *
 * <p>WARNING: iterating all transaction IDs and verifying each chain can be slow on
 * large event logs. Consider date-windowing in production (future enhancement).
 *
 * @param total      total number of distinct transactions checked
 * @param valid      number of transactions with an intact hash chain
 * @param violations list of transactionIds whose hash chain has at least one broken link
 */
public record HashChainAuditSummaryDto(int total, int valid, List<String> violations) {
}
