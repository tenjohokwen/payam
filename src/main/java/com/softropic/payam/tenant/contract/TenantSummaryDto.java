package com.softropic.payam.tenant.contract;

public record TenantSummaryDto(Long id, String tenantRef, String name, TenantStatus tenantStatus) {}
