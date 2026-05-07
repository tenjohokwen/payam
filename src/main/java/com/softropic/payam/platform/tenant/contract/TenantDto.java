package com.softropic.payam.platform.tenant.contract;

public record TenantDto(Long id, String tenantRef, String name, TenantStatus tenantStatus) {}
