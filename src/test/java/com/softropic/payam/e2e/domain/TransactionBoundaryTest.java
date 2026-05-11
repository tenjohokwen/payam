package com.softropic.payam.e2e.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.http.RequestListener;
import com.softropic.payam.e2e.AbstractPayamE2ETest;
import com.softropic.payam.e2e.builder.MtnWebhookPayloadBuilder;
import com.softropic.payam.e2e.builder.PaymentRequestBuilder;
import com.softropic.payam.e2e.builder.TenantBuilder;
import com.softropic.payam.payment.fraud.service.FraudRuleCache;
import com.softropic.payam.mtn.contract.MtnCallbackPayload;
import com.softropic.payam.payment.core.contract.PaymentRequest;
import com.softropic.payam.payment.core.contract.PaymentResponse;
import com.softropic.payam.platform.tenant.repo.TenantRepository;
import com.softropic.payam.platform.tenant.service.TenantService;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TXN-01 through TXN-04: Transaction boundary invariant tests.
 *
 * TXN-01: INIT row committed before provider HTTP call (RequestListener captures DB state).
 * TXN-02: Exception after INIT commit does not roll back the INIT row.
 * TXN-03: Spring Modulith event fires after AFTER_COMMIT (webhook delivery row as indirect proof).
 * TXN-04: Redis NX+EX prevents both concurrent threads from seeing key-absent simultaneously.
 */
public class TransactionBoundaryTest extends AbstractPayamE2ETest {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private FraudRuleCache fraudRuleCache;

    private TenantBuilder.CreatedTenant tenant;
    private RestTemplate noRetryRestTemplate;

    @BeforeEach
    void setUp() {
        // Seed permissive fraud rules
        transactionTemplate.execute(status -> {
            seedFraudRule(1L, "IP_VELOCITY",      40, 200, 60,   true);
            seedFraudRule(2L, "MSISDN_VELOCITY",  35, 200, 60,   true);
            seedFraudRule(3L, "APP_VELOCITY",     25, 200, 60,   true);
            seedFraudRule(4L, "MSISDN_HOUSEHOLD", 15, 200, 3600, true);
            seedFraudRule(5L, "BLOCK_THRESHOLD",   0,  70,  0,   true);
            return null;
        });
        fraudRuleCache.refreshRules();

        tenant = new TenantBuilder()
            .withName("TXN-Boundary-Tenant")
            .create(tenantService, tenantRepository);

        noRetryRestTemplate = buildNoRetryRestTemplate();
    }

    // -------------------------------------------------------------------------
    // TXN-01: INIT row exists in DB before provider HTTP call arrives
    // -------------------------------------------------------------------------

