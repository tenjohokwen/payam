package com.softropic.payam.platform.admin.contract;

import java.time.Instant;

/**
 * Paginated search result entry for admin transaction investigation.
 *
 * <p>Intentionally omits internal Transaction fields: payToken, payTokenIssuedAt,
 * pollAttempts, deviceFingerprint — these are implementation details not needed by
 * the investigation UI and must not be exposed via admin APIs.
 */
public record TransactionSummaryDto(
    String transactionId,
    String traceId,
    String externalReference,
    Long tenantId,
    String provider,
    String txStatus,
    Integer riskScore,
    Instant createdDate,
    Instant lastModifiedDate
) {}
