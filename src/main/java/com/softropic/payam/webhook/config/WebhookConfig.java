package com.softropic.payam.webhook.config;

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
 */
@Configuration
public class WebhookConfig {

    @Bean
    public RestTemplate noRetryRestTemplate() {
        return new RestTemplate(new SimpleClientHttpRequestFactory());
    }
}
