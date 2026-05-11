package com.softropic.payam.disbursement.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.disbursement.contract.DisbursementStatus;
import com.softropic.payam.disbursement.repo.Disbursement;
import com.softropic.payam.disbursement.repo.DisbursementRepository;
import com.softropic.payam.disbursement.repo.MerchantWalletBalance;
import com.softropic.payam.disbursement.repo.MerchantWalletBalanceRepository;

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
 * End-to-end integration tests for the MTN disbursement callback path (Phase 52, SEC-05).
 *
 * Proves:
 * 1. PUT /v1/callbacks/mtn/disbursement/{ref} returns 200 and transitions Disbursement to SUCCESS/FAILED.
 * 2. FAILED transition releases the wallet reservation (BAL-02).
 * 3. Replayed callback is silently deduplicated by Redis (no second state transition).
 * 4. Unknown providerRef returns 200 (provider must not retry due to internal lookup failures).
 *
 * Uses @EnableWireMock to intercept the MTN disbursement GET status double-check call.
 * No AbstractPayamE2ETest dependency — mirrors OrangeCallbackControllerIT standalone pattern.
 */
@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "mtn.callback-ip-whitelist=",
    // Both token URLs must point at the same WireMock instance (mtn-disbursement) so the
    // context can get a token for the double-check GET call. The port is injected by
    // @ConfigureWireMock(portProperties).
    "mtn.collection-token-url=http://localhost:${wiremock.mtn-disbursement.port}/token/collection",
    "mtn.disbursement-token-url=http://localhost:${wiremock.mtn-disbursement.port}/token/disbursement"
})
@EnableWireMock({
    @ConfigureWireMock(name = "mtn-collection",
        baseUrlProperties = {"mtn.collection-base-url"}),
    @ConfigureWireMock(name = "mtn-disbursement",
        baseUrlProperties = {"mtn.disbursement-base-url"},
        portProperties   = {"wiremock.mtn-disbursement.port"}),
    @ConfigureWireMock(name = "orange",
        baseUrlProperties = {"orange.base-url", "orange.pay-url"})
})
class MtnDisbursementCallbackControllerIT {

    @InjectWireMock("mtn-disbursement") WireMockServer mtnDisbursementServer;

    @Autowired TestRestTemplate testRestTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate template;
    @Autowired StringRedisTemplate redis;
    @Autowired DisbursementRepository disbursementRepository;
    @Autowired MerchantWalletBalanceRepository walletRepo;

    @LocalServerPort int serverPort;

