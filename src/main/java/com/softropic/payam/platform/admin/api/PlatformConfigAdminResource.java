package com.softropic.payam.platform.admin.api;

import com.softropic.payam.platform.admin.contract.PinDto;
import com.softropic.payam.platform.admin.contract.PlatformConfigDto;
import com.softropic.payam.platform.admin.service.PlatformConfigService;
import com.softropic.payam.platform.security.common.util.SecurityConstants;

import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin endpoints for viewing and updating platform-level configuration
 * (MSISDN + optional encrypted PIN per provider).
 *
 * <p>All routes require JWT authentication with ROLE_ADMIN or ROLE_LTD_ADMIN (enforced via
 * {@code @PreAuthorize} at class level). Non-admin callers receive HTTP 403.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /v1/admin/platform-config}                  — list all provider configs (ORANGE + MTN)</li>
 *   <li>{@code GET  /v1/admin/platform-config/{provider}}       — single provider config with {@code pinConfigured} (PIN-04)</li>
 *   <li>{@code PUT  /v1/admin/platform-config/{provider}}       — update MSISDN and optionally the PIN (PIN-03)</li>
 *   <li>{@code GET  /v1/admin/platform-config/{provider}/pin}   — reveal the decrypted PIN; 404 when none set (PIN-05)</li>
 * </ul>
 *
 * <p>Validation errors on the PUT body (e.g., non-alphanumeric or out-of-range PIN length)
 * surface as HTTP 400 via {@code ApiAdvice}'s {@code MethodArgumentNotValidException}
 * handler.
 */
@Observed(name = "http.admin.platform-config")
@RestController
@RequestMapping("/v1/admin/platform-config")
@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
@RequiredArgsConstructor
public class PlatformConfigAdminResource {

    private final PlatformConfigService platformConfigService;

    /**
     * List all platform configs.
     *
     * @return HTTP 200 with a list of {@link PlatformConfigDto}; one entry per provider
     */
    @GetMapping
    public ResponseEntity<List<PlatformConfigDto>> findAll() {
        return ResponseEntity.ok(platformConfigService.findAll());
    }

    /**
     * Get the configuration for a single provider (PIN-04).
     * Response includes {@code pinConfigured: boolean} but never the PIN value itself.
     *
     * @param provider the provider key from the URL path (e.g. "ORANGE" or "MTN")
     * @return HTTP 200 with the {@link PlatformConfigDto}
     */
    @GetMapping("/{provider}")
    public ResponseEntity<PlatformConfigDto> findByProvider(@PathVariable String provider) {
        return ResponseEntity.ok(platformConfigService.findByProvider(provider));
    }

    /**
     * Update the platform MSISDN and optionally the PIN for the given provider (PIN-03).
     *
     * <p>The {@code pin} field on the request body is optional. When non-blank it must
     * match the {@code @Pattern} on {@link PlatformConfigDto#pin()} (alphanumeric 4–8
     * chars); otherwise HTTP 400 is returned. When null or empty, the existing PIN is
     * preserved (PIN-08).
     *
     * @param provider the provider key from the URL path
     * @param dto      request body — {@code @Valid} triggers Bean Validation
     * @return HTTP 200 with the updated {@link PlatformConfigDto} ({@code pin} is omitted
     *         from the JSON response, {@code pinConfigured} reflects post-update state)
     */
    @PutMapping("/{provider}")
    public ResponseEntity<PlatformConfigDto> update(
            @PathVariable String provider,
            @Valid @RequestBody PlatformConfigDto dto) {
        return ResponseEntity.ok(platformConfigService.update(provider, dto.platformMsisdn(), dto.pin()));
    }

    /**
     * Reveal the decrypted PIN for a given provider (PIN-05).
     *
     * @param provider the provider key from the URL path
     * @return HTTP 200 with {@link PinDto} carrying the plaintext PIN;
     *         HTTP 404 when no PIN is configured;
     *         HTTP 409 when no config row exists for the provider
     */
    @GetMapping("/{provider}/pin")
    public ResponseEntity<PinDto> getPin(@PathVariable String provider) {
        return ResponseEntity.ok(platformConfigService.findPinByProvider(provider));
    }
}
