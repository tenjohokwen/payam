package com.softropic.payam.webhook;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.tenant.service.TenantService;
import com.softropic.payam.transaction.contract.TransactionStatus;
import com.softropic.payam.transaction.repo.TransactionRepository;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the webhook double-check state transition layer.
 *
 * Verifies that:
 * 1. Orange webhook with provider SUCCESS response transitions tx to SUCCESS
 * 2. Orange webhook with provider PROCESSING response leaves tx in PROCESSING (poller handles it)
 * 3. Orange webhook with circuit breaker OPEN leaves tx in PROCESSING (no crash)
 *
 * Uses @EnableWireMock to intercept outbound provider status API calls.
 * Transactions are seeded directly via jdbcTemplate in PROCESSING state.
 */
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "orange.callback-ip-whitelist=",
    "orange.callback-hmac-secret=",
    "mtn.callback-ip-whitelist="
})
@EnableWireMock({
    @ConfigureWireMock(name = "orange", baseUrlProperties = {"orange.base-url", "orange.pay-url"}),
    @ConfigureWireMock(name = "mtn",    baseUrlProperties = {"mtn.collection-base-url"})
})
class WebhookDoubleCheckIT {

    @InjectWireMock("orange")
    WireMockServer orangeServer;

    @InjectWireMock("mtn")
    WireMockServer mtnServer;

    @Autowired TestRestTemplate testRestTemplate;
    @Autowired TenantService tenantService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired StringRedisTemplate redis;
    @Autowired TransactionRepository transactionRepository;
    @Autowired CircuitBreakerRegistry circuitBreakerRegistry;

    @LocalServerPort int serverPort;

    private Long tenantId;

