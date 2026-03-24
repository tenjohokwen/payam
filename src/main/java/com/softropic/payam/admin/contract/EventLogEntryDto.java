package com.softropic.payam.admin.contract;

import java.time.Instant;

/**
 * Single event entry in the transaction event timeline.
 * Exposes the full audit chain fields needed for investigation.
 */
public record EventLogEntryDto(
    String eventType,
    String statusFrom,
    String statusTo,
    String actor,
    String metadata,
    Instant createdDate,
    String eventHash
) {}
