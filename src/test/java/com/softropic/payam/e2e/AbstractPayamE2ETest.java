package com.softropic.payam.e2e;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.softropic.payam.config.E2ESecurityConfig;
import com.softropic.payam.config.PostgresContainerConfig;
import com.softropic.payam.config.RedisContainerConfig;
import com.softropic.payam.config.TestClockConfig;
import com.softropic.payam.config.TestDataCleaner;
import com.softropic.payam.config.TestMailConfig;
import com.softropic.payam.config.WireMockConfig;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import({PostgresContainerConfig.class, RedisContainerConfig.class,
         E2ESecurityConfig.class, TestClockConfig.class, TestMailConfig.class})
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "mtn.callback-ip-whitelist=",
    "orange.callback-ip-whitelist=",
    "orange.callback-hmac-secret="
})
@EnableWireMock({
    @ConfigureWireMock(name = "mtn",    baseUrlProperties = {"mtn.collection-base-url"}),
    @ConfigureWireMock(name = "orange", baseUrlProperties = {"orange.base-url", "orange.pay-url"})
})
public abstract class AbstractPayamE2ETest {

    @InjectWireMock("mtn")
    protected WireMockServer mtnServer;

    @InjectWireMock("orange")
    protected WireMockServer orangeServer;

    @Autowired
    private E2ESecurityConfig e2eSecurityConfig;

    @Autowired
    protected TestDataCleaner testDataCleaner;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected CircuitBreakerRegistry circuitBreakerRegistry;

    @LocalServerPort
    protected int serverPort;

    @BeforeEach
    void baseSetUp() {
        e2eSecurityConfig.seedSecurityRow();
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        circuitBreakerRegistry.circuitBreaker("mtn").reset();
        circuitBreakerRegistry.circuitBreaker("orange").reset();
        stubTokenEndpoints();
    }

    @AfterEach
    void baseTearDown() {
        mtnServer.resetAll();
        orangeServer.resetAll();
        testDataCleaner.wipeAll();
    }

    /**
     * Stubs the provider OAuth2 token endpoints with default test responses.
     * Subclasses may override this method to provide different token behaviour.
     */
    protected void stubTokenEndpoints() {
        mtnServer.stubFor(post(urlPathEqualTo("/token/"))
            .willReturn(okJson(WireMockConfig.MTN_TOKEN_RESPONSE)));
        orangeServer.stubFor(post(urlPathEqualTo("/token"))
            .willReturn(okJson(WireMockConfig.ORANGE_TOKEN_RESPONSE)));
    }
}
