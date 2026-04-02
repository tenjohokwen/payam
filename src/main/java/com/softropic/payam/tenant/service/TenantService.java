package com.softropic.payam.tenant.service;

import com.softropic.payam.tenant.contract.ApiKeyEnvironment;
import com.softropic.payam.tenant.contract.TenantStatus;
import com.softropic.payam.tenant.repo.Tenant;
import com.softropic.payam.tenant.repo.TenantApiKey;
import com.softropic.payam.tenant.repo.TenantRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;


@Service
@Transactional
public class TenantService {

    private final TenantRepository tenantRepository;
    private final ApiKeyService apiKeyService;

    public TenantService(TenantRepository tenantRepository, ApiKeyService apiKeyService) {
        this.tenantRepository = tenantRepository;
        this.apiKeyService = apiKeyService;
    }

    static String deriveKeyPrefix(String name) {
        if (name == null || name.isBlank()) return "UNK";
        String trimmed = name.trim().toUpperCase();
        if (trimmed.length() >= 3) return trimmed.substring(0, 3);
        if (trimmed.length() == 2) return trimmed + "0";
        return trimmed + "00";
    }

    public TenantCreationResult createTenant(String name, ApiKeyEnvironment environment) {
        Tenant tenant = Tenant.builder()
            .tenantRef(UUID.randomUUID().toString())
            .name(name)
            .keyPrefix(deriveKeyPrefix(name))
            .tenantStatus(TenantStatus.ACTIVE)
            .build();
        Tenant saved = tenantRepository.save(tenant);

        ApiKeyService.ApiKeyAndRawKey keyResult =
            apiKeyService.generateAndStore(saved, environment);

        // Pass the saved TenantApiKey entity directly — do NOT use saved.getApiKeys().get(0)
        return new TenantCreationResult(saved, keyResult.entity(), keyResult.rawKey());
    }

    public record TenantCreationResult(Tenant tenant, TenantApiKey key, String rawKey) {}
}
