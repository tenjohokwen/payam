package com.softropic.payam.orange;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.orange.service.OrangeTokenService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
@EnableWireMock(@ConfigureWireMock(name = "orange", baseUrlProperties = "orange.base-url"))
class OrangeTokenServiceIT {

    @InjectWireMock("orange")
    WireMockServer orangeServer;

    @Autowired OrangeTokenService orangeTokenService;
    @Autowired StringRedisTemplate redis;

    @AfterEach
    void tearDown() {
        orangeServer.resetAll();
        redis.delete("orange:token:cm");
        redis.delete("orange:token:lock");
    }

    @Test
    void getAccessToken_fetches_from_orange_and_caches_in_redis() {
        orangeServer.stubFor(post(urlPathEqualTo("/token"))
            .willReturn(okJson("{\"access_token\":\"my-token\",\"expires_in\":3600}")));

        String token = orangeTokenService.getAccessToken();

        assertThat(token).isEqualTo("my-token");
        // Verify cached in Redis
        assertThat(redis.opsForValue().get("orange:token:cm")).isEqualTo("my-token");
        // Second call should NOT hit Orange again
        orangeServer.verify(1, postRequestedFor(urlPathEqualTo("/token")));
    }

    @Test
    void getAccessToken_returns_cached_token_without_calling_orange() {
        redis.opsForValue().set("orange:token:cm", "cached-token");

        String token = orangeTokenService.getAccessToken();

        assertThat(token).isEqualTo("cached-token");
        orangeServer.verify(0, postRequestedFor(urlPathEqualTo("/token")));
    }

    @Test
    void evict_removes_token_from_redis() {
        redis.opsForValue().set("orange:token:cm", "some-token");

        orangeTokenService.evict();

        assertThat(redis.opsForValue().get("orange:token:cm")).isNull();
    }
}
