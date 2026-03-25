package com.softropic.payam.admin.api;

import com.softropic.payam.admin.contract.ProviderStatusDto;
import com.softropic.payam.security.common.util.SecurityConstants;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin endpoint exposing Resilience4j circuit breaker state per payment provider.
 *
 * <p>GET /v1/admin/providers/status — returns a map of provider name → circuit breaker snapshot.
 *
 * <p>Pitfall 5 fix: force-creates the "orange" and "mtn" circuit breakers before calling
 * {@code getAllCircuitBreakers()}.  Without this, circuit breakers are lazily created only on
 * first use; a freshly-started instance would return an empty map if no payments have yet
 * been processed.
 */
@RestController
@RequestMapping("/v1/admin/providers")
@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
@RequiredArgsConstructor
public class ProviderStatusResource {

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    /**
     * Returns the current circuit breaker state for all known providers.
     *
     * @return 200 with a map of providerName → {@link ProviderStatusDto}
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, ProviderStatusDto>> status() {
        // Force-create known CBs so they appear even if no calls have been made yet
        circuitBreakerRegistry.circuitBreaker("orange");
        circuitBreakerRegistry.circuitBreaker("mtn");

        Map<String, ProviderStatusDto> result = new LinkedHashMap<>();
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(cb -> {
            CircuitBreaker.Metrics metrics = cb.getMetrics();
            result.put(cb.getName(), new ProviderStatusDto(
                    cb.getState().name(),
                    metrics.getFailureRate(),
                    metrics.getNumberOfBufferedCalls(),
                    metrics.getNumberOfFailedCalls()));
        });
        return ResponseEntity.ok(result);
    }
}
