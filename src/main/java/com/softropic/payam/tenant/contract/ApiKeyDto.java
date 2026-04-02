package com.softropic.payam.tenant.contract;

public record ApiKeyDto(
    Long id,
    String keyPrefix,
    ApiKeyEnvironment environment,
    String rawKey   // NON-NULL only on creation/rotation — never stored, shown once
) {}
