package com.softropic.payam.payment.provider.mtn.web;

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
                .addPathPatterns("/v1/callbacks/mtn",
                                 "/v1/callbacks/mtn/disbursement/*");
        // Phase 52 (SEC-05): the disbursement callback path must enforce the same IP whitelist
        // as the collection path. Pattern /v1/callbacks/mtn/disbursement/* matches the
        // {ref} path variable in MtnDisbursementCallbackController.
    }
}
