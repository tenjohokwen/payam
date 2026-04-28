package com.softropic.payam.e2e.disbursement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.config.TestDataCleaner;
import com.softropic.payam.disbursement.contract.DisbursementOrchestratorError;
import com.softropic.payam.disbursement.repo.DisbursementRepository;
import com.softropic.payam.disbursement.repo.MerchantWalletBalanceRepository;
import com.softropic.payam.platform.service.PlatformConfigService;
import com.softropic.payam.tenant.contract.ApiKeyEnvironment;
import com.softropic.payam.tenant.service.TenantService;

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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E integration test for the step-up confirmation flow (TEST-03 part 1).
 *
 * <p>Proves at the HTTP layer:
 * <ul>
 *   <li>POST /v1/disbursements with amount > 500,000 XAF returns HTTP 202 with
 *       status=PENDING_CONFIRMATION and makes ZERO MTN /v1_0/transfer calls.</li>
 *   <li>GET /v1/disbursements/{id} on a PENDING_CONFIRMATION row returns
 *       status=PENDING_CONFIRMATION (DISB-02 read consistency).</li>
 *   <li>POST /v1/disbursements/{id}/confirm transitions PENDING_CONFIRMATION → PROCESSING
 *       and triggers exactly 1 MTN transfer call.</li>
 *   <li>POST /v1/disbursements/{id}/confirm on an already-PROCESSING disbursement returns
 *       HTTP 422 with errorCode=INVALID_STATE and no extra provider call.</li>
 * </ul>
 *
 * <p>Standalone topology (no AbstractPayamE2ETest base) — required because the base class
 * only configures mtn.collection-base-url; disbursement port needs mtn.disbursement-base-url.
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
class StepUpConfirmationE2EIT {

    private static final String MTN_MSISDN  = "+237671234567";
    private static final BigDecimal STEP_UP_AMOUNT = new BigDecimal("600000"); // above 500,000 threshold
    private static final BigDecimal SMALL_AMOUNT   = new BigDecimal("5000");   // below threshold

    @InjectWireMock("mtn")    WireMockServer mtnServer;
    @InjectWireMock("orange") WireMockServer orangeServer;

    @Autowired TestRestTemplate testRestTemplate;
    @LocalServerPort int serverPort;

    @Autowired TenantService tenantService;
    @Autowired DisbursementRepository disbursementRepository;
    @Autowired MerchantWalletBalanceRepository walletRepo;
    @Autowired PlatformConfigService platformConfigService;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired TestDataCleaner testDataCleaner;

    private Long tenantId;
    private String rawApiKey;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        testDataCleaner.wipeAll();

        // Seed JWT secret required by SecurityAdviceFilter
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

        // Create tenant
        var created = tenantService.createTenant("stepup-e2e-" + UUID.randomUUID(), ApiKeyEnvironment.PROD);
        tenantId  = created.tenant().getId();
        rawApiKey = created.rawKey();

