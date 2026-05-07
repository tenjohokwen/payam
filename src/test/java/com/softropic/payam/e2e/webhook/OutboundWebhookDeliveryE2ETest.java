package com.softropic.payam.e2e.webhook;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.softropic.payam.config.E2ESecurityConfig;
import com.softropic.payam.config.TestDataCleaner;
import com.softropic.payam.config.WireMockConfig;
import com.softropic.payam.config.PostgresContainerConfig;
import com.softropic.payam.config.RedisContainerConfig;
import com.softropic.payam.config.TestClockConfig;
import com.softropic.payam.config.TestMailConfig;
import com.softropic.payam.e2e.builder.MtnWebhookPayloadBuilder;
import com.softropic.payam.e2e.builder.TenantBuilder;
import com.softropic.payam.e2e.verify.WebhookDeliveryVerifier;
import com.softropic.payam.mtn.contract.MtnCallbackPayload;
import com.softropic.payam.platform.tenant.repo.TenantRepository;
import com.softropic.payam.platform.tenant.service.TenantService;
import com.softropic.payam.webhook.repo.WebhookDeliveryLog;
import com.softropic.payam.webhook.repo.WebhookDeliveryLogRepository;
import com.softropic.payam.webhook.service.WebhookDeliveryService;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import org.awaitility.Awaitility;
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
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FLOWS-HOOK-04 + FLOWS-HOOK-05: Outbound webhook delivery — HMAC-signed delivery and 5xx retry scheduling.
 *
 * Standalone @SpringBootTest (does NOT extend AbstractPayamE2ETest) because the test requires a
 * third "tenant-wh" WireMock server for the tenant callback endpoint. AbstractPayamE2ETest only
 * declares mtn and orange servers; subclasses cannot add to the parent @EnableWireMock.
 *
 * Pattern mirrors WebhookDeliveryIT — all three WireMock servers declared at class level.
 *
 * FLOWS-HOOK-04: outboundWebhookDeliveredWithHmacSignature
 *   - MTN PUT webhook drives SUCCESS transition
 *   - Tenant callback receives POST with X-Payam-Signature header
 *   - HMAC-SHA256 signature verified correct against "test-secret" and payload body
 *   - delivery log shows delivered=true
 *
 * FLOWS-HOOK-05: outboundWebhookRetriesOn5xx
 *   - 503 from tenant callback leaves delivery in retry-pending state
 *   - delivery log shows delivered=false, attemptCount>=1, httpStatus=503, nextRetryAt not null
 *   - Two additional direct attemptDelivery() calls produce attemptCount>=3
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import({PostgresContainerConfig.class, RedisContainerConfig.class,
         E2ESecurityConfig.class, TestClockConfig.class, TestMailConfig.class})
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "mtn.callback-ip-whitelist=",
    "orange.callback-ip-whitelist="
})
@EnableWireMock({
    @ConfigureWireMock(name = "mtn",       baseUrlProperties = {"mtn.collection-base-url"}),
    @ConfigureWireMock(name = "orange",    baseUrlProperties = {"orange.base-url", "orange.pay-url"}),
    @ConfigureWireMock(name = "tenant-wh") // URL assigned dynamically to Tenant entity — no baseUrlProperties
})
@ActiveProfiles({"dev", "test"})
public class OutboundWebhookDeliveryE2ETest {

    @LocalServerPort
    int serverPort;

    @InjectWireMock("mtn")
    WireMockServer mtnServer;

    @InjectWireMock("orange")
    WireMockServer orangeServer;

    @InjectWireMock("tenant-wh")
    WireMockServer tenantCallbackServer;

    @Autowired TenantService tenantService;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired WebhookDeliveryLogRepository deliveryLogRepo;
    @Autowired WebhookDeliveryService webhookDeliveryService;
    @Autowired StringRedisTemplate redis;
    @Autowired TestDataCleaner testDataCleaner;
    @Autowired CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired E2ESecurityConfig e2eSecurityConfig;

