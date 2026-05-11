package com.softropic.payam.payment.webhook.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Webhook infrastructure configuration.
 *
 * noRetryRestTemplate uses SimpleClientHttpRequestFactory to disable the default
 * Apache HTTP Client 5 auto-retry behaviour. This is required for webhook delivery
 * so that transient failures are recorded as failures (not silently retried), and
 * the exponential-backoff Quartz retry job handles reattempts correctly.
 *
 * WEBHOOK-03: Explicit connect (5s) and read (10s) timeouts — prevents a hanging tenant
 * endpoint from holding a Quartz delivery thread indefinitely. SimpleClientHttpRequestFactory
 * defaults to 0 (infinite) for both. setConnectTimeout / setReadTimeout accept milliseconds
 * as int (no Duration overload on this class).
 */
@Configuration
public class WebhookConfig {

    /** Connect timeout in milliseconds — WEBHOOK-03 (≤5s). */
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    /** Read timeout in milliseconds — WEBHOOK-03 (≤10s). */
    private static final int READ_TIMEOUT_MS = 10_000;

    @Bean
    public RestTemplate noRetryRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);   // WEBHOOK-03: 5s connect
        factory.setReadTimeout(READ_TIMEOUT_MS);         // WEBHOOK-03: 10s read
        return new RestTemplate(factory);
    }
}
