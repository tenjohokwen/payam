package com.softropic.payam.health;

import com.softropic.payam.orange.service.OrangeMoneyPort;
import com.softropic.payam.platform.contract.PlatformConfigDto;
import com.softropic.payam.platform.service.PlatformConfigService;

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
public class OrangePlatformHealthIndicator implements HealthIndicator {

    private final OrangeMoneyPort orangeMoneyPort;
    private final PlatformConfigService platformConfigService;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @Override
    public Health health() {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("orange");
        String cbState = cb.getState().name();

        String msisdn = platformConfigService.findAll().stream()
                .filter(c -> "ORANGE".equals(c.provider()))
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
            var status = orangeMoneyPort.validateSubscriber(msisdn);
            Health.Builder builder = status.active() ? Health.up() : Health.down();
            return builder
                    .withDetail("msisdn", msisdn)
                    .withDetail("rawStatus", status.rawStatus())
                    .withDetail("circuitBreaker", cbState)
                    .build();
        } catch (Exception e) {
            log.warn("Orange platform health check failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("msisdn", msisdn)
                    .withDetail("error", e.getMessage())
                    .withDetail("circuitBreaker", cbState)
                    .build();
        }
    }
}
