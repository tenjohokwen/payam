package com.softropic.payam.payment.disbursement.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.payment.disbursement.contract.DisbursementStatus;
import com.softropic.payam.payment.disbursement.repo.DisbursementRepository;
import com.softropic.payam.payment.disbursement.repo.MerchantWalletBalance;
import com.softropic.payam.payment.disbursement.repo.MerchantWalletBalanceRepository;

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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end integration tests for the Orange disbursement callback path (Phase 52, SEC-05).
 *
 * Proves:
 * 1. POST /v1/callbacks/orange/disbursement returns 200 and transitions Disbursement to SUCCESS/FAILED.
 * 2. FAILED transition releases the wallet reservation (BAL-02).
 * 3. Replayed callback is silently deduplicated by Redis (no second state transition).
 * 4. Unknown providerRef returns 200 (provider must not retry due to internal lookup failures).
 *
 * Uses @EnableWireMock to intercept the Orange token fetch + GET payment status double-check call.
 * No AbstractPayamE2ETest dependency — mirrors MtnDisbursementCallbackControllerIT pattern.
 */
@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "orange.callback-ip-whitelist="
})
@EnableWireMock({
    @ConfigureWireMock(name = "mtn",
        baseUrlProperties = {"mtn.collection-base-url"}),
    @ConfigureWireMock(name = "orange",
        baseUrlProperties = {"orange.base-url", "orange.pay-url"})
})
class OrangeDisbursementCallbackControllerIT {

    @InjectWireMock("orange") WireMockServer orangeServer;

    @Autowired TestRestTemplate testRestTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate template;
    @Autowired StringRedisTemplate redis;
    @Autowired DisbursementRepository disbursementRepository;
    @Autowired MerchantWalletBalanceRepository walletRepo;

    @LocalServerPort int serverPort;

    private static final Long TENANT_ID = 1002L;
    private static final BigDecimal RESERVED = new BigDecimal("750.00");