    @BeforeEach
    void setUp() {
        mtnServer.resetAll();
        orangeServer.resetAll();
        tenantCallbackServer.resetAll();

        // Seed main.sec JWT secret required by SecurityAdviceFilter
        e2eSecurityConfig.seedSecurityRow();

        // Flush Redis — removes cached tokens and dedup keys from any previous test
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();

        // Reset circuit breakers to CLOSED state
        circuitBreakerRegistry.circuitBreaker("mtn").reset();
        circuitBreakerRegistry.circuitBreaker("orange").reset();

        // Re-stub token endpoints (flushing Redis removes cached tokens)
        mtnServer.stubFor(post(urlPathEqualTo("/token/"))
            .willReturn(okJson(WireMockConfig.MTN_TOKEN_RESPONSE)));
        orangeServer.stubFor(post(urlPathEqualTo("/token"))
            .willReturn(okJson(WireMockConfig.ORANGE_TOKEN_RESPONSE)));
    }

    @AfterEach
    void tearDown() {
        mtnServer.resetAll();
        orangeServer.resetAll();
        tenantCallbackServer.resetAll();
        testDataCleaner.wipeAll();
    }

    // ---- FLOWS-HOOK-04 ----

    /**
     * FLOWS-HOOK-04: After MTN webhook drives transaction to SUCCESS, outbound webhook is delivered
     * to tenant callback URL with X-Payam-Signature HMAC-SHA256 header.
     *
     * Drives a full inbound-to-outbound cycle: insert PROCESSING transaction, send MTN PUT
     * callback, wait for double-check + outbound delivery, assert HMAC header correct.
     */
    @Test
    void outboundWebhookDeliveredWithHmacSignature() {
        // Stub MTN double-check endpoint — SUCCESSFUL (single-L per MtnStatusMapper)
        mtnServer.stubFor(get(urlPathMatching("/v1_0/requesttopay/.*"))
            .willReturn(okJson("{\"status\":\"SUCCESSFUL\",\"financialTransactionId\":\"fin-outbound-001\"}")));

        // Stub tenant callback to return 200
        tenantCallbackServer.stubFor(post(urlEqualTo("/webhook"))
            .willReturn(aResponse().withStatus(200).withBody("ok")));

        // Provision tenant with webhook URL and secret
        String webhookUrl = tenantCallbackServer.baseUrl() + "/webhook";
        TenantBuilder.CreatedTenant tenant = new TenantBuilder()
            .withName("Outbound-HMAC-Tenant")
            .withWebhookUrl(webhookUrl, "test-secret")
            .create(tenantService, tenantRepository);

        // Insert PROCESSING MTN transaction directly via JDBC
        String referenceId = UUID.randomUUID().toString();
        String transactionId = insertMtnProcessingTransaction(referenceId, tenant.tenantId());

        // Dispatch MTN PUT callback (MTN is PUT not POST — pitfall 1)
        MtnCallbackPayload payload = new MtnWebhookPayloadBuilder()
            .forTransaction(transactionId)
            .asSuccessful()
            .build();
        new TestRestTemplate().exchange(
            "http://localhost:" + serverPort + "/v1/callbacks/mtn",
            HttpMethod.PUT,
            new HttpEntity<>(payload),
            Void.class);

        // Wait for @TransactionalEventListener(AFTER_COMMIT) + outbound delivery to complete
        WebhookDeliveryVerifier verifier = new WebhookDeliveryVerifier(jdbcTemplate, tenantCallbackServer);
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
            verifier.assertDelivered(transactionId));

        // Assert X-Payam-Signature header present (format: sha256=<hex>)
        verifier.assertHmacHeaderPresent(tenantCallbackServer, transactionId);

