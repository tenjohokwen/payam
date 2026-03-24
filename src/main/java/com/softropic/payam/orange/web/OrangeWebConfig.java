package com.softropic.payam.orange.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the Orange IP whitelist interceptor for the callback path only.
 * Does not interfere with JWT authentication, MTN endpoints, or any other paths.
 */
@Configuration
public class OrangeWebConfig implements WebMvcConfigurer {

    private final OrangeIpWhitelistInterceptor interceptor;

    public OrangeWebConfig(OrangeIpWhitelistInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor)
                .addPathPatterns("/v1/callbacks/orange");
        // Registered ONLY for the Orange callback path — does not touch JWT or other endpoints
    }
}
