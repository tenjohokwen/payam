package com.softropic.payam.tenant.service;

import com.softropic.payam.tenant.contract.ApiKeyEnvironment;
import com.softropic.payam.tenant.contract.ApiKeyStatus;
import com.softropic.payam.tenant.repo.Tenant;
import com.softropic.payam.tenant.repo.TenantApiKey;
import com.softropic.payam.tenant.repo.TenantApiKeyRepository;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import jakarta.persistence.EntityNotFoundException;


@Service
@Transactional
public class ApiKeyService {

    private static final Duration GRACE_PERIOD = Duration.ofHours(24);

    private final TenantApiKeyRepository keyRepository;

    public ApiKeyService(TenantApiKeyRepository keyRepository) {
        this.keyRepository = keyRepository;
    }

    public ApiKeyAndRawKey generateAndStore(Tenant tenant, ApiKeyEnvironment environment) {
        String rawKey = generateSecureKey();
        String hash   = DigestUtils.sha256Hex(rawKey);
        String prefix = tenant.getKeyPrefix();

        TenantApiKey entity = TenantApiKey.builder()
            .tenant(tenant)
            .keyHash(hash)
            .keyPrefix(prefix)
            .keyStatus(ApiKeyStatus.ACTIVE)
            .environment(environment)
            .build();

        TenantApiKey saved = keyRepository.save(entity);
        return new ApiKeyAndRawKey(saved, rawKey);
    }

    @Transactional(readOnly = true)
    public TenantApiKey authenticate(String rawKey) {
        String hash = DigestUtils.sha256Hex(rawKey);
        Instant graceDeadline = Instant.now().minus(GRACE_PERIOD);
        return keyRepository.findValidKeyByHash(hash, graceDeadline)
            .orElseThrow(() -> new BadCredentialsException("Invalid or expired API key"));
    }

    public ApiKeyAndRawKey rotate(Long keyId) {
        TenantApiKey old = keyRepository.findById(keyId)
            .orElseThrow(() -> new EntityNotFoundException("Key not found: " + keyId));
        old.setKeyStatus(ApiKeyStatus.ROTATED);
        old.setRotatedAt(Instant.now());
        keyRepository.save(old);
        return generateAndStore(old.getTenant(), old.getEnvironment());
    }

    public void revoke(Long keyId) {
        TenantApiKey key = keyRepository.findById(keyId)
            .orElseThrow(() -> new EntityNotFoundException("Key not found: " + keyId));
        key.setKeyStatus(ApiKeyStatus.REVOKED);
        keyRepository.save(key);
    }

    private String generateSecureKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record ApiKeyAndRawKey(TenantApiKey entity, String rawKey) {}
}
