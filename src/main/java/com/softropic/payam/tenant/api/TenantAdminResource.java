package com.softropic.payam.tenant.api;

import com.softropic.payam.tenant.contract.ApiKeyDto;
import com.softropic.payam.tenant.contract.TenantDto;
import com.softropic.payam.tenant.service.ApiKeyService;
import com.softropic.payam.tenant.service.TenantService;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;


@RestController
@RequestMapping("/v1/admin/tenants")
public class TenantAdminResource {

    private final TenantService tenantService;
    private final ApiKeyService apiKeyService;

    public TenantAdminResource(TenantService tenantService, ApiKeyService apiKeyService) {
        this.tenantService = tenantService;
        this.apiKeyService = apiKeyService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TenantCreationResponse createTenant(@Valid @RequestBody CreateTenantRequest request) {
        TenantService.TenantCreationResult result =
            tenantService.createTenant(request.name(), request.environment());

        TenantDto tenantDto = new TenantDto(
            result.tenant().getId(),
            result.tenant().getTenantRef(),
            result.tenant().getName(),
            result.tenant().getTenantStatus()
        );
        ApiKeyDto apiKeyDto = new ApiKeyDto(
            result.key().getId(),
            result.key().getKeyPrefix(),
            request.environment(),
            result.rawKey()   // shown exactly once — not stored
        );
        return new TenantCreationResponse(tenantDto, apiKeyDto);
    }

    @PostMapping("/{tenantId}/keys/{keyId}/rotate")
    public ApiKeyDto rotateKey(@PathVariable Long tenantId, @PathVariable Long keyId) {
        ApiKeyService.ApiKeyAndRawKey result = apiKeyService.rotate(keyId);
        return new ApiKeyDto(
            result.entity().getId(),
            result.entity().getKeyPrefix(),
            result.entity().getEnvironment(),
            result.rawKey()   // new raw key — shown once, never stored
        );
    }

    @DeleteMapping("/{tenantId}/keys/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeKey(@PathVariable Long tenantId, @PathVariable Long keyId) {
        apiKeyService.revoke(keyId);
    }

    public record CreateTenantRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Pattern(regexp = "LIVE|SANDBOX", message = "environment must be LIVE or SANDBOX") String environment
    ) {}

    public record TenantCreationResponse(TenantDto tenant, ApiKeyDto apiKey) {}
}
