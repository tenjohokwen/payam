package com.softropic.payam.disbursement.webhook;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.common.payment.ProviderResult;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.disbursement.repo.DisbursementRepository;
import com.softropic.payam.disbursement.repo.MerchantWalletBalanceRepository;
import com.softropic.payam.disbursement.service.DisbursementCallbackTransitionService;
import com.softropic.payam.platform.tenant.contract.ApiKeyEnvironment;
import com.softropic.payam.platform.tenant.repo.TenantRepository;
import com.softropic.payam.platform.tenant.service.TenantService;
import com.softropic.payam.transaction.contract.LedgerFlow;
import com.softropic.payam.transaction.contract.TransactionStatus;
import com.softropic.payam.payment.webhook.contract.WebhookReceivedEvent;
import com.softropic.payam.payment.webhook.repo.WebhookDeliveryLog;
import com.softropic.payam.payment.webhook.repo.WebhookDeliveryLogRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end integration tests for outbound webhook delivery after disbursement terminal transitions.
 * Proves SEC-06: a terminal disbursement transition fires an outbound webhook POST to the tenant's
 * webhookUrl with X-Payam-Signature HMAC-SHA256 header, correct eventType, and retry on non-2xx.
 *
 * Strategy: invoke DisbursementCallbackTransitionService.applyDisbursementTransition() directly
 * (simulates what WebhookDoubleCheckHandler does after the double-check). The @Transactional(REQUIRES_NEW)
 * on applyDisbursementTransition commits, which fires @TransactionalEventListener(AFTER_COMMIT) in
 * WebhookDeliveryService → webhook POST to the tenant-wh WireMock server.
 *
 * Does NOT use AbstractPayamE2ETest — mirrors WebhookDeliveryIT standalone pattern.
 */
@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "orange.callback-ip-whitelist=",
    "mtn.callback-ip-whitelist="
})
@EnableWireMock({
    @ConfigureWireMock(name = "orange",          baseUrlProperties = {"orange.base-url", "orange.pay-url"}),
    @ConfigureWireMock(name = "mtn",              baseUrlProperties = {"mtn.collection-base-url"}),
    @ConfigureWireMock(name = "mtn-disbursement", baseUrlProperties = {"mtn.disbursement-base-url"}),
    @ConfigureWireMock(name = "tenant-wh")  // no baseUrlProperties — URL set dynamically on Tenant
})
class DisbursementWebhookDeliveryIT {

    @InjectWireMock("tenant-wh") WireMockServer tenantWebhookServer;

    @Autowired TenantService tenantService;
    @Autowired TenantRepository tenantRepository;
    @Autowired DisbursementRepository disbursementRepository;
    @Autowired MerchantWalletBalanceRepository walletRepo;
    @Autowired WebhookDeliveryLogRepository deliveryLogRepo;
    @Autowired DisbursementCallbackTransitionService transitionService;
    @Autowired TransactionTemplate template;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redis;

    private Long tenantId;
    private static final String WEBHOOK_SECRET = "test-secret";

