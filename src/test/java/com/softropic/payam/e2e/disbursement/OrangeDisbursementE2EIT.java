package com.softropic.payam.e2e.disbursement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.config.TestDataCleaner;
import com.softropic.payam.disbursement.contract.DisbursementOrchestratorError;
import com.softropic.payam.disbursement.contract.DisbursementStatus;
import com.softropic.payam.disbursement.repo.DisbursementRepository;
import com.softropic.payam.disbursement.repo.MerchantWalletBalanceRepository;
import com.softropic.payam.platform.service.PlatformConfigService;
import com.softropic.payam.tenant.contract.ApiKeyEnvironment;
import com.softropic.payam.tenant.service.TenantService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end integration test for Orange Money disbursement lifecycle (TEST-02).
 *
 * <p>Proves the full HTTP-level Orange disbursement lifecycle:
 * <ol>
 *   <li>Happy path: POST /v1/disbursements → 202 PROCESSING → Orange callback SUCCESSFULL → SUCCESS</li>
 *   <li>Insufficient balance: POST /v1/disbursements with amount > wallet → 422 INSUFFICIENT_BALANCE; zero /cashout calls</li>
 *   <li>Callback replay: identical Orange callback deduplicated; only 1 double-check GET to /mp/paymentstatus</li>
 * </ol>
 *
 * <p>Orange-specific quirks honored:
 * <ul>
 *   <li>Cashout endpoint is {@code /cashout} (NOT {@code /ic2c/pay})</li>
 *   <li>Success status is {@code SUCCESSFULL} (double-L)</li>
 *   <li>Callback double-check is GET {@code /mp/paymentstatus/{payToken}}</li>
 *   <li>Callback endpoint is POST {@code /v1/callbacks/orange/disbursement}</li>
 * </ul>
 *
 * <p>Standalone IT — does NOT extend AbstractPayamE2ETest because that base class only wires
 * {@code mtn.collection-base-url} and not {@code mtn.disbursement-base-url}. Each disbursement
 * E2E test must configure its own full dual-WireMock topology.
 */
@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "mtn.callback-ip-whitelist=",
    "orange.callback-ip-whitelist=",
    "mtn.collection-token-url=http://localhost:${wiremock.mtn.port}/token/collection",
    "mtn.disbursement-token-url=http://localhost:${wiremock.mtn.port}/token/disbursement"
})
@EnableWireMock({
    @ConfigureWireMock(name = "mtn",
        baseUrlProperties = {"mtn.collection-base-url", "mtn.disbursement-base-url"},
        portProperties    = {"wiremock.mtn.port"}),
    @ConfigureWireMock(name = "orange",
        baseUrlProperties = {"orange.base-url", "orange.pay-url"},
        portProperties    = {"wiremock.orange.port"})
})
class OrangeDisbursementE2EIT {

    private static final String ORANGE_MSISDN = "+237691234567";
    private static final BigDecimal PRINCIPAL = new BigDecimal("5000");

    @InjectWireMock("mtn")    WireMockServer mtnServer;
    @InjectWireMock("orange") WireMockServer orangeServer;

    @Autowired TestRestTemplate testRestTemplate;
    @Autowired TenantService tenantService;
    @Autowired DisbursementRepository disbursementRepository;
    @Autowired MerchantWalletBalanceRepository walletRepo;
    @Autowired PlatformConfigService platformConfigService;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired TestDataCleaner testDataCleaner;

    @LocalServerPort int serverPort;

    private Long tenantId;
    private String rawApiKey;