        // Recompute HMAC from captured request to verify signature correctness
        // CRITICAL: WebhookDeliveryVerifier uses HmacSHA256 — must NOT use DigestUtils.sha256Hex
        LoggedRequest logged = tenantCallbackServer
            .findAll(postRequestedFor(urlEqualTo("/webhook"))).get(0);
        verifier.assertHmacSignatureCorrect(
            logged.getBodyAsString(), "test-secret", logged.getHeader("X-Payam-Signature"));
    }

    // ---- FLOWS-HOOK-05 ----

    /**
     * FLOWS-HOOK-05: When tenant callback URL returns 503, delivery log shows delivered=false,
     * attemptCount>=1, httpStatus=503, nextRetryAt not null.
     * After 3 direct attemptDelivery() calls, delivery log shows attemptCount>=3.
     *
     * Uses direct attemptDelivery() invocation to bypass Quartz scheduler timing — deterministic.
     * WebhookDeliveryService.MAX_ATTEMPTS = 5, so after 3 attempts nextRetryAt stays non-null.
     */
    @Test
    void outboundWebhookRetriesOn5xx() throws InterruptedException {
        // Stub MTN double-check endpoint — SUCCESSFUL
        mtnServer.stubFor(get(urlPathMatching("/v1_0/requesttopay/.*"))
            .willReturn(okJson("{\"status\":\"SUCCESSFUL\",\"financialTransactionId\":\"fin-outbound-002\"}")));

        // Stub tenant callback to return 503
        tenantCallbackServer.stubFor(post(urlEqualTo("/webhook"))
            .willReturn(aResponse().withStatus(503).withBody("Service Unavailable")));

        // Provision tenant with webhook URL and secret
        String webhookUrl = tenantCallbackServer.baseUrl() + "/webhook";
        TenantBuilder.CreatedTenant tenant = new TenantBuilder()
            .withName("Outbound-Retry-Tenant")
            .withWebhookUrl(webhookUrl, "test-secret")
            .create(tenantService, tenantRepository);

        // Insert PROCESSING MTN transaction directly via JDBC
        String referenceId = UUID.randomUUID().toString();
        String transactionId = insertMtnProcessingTransaction(referenceId, tenant.tenantId());

        // Dispatch MTN PUT callback
        MtnCallbackPayload payload = new MtnWebhookPayloadBuilder()
            .forTransaction(transactionId)
            .asSuccessful()
            .build();
        new TestRestTemplate().exchange(
            "http://localhost:" + serverPort + "/v1/callbacks/mtn",
            HttpMethod.PUT,
            new HttpEntity<>(payload),
            Void.class);

        // Wait for @TransactionalEventListener(AFTER_COMMIT) + first inline delivery attempt to complete
        // Thread.sleep is acceptable here — the first attempt is synchronous in enqueue()
        // and we need the delivery log row to exist before reading it
        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() ->
            !deliveryLogRepo.findByTransactionIdOrderByCreatedDateAsc(transactionId).isEmpty());

        // Assert first attempt state: delivered=false, attemptCount>=1, httpStatus=503, nextRetryAt not null
        List<WebhookDeliveryLog> logs = deliveryLogRepo
            .findByTransactionIdOrderByCreatedDateAsc(transactionId);
        assertThat(logs).hasSize(1);
        WebhookDeliveryLog log = logs.get(0);
        assertThat(log.getDelivered()).isFalse();
        assertThat(log.getAttemptCount()).isGreaterThanOrEqualTo(1);
        assertThat(log.getHttpStatus()).isEqualTo(503);
        assertThat(log.getNextRetryAt()).isNotNull();
        assertThat(log.getNextRetryAt()).isAfter(java.time.Instant.now());

        // Trigger two more direct delivery attempts (bypasses Quartz scheduler — deterministic)
        // MAX_ATTEMPTS = 5, so after 3 attempts nextRetryAt stays non-null
        webhookDeliveryService.attemptDelivery(logs.get(0));
        WebhookDeliveryLog after2 = deliveryLogRepo.findById(logs.get(0).getId()).orElseThrow();
        webhookDeliveryService.attemptDelivery(after2);
        WebhookDeliveryLog after3 = deliveryLogRepo.findById(logs.get(0).getId()).orElseThrow();

        assertThat(after3.getAttemptCount()).isGreaterThanOrEqualTo(3);
        assertThat(after3.getDelivered()).isFalse();
        assertThat(after3.getNextRetryAt()).isNotNull(); // still below MAX_ATTEMPTS=5
    }

    // ---- helpers ----

    /**
     * Insert a PROCESSING MTN transaction with the given referenceId directly via JDBC.
     * The referenceId (provider_ref) is the correlation key for MTN double-check.
     * Returns the transactionId (UUID string).
     */
    private String insertMtnProcessingTransaction(String referenceId, Long tenantId) {
        String txId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        long id = System.nanoTime() & Long.MAX_VALUE;
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.transaction " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, " +
                "transaction_id, trace_id, tenant_id, tx_status, status, provider, amount, currency, provider_ref) " +
                "VALUES (?, 'TEST', NOW(), 'TEST', NOW(), ?, ?, ?, 'PROCESSING', 'ACTIVE', 'MTN', 100, 'XAF', ?)",
                id, txId, traceId, tenantId, referenceId);
            return null;
        });
        return txId;
    }
}
