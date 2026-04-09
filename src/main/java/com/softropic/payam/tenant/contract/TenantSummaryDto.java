package com.softropic.payam.tenant.contract;

import java.time.Instant;

public record TenantSummaryDto(Long id, String tenantRef, String name, TenantStatus tenantStatus, String email, Instant createdAt) {}
