package com.softropic.payam.tenant.service;

import com.softropic.payam.tenant.contract.ApiKeyEnvironment;
import com.softropic.payam.tenant.contract.TenantStatus;
import com.softropic.payam.tenant.repo.Tenant;
import com.softropic.payam.tenant.repo.TenantApiKey;
import com.softropic.payam.tenant.repo.TenantApiKeyRepository;
import com.softropic.payam.tenant.repo.TenantRepository;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;


@Service
@Transactional
public class TenantService {

    private final TenantRepository tenantRepository;
    private final ApiKeyService apiKeyService;
    private final TenantApiKeyRepository keyRepository;

    public TenantService(TenantRepository tenantRepository, ApiKeyService apiKeyService,
                         TenantApiKeyRepository keyRepository) {
        this.tenantRepository = tenantRepository;
        this.apiKeyService = apiKeyService;
        this.keyRepository = keyRepository;
    }

    static String deriveKeyPrefix(String name) {
        if (name == null || name.isBlank()) return "UNK";
        String trimmed = name.trim().toUpperCase();
        if (trimmed.length() >= 3) return trimmed.substring(0, 3);
        if (trimmed.length() == 2) return trimmed + "0";
        return trimmed + "00";
    }

    private Tenant findTenantOrThrow(String tenantRef) {
        return tenantRepository.findByTenantRef(tenantRef)
            .orElseThrow(() -> new EntityNotFoundException("Tenant not found: " + tenantRef));
    }

    public TenantCreationResult createTenant(String name, ApiKeyEnvironment environment) {
        Tenant tenant = Tenant.builder()
            .tenantRef(UUID.randomUUID().toString())
            .name(name)
            .keyPrefix(deriveKeyPrefix(name))
            .tenantStatus(TenantStatus.ACTIVE)
            .webhookSecret(UUID.randomUUID().toString())
            .build();
        Tenant saved = tenantRepository.save(tenant);

        ApiKeyService.ApiKeyAndRawKey keyResult =
            apiKeyService.generateAndStore(saved, environment);

        // Pass the saved TenantApiKey entity directly — do NOT use saved.getApiKeys().get(0)
        return new TenantCreationResult(saved, keyResult.entity(), keyResult.rawKey());
    }

    public void updateName(String tenantRef, String name) {
        Tenant tenant = findTenantOrThrow(tenantRef);
        tenant.setName(name);
        tenantRepository.save(tenant);
    }

    public void updateEmail(String tenantRef, String email) {
        Tenant tenant = findTenantOrThrow(tenantRef);
        tenant.setEmail(email);
        tenantRepository.save(tenant);
    }

    public void updateWebhookUrl(String tenantRef, String webhookUrl) {
        Tenant tenant = findTenantOrThrow(tenantRef);
        tenant.setWebhookUrl(webhookUrl);
        tenantRepository.save(tenant);
    }

    public void suspend(String tenantRef) {
        Tenant tenant = findTenantOrThrow(tenantRef);
        tenant.setTenantStatus(TenantStatus.SUSPENDED);
        tenantRepository.save(tenant);
        keyRepository.revokeAllActiveAndRotatedByTenantId(tenant.getId());
    }

    public ApiKeyService.ApiKeyAndRawKey reactivate(String tenantRef) {
        Tenant tenant = findTenantOrThrow(tenantRef);
        tenant.setTenantStatus(TenantStatus.ACTIVE);
        tenantRepository.save(tenant);
        return apiKeyService.generateAndStore(tenant, ApiKeyEnvironment.PROD);
    }

    public String regenerateWebhookSecret(String tenantRef) {
        Tenant tenant = findTenantOrThrow(tenantRef);
        String newSecret = UUID.randomUUID().toString();
        tenant.setWebhookSecret(newSecret);
        tenantRepository.save(tenant);
        return newSecret;
    }

    public record TenantCreationResult(Tenant tenant, TenantApiKey key, String rawKey) {}
}
