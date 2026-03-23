package com.softropic.payam.tenant.contract;

public record TenantDto(Long id, String tenantRef, String name, TenantStatus tenantStatus) {}