    @Test
    void initRow_existsAtProviderCallTime() {
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));

        AtomicInteger rowCountAtProviderCall = new AtomicInteger(-1);

        // Register listener BEFORE payment POST — captures DB row count at provider call time
        RequestListener listener = (request, response) -> {
            if (request.getUrl().contains("/v1_0/requesttopay") &&
                "POST".equals(request.getMethod().value())) {
                Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM main.transaction WHERE tx_status = 'INITIATED'",
                    Integer.class);
                rowCountAtProviderCall.set(count != null ? count : 0);
            }
        };

        // Note: WireMock 3.x (wiremock-spring-boot 4.0.9) does not expose
        // removeMockServiceRequestListener(). The listener is scoped to this test's AtomicInteger;
        // baseTearDown() calls mtnServer.resetAll() which clears all registered listeners.
        // The listener does not bleed into subsequent tests because resetAll() runs in @AfterEach.
        mtnServer.addMockServiceRequestListener(listener);

        PaymentRequest req = new PaymentRequestBuilder()
            .withMsisdn("+237672000001")
            .withIdempotencyKey(UUID.randomUUID().toString())
            .build();

        ResponseEntity<PaymentResponse> response = postPayment(req);

        assertThat(response.getStatusCode().value())
            .as("POST /v1/payments must return 202")
            .isEqualTo(202);

        // Listener must have fired (rowCount captured, not the initial -1)
        assertThat(rowCountAtProviderCall.get())
            .as("RequestListener must have fired during POST /v1_0/requesttopay")
            .isGreaterThan(-1);

        // INIT row must be in DB when provider call arrives — proves INIT before provider
        assertThat(rowCountAtProviderCall.get())
            .as("INIT row must exist in main.transaction when provider HTTP call arrives (TXN-01)")
            .isGreaterThanOrEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // TXN-02: Exception after INIT commit does not roll back the INIT row
    // -------------------------------------------------------------------------

    @Test
    void exceptionAfterInit_doesNotRollbackInitRow() {
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{}")));
        // Provider returns 500 — orchestrator will mark FAILED
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(500)));

        String idempotencyKey = UUID.randomUUID().toString();
        PaymentRequest req = new PaymentRequestBuilder()
            .withMsisdn("+237672000001")
            .withIdempotencyKey(idempotencyKey)
            .build();

        ResponseEntity<PaymentResponse> response = postPayment(req);

        // Provider error (500) → orchestrator marks FAILED; response may be 4xx/5xx
        assertThat(response.getStatusCode().value())
            .as("Provider 500 must result in non-202 response (TXN-02)")
            .isNotEqualTo(202);

        // THE KEY ASSERTION: the transaction row must still exist (not rolled back to nothing)
        // The INIT commit is durable — even if the provider call fails, the row persists.
        // Count FAILED transactions for this tenant — the row must exist in DB after provider error.
        Integer failedCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM main.transaction WHERE tenant_id = ? AND tx_status = 'FAILED'",
            Integer.class, tenant.tenantId());
        assertThat(failedCount)
            .as("FAILED transaction row must exist for tenant after provider 500 " +
                "(INIT commit is durable, not rolled back) (TXN-02)")
            .isGreaterThanOrEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // TXN-03: Spring Modulith event fires after AFTER_COMMIT
    // -------------------------------------------------------------------------

    @Test
    void modulithEvent_firesAfterCommit() {
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));
        mtnServer.stubFor(get(urlPathMatching("/v1_0/requesttopay/.*"))
            .willReturn(okJson("{\"status\":\"SUCCESSFUL\",\"financialTransactionId\":\"fin-txn03\"}")));

        String idempotencyKey = UUID.randomUUID().toString();
        PaymentRequest req = new PaymentRequestBuilder()
            .withMsisdn("+237672000001")
            .withIdempotencyKey(idempotencyKey)
            .build();

        ResponseEntity<PaymentResponse> response = postPayment(req);
        assertThat(response.getStatusCode().value())
            .as("POST /v1/payments must return 202")
            .isEqualTo(202);
        assertThat(response.getBody()).isNotNull();
        String transactionId = response.getBody().transactionId();

        // Drive to SUCCESS via MTN PUT webhook
        sendMtnSuccessCallback(transactionId);

        // TXN-03: @TransactionalEventListener(AFTER_COMMIT) fires asynchronously after the
        // webhook transaction commits. The WebhookDoubleCheckHandler fires, calls the provider,
        // and WebhookTransitionService posts PROVIDER_SUCCESS to the event log.
        // Assert that PROVIDER_SUCCESS event exists — proves AFTER_COMMIT event fired end-to-end.
        Awaitility.await().atMost(8, TimeUnit.SECONDS).until(() -> {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM main.payment_event_log " +
                "WHERE transaction_id = ? AND event_type = 'PROVIDER_SUCCESS'",
                Integer.class, transactionId);
            return count != null && count >= 1;
        });

        // Additional assertion: transaction reached SUCCESS state
        String txStatus = jdbcTemplate.queryForObject(
            "SELECT tx_status FROM main.transaction WHERE transaction_id = ?",
            String.class, transactionId);
        assertThat(txStatus)
            .as("Transaction must be SUCCESS after AFTER_COMMIT event fired (TXN-03)")
            .isEqualTo("SUCCESS");
    }

    // -------------------------------------------------------------------------
    // TXN-04: Redis NX+EX prevents both concurrent threads from seeing key-absent
    // -------------------------------------------------------------------------

    @Test
    void redisNx_preventsDoubleReservation() throws Exception {
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));

        // Same idempotency key for both threads — tests Redis NX+EX atomicity
        String sharedIdempotencyKey = UUID.randomUUID().toString();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);

        Future<ResponseEntity<PaymentResponse>> t1 = pool.submit(() -> {
            try { barrier.await(10, TimeUnit.SECONDS); } catch (Exception ignored) {}
            return postPayment(new PaymentRequestBuilder()
                .withMsisdn("+237672000001")
                .withIdempotencyKey(sharedIdempotencyKey)
                .build());
        });

        Future<ResponseEntity<PaymentResponse>> t2 = pool.submit(() -> {
            try { barrier.await(10, TimeUnit.SECONDS); } catch (Exception ignored) {}
            return postPayment(new PaymentRequestBuilder()
                .withMsisdn("+237672000001")
                .withIdempotencyKey(sharedIdempotencyKey)
                .build());
        });

        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        ResponseEntity<PaymentResponse> r1 = t1.get();
        ResponseEntity<PaymentResponse> r2 = t2.get();

        // Both responses must be 202 (one is a fresh reservation; the other may be an idempotency
        // deduplication returning the same transactionId). Either way, no 5xx errors.
        assertThat(r1.getStatusCode().value())
            .as("Thread 1 response must be 202 (TXN-04)")
            .isEqualTo(202);
        assertThat(r2.getStatusCode().value())
            .as("Thread 2 response must be 202 (TXN-04)")
            .isEqualTo(202);

        // Exactly 1 transaction row must exist — Redis NX+EX ensured only one reservation.
        // Use idempotency_key table to find the transaction for this tenant+key.
        Integer rowCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM main.transaction t " +
            "JOIN main.idempotency_key ik ON ik.tenant_id = t.tenant_id " +
            "WHERE ik.idempotency_key = ?",
            Integer.class, sharedIdempotencyKey);
        assertThat(rowCount)
            .as("Exactly 1 transaction row must exist for the shared idempotency key (TXN-04)")
            .isEqualTo(1);

        // Exactly 1 provider call — only one thread should have initiated the provider request
        mtnServer.verify(exactly(1), postRequestedFor(urlPathEqualTo("/v1_0/requesttopay")));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ResponseEntity<PaymentResponse> postPayment(PaymentRequest req) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", tenant.rawApiKey());

        String body;
        try {
            body = new ObjectMapper().writeValueAsString(req);
        } catch (Exception e) {
            throw new RuntimeException("Serialization failed", e);
        }

        return noRetryRestTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/payments",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            PaymentResponse.class);
    }

    private void sendMtnSuccessCallback(String transactionId) {
        MtnCallbackPayload payload = new MtnWebhookPayloadBuilder()
            .forTransaction(transactionId)
            .asSuccessful()
            .build();

        noRetryRestTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/callbacks/mtn",
            HttpMethod.PUT,
            new HttpEntity<>(payload),
            Void.class);
    }

    private void seedFraudRule(long id, String signalName, int weight, int threshold,
                               int windowSeconds, boolean enabled) {
        jdbcTemplate.update(
            "INSERT INTO main.fraud_rule " +
            "(id, status, signal_name, weight, threshold, window_seconds, enabled, description) " +
            "VALUES (?, 'ACTIVE', ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (id) DO UPDATE SET threshold = EXCLUDED.threshold",
            id, signalName, weight, threshold, windowSeconds, enabled, signalName + " test rule");
    }

    private RestTemplate buildNoRetryRestTemplate() {
        RestTemplate rt = new RestTemplate(new SimpleClientHttpRequestFactory());
        rt.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(HttpStatusCode statusCode) { return false; }
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException { return false; }
        });
        return rt;
    }
}
