package com.softropic.payam.payment.provider.mtn.config;

import com.softropic.payam.payment.provider.mtn.infrastructure.MtnMoMoClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MtnMoMoConfig.class)
public class MtnConfig {

    @Bean
    public MtnMoMoClient mtnMoMoClient(MtnMoMoConfig config) {
        return new MtnMoMoClient(config);
    }
}
