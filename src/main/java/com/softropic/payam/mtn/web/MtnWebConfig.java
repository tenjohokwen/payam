package com.softropic.payam.mtn.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the MTN IP whitelist interceptor for the callback path only.
 * Does not interfere with JWT authentication, Orange endpoints, or any other paths.
 */
@Configuration
public class MtnWebConfig implements WebMvcConfigurer {

    private final MtnIpWhitelistInterceptor mtnIpWhitelistInterceptor;

    public MtnWebConfig(MtnIpWhitelistInterceptor mtnIpWhitelistInterceptor) {
        this.mtnIpWhitelistInterceptor = mtnIpWhitelistInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(mtnIpWhitelistInterceptor)
                .addPathPatterns("/v1/callbacks/mtn");
        // Registered ONLY for the MTN callback path — does not touch JWT or other endpoints
    }
}
