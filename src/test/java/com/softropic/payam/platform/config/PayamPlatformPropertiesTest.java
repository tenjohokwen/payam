package com.softropic.payam.platform.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for PayamPlatformProperties PIN-02 property binding.
 *
 * Uses a plain unit test (setter injection) to avoid loading the full Spring context,
 * consistent with PlatformConfigServiceTest which uses @ExtendWith(MockitoExtension.class).
 */
class PayamPlatformPropertiesTest {

    @Test
    void pinEncryptionSecret_boundFromProperty() {
        PayamPlatformProperties properties = new PayamPlatformProperties();
        properties.setPinEncryptionSecret("test-secret-for-unit-test");

        assertThat(properties.getPinEncryptionSecret())
            .isEqualTo("test-secret-for-unit-test");
    }

    @Test
    void pinEncryptionSecret_nullWhenNotSet() {
        PayamPlatformProperties properties = new PayamPlatformProperties();

        assertThat(properties.getPinEncryptionSecret()).isNull();
    }

    @Test
    void notificationEmail_stillBindsCorrectly() {
        PayamPlatformProperties properties = new PayamPlatformProperties();
        properties.setNotificationEmail("test@example.com");

        assertThat(properties.getNotificationEmail())
            .isEqualTo("test@example.com");
    }
}
