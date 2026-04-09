package com.softropic.payam.tenant.contract;

import java.time.Instant;

public record ApiKeySummaryDto(
    Long id,
    String keyPrefix,
    ApiKeyEnvironment environment,
    ApiKeyStatus keyStatus,
    Instant createdAt
) {}