        // Seed wallet with 1,000,000 XAF (large enough to reserve 600,000+)
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.merchant_wallet_balance " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, " +
                "request_id, status, tenant_id, balance, reserved_amount, currency, version) " +
                "VALUES (?, 'TEST', NOW(), 'TEST', NOW(), gen_random_uuid()::text, 'ACTIVE', ?, ?, 0, 'XAF', 0)",
                System.currentTimeMillis(), tenantId, new BigDecimal("1000000"));
            return null;
        });

        // Stub MTN collection + disbursement token endpoints
        mtnServer.stubFor(post(urlPathEqualTo("/token/collection"))
            .willReturn(okJson("{\"access_token\":\"mtn-coll-bearer\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
        mtnServer.stubFor(post(urlPathEqualTo("/token/disbursement"))
            .willReturn(okJson("{\"access_token\":\"mtn-disb-bearer\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));

        // Stub Orange token endpoint
        orangeServer.stubFor(post(urlPathEqualTo("/token"))
            .withHeader("Authorization", containing("Basic"))
            .willReturn(okJson("{\"access_token\":\"orange-bearer\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));

        // Seed Orange platform config
        platformConfigService.update("ORANGE", "691000000");

        // Flush Redis
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

    // ────────────────────────────────────────────────────────────────────────────
    // Test 1: amount > 500,000 XAF → 202 PENDING_CONFIRMATION; NO provider call;
    //         wallet IS reserved; GET returns PENDING_CONFIRMATION
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    void stepUpAmount_returnsPendingConfirmation_andDoesNotCallProvider() throws Exception {
        // Do NOT stub /v1_0/transfer — this test asserts ZERO transfer calls
        ResponseEntity<String> initResponse = postDisbursement(
            STEP_UP_AMOUNT,
            "REF-STEPUP-1-" + UUID.randomUUID(),
            "IDEM-STEPUP-1-" + UUID.randomUUID());

        assertThat(initResponse.getStatusCode().value()).isEqualTo(202);

        String body = initResponse.getBody();
        assertThat(body).isNotNull();
        String disbursementId = parseDisbursementId(body);
        assertThat(disbursementId).isNotBlank();
        assertThat(parseStatus(body)).isEqualTo("PENDING_CONFIRMATION");
        assertThat(parseErrorCode(body)).isNull();

        // Provider NOT called — verify ZERO MTN transfer calls
        mtnServer.verify(0, postRequestedFor(urlPathEqualTo("/v1_0/transfer")));

        // Wallet IS reserved — balance reduced from 1,000,000
        BigDecimal balance = jdbcTemplate.queryForObject(
            "SELECT balance FROM main.merchant_wallet_balance WHERE tenant_id = ?",
            BigDecimal.class, tenantId);
        assertThat(balance).isLessThan(new BigDecimal("1000000"));

        // GET /v1/disbursements/{id} → 200, status=PENDING_CONFIRMATION (DISB-02)
        ResponseEntity<String> getResponse = getDisbursement(disbursementId);
        assertThat(getResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(parseStatus(getResponse.getBody())).isEqualTo("PENDING_CONFIRMATION");
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Test 2: POST /confirm on PENDING_CONFIRMATION → 202 PROCESSING + 1 transfer
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    void confirmPendingDisbursement_dispatchesToProvider_andTransitionsToProcessing() throws Exception {
        // Initiate step-up disbursement — goes to PENDING_CONFIRMATION without provider call
        ResponseEntity<String> initResponse = postDisbursement(
            STEP_UP_AMOUNT,
            "REF-SU-CONF-" + UUID.randomUUID(),
            "IDEM-STEPUP-CONFIRM-" + UUID.randomUUID());

        assertThat(initResponse.getStatusCode().value()).isEqualTo(202);
        String disbursementId = parseDisbursementId(initResponse.getBody());

        // Verify ZERO MTN transfer calls before confirm
        mtnServer.verify(0, postRequestedFor(urlPathEqualTo("/v1_0/transfer")));

        // Now stub MTN endpoints for the confirm dispatch
        stubMtnAccountAndTransfer();

        // POST confirm
        ResponseEntity<String> confirmResponse = postConfirm(disbursementId);
        assertThat(confirmResponse.getStatusCode().value()).isEqualTo(202);

        String confirmBody = confirmResponse.getBody();
        assertThat(parseStatus(confirmBody)).isEqualTo("PROCESSING");
        assertThat(parseErrorCode(confirmBody)).isNull();

        // Verify exactly 1 MTN transfer call AFTER confirm
        mtnServer.verify(1, postRequestedFor(urlPathEqualTo("/v1_0/transfer")));

        // DB row in PROCESSING
        String dbStatus = jdbcTemplate.queryForObject(
            "SELECT disbursement_status FROM main.disbursement WHERE disbursement_id = ?",
            String.class, disbursementId);
        assertThat(dbStatus).isEqualTo("PROCESSING");
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Test 3: POST /confirm on already-PROCESSING → 422 INVALID_STATE; no extra call
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    void confirmAlreadyProcessingDisbursement_returns422_invalidState_noExtraProviderCall() throws Exception {
        // Submit a small-amount disbursement — goes directly to PROCESSING
        stubMtnAccountAndTransfer();

        ResponseEntity<String> initResponse = postDisbursement(
            SMALL_AMOUNT,
            "REF-SMALL-" + UUID.randomUUID(),
            "IDEM-SMALL-" + UUID.randomUUID());

        assertThat(initResponse.getStatusCode().value()).isEqualTo(202);
        String disbursementId = parseDisbursementId(initResponse.getBody());
        assertThat(parseStatus(initResponse.getBody())).isEqualTo("PROCESSING");

        // Reset MTN call recordings and re-stub token endpoints only (no transfer stub)
        mtnServer.resetAll();
        mtnServer.stubFor(post(urlPathEqualTo("/token/collection"))
            .willReturn(okJson("{\"access_token\":\"t\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
        mtnServer.stubFor(post(urlPathEqualTo("/token/disbursement"))
            .willReturn(okJson("{\"access_token\":\"t\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));

        // Use a RestTemplate with DefaultResponseErrorHandler override (to capture 422 without exception)
        ResponseEntity<String> confirmResponse = postConfirmExpectingError(disbursementId);
        assertThat(confirmResponse.getStatusCode().value()).isEqualTo(422);

        String confirmBody = confirmResponse.getBody();
        assertThat(parseErrorCode(confirmBody))
            .isEqualTo(DisbursementOrchestratorError.INVALID_STATE.getErrorCode());

        // Verify no /v1_0/transfer call after the confirm attempt
        mtnServer.verify(0, postRequestedFor(urlPathEqualTo("/v1_0/transfer")));
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────────

    private ResponseEntity<String> postDisbursement(BigDecimal amount, String reference,
                                                     String idempotencyKey) {
        String body = String.format(
            "{\"recipientMsisdn\":\"%s\",\"amount\":%s,\"currency\":\"XAF\",\"reference\":\"%s\"}",
            MTN_MSISDN, amount.toPlainString(), reference);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", rawApiKey);
        headers.set("Idempotency-Key", idempotencyKey);
        return testRestTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/disbursements",
            HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> postConfirm(String disbursementId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Api-Key", rawApiKey);
        return testRestTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/disbursements/" + disbursementId + "/confirm",
            HttpMethod.POST, new HttpEntity<>(headers), String.class);
    }

    /**
     * POST confirm using a RestTemplate with DefaultResponseErrorHandler override so that
     * 4xx responses (like 422) are returned as-is instead of throwing an exception.
     */
    private ResponseEntity<String> postConfirmExpectingError(String disbursementId) {
        RestTemplate rt = new RestTemplate(new SimpleClientHttpRequestFactory());
        rt.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(HttpStatusCode statusCode) { return false; }
            @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse response)
                throws IOException { return false; }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Api-Key", rawApiKey);
        return rt.exchange(
            "http://localhost:" + serverPort + "/v1/disbursements/" + disbursementId + "/confirm",
            HttpMethod.POST, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> getDisbursement(String disbursementId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Api-Key", rawApiKey);
        return testRestTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/disbursements/" + disbursementId,
            HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private void stubMtnAccountAndTransfer() {
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*"))
            .willReturn(okJson("{}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/transfer"))
            .willReturn(aResponse().withStatus(202)));
    }

    private String parseDisbursementId(String body) throws Exception {
        return objectMapper.readTree(body).path("disbursementId").asText(null);
    }

    private String parseStatus(String body) throws Exception {
        return objectMapper.readTree(body).path("status").asText(null);
    }

    private String parseErrorCode(String body) throws Exception {
        if (body == null) return null;
        var node = objectMapper.readTree(body).path("errorCode");
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
