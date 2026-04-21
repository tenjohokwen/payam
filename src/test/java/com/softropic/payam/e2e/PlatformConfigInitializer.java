package com.softropic.payam.e2e;

import com.softropic.payam.platform.service.PlatformConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Helper to initialize and clear PlatformConfig data for E2E tests.
 *
 * <p>Required by Orange provider for 'request to pay' endpoints which now mandate
 * a configured platform PIN and MSISDN.
 */
@Component
@RequiredArgsConstructor
public class PlatformConfigInitializer {

    private final PlatformConfigService platformConfigService;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Initializes the platform configuration with Orange-specific test data.
     */
    public void initOrange() {
        platformConfigService.update("ORANGE", "690123456", "1234");
    }

    /**
     * Initializes the platform configuration with MTN-specific test data.
     */
    public void initMtn() {
        platformConfigService.update("MTN", "670123456", "5678");
    }

    /**
     * Resets the platform configuration to its initial seeded state (empty MSISDNs, null PINs).
     *
     * <p>Directly updates the database via JdbcTemplate to bypass service-layer
     * event publishing and auditing overhead during cleanup.
     */
    public void clear() {
        jdbcTemplate.update("UPDATE main.platform_config SET platform_msisdn = '', pin = NULL");
    }
}
