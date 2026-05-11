package com.softropic.payam.payment.provider.mtn;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.payment.provider.mtn.service.MtnTokenService;

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

@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "mtn.collection-token-url=http://localhost:${wiremock.server.port}/token/collection",
    "mtn.disbursement-token-url=http://localhost:${wiremock.server.port}/token/disbursement"
})
@EnableWireMock(@ConfigureWireMock(name = "mtn", baseUrlProperties = {"mtn.collection-base-url", "mtn.disbursement-base-url"}))
class MtnTokenServiceIT {

    @InjectWireMock("mtn")
    WireMockServer mtnServer;

    @Autowired MtnTokenService mtnTokenService;
    @Autowired StringRedisTemplate redis;

    @AfterEach
    void tearDown() {
        mtnServer.resetAll();
        redis.delete("mtn:token:coll");
        redis.delete("mtn:token:disb");
        redis.delete("mtn:token:lock:mtn:token:coll");
        redis.delete("mtn:token:lock:mtn:token:disb");
    }

    @Test
    void getCollectionToken_fetches_from_mtn_and_caches_in_redis() {
        mtnServer.stubFor(post(urlPathEqualTo("/token/collection"))
            .willReturn(okJson("{\"access_token\":\"mtn-coll-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));

        String token = mtnTokenService.getCollectionToken();

        assertThat(token).isEqualTo("mtn-coll-token");
        // Verify cached in Redis
        assertThat(redis.opsForValue().get("mtn:token:coll")).isEqualTo("mtn-coll-token");
        // Second call should NOT hit MTN again
        mtnServer.verify(1, postRequestedFor(urlPathEqualTo("/token/collection")));
    }

    @Test
    void getDisbursementToken_fetches_from_mtn_and_caches_in_redis() {
        mtnServer.stubFor(post(urlPathEqualTo("/token/disbursement"))
            .willReturn(okJson("{\"access_token\":\"mtn-disb-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));

        String token = mtnTokenService.getDisbursementToken();

        assertThat(token).isEqualTo("mtn-disb-token");
        // Verify cached in Redis
        assertThat(redis.opsForValue().get("mtn:token:disb")).isEqualTo("mtn-disb-token");
        // Second call should NOT hit MTN again
        mtnServer.verify(1, postRequestedFor(urlPathEqualTo("/token/disbursement")));
    }

    @Test
    void getAccessToken_returns_cached_collection_token_without_calling_mtn() {
        redis.opsForValue().set("mtn:token:coll", "cached-mtn-coll-token");

        String token = mtnTokenService.getAccessToken();

        assertThat(token).isEqualTo("cached-mtn-coll-token");
        mtnServer.verify(0, postRequestedFor(urlPathEqualTo("/token/collection")));
    }

    @Test
    void evict_removes_tokens_from_redis() {
        redis.opsForValue().set("mtn:token:coll", "some-coll-token");
        redis.opsForValue().set("mtn:token:disb", "some-disb-token");

        mtnTokenService.evict();

        assertThat(redis.opsForValue().get("mtn:token:coll")).isNull();
        assertThat(redis.opsForValue().get("mtn:token:disb")).isNull();
    }
}
