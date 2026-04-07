package com.softropic.payam.tenant.contract;

public record ApiKeySummaryDto(
    Long id,
    String keyPrefix,
    ApiKeyEnvironment environment,
    ApiKeyStatus keyStatus
) {}
