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
 *     pin-encryption-secret: ${PLATFORM_PIN_ENCRYPTION_SECRET:}
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

    /**
     * AES256 encryption secret for provider PINs.
     * Bound from {@code payam.platform.pin-encryption-secret}.
     * Set via {@code PLATFORM_PIN_ENCRYPTION_SECRET} environment variable.
     * Phase 42 validates non-blank before constructing the encryptor.
     */
    private String pinEncryptionSecret;

    public String getPinEncryptionSecret() {
        return pinEncryptionSecret;
    }

    public void setPinEncryptionSecret(String pinEncryptionSecret) {
        this.pinEncryptionSecret = pinEncryptionSecret;
    }
}
