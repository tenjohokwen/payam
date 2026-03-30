package com.softropic.payam.platform.api;

import com.softropic.payam.platform.contract.PlatformConfigDto;
import com.softropic.payam.platform.service.PlatformConfigService;
import com.softropic.payam.security.common.util.SecurityConstants;

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
 * Admin endpoints for viewing and updating platform-level configuration (MSISDNs per provider).
 *
 * <p>All routes require JWT authentication with ROLE_ADMIN or ROLE_LTD_ADMIN (enforced via
 * {@code @PreAuthorize} at class level). Non-admin callers receive HTTP 403.
 *
 * <p>GET /v1/admin/platform-config         — list all provider configs (ORANGE + MTN)
 * PUT /v1/admin/platform-config/{provider} — update the MSISDN for a specific provider
 */
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
     * Update the platform MSISDN for the given provider.
     *
     * @param provider the provider key from the URL path (e.g. "ORANGE" or "MTN")
     * @param dto      request body containing the new MSISDN
     * @return HTTP 200 with the updated {@link PlatformConfigDto}
     */
    @PutMapping("/{provider}")
    public ResponseEntity<PlatformConfigDto> update(
            @PathVariable String provider,
            @RequestBody PlatformConfigDto dto) {
        return ResponseEntity.ok(platformConfigService.update(provider, dto.platformMsisdn()));
    }
}
