package com.softropic.payam.payment.provider.mtn.web;

import com.softropic.payam.payment.provider.mtn.config.MtnMoMoConfig;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MtnWebConfigTest {

    @Test
    void registersInterceptorForBothCallbackPaths() {
        MtnMoMoConfig config = mock(MtnMoMoConfig.class);
        when(config.getCallbackIpWhitelist()).thenReturn(Collections.emptyList());
        MtnIpWhitelistInterceptor interceptor = new MtnIpWhitelistInterceptor(config);

        MtnWebConfig sut = new MtnWebConfig(interceptor);

        InterceptorRegistry registry = mock(InterceptorRegistry.class);
        InterceptorRegistration registration = mock(InterceptorRegistration.class);
        when(registry.addInterceptor(any())).thenReturn(registration);
        when(registration.addPathPatterns(any(String[].class))).thenReturn(registration);

        sut.addInterceptors(registry);

        verify(registration).addPathPatterns(
            "/v1/callbacks/mtn",
            "/v1/callbacks/mtn/disbursement/*");
    }
}
