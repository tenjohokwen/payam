package com.softropic.payam.webhook.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for WebhookConfig bean wiring (WEBHOOK-03).
 *
 * SimpleClientHttpRequestFactory has no public getters for connectTimeout / readTimeout,
 * so this test reflects on the private int fields to assert the configured values.
 */
class WebhookConfigTest {

    @Test
    void noRetryRestTemplate_hasExplicitConnectAndReadTimeouts() throws Exception {
        WebhookConfig config = new WebhookConfig();
        RestTemplate template = config.noRetryRestTemplate();

        assertThat(template).isNotNull();
        ClientHttpRequestFactory factory = template.getRequestFactory();
        assertThat(factory).isInstanceOf(SimpleClientHttpRequestFactory.class);

        int connectTimeout = readPrivateIntField(factory, "connectTimeout");
        int readTimeout = readPrivateIntField(factory, "readTimeout");

        // WEBHOOK-03: connect ≤ 5s, read ≤ 10s, both > 0 (0 = infinite, not allowed)
        assertThat(connectTimeout)
            .as("WEBHOOK-03 connect timeout must be > 0 and ≤ 5000 ms")
            .isGreaterThan(0)
            .isLessThanOrEqualTo(5_000);
        assertThat(readTimeout)
            .as("WEBHOOK-03 read timeout must be > 0 and ≤ 10000 ms")
            .isGreaterThan(0)
            .isLessThanOrEqualTo(10_000);

        // Pin the exact values chosen in WebhookConfig — if these change, update the test deliberately.
        assertThat(connectTimeout).isEqualTo(5_000);
        assertThat(readTimeout).isEqualTo(10_000);
    }

    private static int readPrivateIntField(Object target, String fieldName) throws Exception {
        Field field = SimpleClientHttpRequestFactory.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (int) field.get(target);
    }
}