    @BeforeEach
    void setUp() {
        // Seed JWT secret required by SecurityAdviceFilter.addSecretToThread()
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute(
                "INSERT INTO main.sec (id, created_by, created_date, last_modified_by, last_modified_date, " +
                "request_id, session_id, status, bus_id, value, version) " +
                "VALUES ('659287191260154475','SYSTEM_ACCOUNT','2024-12-24 06:51:55.357352'," +
                "'SYSTEM_ACCOUNT','2024-12-24 06:51:55.357352'," +
                "'bed78f34-3e09-4fa8-81db-32326a528cca', null, 'ACTIVE', 'jot'," +
                "'loiI8oT2C1tWecrNXPDjN8fveYEU8rD6nb1k1NbVy92rwdd4/KO+aHhXh3A5zjsT5eSFL/xI+9Rqyj4RI6QCiFywn5nZLIwHGPNEY0F9lnDnGGmVjv/9rO5fgGt83+cxNDyGoCePaVEpBd7xHxyDdfpAoLxQs8mhKGqcEsh09Q+26qEiEm/a9bgDSbSQ0sX00VHBLd35OLmvN+ydjEluYxBTa6KzGb2CQ6Ttg4ZaELmbZOWpEjQ1Z7BbbYiXmWyaY+2HnkyhONoGbUpvVKl1c4e9IlQzeUYkekbUbADIm2LNK9Nhfv5/L5esvFrdVOUcUpLk/y8UT9f5xOMLFJ4Ct6s0eTKvNqYkSz2DFRI8Ip4p/ns6gA4V/1MUf9GeqPUWLiOa28Vw15+R8ycUMqk8NZHOP1oj9RunhSwA7EY84bZL3+yePc3n1b8ne8xzaYVEdK1WBu3J6s2AoBaOL/JLWfu8MuxXI+ub', 'v1') " +
                "ON CONFLICT DO NOTHING");
            return null;
        });

        // Stub Orange token endpoint
        orangeServer.stubFor(post(urlPathEqualTo("/token"))
            .willReturn(okJson("{\"access_token\":\"orange-token\",\"token_type\":\"Bearer\",\"expires_in\":7200}")));

        // Stub MTN collection token endpoint
        mtnServer.stubFor(post(urlPathEqualTo("/token/"))
            .willReturn(okJson("{\"access_token\":\"test-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));

        // Clear Redis (dedup keys + token caches)
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();

        // Create tenant
        tenantId = tenantService.createTenant("wh-dc-test", "LIVE").tenant().getId();

        // Reset circuit breakers for test isolation
        circuitBreakerRegistry.circuitBreaker("orange").reset();
        circuitBreakerRegistry.circuitBreaker("mtn").reset();
    }

    @AfterEach
    void tearDown() {
        orangeServer.resetAll();
        mtnServer.resetAll();
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        redis.delete("orange:token");
        redis.delete("mtn:token:cm");

        // Reset circuit breakers
        circuitBreakerRegistry.circuitBreaker("orange").reset();
        circuitBreakerRegistry.circuitBreaker("mtn").reset();

        // FK-safe DELETE order
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM main.payment_event_log");
            jdbcTemplate.execute("DELETE FROM main.transaction");
            jdbcTemplate.execute("DELETE FROM main.tenant_api_key");
            jdbcTemplate.execute("DELETE FROM main.tenant");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    // ---- helpers ----

    /**
     * Insert a PROCESSING Orange transaction with the given payToken directly via JDBC.
     * Uses TSID-like ID generation (timestamp-shifted long).
     *
     * Returns the transactionId (UUID string).
     */
    private String createOrangeProcessingTransaction(String payToken) {
        String txId = java.util.UUID.randomUUID().toString();
        String traceId = java.util.UUID.randomUUID().toString();
        long id = System.nanoTime() & Long.MAX_VALUE; // unique positive long
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.transaction " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, " +
                "transaction_id, trace_id, tenant_id, tx_status, status, provider, amount, currency, provider_ref, pay_token) " +
                "VALUES (?, 'TEST', NOW(), 'TEST', NOW(), ?, ?, ?, 'PROCESSING', 'ACTIVE', 'ORANGE', 100, 'XAF', ?, ?)",
                id, txId, traceId, tenantId, payToken, payToken);
            return null;
        });
        return txId;
    }

    private void postOrangeCallback(String payToken, String status) {
        String body = String.format(
            "{\"payToken\":\"%s\",\"notif_token\":\"tok\",\"status\":\"%s\",\"txnid\":\"TXN001\",\"msisdn\":\"237650000000\",\"amount\":\"100\",\"createtime\":\"2026-03-24T10:00:00\"}",
            payToken, status);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        testRestTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/callbacks/orange",
            HttpMethod.POST, new HttpEntity<>(body, headers), Void.class);
    }

    // ---- tests ----

    @Test
    void shouldTransitionToSuccessOnOrangeWebhookWithSuccessStatus() throws Exception {
        String payToken = "pt-success-001";
        createOrangeProcessingTransaction(payToken);

        // Stub provider status poll — Orange returns SUCCESSFULL
        orangeServer.stubFor(get(urlPathMatching("/mp/paymentstatus/.*"))
            .willReturn(okJson("{\"status\":\"SUCCESSFULL\",\"pay_token\":\"" + payToken + "\"}")));

        postOrangeCallback(payToken, "SUCCESSFULL");

        // @TransactionalEventListener fires synchronously in same thread after transaction commits
        // Add brief wait to allow async event processing in case of thread scheduling delay
        Thread.sleep(300);

        var tx = transactionRepository.findByPayToken(payToken);
        assertThat(tx).isPresent();
        assertThat(tx.get().getTxStatus()).isEqualTo(TransactionStatus.SUCCESS);
    }

    @Test
    void shouldNotTransitionWhenProviderStillProcessing() throws Exception {
        String payToken = "pt-pending-001";
        createOrangeProcessingTransaction(payToken);

        // Stub provider status poll — still PENDING
        orangeServer.stubFor(get(urlPathMatching("/mp/paymentstatus/.*"))
            .willReturn(okJson("{\"status\":\"PENDING\",\"pay_token\":\"" + payToken + "\"}")));

        postOrangeCallback(payToken, "PENDING");

        Thread.sleep(300);

        var tx = transactionRepository.findByPayToken(payToken);
        assertThat(tx).isPresent();
        // Still PROCESSING — poller will handle it later
        assertThat(tx.get().getTxStatus()).isEqualTo(TransactionStatus.PROCESSING);
    }

    @Test
    void shouldNotCrashWhenCircuitBreakerOpenAndLeaveTransactionInProcessing() throws Exception {
        String payToken = "pt-circuit-001";
        createOrangeProcessingTransaction(payToken);

        // Force circuit breaker to OPEN state — no provider call should be made
        circuitBreakerRegistry.circuitBreaker("orange").transitionToOpenState();

        postOrangeCallback(payToken, "SUCCESSFULL");

        Thread.sleep(300);

        var tx = transactionRepository.findByPayToken(payToken);
        assertThat(tx).isPresent();
        // Circuit open — no state transition; stays PROCESSING for poller
        assertThat(tx.get().getTxStatus()).isEqualTo(TransactionStatus.PROCESSING);

        // Verify no provider status API was called (circuit was open)
        orangeServer.verify(0, getRequestedFor(urlPathMatching("/mp/paymentstatus/.*")));
    }
}
