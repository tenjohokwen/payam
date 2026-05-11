package com.softropic.payam.payment.provider.orange.web;

import com.softropic.payam.payment.provider.orange.config.OrangeMoneyConfig;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrangeWebConfigTest {

    @Test
    void registersInterceptorForBothCallbackPaths() {
        OrangeMoneyConfig config = mock(OrangeMoneyConfig.class);
        when(config.getCallbackIpWhitelist()).thenReturn(Collections.emptyList());
        OrangeIpWhitelistInterceptor interceptor = new OrangeIpWhitelistInterceptor(config);

        OrangeWebConfig sut = new OrangeWebConfig(interceptor);

        InterceptorRegistry registry = mock(InterceptorRegistry.class);
        InterceptorRegistration registration = mock(InterceptorRegistration.class);
        when(registry.addInterceptor(any())).thenReturn(registration);
        when(registration.addPathPatterns(any(String[].class))).thenReturn(registration);

        sut.addInterceptors(registry);

        verify(registration).addPathPatterns(
            "/v1/callbacks/orange",
            "/v1/callbacks/orange/disbursement");
    }
}