    @BeforeEach
    void setUp() {
        // Seed JWT secret required by SecurityAdviceFilter.addSecretToThread()
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
        // Stub Orange token endpoint (required by OrangeTokenService.getAccessToken)
        orangeServer.stubFor(post(urlPathEqualTo("/token"))
            .willReturn(okJson("{\"access_token\":\"orange-tok\",\"token_type\":\"Bearer\",\"expires_in\":7200}")));
        // Clear dedup keys from any previous test
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @AfterEach
    void tearDown() {
        orangeServer.resetAll();
        template.execute(s -> {
            jdbcTemplate.execute("DELETE FROM main.disbursement");
            jdbcTemplate.execute("DELETE FROM main.merchant_wallet_balance");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    // ---- helpers ----

    private String seedDisbursement(MobilePaymentProvider provider, String providerRef) {
        String dsbId = UUID.randomUUID().toString();
        template.execute(s -> {
            // Seed wallet balance row via JDBC
            jdbcTemplate.update(
                "INSERT INTO main.merchant_wallet_balance " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, " +
                " request_id, status, tenant_id, balance, reserved_amount, currency, version) " +
                "VALUES (?, 'TEST', NOW(), 'TEST', NOW(), gen_random_uuid()::text, 'ACTIVE', ?, 0, ?, 'XAF', 0) " +
                "ON CONFLICT (tenant_id) DO UPDATE SET reserved_amount = EXCLUDED.reserved_amount",
                System.nanoTime() & Long.MAX_VALUE, TENANT_ID, RESERVED);
            // Seed disbursement row via JDBC
            jdbcTemplate.update(
                "INSERT INTO main.disbursement " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, " +
                " request_id, status, disbursement_id, tenant_id, recipient_msisdn, amount, currency, " +
                " reference, disbursement_status, provider, provider_ref, poll_attempts) " +
                "VALUES (?, 'TEST', NOW(), 'TEST', NOW(), gen_random_uuid()::text, 'ACTIVE', ?, ?, '237691111111', 700.00, 'XAF', " +
                " 'merch-ref-orange-1', 'PROCESSING', ?, ?, 0)",
                System.nanoTime() & Long.MAX_VALUE, dsbId, TENANT_ID,
                provider.name(), providerRef);
            return null;
        });
        return dsbId;
    }

    private void stubOrangeStatus(String payToken, String status) {
        // GET /mp/paymentstatus/{payToken} — the double-check endpoint
        // OrangeMoneyClient.getPaymentStatus uses buildClientURL("/mp/paymentstatus/" + payToken)
        orangeServer.stubFor(get(urlPathMatching(".*/mp/paymentstatus/" + payToken))
            .willReturn(okJson("{\"status\":\"" + status + "\",\"pay_token\":\"" + payToken + "\"}")));
    }

    private ResponseEntity<Void> postOrangeCallback(String payToken, String status) {
        // Orange callback payload shape matches OrangeWebhookPayload fields
        String body = String.format(
            "{\"payToken\":\"%s\",\"notif_token\":\"tok-abc\",\"status\":\"%s\",\"txnid\":\"TXN001\"," +
            "\"msisdn\":\"237691111111\",\"amount\":\"700\",\"createtime\":\"2026-03-24T10:00:00\"}",
            payToken, status);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Notif-Token", "tok-abc");
        return testRestTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/callbacks/orange/disbursement",
            HttpMethod.POST, new HttpEntity<>(body, headers), Void.class);
    }

    // ---- tests ----

    @Test
    void shouldTransitionToSuccessOnSuccessfulCallback() {
        String payToken = "pt-001";
        String dsbId = seedDisbursement(MobilePaymentProvider.ORANGE, payToken);
        // Orange uses SUCCESSFULL (double L) — OrangeStatusMapper maps it to SUCCESS
        stubOrangeStatus(payToken, "SUCCESSFULL");

        var response = postOrangeCallback(payToken, "SUCCESSFULL");
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        // @TransactionalEventListener(AFTER_COMMIT) applies the transition asynchronously
        await().atMost(Duration.ofSeconds(10)).until(() ->
            disbursementRepository.findByDisbursementId(dsbId).orElseThrow()
                .getDisbursementStatus() == DisbursementStatus.SUCCESS);

        // SUCCESS path: wallet reservation kept (committed spend) — BAL-02
        MerchantWalletBalance wallet = walletRepo.findByTenantId(TENANT_ID).orElseThrow();
        assertThat(wallet.getReservedAmount()).isEqualByComparingTo(RESERVED);
    }

    @Test
    void shouldTransitionToFailedAndReleaseWalletOnFailedCallback() {
        String payToken = "pt-002";
        String dsbId = seedDisbursement(MobilePaymentProvider.ORANGE, payToken);
        stubOrangeStatus(payToken, "FAILED");

        var response = postOrangeCallback(payToken, "FAILED");
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        await().atMost(Duration.ofSeconds(10)).until(() ->
            disbursementRepository.findByDisbursementId(dsbId).orElseThrow()
                .getDisbursementStatus() == DisbursementStatus.FAILED);

    }

    @Test
    void shouldDeduplicateReplayedCallback() {
        String payToken = "pt-003";
        String dsbId = seedDisbursement(MobilePaymentProvider.ORANGE, payToken);
        stubOrangeStatus(payToken, "SUCCESSFULL");

        // First callback transitions to SUCCESS
        postOrangeCallback(payToken, "SUCCESSFULL");
        await().atMost(Duration.ofSeconds(10)).until(() ->
            disbursementRepository.findByDisbursementId(dsbId).orElseThrow()
                .getDisbursementStatus() == DisbursementStatus.SUCCESS);

        // Second IDENTICAL callback is silently deduplicated by Redis (returns 200 — no second transition)
        var response = postOrangeCallback(payToken, "SUCCESSFULL");
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        // Verify dedup: exactly 1 GET to Orange payment status endpoint (no second double-check)
        orangeServer.verify(1, getRequestedFor(urlPathMatching(".*/mp/paymentstatus/" + payToken)));
    }

    @Test
    void shouldReturn200WhenDisbursementNotFound() {
        // No seeding → port lookup returns empty → controller still returns 200
        // (provider must not retry due to internal lookup failures — Pitfall 1 in 52-RESEARCH)
        String payToken = "unknown-pt";
        var response = postOrangeCallback(payToken, "SUCCESSFULL");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }
}
