package com.softropic.payam.e2e.domain;

import com.softropic.payam.e2e.AbstractPayamE2ETest;
import com.softropic.payam.e2e.builder.MtnWebhookPayloadBuilder;
import com.softropic.payam.e2e.builder.PaymentRequestBuilder;
import com.softropic.payam.e2e.builder.TenantBuilder;
import com.softropic.payam.e2e.verify.LedgerVerifier;
import com.softropic.payam.payment.fraud.service.FraudRuleCache;
import com.softropic.payam.mtn.contract.MtnCallbackPayload;
import com.softropic.payam.payment.core.contract.PaymentRequest;
import com.softropic.payam.payment.core.contract.PaymentResponse;
import com.softropic.payam.platform.tenant.repo.TenantRepository;
import com.softropic.payam.platform.tenant.service.TenantService;

import org.awaitility.Awaitility;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * INV-02-TEST: Ledger double-entry invariant.
 *
 * Proves that:
 * 1. Successful payment posts exactly 2 balanced ledger entries (DEBIT + CREDIT).
 * 2. Failed payment posts zero ledger entries.
 * 3. (REVERSED path is not E2E-testable without reversal API — ledger remains balanced on SUCCESS.)
 */
public class LedgerDoubleEntryTest extends AbstractPayamE2ETest {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private FraudRuleCache fraudRuleCache;

    @Test
    void success_postsBalancedDebitCredit() {
        seedFraudRules();

        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{\"name\":\"Test User\"}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));
        mtnServer.stubFor(get(urlPathMatching("/v1_0/requesttopay/.*"))
            .willReturn(okJson("{\"status\":\"SUCCESSFUL\",\"financialTransactionId\":\"fin-ledger-001\"}")));

        TenantBuilder.CreatedTenant tenant = new TenantBuilder()
            .withName("Ledger-Success-Test")
            .create(tenantService, tenantRepository);

        String transactionId = postPaymentAndGetTransactionId(tenant.rawApiKey());
        assertThat(transactionId).isNotNull();

        // Drive to SUCCESS via MTN callback
        MtnCallbackPayload payload = new MtnWebhookPayloadBuilder()
            .forTransaction(transactionId)
            .asSuccessful()
            .build();

        new RestTemplate().exchange(
            "http://localhost:" + serverPort + "/v1/callbacks/mtn",
            HttpMethod.PUT,
            new HttpEntity<>(payload),
            Void.class);

        // Wait for async @TransactionalEventListener(AFTER_COMMIT) processing
        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            String status = jdbcTemplate.queryForObject(
                "SELECT tx_status FROM main.transaction WHERE transaction_id = ?",
                String.class, transactionId);
            assertThat(status).isEqualTo("SUCCESS");
        });

        new LedgerVerifier(jdbcTemplate).assertLedgerBalanced(transactionId);
    }

    @Test
    void failed_postsNoLedgerEntry() {
        seedFraudRules();

        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{\"name\":\"Test User\"}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));
        mtnServer.stubFor(get(urlPathMatching("/v1_0/requesttopay/.*"))
            .willReturn(okJson("{\"status\":\"FAILED\"}")));

        TenantBuilder.CreatedTenant tenant = new TenantBuilder()
            .withName("Ledger-Failed-Test")
            .create(tenantService, tenantRepository);

        String transactionId = postPaymentAndGetTransactionId(tenant.rawApiKey());
        assertThat(transactionId).isNotNull();

        // Drive to FAILED via MTN FAILED callback
        MtnCallbackPayload payload = new MtnWebhookPayloadBuilder()
            .forTransaction(transactionId)
            .asFailed("PAYER_NOT_FOUND")
            .build();

        new RestTemplate().exchange(
            "http://localhost:" + serverPort + "/v1/callbacks/mtn",
            HttpMethod.PUT,
            new HttpEntity<>(payload),
            Void.class);

        // Wait for FAILED state
        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            String status = jdbcTemplate.queryForObject(
                "SELECT tx_status FROM main.transaction WHERE transaction_id = ?",
                String.class, transactionId);
            assertThat(status).isEqualTo("FAILED");
        });

        Integer ledgerCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM main.ledger_entry WHERE transaction_id = ?",
            Integer.class, transactionId);
        assertThat(ledgerCount)
            .as("Failed payment must have zero ledger entries")
            .isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String postPaymentAndGetTransactionId(String rawApiKey) {
        RestTemplate restTemplate = buildNoRetryRestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Api-Key", rawApiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        PaymentRequest req = new PaymentRequestBuilder()
            .withMsisdn("+237672000001")
            .withIdempotencyKey(UUID.randomUUID().toString())
            .build();

        ResponseEntity<PaymentResponse> response = restTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/payments",
            HttpMethod.POST,
            new HttpEntity<>(req, headers),
            PaymentResponse.class);

        assertThat(response.getStatusCode().value())
            .as("Payment initiation must return 202")
            .isEqualTo(202);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().transactionId();
    }

    private void seedFraudRules() {
        transactionTemplate.execute(status -> {
            seedFraudRule(1L, "IP_VELOCITY",      40, 10,   60,   true);
            seedFraudRule(2L, "MSISDN_VELOCITY",  35, 5,    60,   true);
            seedFraudRule(3L, "APP_VELOCITY",     25, 20,   60,   true);
            seedFraudRule(4L, "MSISDN_HOUSEHOLD", 15, 8,    3600, true);
            seedFraudRule(5L, "BLOCK_THRESHOLD",   0, 70,   0,    true);
            return null;
        });
        fraudRuleCache.refreshRules();
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
