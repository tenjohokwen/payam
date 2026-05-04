package com.softropic.payam.disbursement.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.config.TestDataCleaner;
import com.softropic.payam.disbursement.repo.DisbursementRepository;
import com.softropic.payam.platform.service.PlatformConfigService;
import com.softropic.payam.tenant.contract.ApiKeyEnvironment;
import com.softropic.payam.tenant.service.TenantService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.github.tomakehurst.wiremock.client.WireMock;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test for DisbursementResource — covers DISB-02 (wrong-tenant 404),
 * DISB-03 (paginated list with status filter and tenant scope), DISB-04 (confirm
 * routed via HTTP).
 *
 * <p>CRITICAL: @ConfigureWireMock includes both mtn.collection-base-url AND
 * mtn.disbursement-base-url — without the disbursement URL, MtnMoMoPort.initiateDisbursement
 * uses the wrong endpoint (Pitfall 5 from 51-RESEARCH.md).
 */
@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@AutoConfigureMockMvc
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
class DisbursementResourceIT {

    private static final String MTN_MSISDN = "+237671234567";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TenantService tenantService;
    @Autowired DisbursementRepository disbursementRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired StringRedisTemplate redis;
    @Autowired TestDataCleaner testDataCleaner;
    @Autowired PlatformConfigService platformConfigService;

    @InjectWireMock("mtn") WireMockServer mtnServer;
    @InjectWireMock("orange") WireMockServer orangeServer;

    private Long tenantIdA;
    private String apiKeyA;
    private Long tenantIdB;
    private String apiKeyB;

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

        // Create two tenants
        var provisionA = tenantService.createTenant("dsb-it-A-" + UUID.randomUUID(), ApiKeyEnvironment.PROD);
        tenantIdA = provisionA.tenant().getId();
        apiKeyA = provisionA.rawKey();

        var provisionB = tenantService.createTenant("dsb-it-B-" + UUID.randomUUID(), ApiKeyEnvironment.PROD);
        tenantIdB = provisionB.tenant().getId();
        apiKeyB = provisionB.rawKey();

        // Seed wallets for both tenants with 100,000 XAF balance
        transactionTemplate.execute(st -> {
            jdbcTemplate.update(
                "INSERT INTO main.merchant_wallet_balance " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, " +
                "request_id, status, tenant_id, balance, reserved_amount, currency, version) " +
                "VALUES (?, 'TEST', NOW(), 'TEST', NOW(), gen_random_uuid()::text, 'ACTIVE', ?, ?, 0, 'XAF', 0)",
                System.currentTimeMillis(), tenantIdA, new BigDecimal("100000"));
            jdbcTemplate.update(
                "INSERT INTO main.merchant_wallet_balance " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, " +
                "request_id, status, tenant_id, balance, reserved_amount, currency, version) " +
                "VALUES (?, 'TEST', NOW(), 'TEST', NOW(), gen_random_uuid()::text, 'ACTIVE', ?, ?, 0, 'XAF', 0)",
                System.currentTimeMillis() + 1, tenantIdB, new BigDecimal("100000"));
            return null;
        });

