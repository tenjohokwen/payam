package com.softropic.payam.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties bound from the {@code payam.platform} YAML block.
 *
 * <p>Registered via {@link PlatformConfig#enablePlatformProperties()} which uses
 * {@code @EnableConfigurationProperties(PayamPlatformProperties.class)} — same pattern
 * as {@code OrangeMoneyConfig} registered by {@code OrangeConfig}.
 *
 * <p>YAML binding example:
 * <pre>
 * payam:
 *   platform:
 *     notification-email: ${PLATFORM_NOTIFICATION_EMAIL:admin@example.com}
 * </pre>
 */
@ConfigurationProperties(prefix = "payam.platform")
public class PayamPlatformProperties {

    /** Email address to notify when a platform MSISDN is updated by admin. */
    private String notificationEmail;

    public String getNotificationEmail() {
        return notificationEmail;
    }

    public void setNotificationEmail(String notificationEmail) {
        this.notificationEmail = notificationEmail;
    }
}