    @BeforeEach
    void setUp() {
        testDataCleaner.wipeAll();

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

        // Create tenant and capture raw API key for X-Api-Key header
        var created = tenantService.createTenant("orange-e2e-" + UUID.randomUUID(), ApiKeyEnvironment.PROD);
        tenantId = created.tenant().getId();
        rawApiKey = created.rawKey();

        // Seed wallet with 1,000,000 XAF (large enough for happy path; insufficient balance test uses 2,000,000)
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.merchant_wallet_balance " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, " +
                "request_id, status, tenant_id, balance, reserved_amount, currency, version) " +
                "VALUES (?, 'TEST', NOW(), 'TEST', NOW(), gen_random_uuid()::text, 'ACTIVE', ?, ?, 0, 'XAF', 0)",
                System.currentTimeMillis(), tenantId, new BigDecimal("1000000"));
            return null;
        });

        // Stub MTN token endpoints (required — mtn.disbursement-base-url is configured even if unused in Orange tests)
        mtnServer.stubFor(post(urlPathEqualTo("/token/collection"))
            .willReturn(okJson("{\"access_token\":\"mtn-coll-bearer\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
        mtnServer.stubFor(post(urlPathEqualTo("/token/disbursement"))
            .willReturn(okJson("{\"access_token\":\"mtn-disb-bearer\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));

        // Stub Orange token endpoint
        orangeServer.stubFor(post(urlPathEqualTo("/token"))
            .withHeader("Authorization", containing("Basic"))
            .willReturn(okJson("{\"access_token\":\"orange-bearer\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));

        // Seed Orange platform config for channelMsisdn (required by OrangeMoneyPort.validateAccountHolder)
        platformConfigService.update("ORANGE", "691000000");

        // Flush Redis to avoid idempotency/velocity bleed between tests
        try {
            redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        } catch (Exception ignored) {}
    }

    @AfterEach
    void tearDown() {
        mtnServer.resetAll();
        orangeServer.resetAll();
        try {
            redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        } catch (Exception ignored) {}
        testDataCleaner.wipeAll();
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 1: Orange happy path — initiate → 202 PROCESSING → callback SUCCESSFULL → SUCCESS
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void orangeHappyPath_initiateThenCallbackSuccessful_transitionsToSuccess() throws Exception {
        stubOrangeSubscriberAndCashout();

        List<String> txnIds = seedTxnsForClaim(tenantId, 1, PRINCIPAL);
        ResponseEntity<String> initResponse = postDisbursement(
            ORANGE_MSISDN, PRINCIPAL, "REF-OR-HAPPY-" + UUID.randomUUID(), "IDEM-OR-HAPPY-" + UUID.randomUUID(), txnIds);

        assertThat(initResponse.getStatusCode().value())
            .as("POST /v1/disbursements must return 202 Accepted")
            .isEqualTo(202);

        String disbursementId = parseDisbursementId(initResponse.getBody());
        String status = parseStatus(initResponse.getBody());
        assertThat(disbursementId).as("disbursementId must be present in 202 response").isNotBlank();
        assertThat(status).as("status must be PROCESSING").isEqualTo("PROCESSING");

        // CLAIM-01: claim row created in PENDING state at initiation time (before callback)
        assertClaimStatuses(disbursementId, "PENDING", 1);

        // Fetch providerRef (payToken) from DB — Orange stores cashout providerRef
        String providerRef = fetchProviderRef(disbursementId);
        assertThat(providerRef).as("providerRef (payToken) must be set after Orange cashout call").isNotNull();

        // Stub the double-check GET endpoint — Orange uses SUCCESSFULL (double-L)
        stubOrangePaymentStatus(providerRef, "SUCCESSFULL");

        // Dispatch the Orange disbursement callback (SUCCESSFULL)
        ResponseEntity<Void> callbackResponse = postOrangeCallback(providerRef, "SUCCESSFULL");
        assertThat(callbackResponse.getStatusCode().value())
            .as("Orange callback POST must return 200")
            .isEqualTo(200);

        // @TransactionalEventListener(AFTER_COMMIT) fires asynchronously — wait for SUCCESS transition
        await().atMost(Duration.ofSeconds(10)).until(() ->
            disbursementRepository.findByDisbursementId(disbursementId).orElseThrow()
                .getDisbursementStatus() == DisbursementStatus.SUCCESS);

        // CLAIM-02: claim transitions PENDING→CLAIMED inside the AFTER_COMMIT listener
        awaitClaimStatuses(disbursementId, "CLAIMED", 1);

        // Verify exactly 1 cashout call was made to Orange (not 0, not 2)
        assertThat(orangeServer.findAll(postRequestedFor(urlPathEqualTo("/cashout"))))
            .as("exactly 1 POST /cashout must be made for Orange disbursement")
            .hasSize(1);
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 2: Insufficient balance → 422 INSUFFICIENT_BALANCE; Orange /cashout NOT called
    // ────────────────────────────────────────────────────────────────────────────────

    @Disabled("INSUFFICIENT_BALANCE path retired in SCHEMA-03 — wallet model removed, " +
              "orchestrator never checks merchant_wallet_balance. " +
              "Phase 56 ADMIN-02 or Phase 57 IDEM-02 may introduce a replacement guard.")
    @Test
    void insufficientBalance_returns422_andOrangeCashoutNotCalled() {
        // Stub Orange subscriber endpoint only — cashout must NOT be called
        orangeServer.stubFor(post(urlPathMatching("/infos/subscriber/customer/.*"))
            .willReturn(okJson("{\"data\":{\"firstname\":\"Jean\"},\"message\":\"OK\"}")));

        // Use fresh RestTemplate with error handler override so 422 does NOT throw an exception
        RestTemplate rt = new RestTemplate();
        rt.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(HttpStatusCode statusCode) {
                return false;
            }

            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return false;
            }
        });

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", rawApiKey);
        headers.set("Idempotency-Key", "IDEM-OR-INSUF-" + UUID.randomUUID());

        // Amount 2,000,000 exceeds wallet balance of 1,000,000 — must trigger INSUFFICIENT_BALANCE
        List<String> txnIds = seedTxnsForClaim(tenantId, 1, new BigDecimal("2000000"));
        String body = buildDisbursementBody(ORANGE_MSISDN, new BigDecimal("2000000"), "REF-OR-INSUF-" + UUID.randomUUID(), txnIds);
        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        ResponseEntity<String> response = rt.exchange(
            "http://localhost:" + serverPort + "/v1/disbursements",
            HttpMethod.POST, entity, String.class);

        assertThat(response.getStatusCode().value())
            .as("Insufficient balance must return HTTP 422")
            .isEqualTo(422);

        String errorCode = parseErrorCode(response.getBody());
        assertThat(errorCode)
            .as("errorCode must be INSUFFICIENT_BALANCE")
            .isEqualTo(DisbursementOrchestratorError.INSUFFICIENT_BALANCE.getErrorCode());

        // Assert ZERO calls to Orange cashout — provider must not be contacted
        orangeServer.verify(0, postRequestedFor(urlPathEqualTo("/cashout")));
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 3: Replayed Orange callback is deduplicated — only 1 double-check GET
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void orangeReplayedCallback_isDeduplicated() throws Exception {
        stubOrangeSubscriberAndCashout();

        List<String> txnIds = seedTxnsForClaim(tenantId, 1, PRINCIPAL);
        ResponseEntity<String> initResponse = postDisbursement(
            ORANGE_MSISDN, PRINCIPAL, "REF-OR-REPLAY-" + UUID.randomUUID(), "IDEM-OR-REPLAY-" + UUID.randomUUID(), txnIds);

        assertThat(initResponse.getStatusCode().value()).isEqualTo(202);

        String disbursementId = parseDisbursementId(initResponse.getBody());
        String providerRef = fetchProviderRef(disbursementId);
        assertThat(providerRef).isNotNull();

        stubOrangePaymentStatus(providerRef, "SUCCESSFULL");

        // First callback — should trigger transition to SUCCESS
        ResponseEntity<Void> firstCallback = postOrangeCallback(providerRef, "SUCCESSFULL");
        assertThat(firstCallback.getStatusCode().value()).isEqualTo(200);

        // Wait for SUCCESS transition before dispatching the replay
        await().atMost(Duration.ofSeconds(10)).until(() ->
            disbursementRepository.findByDisbursementId(disbursementId).orElseThrow()
                .getDisbursementStatus() == DisbursementStatus.SUCCESS);

        // Second IDENTICAL callback — must be silently deduplicated (returns 200)
        ResponseEntity<Void> secondCallback = postOrangeCallback(providerRef, "SUCCESSFULL");
        assertThat(secondCallback.getStatusCode().value())
            .as("Replayed Orange callback must return 200 (idempotent)")
            .isEqualTo(200);

        // Dedup assertion: exactly 1 double-check GET to /mp/paymentstatus/{providerRef}
        // The second callback is deduplicated before the double-check is triggered
        orangeServer.verify(1, getRequestedFor(urlPathMatching(".*/mp/paymentstatus/" + providerRef)));
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 4: CLAIM-03 — FAILED callback releases claims to RELEASED, and the SAME
    //         transactionIds can back a brand-new disbursement (proves the partial
    //         unique index does NOT block RELEASED rows).
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void orangeFailedCallback_releasesClaimsAndAllowsReuse_transitionsToFailed() {
        stubOrangeSubscriberAndCashout();

        // Step 1 — Initiate first disbursement (will fail)
        List<String> txnIds = seedTxnsForClaim(tenantId, 1, PRINCIPAL);
        String firstIdem = "IDEM-OR-FAIL-" + UUID.randomUUID();
        ResponseEntity<String> firstResponse = postDisbursement(
            ORANGE_MSISDN, PRINCIPAL, "REF-OR-FAIL-" + UUID.randomUUID(),
            firstIdem, txnIds);
        assertThat(firstResponse.getStatusCode().value()).isEqualTo(202);
        String firstDsbId = parseDisbursementId(firstResponse.getBody());
        assertThat(firstDsbId).isNotBlank();
        // Sanity: claim is PENDING right after initiation
        assertClaimStatuses(firstDsbId, "PENDING", 1);

        String providerRef = fetchProviderRef(firstDsbId);
        assertThat(providerRef).isNotNull();

        // Step 2 — Stub the double-check GET to return FAILED then dispatch failure callback
        stubOrangePaymentStatus(providerRef, "FAILED");
        ResponseEntity<Void> callbackResponse = postOrangeCallback(providerRef, "FAILED");
        assertThat(callbackResponse.getStatusCode().value()).isEqualTo(200);

        // Step 3 — Wait for FAILED transition (AFTER_COMMIT listener)
        await().atMost(Duration.ofSeconds(10)).until(() ->
            disbursementRepository.findByDisbursementId(firstDsbId).orElseThrow()
                .getDisbursementStatus() == DisbursementStatus.FAILED);

        // Step 4 — CLAIM-03: claims released to RELEASED via AFTER_COMMIT listener
        awaitClaimStatuses(firstDsbId, "RELEASED", 1);

        // Step 5 — Reuse the SAME transactionIds in a brand-new disbursement.
        //         The partial unique index uq_dtr_txn_active_claim does NOT include
        //         RELEASED rows, so a fresh PENDING claim row CAN be inserted.
        //         Different idempotency key required (Pitfall 4).
        String secondIdem = "IDEM-OR-REUSE-" + UUID.randomUUID();
        ResponseEntity<String> secondResponse = postDisbursement(
            ORANGE_MSISDN, PRINCIPAL, "REF-OR-REUSE-" + UUID.randomUUID(),
            secondIdem, txnIds);
        assertThat(secondResponse.getStatusCode().value())
            .as("Reuse of RELEASED transactionIds must succeed with 202 PROCESSING")
            .isEqualTo(202);
        String secondDsbId = parseDisbursementId(secondResponse.getBody());
        assertThat(secondDsbId)
            .as("Second disbursement must have a NEW disbursementId")
            .isNotBlank()
            .isNotEqualTo(firstDsbId);

        // Step 6 — Second disbursement also has its own PENDING claim row
        assertClaimStatuses(secondDsbId, "PENDING", 1);
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────────────

    /**
     * Stubs Orange subscriber validation and cashout endpoints for happy path.
     *
     * <p>The cashout stub returns {@code payToken} in the response body. This allows
     * {@code OrangeMoneyPort.initiateDisbursement} to extract and store it as
     * {@code provider_ref}, enabling {@code processDisbursementCallback} to look up
     * the disbursement via {@code findByProviderRef(payToken)}.
     */
    private void stubOrangeSubscriberAndCashout() {
        orangeServer.stubFor(post(urlPathMatching("/infos/subscriber/customer/.*"))
            .willReturn(okJson("{\"data\":{\"firstname\":\"Jean\"},\"message\":\"OK\"}")));
        // Orange cashout endpoint — response includes payToken for providerRef correlation
        orangeServer.stubFor(post(urlPathEqualTo("/cashout"))
            .willReturn(okJson("{\"status\":\"SUCCESS\",\"payToken\":\"PAY-TOKEN-OR-TEST\"}")));
    }

    /** Stubs Orange double-check GET endpoint for callback processing. */
    private void stubOrangePaymentStatus(String payToken, String status) {
        orangeServer.stubFor(get(urlPathMatching(".*/mp/paymentstatus/" + payToken))
            .willReturn(okJson("{\"status\":\"" + status + "\",\"pay_token\":\"" + payToken + "\"}")));
    }

    private List<String> seedTxnsForClaim(Long tenantId, int count, BigDecimal eachAmount) {
        List<String> ids = new java.util.ArrayList<>();
        final ThreadLocalRandom threadLocalRandom = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            Long id = threadLocalRandom.nextLong();
            transactionTemplate.execute(s -> {
                jdbcTemplate.update(
                        "INSERT INTO main.transaction " +
                                "(id, transaction_id, trace_id, tenant_id, provider, tx_status, flow, " +
                                " amount, fee_amount, currency, created_by, created_date, last_modified_by, " +
                                " last_modified_date, request_id, status) " +
                                "VALUES (?, ?, ?, ?, 'MTN', 'SUCCESS', 'COLLECTION', " +
                                "       ?, 0, 'XAF', 'TEST', NOW(), 'TEST', NOW(), gen_random_uuid()::text, 'ACTIVE')",
                        id, id, id, tenantId, eachAmount);
                return null;
            });
            ids.add(String.valueOf(id));
        }
        return ids;
    }

    /** Posts a disbursement via HTTP to /v1/disbursements with X-Api-Key + Idempotency-Key headers. */
    private ResponseEntity<String> postDisbursement(
            String msisdn, BigDecimal amount, String reference, String idempotencyKey,
            List<String> transactionIds) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", rawApiKey);
        headers.set("Idempotency-Key", idempotencyKey);

        String body = buildDisbursementBody(msisdn, amount, reference, transactionIds);
        return testRestTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/disbursements",
            HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    /** Builds the JSON body for POST /v1/disbursements. */
    private String buildDisbursementBody(String msisdn, BigDecimal amount, String reference,
                                          List<String> transactionIds) {
        String txnIdsJson = transactionIds.stream()
            .map(id -> "\"" + id + "\"")
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        return String.format(
            "{\"recipientMsisdn\":\"%s\",\"amount\":%s,\"currency\":\"XAF\"," +
            "\"reference\":\"%s\",\"transactionIds\":%s}",
            msisdn, amount.toPlainString(), reference, txnIdsJson);
    }

    /** Posts an Orange disbursement callback to /v1/callbacks/orange/disbursement. */
    private ResponseEntity<Void> postOrangeCallback(String payToken, String status) {
        String body = String.format(
            "{\"payToken\":\"%s\",\"notif_token\":\"tok-abc\",\"status\":\"%s\",\"txnid\":\"TXN001\"," +
            "\"msisdn\":\"237691111111\",\"amount\":\"5000\",\"createtime\":\"2026-03-24T10:00:00\"}",
            payToken, status);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Notif-Token", "tok-abc");
        return testRestTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/callbacks/orange/disbursement",
            HttpMethod.POST, new HttpEntity<>(body, headers), Void.class);
    }

    /** Fetches the providerRef (Orange cashout reference/payToken) for a disbursement by disbursementId. */
    private String fetchProviderRef(String disbursementId) {
        return jdbcTemplate.queryForObject(
            "SELECT provider_ref FROM main.disbursement WHERE disbursement_id = ?",
            String.class, disbursementId);
    }

    /** Parses the disbursementId field from a JSON response body. */
    private String parseDisbursementId(String body) {
        try {
            return new ObjectMapper().readTree(body).path("disbursementId").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Parses the status field from a JSON response body. */
    private String parseStatus(String body) {
        try {
            return new ObjectMapper().readTree(body).path("status").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Parses the errorCode field from a JSON response body; returns null if absent. */
    private String parseErrorCode(String body) {
        try {
            String val = new ObjectMapper().readTree(body).path("errorCode").asText(null);
            return (val != null && val.isBlank()) ? null : val;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Assert that the disbursement_transaction_ref rows for the given disbursement UUID
     * all have the expected ref_status. Uses raw JDBC to avoid JPA first-level cache.
     * Pattern from DisbursementAdminApprovalExpiryJobIT.
     */
    private void assertClaimStatuses(String disbursementId, String expectedStatus, int expectedCount) {
        List<String> statuses = jdbcTemplate.queryForList(
            "SELECT ref_status FROM main.disbursement_transaction_ref " +
            "WHERE disbursement_id = (SELECT id FROM main.disbursement WHERE disbursement_id = ?)",
            String.class, disbursementId);
        assertThat(statuses).hasSize(expectedCount);
        assertThat(statuses).containsOnly(expectedStatus);
    }

    /**
     * Await the disbursement_transaction_ref rows reaching the expected ref_status.
     * Required because PENDING→CLAIMED and PENDING→RELEASED happen inside AFTER_COMMIT.
     */
    private void awaitClaimStatuses(String disbursementId, String expectedStatus, int expectedCount) {
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            List<String> statuses = jdbcTemplate.queryForList(
                "SELECT ref_status FROM main.disbursement_transaction_ref " +
                "WHERE disbursement_id = (SELECT id FROM main.disbursement WHERE disbursement_id = ?)",
                String.class, disbursementId);
            return statuses.size() == expectedCount && statuses.stream().allMatch(expectedStatus::equals);
        });
    }
}
