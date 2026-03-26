package com.softropic.payam.webhook;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.softropic.payam.common.AdminLogin;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.tenant.service.TenantService;
import com.softropic.payam.tenant.repo.TenantRepository;
import com.softropic.payam.transaction.contract.TransactionStatus;
import com.softropic.payam.transaction.repo.TransactionRepository;
import com.softropic.payam.webhook.repo.WebhookDeliveryLog;
import com.softropic.payam.webhook.repo.WebhookDeliveryLogRepository;

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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the outbound webhook delivery pipeline.
 *
 * Verifies that:
 * 1. After a SUCCESS transition, an outbound webhook is delivered to tenant URL with correct HMAC signature
 * 2. A 503 from the tenant endpoint marks delivery as failed with retry scheduled
 * 3. GET /v1/webhooks/deliveries/{transactionId} returns delivery log entries
 *
 * Uses @EnableWireMock for both provider status APIs and the tenant's webhook endpoint.
 * The "tenant-wh" WireMock server acts as the tenant's configured webhook URL.
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
    @ConfigureWireMock(name = "orange",    baseUrlProperties = {"orange.base-url", "orange.pay-url"}),
    @ConfigureWireMock(name = "mtn",       baseUrlProperties = {"mtn.collection-base-url"}),
    @ConfigureWireMock(name = "tenant-wh") // no baseUrlProperties — URL set on Tenant entity dynamically
})
class WebhookDeliveryIT {

    @InjectWireMock("orange")
    WireMockServer orangeServer;

    @InjectWireMock("mtn")
    WireMockServer mtnServer;

    @InjectWireMock("tenant-wh")
    WireMockServer tenantWebhookServer;

    @Autowired TestRestTemplate testRestTemplate;
    @Autowired TenantService tenantService;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired StringRedisTemplate redis;
    @Autowired TransactionRepository transactionRepository;
    @Autowired WebhookDeliveryLogRepository deliveryLogRepo;
    @Autowired CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired RestTemplateBuilder restTemplateBuilder;

    private RestTemplate noRetryRestTemplate;
    private HttpHeaders adminCookies;

    @LocalServerPort int serverPort;

    private Long tenantId;
    private String apiKey;

    private static final String WEBHOOK_SECRET = "test-secret";

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