    @BeforeEach
    void setUp() {
        // Seed JWT secret required by SecurityAdviceFilter
        template.execute(status -> {
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

        // Create tenant with a unique name
        var result = tenantService.createTenant("dsb-wh-" + UUID.randomUUID(), ApiKeyEnvironment.PROD);
        tenantId = result.tenant().getId();

        // Set webhook URL and secret on tenant via JDBC direct UPDATE
        template.execute(status -> {
            jdbcTemplate.update(
                "UPDATE main.tenant SET webhook_url = ?, webhook_secret = ? WHERE id = ?",
                tenantWebhookServer.baseUrl() + "/wh", WEBHOOK_SECRET, tenantId);
            return null;
        });

        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @AfterEach
    void tearDown() {
        tenantWebhookServer.resetAll();
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();

        // FK-safe DELETE order
        template.execute(status -> {
            jdbcTemplate.execute("DELETE FROM main.webhook_delivery_log");
            jdbcTemplate.execute("DELETE FROM main.disbursement");
            jdbcTemplate.execute("DELETE FROM main.merchant_wallet_balance");
            jdbcTemplate.execute("DELETE FROM main.tenant_api_key");
            jdbcTemplate.execute("DELETE FROM main.tenant");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    // ---- helpers ----

    /**
     * Seed a Disbursement in PROCESSING state and return its disbursementId.
     * Seeds the wallet balance as well (required for wallet release assertions on FAILED).
     */
    private String seedDisbursementInProcessing() {
        String dsbId = UUID.randomUUID().toString();
        String providerRef = "ref-" + UUID.randomUUID();
        template.execute(s -> {
            jdbcTemplate.update(
                "INSERT INTO main.merchant_wallet_balance " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, " +
                " request_id, status, tenant_id, balance, reserved_amount, currency, version) " +
                "VALUES (?, 'TEST', NOW(), 'TEST', NOW(), gen_random_uuid()::text, 'ACTIVE', ?, 0, 750.00, 'XAF', 0) " +
                "ON CONFLICT (tenant_id) DO UPDATE SET reserved_amount = 750.00",
                System.nanoTime() & Long.MAX_VALUE, tenantId);
            jdbcTemplate.update(
                "INSERT INTO main.disbursement " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, " +
                " request_id, status, disbursement_id, tenant_id, recipient_msisdn, amount, currency, " +
                " reference, disbursement_status, provider, provider_ref, poll_attempts) " +
                "VALUES (?, 'TEST', NOW(), 'TEST', NOW(), gen_random_uuid()::text, 'ACTIVE', ?, ?, '237691111111', 700.00, 'XAF', " +
                " 'merch-ref-wh-1', 'PROCESSING', 'MTN', ?, 0)",
                System.nanoTime() & Long.MAX_VALUE, dsbId, tenantId, providerRef);
            return null;
        });
        return dsbId;
    }

    /**
     * Invoke DisbursementCallbackTransitionService.applyDisbursementTransition directly.
     * The method is @Transactional(REQUIRES_NEW) — calling it outside a transaction is fine;
     * Spring opens a new transaction that commits immediately, firing AFTER_COMMIT listeners.
     */
    private void triggerTransition(String dsbId, String rawStatus) {
        // Build a synthetic WebhookReceivedEvent — transactionId = dsbId (disbursement flow)
        WebhookReceivedEvent event = new WebhookReceivedEvent(
            dsbId,
            MobilePaymentProvider.MTN,
            "ref-stub",
            dsbId,   // traceId
            LedgerFlow.DISBURSEMENT
        );
        ProviderResult result = rawStatus.equals("SUCCESSFUL") || rawStatus.equals("SUCCESSFULL")
            ? ProviderResult.success("ref-stub", rawStatus)
            : ProviderResult.pending("ref-stub", rawStatus);  // non-success treated as FAILED by resolver
        transitionService.applyDisbursementTransition(event, result);
    }

    // ---- tests ----

    /**
     * Test 1: SUCCESS transition delivers signed webhook with correct HMAC-SHA256 and eventType.
     */
    @Test
    void shouldDeliverSignedPayloadOnSuccessTransition() throws Exception {
        String dsbId = seedDisbursementInProcessing();

        // Stub tenant webhook returning 200
        tenantWebhookServer.stubFor(post(urlEqualTo("/wh"))
            .willReturn(aResponse().withStatus(200).withBody("ok")));

        triggerTransition(dsbId, "SUCCESSFUL");

        // Wait for @TransactionalEventListener(AFTER_COMMIT) to fire and delivery log to appear
        await().atMost(Duration.ofSeconds(10)).until(() ->
            !deliveryLogRepo.findByTransactionIdOrderByCreatedDateAsc(dsbId).isEmpty());

        List<WebhookDeliveryLog> logs = deliveryLogRepo.findByTransactionIdOrderByCreatedDateAsc(dsbId);
        assertThat(logs).hasSize(1);
        WebhookDeliveryLog log = logs.get(0);

        // Assert delivery result
        assertThat(log.getDelivered()).isTrue();
        assertThat(log.getAttemptCount()).isEqualTo(1);
        assertThat(log.getEventType()).isEqualTo("DISBURSEMENT_COMPLETED");
        assertThat(log.getTransactionStatus()).isEqualTo(TransactionStatus.SUCCESS);

        // Assert tenant URL was hit exactly once
        tenantWebhookServer.verify(1, postRequestedFor(urlEqualTo("/wh")));

        // Assert X-Payam-Signature header is present and correct format
        LoggedRequest capturedRequest = tenantWebhookServer
            .findAll(postRequestedFor(urlEqualTo("/wh"))).get(0);
        String signature = capturedRequest.getHeader("X-Payam-Signature");
        assertThat(signature).isNotNull();
        assertThat(signature).matches("sha256=[0-9a-f]{64}");

        // Verify signature matches recomputed HMAC-SHA256
        String body = capturedRequest.getBodyAsString();
        String expectedSignature = computeHmac(WEBHOOK_SECRET, body);
        assertThat(signature).isEqualTo(expectedSignature);

        // Payload body contains "SUCCESS" status (derived from eventType DISBURSEMENT_COMPLETED)
        assertThat(body).contains("\"status\":\"SUCCESS\"");
    }

    /**
     * Test 2: FAILED transition delivers webhook with eventType=DISBURSEMENT_FAILED.
     */
    @Test
    void shouldDeliverFailedEventOnFailedTransition() {
        String dsbId = seedDisbursementInProcessing();

        // Stub tenant webhook returning 200
        tenantWebhookServer.stubFor(post(urlEqualTo("/wh"))
            .willReturn(aResponse().withStatus(200).withBody("ok")));

        // rawStatus "PENDING" resolves to FAILED via DisbursementCallbackTransitionService.resolveTarget
        triggerTransition(dsbId, "PENDING");

        await().atMost(Duration.ofSeconds(10)).until(() ->
            !deliveryLogRepo.findByTransactionIdOrderByCreatedDateAsc(dsbId).isEmpty());

        List<WebhookDeliveryLog> logs = deliveryLogRepo.findByTransactionIdOrderByCreatedDateAsc(dsbId);
        WebhookDeliveryLog log = logs.get(0);

        assertThat(log.getDelivered()).isTrue();
        assertThat(log.getEventType()).isEqualTo("DISBURSEMENT_FAILED");
        assertThat(log.getTransactionStatus()).isEqualTo(TransactionStatus.FAILED);

        // Payload body must contain "FAILED" status
        LoggedRequest capturedRequest = tenantWebhookServer
            .findAll(postRequestedFor(urlEqualTo("/wh"))).get(0);
        assertThat(capturedRequest.getBodyAsString()).contains("\"status\":\"FAILED\"");
    }

    /**
     * Test 3: Non-2xx from tenant endpoint schedules a retry.
     */
    @Test
    void shouldScheduleRetryWhen5xxFromTenant() {
        String dsbId = seedDisbursementInProcessing();

        // Stub tenant webhook returning 500
        tenantWebhookServer.stubFor(post(urlEqualTo("/wh"))
            .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

        triggerTransition(dsbId, "SUCCESSFUL");

        // Wait for delivery log row to appear
        await().atMost(Duration.ofSeconds(10)).until(() ->
            !deliveryLogRepo.findByTransactionIdOrderByCreatedDateAsc(dsbId).isEmpty());

        List<WebhookDeliveryLog> logs = deliveryLogRepo.findByTransactionIdOrderByCreatedDateAsc(dsbId);
        WebhookDeliveryLog log = logs.get(0);

        // Delivery should have failed and retry scheduled
        assertThat(log.getDelivered()).isFalse();
        // Verify attemptCount incremented (first attempt)
        Integer attemptCount = log.getAttemptCount();
        assertThat(attemptCount).isEqualTo(1);
        // Verify nextRetryAt is set in the future (exponential back-off)
        Instant nextRetryAt = log.getNextRetryAt();
        assertThat(nextRetryAt).isNotNull();
        assertThat(nextRetryAt).isAfter(Instant.now());

        // Tenant URL was hit exactly once (the inline first attempt)
        tenantWebhookServer.verify(1, postRequestedFor(urlEqualTo("/wh")));
    }

    /**
     * Test 4: Tenant with no webhookUrl gets no delivery row and tenant URL is never called.
     */
    @Test
    void shouldNotEnqueueWhenTenantHasNoWebhookUrl() throws Exception {
        // Remove webhookUrl from tenant
        template.execute(status -> {
            jdbcTemplate.update("UPDATE main.tenant SET webhook_url = NULL WHERE id = ?", tenantId);
            return null;
        });

        String dsbId = seedDisbursementInProcessing();

        triggerTransition(dsbId, "SUCCESSFUL");

        // Wait briefly to ensure any async processing has completed
        Thread.sleep(500);

        // No delivery log row should have been created
        List<WebhookDeliveryLog> logs = deliveryLogRepo.findByTransactionIdOrderByCreatedDateAsc(dsbId);
        assertThat(logs).isEmpty();

        // Tenant webhook server was never called
        tenantWebhookServer.verify(0, postRequestedFor(anyUrl()));
    }

    // ---- private helpers ----

    private String computeHmac(String secret, String payload) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
            secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hmacBytes = mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "sha256=" + org.apache.commons.codec.binary.Hex.encodeHexString(hmacBytes);
    }
}
