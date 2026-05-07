package com.softropic.payam.platform.monitoring;

import com.softropic.payam.mtn.service.MtnMoMoPort;
import com.softropic.payam.platform.admin.contract.PlatformConfigDto;
import com.softropic.payam.platform.admin.service.PlatformConfigService;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MtnPlatformHealthIndicator implements HealthIndicator {

    private final MtnMoMoPort mtnMoMoPort;
    private final PlatformConfigService platformConfigService;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @Override
    public Health health() {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("mtn");
        String cbState = cb.getState().name();

        String msisdn = platformConfigService.findAll().stream()
                .filter(c -> "MTN".equals(c.provider()))
                .map(PlatformConfigDto::platformMsisdn)
                .findFirst()
                .orElse("");

        if (msisdn.isBlank()) {
            return Health.down()
                    .withDetail("reason", "platform MSISDN not configured")
                    .withDetail("circuitBreaker", cbState)
                    .build();
        }

        try {
            var status = mtnMoMoPort.validateSubscriber(msisdn);
            Health.Builder builder = status.active() ? Health.up() : Health.down();
            return builder
                    .withDetail("msisdn", msisdn)
                    .withDetail("rawStatus", status.rawStatus())
                    .withDetail("circuitBreaker", cbState)
                    .build();
        } catch (Exception e) {
            log.warn("MTN platform health check failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("msisdn", msisdn)
                    .withDetail("error", e.getMessage())
                    .withDetail("circuitBreaker", cbState)
                    .build();
        }
    }
}
