package com.softropic.payam.mtn;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.mtn.service.MtnTokenService;

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
@EnableWireMock(@ConfigureWireMock(name = "mtn", baseUrlProperties = {"mtn.collection-base-url"}))
class MtnTokenServiceIT {

    @InjectWireMock("mtn")
    WireMockServer mtnServer;

    @Autowired MtnTokenService mtnTokenService;
    @Autowired StringRedisTemplate redis;

    @AfterEach
    void tearDown() {
        mtnServer.resetAll();
        redis.delete("mtn:token:cm");
        redis.delete("mtn:token:lock");
    }

    @Test
    void getAccessToken_fetches_from_mtn_and_caches_in_redis() {
        // Note: MTN token endpoint stub path is /token/ (trailing slash) — matches collection-token-url path
        mtnServer.stubFor(post(urlPathEqualTo("/token/"))
            .willReturn(okJson("{\"access_token\":\"mtn-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));

        String token = mtnTokenService.getAccessToken();

        assertThat(token).isEqualTo("mtn-token");
        // Verify cached in Redis
        assertThat(redis.opsForValue().get("mtn:token:cm")).isEqualTo("mtn-token");
        // Second call should NOT hit MTN again
        mtnServer.verify(1, postRequestedFor(urlPathEqualTo("/token/")));
    }

    @Test
    void getAccessToken_returns_cached_token_without_calling_mtn() {
        redis.opsForValue().set("mtn:token:cm", "cached-mtn-token");

        String token = mtnTokenService.getAccessToken();

        assertThat(token).isEqualTo("cached-mtn-token");
        mtnServer.verify(0, postRequestedFor(urlPathEqualTo("/token/")));
    }

    @Test
    void evict_removes_token_from_redis() {
        redis.opsForValue().set("mtn:token:cm", "some-token");

        mtnTokenService.evict();

        assertThat(redis.opsForValue().get("mtn:token:cm")).isNull();
    }
}