        // Stub MTN token endpoints
        mtnServer.stubFor(WireMock.post(urlPathEqualTo("/token/collection"))
            .willReturn(okJson("{\"access_token\":\"mtn-coll-bearer\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
        mtnServer.stubFor(WireMock.post(urlPathEqualTo("/token/disbursement"))
            .willReturn(okJson("{\"access_token\":\"mtn-disb-bearer\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));

        // Stub Orange token endpoint
        orangeServer.stubFor(WireMock.post(urlPathEqualTo("/token"))
            .withHeader("Authorization", containing("Basic"))
            .willReturn(okJson("{\"access_token\":\"orange-bearer\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));

        // Stub MTN account validation (validateSubscriber → GET /v1_0/accountholder/MSISDN/...)
        mtnServer.stubFor(WireMock.get(urlPathMatching("/v1_0/accountholder/MSISDN/.*"))
            .willReturn(okJson("{}")));

        // Stub MTN transfer → 202 Accepted
        mtnServer.stubFor(WireMock.post(urlPathEqualTo("/v1_0/transfer"))
            .willReturn(aResponse().withStatus(202)));

        // Seed Orange platform config for subscriber validation
        platformConfigService.update("ORANGE", "691000000");

        // Flush Redis to ensure clean idempotency state
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

    // ────────────────────────────────────────────────────────────────────────
    // Test 1: POST /v1/disbursements happy path → 202 with disbursementId
    // ────────────────────────────────────────────────────────────────────────

    @Test
    void post_happy_path_returns_202_with_disbursement_id() throws Exception {
        List<String> txnIds = seedTxnsForClaim(tenantIdA, 1, new BigDecimal("5000"));
        String body = objectMapper.writeValueAsString(Map.of(
            "recipientMsisdn", MTN_MSISDN,
            "amount", 5000,
            "currency", "XAF",
            "reference", "REF-T1",
            "transactionIds", txnIds));

        mockMvc.perform(post("/v1/disbursements")
                .header("X-Api-Key", apiKeyA)
                .header("Idempotency-Key", "IDEM-T1-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.disbursementId").exists())
            .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 2: GET /v1/disbursements/{id} — wrong tenant returns 404 (DISB-02)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    void getById_wrongTenant_returns404() throws Exception {
        // tenantA submits
        String disbId = postAndGetDisbursementId(apiKeyA, MTN_MSISDN, 5000, "IDEM-T2-" + UUID.randomUUID());

        // tenantB tries to GET tenantA's disbursement — must return 404
        mockMvc.perform(get("/v1/disbursements/" + disbId)
                .header("X-Api-Key", apiKeyB))
            .andExpect(status().isNotFound());

        // tenantA can still GET it
        mockMvc.perform(get("/v1/disbursements/" + disbId)
                .header("X-Api-Key", apiKeyA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.disbursementId").value(disbId));
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 3: GET /v1/disbursements/{id} — unknown id returns 404 (DISB-02)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    void getById_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/v1/disbursements/does-not-exist")
                .header("X-Api-Key", apiKeyA))
            .andExpect(status().isNotFound());
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 4: GET /v1/disbursements?status=PROCESSING — filters + tenant scoped (DISB-03)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    void list_filterByStatus_returnsTenantScopedPage() throws Exception {
        // tenantA submits 3 disbursements
        postAndGetDisbursementId(apiKeyA, MTN_MSISDN, 5000, "IDEM-L1-" + UUID.randomUUID());
        postAndGetDisbursementId(apiKeyA, "+237671234568", 6000, "IDEM-L2-" + UUID.randomUUID());
        postAndGetDisbursementId(apiKeyA, "+237671234569", 7000, "IDEM-L3-" + UUID.randomUUID());
        // tenantB submits 1 — must NOT appear in tenantA's list
        postAndGetDisbursementId(apiKeyB, "+237671234570", 8000, "IDEM-L4-" + UUID.randomUUID());

        mockMvc.perform(get("/v1/disbursements")
                .header("X-Api-Key", apiKeyA)
                .param("status", "PROCESSING")
                .param("page", "0")
                .param("size", "20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.content[0].disbursementId").exists())
            .andExpect(jsonPath("$.content[0].status").value("PROCESSING"));
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 5: GET /v1/disbursements with size=1 — pagination works (DISB-03)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    void list_pagination_size1_returnsPagedResult() throws Exception {
        postAndGetDisbursementId(apiKeyA, MTN_MSISDN, 5000, "IDEM-P1-" + UUID.randomUUID());
        postAndGetDisbursementId(apiKeyA, "+237671234568", 6000, "IDEM-P2-" + UUID.randomUUID());

        mockMvc.perform(get("/v1/disbursements")
                .header("X-Api-Key", apiKeyA)
                .param("page", "0")
                .param("size", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.size").value(1));
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 6: POST /v1/disbursements/{id}/confirm — step-up routes via HTTP (DISB-04)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    void confirm_pendingDisbursement_dispatches() throws Exception {
        // Submit a step-up amount (> 500,000) — wallet must have enough (seeded with 100,000)
        // Use smaller step-up: the check is amount > 500,000; 600,000 exceeds wallet (100,000)
        // So we need a larger wallet. Re-seed wallet with 1,000,000 for this test.
        transactionTemplate.execute(st -> {
            jdbcTemplate.update(
                "UPDATE main.merchant_wallet_balance SET balance = 1000000 WHERE tenant_id = ?",
                tenantIdA);
            return null;
        });

        String disbId = postAndGetDisbursementId(apiKeyA, MTN_MSISDN, 600000, "IDEM-C1-" + UUID.randomUUID());

        // GET should reflect PENDING_CONFIRMATION
        mockMvc.perform(get("/v1/disbursements/" + disbId)
                .header("X-Api-Key", apiKeyA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"));

        // Confirm
        mockMvc.perform(post("/v1/disbursements/" + disbId + "/confirm")
                .header("X-Api-Key", apiKeyA))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helper
    // ────────────────────────────────────────────────────────────────────────

    private List<String> seedTxnsForClaim(Long tenantId, int count, BigDecimal eachAmount) {
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = java.util.UUID.randomUUID().toString();
            transactionTemplate.execute(s -> {
                jdbcTemplate.update(
                    "INSERT INTO main.transaction " +
                    "(transaction_id, trace_id, tenant_id, provider, tx_status, flow, " +
                    " amount, fee_amount, currency, created_by, created_date, last_modified_by, " +
                    " last_modified_date, request_id, version) " +
                    "VALUES (?, ?, ?, 'MTN', 'SUCCESS', 'COLLECTION', " +
                    "       ?, 0, 'XAF', 'TEST', NOW(), 'TEST', NOW(), gen_random_uuid()::text, 0)",
                    id, id, tenantId, eachAmount);
                return null;
            });
            ids.add(id);
        }
        return ids;
    }

    private String postAndGetDisbursementId(String apiKey, String msisdn, int amount, String idemKey) throws Exception {
        Long tenantId = apiKey.equals(apiKeyA) ? tenantIdA : tenantIdB;
        List<String> txnIds = seedTxnsForClaim(tenantId, 1, new BigDecimal(amount));
        String body = objectMapper.writeValueAsString(Map.of(
            "recipientMsisdn", msisdn,
            "amount", amount,
            "currency", "XAF",
            "reference", "REF-" + UUID.randomUUID().toString().substring(0, 8),
            "transactionIds", txnIds));

        String responseJson = mockMvc.perform(post("/v1/disbursements")
                .header("X-Api-Key", apiKey)
                .header("Idempotency-Key", idemKey)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn()
            .getResponse()
            .getContentAsString();

        return objectMapper.readTree(responseJson).path("disbursementId").asText();
    }
}
