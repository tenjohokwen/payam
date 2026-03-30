package com.softropic.payam.platform.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the platform module.
 *
 * <p>Registers {@link PayamPlatformProperties} as a configuration-properties bean
 * bound from the {@code payam.platform} YAML prefix. Mirrors the
 * {@code OrangeConfig} / {@code OrangeMoneyConfig} pattern used for Orange settings.
 */
@Configuration
@EnableConfigurationProperties(PayamPlatformProperties.class)
public class PlatformConfig {
}
