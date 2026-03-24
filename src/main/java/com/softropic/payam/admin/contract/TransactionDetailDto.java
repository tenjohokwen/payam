package com.softropic.payam.admin.contract;

import java.util.List;

/**
 * Full transaction detail: summary fields + ordered event timeline.
 * Returned by GET /v1/admin/transactions/{transactionId}/events.
 */
public record TransactionDetailDto(
    TransactionSummaryDto summary,
    List<EventLogEntryDto> events
) {}
