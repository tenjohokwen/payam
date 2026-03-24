package com.softropic.payam.orange.config;

import com.softropic.payam.orange.infrastructure.OrangeMoneyClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OrangeMoneyConfig.class)
public class OrangeConfig {

    @Bean
    public OrangeMoneyClient orangeMoneyClient(OrangeMoneyConfig config) {
        return new OrangeMoneyClient(config);
    }
}