        // Seed admin user for Test 3 (delivery endpoint now admin-only)
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute(
                "INSERT INTO main.authority (id, name, status, created_by, created_date, last_modified_by, last_modified_date, request_id) " +
                "VALUES (6747751741842104908, 'ROLE_ADMIN', 'ACTIVE', 'system', '2016-04-26 20:41:25', 'system', '2016-04-26 20:41:25', '') " +
                "ON CONFLICT DO NOTHING");
            jdbcTemplate.execute(
                "INSERT INTO main.authority (id, name, status, created_by, created_date, last_modified_by, last_modified_date, request_id) " +
                "VALUES (5418719445932238328, 'ROLE_USER', 'ACTIVE', 'system', '2016-04-26 20:41:25', 'system', '2016-04-26 20:41:25', '') " +
                "ON CONFLICT DO NOTHING");
            jdbcTemplate.execute(
                "INSERT INTO main.\"user\" " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
                " status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, phone_type, " +
                " title, activated, activation_date, activation_key, locked, login, login_id_type, " +
                " password_hash, reset_expiration, reset_key, otp_enabled) " +
                "VALUES " +
                "(675373350208068096, 'anonymousUser', '2025-02-06 16:12:34.516705', 'anonymousUser', '2025-02-06 16:12:35.198266', " +
                " 'd503b412-b576-48c2-8ead-ec9e10d42880', NULL, 'ACTIVE', '1990-02-20', 'queb@yahoo.com', " +
                " 'VAYM', 'MALE', 'en', 'FXFUOUQBUO', 'DE', '01724527687', 'MOBILE', NULL, " +
                " true, NULL, NULL, false, 'queb@yahoo.com', 'EMAIL', " +
                " '$2a$10$Sdo/qTAcMcYaIAV6XXw3dejlsDwL93g6zb.uPUwFohPpC8q3bEg5i', NULL, NULL, false) " +
                "ON CONFLICT DO NOTHING");
            jdbcTemplate.execute(
                "INSERT INTO main.user_authority (user_id, authority_id) VALUES (675373350208068096, 5418719445932238328) " +
                "ON CONFLICT DO NOTHING");
            jdbcTemplate.execute(
                "INSERT INTO main.user_authority (user_id, authority_id) VALUES (675373350208068096, 6747751741842104908) " +
                "ON CONFLICT DO NOTHING");
            return null;
        });

        // Obtain admin JWT cookies for Test 3
        adminCookies = AdminLogin.loginAsAdmin(
            "http://localhost:" + serverPort + "/authenticate", noRetryRestTemplate);

        // Stub provider token endpoints
        orangeServer.stubFor(post(urlPathEqualTo("/token"))
            .willReturn(okJson("{\"access_token\":\"orange-token\",\"token_type\":\"Bearer\",\"expires_in\":7200}")));
        mtnServer.stubFor(post(urlPathEqualTo("/token/"))
            .willReturn(okJson("{\"access_token\":\"test-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));

        // Clear Redis dedup keys and token caches
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();

        // Provision tenant
        var provision = tenantService.createTenant("wh-delivery-test", "LIVE");
        tenantId = provision.tenant().getId();
        apiKey = provision.rawKey();

        // Set webhookUrl and webhookSecret on tenant via JDBC (direct UPDATE)
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "UPDATE main.tenant SET webhook_url = ?, webhook_secret = ? WHERE id = ?",
                tenantWebhookServer.baseUrl() + "/webhook", WEBHOOK_SECRET, tenantId);
            return null;
        });

        // Reset circuit breakers for test isolation
        circuitBreakerRegistry.circuitBreaker("orange").reset();
        circuitBreakerRegistry.circuitBreaker("mtn").reset();
    }

    @AfterEach
    void tearDown() {
        orangeServer.resetAll();
        mtnServer.resetAll();
        tenantWebhookServer.resetAll();

        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        redis.delete("orange:token");
        redis.delete("mtn:token:cm");

        circuitBreakerRegistry.circuitBreaker("orange").reset();
        circuitBreakerRegistry.circuitBreaker("mtn").reset();

        // FK-safe DELETE order — webhook_delivery_log before transaction before tenant
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM main.ledger_entry");
            jdbcTemplate.execute("DELETE FROM main.webhook_delivery_log");
            jdbcTemplate.execute("DELETE FROM main.payment_event_log");
            jdbcTemplate.execute("DELETE FROM main.transaction");
            jdbcTemplate.execute("DELETE FROM main.tenant_api_key");
            jdbcTemplate.execute("DELETE FROM main.tenant");
            jdbcTemplate.execute("DELETE FROM main.sec");
            jdbcTemplate.execute("DELETE FROM main.user_authority WHERE user_id = 675373350208068096");
            jdbcTemplate.execute("DELETE FROM main.\"user\" WHERE id = 675373350208068096");
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id IN (6747751741842104908, 5418719445932238328)");
            return null;
        });
    }

    // ---- helpers ----

    /**
     * Insert a PROCESSING Orange transaction with the given payToken directly via JDBC.
     * Returns the transactionId (UUID string).
     */
    private String createOrangeProcessingTransaction(String payToken) {
        String txId = java.util.UUID.randomUUID().toString();
        String traceId = java.util.UUID.randomUUID().toString();
        long id = System.nanoTime() & Long.MAX_VALUE;
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

    /**
     * Compute expected HMAC-SHA256 signature for verification against the captured request.
     * Algorithm matches WebhookDeliveryService: javax.crypto.Mac with HmacSHA256.
     */
    private String computeExpectedSignature(String secret, String payloadJson) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hmacBytes = mac.doFinal(payloadJson.getBytes(StandardCharsets.UTF_8));
        return "sha256=" + org.apache.commons.codec.binary.Hex.encodeHexString(hmacBytes);
    }

    // ---- tests ----

    /**
     * Test 1: After a SUCCESS transition, webhook is delivered with correct HmacSHA256 signature.
     *
     * Verifies:
     * - Transaction transitions to SUCCESS
     * - Delivery log entry created with delivered=true, httpStatus=200
     * - X-Payam-Signature header is "sha256=<64 hex chars>"
     * - Signature recomputed from captured body matches (algorithm: HmacSHA256, not plain SHA-256)
     */
    @Test
    void shouldDeliverWebhookWithCorrectHmacSignatureOnSuccessTransition() throws Exception {
        String payToken = "pt-del-001";
        String txId = createOrangeProcessingTransaction(payToken);

        // Stub Orange status endpoint returning SUCCESSFULL
        orangeServer.stubFor(get(urlPathMatching("/mp/paymentstatus/.*"))
            .willReturn(okJson("{\"status\":\"SUCCESSFULL\",\"pay_token\":\"" + payToken + "\"}")));

        // Stub tenant webhook endpoint returning 200
        tenantWebhookServer.stubFor(post(urlEqualTo("/webhook"))
            .willReturn(aResponse().withStatus(200).withBody("ok")));

        postOrangeCallback(payToken, "SUCCESSFULL");

        // Allow time for @TransactionalEventListener(AFTER_COMMIT) + delivery to complete
        Thread.sleep(500);

        // Verify transaction transitioned to SUCCESS
        var tx = transactionRepository.findByPayToken(payToken);
        assertThat(tx).isPresent();
        assertThat(tx.get().getTxStatus()).isEqualTo(TransactionStatus.SUCCESS);

        // Verify delivery log entry exists and is marked delivered
        List<WebhookDeliveryLog> logs = deliveryLogRepo.findByTransactionIdOrderByCreatedDateAsc(txId);
        assertThat(logs).isNotEmpty();
        WebhookDeliveryLog log = logs.get(0);
        assertThat(log.getDelivered()).isTrue();
        assertThat(log.getHttpStatus()).isEqualTo(200);

        // Verify the tenant webhook endpoint received the POST
        tenantWebhookServer.verify(postRequestedFor(urlEqualTo("/webhook")));

        // Verify HMAC-SHA256 algorithm: capture the logged request and recompute signature
        LoggedRequest loggedRequest = tenantWebhookServer.findAll(
            postRequestedFor(urlEqualTo("/webhook"))).get(0);
        String receivedSignature = loggedRequest.getHeader("X-Payam-Signature");
        String receivedBody = loggedRequest.getBodyAsString();

        // Recompute expected signature using same algorithm as WebhookDeliveryService
        String expectedSignature = computeExpectedSignature(WEBHOOK_SECRET, receivedBody);
        assertThat(receivedSignature).isEqualTo(expectedSignature);
        // Confirm format: sha256=<64 hex chars>
        assertThat(receivedSignature).matches("sha256=[0-9a-f]{64}");

        // Fee field must appear in outbound webhook payload
        assertThat(receivedBody).contains("\"feeAmount\"");
        // Parse the body as a Map to assert the value is numeric 0 (no fee rule for this tenant)
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> bodyMap = mapper.readValue(receivedBody, java.util.Map.class);
        assertThat(bodyMap).containsKey("feeAmount");
        Object feeAmountValue = bodyMap.get("feeAmount");
        assertThat(feeAmountValue).isNotNull();
        // BigDecimal serializes as a JSON number; Jackson reads it as Integer(0) or Double(0.0) for zero
        assertThat(new java.math.BigDecimal(feeAmountValue.toString()))
            .isEqualByComparingTo(java.math.BigDecimal.ZERO);
    }

    /**
     * Test 2: A 503 from the tenant endpoint leaves delivery in retry-pending state.
     *
     * Verifies:
     * - Transaction still transitions to SUCCESS (delivery failure does not block inbound path)
     * - Delivery log entry has delivered=false, attemptCount >= 1, nextRetryAt != null
     * - httpStatus == 503
     */
    @Test
    void shouldScheduleRetryWhenTenantEndpointReturns503() throws Exception {
        String payToken = "pt-del-002";
        String txId = createOrangeProcessingTransaction(payToken);

        // Stub Orange status endpoint returning SUCCESSFULL
        orangeServer.stubFor(get(urlPathMatching("/mp/paymentstatus/.*"))
            .willReturn(okJson("{\"status\":\"SUCCESSFULL\",\"pay_token\":\"" + payToken + "\"}")));

        // Stub tenant webhook endpoint returning 503
        tenantWebhookServer.stubFor(post(urlEqualTo("/webhook"))
            .willReturn(aResponse().withStatus(503).withBody("Service Unavailable")));

        postOrangeCallback(payToken, "SUCCESSFULL");

        Thread.sleep(500);

        // Transaction still transitions to SUCCESS — delivery failure is async, does not block
        var tx = transactionRepository.findByPayToken(payToken);
        assertThat(tx).isPresent();
        assertThat(tx.get().getTxStatus()).isEqualTo(TransactionStatus.SUCCESS);

        // Delivery log entry should show failure with retry scheduled
        List<WebhookDeliveryLog> logs = deliveryLogRepo.findByTransactionIdOrderByCreatedDateAsc(txId);
        assertThat(logs).isNotEmpty();
        WebhookDeliveryLog log = logs.get(0);
        assertThat(log.getDelivered()).isFalse();
        assertThat(log.getAttemptCount()).isGreaterThanOrEqualTo(1);
        assertThat(log.getHttpStatus()).isEqualTo(503);
        assertThat(log.getNextRetryAt()).isNotNull(); // retry scheduled
    }

    /**
     * Test 3: GET /v1/webhooks/deliveries/{transactionId} returns delivery log entries.
     *
     * Verifies:
     * - 200 response from the delivery status endpoint
     * - Response body is a non-empty JSON array
     * - First entry contains the transactionId
     */
    @Test
    void shouldReturnDeliveryLogViaApi() throws Exception {
        String payToken = "pt-del-003";
        String txId = createOrangeProcessingTransaction(payToken);

        // Stub Orange status endpoint returning SUCCESSFULL
        orangeServer.stubFor(get(urlPathMatching("/mp/paymentstatus/.*"))
            .willReturn(okJson("{\"status\":\"SUCCESSFULL\",\"pay_token\":\"" + payToken + "\"}")));

        // Stub tenant webhook endpoint returning 200
        tenantWebhookServer.stubFor(post(urlEqualTo("/webhook"))
            .willReturn(aResponse().withStatus(200).withBody("ok")));

        postOrangeCallback(payToken, "SUCCESSFULL");

        Thread.sleep(500);

        // GET /v1/admin/webhooks/deliveries/{transactionId} with admin JWT
        ResponseEntity<List<WebhookDeliveryLog>> response = noRetryRestTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/admin/webhooks/deliveries/" + txId,
            HttpMethod.GET,
            new HttpEntity<>(adminCookies),
            new ParameterizedTypeReference<List<WebhookDeliveryLog>>() {});

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotEmpty();
        assertThat(response.getBody().get(0).getTransactionId()).isEqualTo(txId);
    }
}
