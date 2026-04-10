package com.softropic.payam.webhook;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.tenant.contract.ApiKeyEnvironment;
import com.softropic.payam.tenant.service.TenantService;
import com.softropic.payam.transaction.contract.LedgerDirection;
import com.softropic.payam.transaction.contract.TransactionStatus;
import com.softropic.payam.transaction.repo.LedgerEntry;
import com.softropic.payam.transaction.repo.LedgerEntryRepository;
import com.softropic.payam.transaction.repo.TransactionRepository;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
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
import org.springframework.web.client.RestTemplate;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired RestTemplateBuilder restTemplateBuilder;

    private RestTemplate noRetryRestTemplate;

    @LocalServerPort int serverPort;

    private Long tenantId;

    @BeforeEach
    void setUp() {
        noRetryRestTemplate = restTemplateBuilder
            .requestFactory(org.springframework.http.client.SimpleClientHttpRequestFactory.class)
            .build();

        // Seed JWT secret required by SecurityAdviceFilter.addSecretToThread()
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute(
                "INSERT INTO main.sec (id, created_by, created_date, last_modified_by, last_modified_date, " +
                "request_id, session_id, status, bus_id, value, version) " +
                "VALUES ('659287191260154475','SYSTEM_ACCOUNT','2024-12-24 06:51:55.357352'," +
                "'SYSTEM_ACCOUNT','2024-12-24 06:51:55.357352'," +
                "'bed78f34-3e09-4fa8-81db-32326a528cca', null, 'ACTIVE', 'jot'," +
                "'loiI8oT2C1tWecrNXPDjN8fveYEU8rD6nb1k1NbVy92rwdd4/KO+aHhXh3A5zjsT5eSFL/xI+9Rqyj4RI6QCiFywn5nZLIwHGPNEY0F9lnDnGGmVjv/9rO5fgGt83+cxNDyGoCePaVEpBd7xHxyDdfpAoLxQs8mhKGqcEsh09Q+26qEiEm/a9bgDSbSQ0sX00VHBLd35OLmvN+ydjEluYxBTa6KzGb2CQ6Ttg4ZaELmbZOWpEjQ1Z7BbbYiXmWyaY+2HnkyhONoGbUpvVKl1c4e9IlQzeUYkekbUbADIm2LNK9Nhfv5/L5esvFrdVOUcUpLk/y8UT9f5xOMLFJ4Ct6s0eTKvNqYkSz2DFRI8Ip4p/ns6gA4V/1MUf9GeqPUWLiOa28Vw15+R8ycUMqb8NZHOP1oj9RunhSwA7EY84bZL3+yePc3n1b8ne8xzaYVEdK1WBu3J6s2AoBaOL/JLWfu8MuxXI+ub', 'v1') " +
                "ON CONFLICT DO NOTHING");
            return null;
        });

        // Seed security prerequisites for ROLE_USER 403 test
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute(
                "INSERT INTO main.authority (id, name, status, created_by, created_date, last_modified_by, last_modified_date, request_id) " +
                "VALUES (6747751741842104908, 'ROLE_ADMIN', 'ACTIVE', 'system', '2016-04-26 20:41:25', 'system', '2016-04-26 20:41:25', '') " +
                "ON CONFLICT DO NOTHING");
            jdbcTemplate.execute(
                "INSERT INTO main.authority (id, name, status, created_by, created_date, last_modified_by, last_modified_date, request_id) " +
                "VALUES (5418719445932238328, 'ROLE_USER', 'ACTIVE', 'system', '2016-04-26 20:41:25', 'system', '2016-04-26 20:41:25', '') " +
                "ON CONFLICT DO NOTHING");
            // ROLE_USER-only user (no ROLE_ADMIN) — password: admin*123!
            jdbcTemplate.execute(
                "INSERT INTO main.\"user\" " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
                " status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, phone_type, " +
                " title, activated, activation_date, activation_key, locked, login, login_id_type, " +
                " password_hash, reset_expiration, reset_key, otp_enabled) " +
                "VALUES " +
                "(675373350208068097, 'anonymousUser', '2025-02-06 16:12:34.516705', 'anonymousUser', '2025-02-06 16:12:35.198266', " +
                " 'd503b412-b576-48c2-8ead-ec9e10d42881', NULL, 'ACTIVE', '1990-02-20', 'user@test.com', " +
                " 'TEST', 'MALE', 'en', 'USER', 'DE', '01724527688', 'MOBILE', NULL, " +
                " true, NULL, NULL, false, 'user@test.com', 'EMAIL', " +
                " '$2a$10$Sdo/qTAcMcYaIAV6XXw3dejlsDwL93g6zb.uPUwFohPpC8q3bEg5i', NULL, NULL, false) " +
                "ON CONFLICT DO NOTHING");
            jdbcTemplate.execute(
                "INSERT INTO main.user_authority (user_id, authority_id) VALUES (675373350208068097, 5418719445932238328) " +
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
        tenantId = tenantService.createTenant("wh-dc-test", ApiKeyEnvironment.PROD).tenant().getId();

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
            jdbcTemplate.execute("DELETE FROM main.ledger_entry");
            jdbcTemplate.execute("DELETE FROM main.payment_event_log");
            jdbcTemplate.execute("DELETE FROM main.transaction");
            jdbcTemplate.execute("DELETE FROM main.tenant_api_key");
            jdbcTemplate.execute("DELETE FROM main.tenant");
            jdbcTemplate.execute("DELETE FROM main.sec");
            jdbcTemplate.execute("DELETE FROM main.user_authority WHERE user_id IN (675373350208068097)");
            jdbcTemplate.execute("DELETE FROM main.\"user\" WHERE id IN (675373350208068097)");
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (6747751741842104908, 5418719445932238328)");
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

        // Verify ledger entries written atomically with SUCCESS transition
        String txId = tx.get().getTransactionId();
        List<LedgerEntry> ledgerEntries = ledgerEntryRepository.findByTransactionId(txId);
        assertThat(ledgerEntries).hasSize(2);
        assertThat(ledgerEntries)
            .extracting(LedgerEntry::getDirection)
            .containsExactlyInAnyOrder(LedgerDirection.DEBIT, LedgerDirection.CREDIT);
        assertThat(ledgerEntries)
            .allSatisfy(e -> {
                assertThat(e.getAmount()).isEqualByComparingTo(new java.math.BigDecimal("100"));
                assertThat(e.getCurrency()).isEqualTo("XAF");
                assertThat(e.getTenantId()).isEqualTo(tenantId);
            });
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

    @Test
    void deliveryEndpoint_roleUserJwt_returns403() throws Exception {
        // Login as ROLE_USER-only user (no ROLE_ADMIN)
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        loginHeaders.add(HttpHeaders.COOKIE, "fcookie=fingerprintCookie");
        java.util.Map<String, String> credentials = java.util.Map.of("id", "user@test.com", "password", "admin*123!");
        org.springframework.http.ResponseEntity<java.util.Map> loginResponse = noRetryRestTemplate.exchange(
            "http://localhost:" + serverPort + "/authenticate",
            HttpMethod.POST,
            new HttpEntity<>(credentials, loginHeaders),
            java.util.Map.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.OK);

        List<String> setCookies = loginResponse.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).isNotNull().isNotEmpty();
        String cookieHeader = String.join("; ",
            setCookies.stream().map(c -> c.split(";", 2)[0]).toList());

        HttpHeaders userCookies = new HttpHeaders();
        userCookies.set(HttpHeaders.COOKIE, cookieHeader);

        // ROLE_USER JWT must receive 403 on the admin-only delivery endpoint
        assertThatThrownBy(() ->
            noRetryRestTemplate.exchange(
                "http://localhost:" + serverPort + "/v1/admin/webhooks/deliveries/tx-999",
                HttpMethod.GET,
                new HttpEntity<>(userCookies),
                List.class))
            .isInstanceOf(org.springframework.web.client.HttpClientErrorException.class)
            .satisfies(e -> {
                int code = ((org.springframework.web.client.HttpClientErrorException) e).getStatusCode().value();
                assertThat(code).isEqualTo(403);
            });
    }
}