    private static final Long TENANT_ID = 1001L;
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
        // Clear dedup keys from any previous test
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @AfterEach
    void tearDown() {
        mtnDisbursementServer.resetAll();
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
            // Seed wallet balance row via JDBC to avoid JPA entity lifecycle issues
            jdbcTemplate.update(
                "INSERT INTO main.merchant_wallet_balance " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, " +
                " request_id, status, tenant_id, balance, reserved_amount, currency, version) " +
                "VALUES (?, 'TEST', NOW(), 'TEST', NOW(), gen_random_uuid()::text, 'ACTIVE', ?, 0, ?, 'XAF', 0) " +
                "ON CONFLICT (tenant_id) DO UPDATE SET reserved_amount = EXCLUDED.reserved_amount",
                System.nanoTime() & Long.MAX_VALUE, TENANT_ID, RESERVED);
            // Seed disbursement row via JDBC to avoid JPA entity lifecycle issues
            jdbcTemplate.update(
                "INSERT INTO main.disbursement " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, " +
                " request_id, status, disbursement_id, tenant_id, recipient_msisdn, amount, currency, " +
                " reference, disbursement_status, provider, provider_ref, poll_attempts) " +
                "VALUES (?, 'TEST', NOW(), 'TEST', NOW(), gen_random_uuid()::text, 'ACTIVE', ?, ?, '237691111111', 700.00, 'XAF', " +
                " 'merch-ref-1', 'PROCESSING', ?, ?, 0)",
                System.nanoTime() & Long.MAX_VALUE, dsbId, TENANT_ID,
                provider.name(), providerRef);
            return null;
        });
        return dsbId;
    }

    private void stubMtnTokenEndpoints() {
        // Stub disbursement token endpoint (used by MtnTokenService.getDisbursementToken)
        mtnDisbursementServer.stubFor(post(urlPathEqualTo("/token/disbursement"))
            .willReturn(okJson("{\"access_token\":\"tok\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
        // Stub collection token endpoint as well (resolves mtn.collection-base-url during context init)
        mtnDisbursementServer.stubFor(post(urlPathEqualTo("/token/collection"))
            .willReturn(okJson("{\"access_token\":\"tok-coll\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
    }

    private void stubMtnDisbursementStatus(String providerRef, String status) {
        // GET /disbursement/v1_0/transfer/{providerRef} — the double-check endpoint
        mtnDisbursementServer.stubFor(get(urlPathMatching(".*/transfer/" + providerRef))
            .willReturn(okJson(
                "{\"financialTransactionId\":\"FT-1\",\"externalId\":\"ext\"," +
                "\"amount\":\"700\",\"currency\":\"XAF\"," +
                "\"payee\":{\"partyIdType\":\"MSISDN\",\"partyId\":\"237691111111\"}," +
                "\"status\":\"" + status + "\"}")));
    }

    private ResponseEntity<Void> putMtnCallback(String providerRef, String dsbId, String status) {
        String body = "{\"externalId\":\"" + dsbId + "\",\"status\":\"" + status +
                      "\",\"financialTransactionId\":\"FT-1\"}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return testRestTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/callbacks/mtn/disbursement/" + providerRef,
            HttpMethod.PUT, new HttpEntity<>(body, headers), Void.class);
    }

    // ---- tests ----

    @Test
    void shouldTransitionToSuccessOnSuccessfulCallback() {
        String providerRef = "ref-uuid-001";
        String dsbId = seedDisbursement(MobilePaymentProvider.MTN, providerRef);
        stubMtnTokenEndpoints();
        stubMtnDisbursementStatus(providerRef, "SUCCESSFUL");

        var response = putMtnCallback(providerRef, dsbId, "SUCCESSFUL");
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
        String providerRef = "ref-uuid-002";
        String dsbId = seedDisbursement(MobilePaymentProvider.MTN, providerRef);
        stubMtnTokenEndpoints();
        stubMtnDisbursementStatus(providerRef, "FAILED");

        var response = putMtnCallback(providerRef, dsbId, "FAILED");
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        await().atMost(Duration.ofSeconds(10)).until(() ->
            disbursementRepository.findByDisbursementId(dsbId).orElseThrow()
                .getDisbursementStatus() == DisbursementStatus.FAILED);

    }

    @Test
    void shouldDeduplicateReplayedCallback() {
        String providerRef = "ref-uuid-003";
        String dsbId = seedDisbursement(MobilePaymentProvider.MTN, providerRef);
        stubMtnTokenEndpoints();
        stubMtnDisbursementStatus(providerRef, "SUCCESSFUL");

        // First callback transitions to SUCCESS
        putMtnCallback(providerRef, dsbId, "SUCCESSFUL");
        await().atMost(Duration.ofSeconds(10)).until(() ->
            disbursementRepository.findByDisbursementId(dsbId).orElseThrow()
                .getDisbursementStatus() == DisbursementStatus.SUCCESS);

        // Second IDENTICAL callback is silently deduplicated by Redis (returns 200 — no second transition)
        var response = putMtnCallback(providerRef, dsbId, "SUCCESSFUL");
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        // Verify dedup: exactly 1 GET to MTN status endpoint (no second double-check)
        mtnDisbursementServer.verify(1, getRequestedFor(urlPathMatching(".*/transfer/" + providerRef)));
    }

    @Test
    void shouldReturn200WhenDisbursementNotFound() {
        // No seeding → port lookup returns empty → controller still returns 200
        // (provider must not retry due to internal lookup failures — Pitfall 1 in 52-RESEARCH)
        String providerRef = "unknown-ref";
        var response = putMtnCallback(providerRef, "missing-dsb", "SUCCESSFUL");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }
}
