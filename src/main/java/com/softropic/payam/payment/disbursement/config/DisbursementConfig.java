package com.softropic.payam.payment.disbursement.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the disbursement module.
 *
 * <p>Registers {@link DisbursementProperties} as a configuration-properties bean
 * bound from the {@code payam.disbursement} YAML prefix.
 */
@Configuration
@EnableConfigurationProperties(DisbursementProperties.class)
public class DisbursementConfig {
}
