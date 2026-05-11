package com.softropic.payam.e2e.disbursement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.config.TestDataCleaner;
import com.softropic.payam.payment.disbursement.service.DisbursementAdminApprovalExpiryJob;
import com.softropic.payam.platform.admin.service.PlatformConfigService;
import com.softropic.payam.platform.tenant.contract.ApiKeyEnvironment;
import com.softropic.payam.platform.tenant.service.TenantService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import org.springframework.web.client.RestTemplate;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E integration test proving Phase 58 SC-3: HTTP-driven PENDING_ADMIN_APPROVAL →
 * expiry job → EXPIRED + claims RELEASED lifecycle (CLAIM-04, ADMIN-01, ADMIN-03).
 *
 * <p>The existing {@code DisbursementAdminApprovalExpiryJobIT} proves expiry in isolation
 * by seeding rows directly via JdbcTemplate. This test drives the FULL path:
 * <ol>
 *   <li>Real HTTP POST /v1/disbursements with amount {@literal >} adminApprovalThreshold
 *       → {@code DisbursementOrchestrator.initiate()} routes to PENDING_ADMIN_APPROVAL</li>
 *   <li>Claim rows are PENDING immediately (ADMIN-01)</li>
 *   <li>NO call to MTN /v1_0/transfer — provider dispatch is skipped on this branch</li>
 *   <li>Backdate created_date by 120 minutes (past the 1-hour test timeout)</li>
 *   <li>Invoke {@code DisbursementAdminApprovalExpiryJob.executeInternal(null)} via reflection</li>
 *   <li>Disbursement transitions to EXPIRED (ADMIN-03)</li>
 *   <li>All claim rows transition to RELEASED (CLAIM-04)</li>
 * </ol>
 *
 * <p>CRITICAL — do NOT add {@code @Transactional} to this class or any test method.
 * The expiry job's claim release uses {@code @TransactionalEventListener(AFTER_COMMIT)};
 * if the test runs inside a rolled-back transaction, those listeners never fire.
 *
 * <p>{@code spring.quartz.auto-startup=false} prevents background Quartz threads from
 * racing the direct {@code executeInternal} invocation during the test.
 *
 * <p>No balance reservation needed — V32 Flyway migration retired balance gating
 * from the orchestrator path (SCHEMA-03 / Pitfall 6 in Phase 58 RESEARCH.md).
 */
@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true", "spring.quartz.auto-startup=false"})
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "mtn.callback-ip-whitelist=",
    "orange.callback-ip-whitelist=",
    "mtn.collection-token-url=http://localhost:${wiremock.mtn.port}/token/collection",
    "mtn.disbursement-token-url=http://localhost:${wiremock.mtn.port}/token/disbursement",
    "payam.disbursement.admin-approval-timeout-hours=1"
})
@EnableWireMock({
    @ConfigureWireMock(name = "mtn",
        baseUrlProperties = {"mtn.collection-base-url", "mtn.disbursement-base-url"},
        portProperties    = {"wiremock.mtn.port"}),
    @ConfigureWireMock(name = "orange",
        baseUrlProperties = {"orange.base-url", "orange.pay-url"},
        portProperties    = {"wiremock.orange.port"})
})
class DisbursementAdminApprovalE2EIT {

    private static final String MTN_MSISDN = "+237671234567";
    // 6,000,000 XAF > default adminApprovalThreshold (5,000,000) → PENDING_ADMIN_APPROVAL
    private static final BigDecimal ADMIN_APPROVAL_AMOUNT = new BigDecimal("6000000");

    @InjectWireMock("mtn")    WireMockServer mtnServer;
    @InjectWireMock("orange") WireMockServer orangeServer;

    @Autowired DisbursementAdminApprovalExpiryJob adminApprovalExpiryJob;
    @Autowired TenantService tenantService;
    @Autowired PlatformConfigService platformConfigService;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired TestDataCleaner testDataCleaner;

    @LocalServerPort int serverPort;

    private Long tenantId;
    private String rawApiKey;

    private final RestTemplate restTemplate = new RestTemplate();
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

        // Create tenant and capture raw API key for HTTP calls
        TenantService.TenantCreationResult result =
            tenantService.createTenant("admin-approval-e2e-" + UUID.randomUUID(), ApiKeyEnvironment.PROD);
        tenantId = result.tenant().getId();
        rawApiKey = result.rawKey();

        // NO balance seed required — V32 Flyway migration retired balance gating from orchestrator path (SCHEMA-03)

        // Stub MTN token endpoints (collection + disbursement)
        mtnServer.stubFor(post(urlPathEqualTo("/token/collection"))
            .willReturn(okJson("{\"access_token\":\"mtn-coll-bearer\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
        mtnServer.stubFor(post(urlPathEqualTo("/token/disbursement"))
            .willReturn(okJson("{\"access_token\":\"mtn-disb-bearer\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));

        // Stub Orange token endpoint (Orange WireMock must be alive even though unused — Pitfall 2)
        orangeServer.stubFor(post(urlPathEqualTo("/token"))
            .willReturn(okJson("{\"access_token\":\"orange-bearer\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));

        // Seed Orange platform config (channelMsisdn needed by OrangeMoneyPort startup)
        platformConfigService.update("ORANGE", "691000000");

        // Flush Redis to prevent idempotency/velocity key bleed between tests
        try {
            redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        } catch (Exception ignored) {}

        // NOTE: Do NOT pre-stub /v1_0/transfer — the admin-approval test asserts ZERO calls
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

    // ─────────────────────────────────────────────────────────────────────────────
    // Test 1: HTTP → PENDING_ADMIN_APPROVAL → expiry job → EXPIRED + claims RELEASED
    //         (Phase 58 SC-3 / CLAIM-04 + ADMIN-01 + ADMIN-03)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * E2E test for Phase 58 SC-3 + CLAIM-04 + ADMIN-01 + ADMIN-03.
     *
     * Flow:
     *   1. POST /v1/disbursements with amount > adminApprovalThreshold (6M XAF > default 5M)
     *      → orchestrator routes to PENDING_ADMIN_APPROVAL (NOT to provider, NOT to PENDING_CONFIRMATION)
     *   2. Claims are PENDING immediately (CLAIM-01 seed for ADMIN-01)
     *   3. NO call to MTN /v1_0/transfer — provider dispatch must be skipped on this path
     *   4. Backdate created_date by 120 minutes (well past the 1-hour test threshold)
     *   5. Invoke DisbursementAdminApprovalExpiryJob.executeInternal(null) via reflection
     *   6. Assert disbursement transitioned to EXPIRED (ADMIN-03)
     *   7. Assert all claims transitioned to RELEASED (CLAIM-04)
     *   8. GET /v1/disbursements/{id} → 200 + status=EXPIRED
     */
    @Test
    void httpInitiatedAdminApproval_expiresViaJob_releasesAllClaims_E2E() throws Exception {
        // Step 1 — POST with 6M XAF, 3 backing transactions (proves multi-claim release)
        List<String> txnIds = seedTxnsForClaim(tenantId, 3, new BigDecimal("2000000"));
        String idem = "IDEM-ADMIN-APP-" + UUID.randomUUID();
        ResponseEntity<String> response = postDisbursement(
            MTN_MSISDN, ADMIN_APPROVAL_AMOUNT,
            "REF-ADMIN-APP-" + UUID.randomUUID(), idem, txnIds);

        assertThat(response.getStatusCode().value())
            .as("POST with amount > adminApprovalThreshold must return 202")
            .isEqualTo(202);
        String disbursementId = parseDisbursementId(response.getBody());
        assertThat(disbursementId).as("disbursementId must be returned in 202 body").isNotBlank();
        String status = parseStatus(response.getBody());
        assertThat(status)
            .as("Amount > adminApprovalThreshold must gate to PENDING_ADMIN_APPROVAL (NOT PENDING_CONFIRMATION)")
            .isEqualTo("PENDING_ADMIN_APPROVAL");

        // Step 2 — claims are PENDING immediately (orchestrator inserts claim rows before returning)
        assertClaimStatuses(disbursementId, "PENDING", 3);

        // Step 3 — provider NOT called: admin-approval branch must NOT dispatch to MTN
        mtnServer.verify(0, postRequestedFor(urlPathEqualTo("/v1_0/transfer")));

        // Step 4 — backdate by 120 minutes (well past 1-hour test threshold)
        backdateDisbursement(disbursementId, 120);

        // Step 5 — invoke the admin-approval expiry job via reflection
        invokeAdminApprovalExpiryJob();

        // Step 6 — disbursement must now be EXPIRED (ADMIN-03)
        String dbStatus = jdbcTemplate.queryForObject(
            "SELECT disbursement_status FROM main.disbursement WHERE disbursement_id = ?",
            String.class, disbursementId);
        assertThat(dbStatus).as("Aged PENDING_ADMIN_APPROVAL must transition to EXPIRED").isEqualTo("EXPIRED");

        // Step 7 — all 3 claims must now be RELEASED (CLAIM-04)
        assertClaimStatuses(disbursementId, "RELEASED", 3);

        // Step 8 — provider STILL not called even after expiry
        mtnServer.verify(0, postRequestedFor(urlPathEqualTo("/v1_0/transfer")));

        // Step 9 — HTTP-visible state is consistent
        ResponseEntity<String> getResponse = getDisbursement(disbursementId);
        assertThat(getResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(parseStatus(getResponse.getBody())).isEqualTo("EXPIRED");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Test 2: Negative control — fresh PENDING_ADMIN_APPROVAL row not expired by job
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Negative control: a freshly-created PENDING_ADMIN_APPROVAL row (created_date = NOW())
     * is NOT touched by the expiry job. Pairs with Test 1 to prove the expiry job's
     * threshold check is functional (not a no-op that always expires every row).
     */
    @Test
    void freshAdminApproval_isNotExpired_claimsRemainPending() throws Exception {
        // POST → PENDING_ADMIN_APPROVAL
        List<String> txnIds = seedTxnsForClaim(tenantId, 1, ADMIN_APPROVAL_AMOUNT);
        String idem = "IDEM-ADMIN-FRESH-" + UUID.randomUUID();
        ResponseEntity<String> response = postDisbursement(
            MTN_MSISDN, ADMIN_APPROVAL_AMOUNT,
            "REF-FRESH-" + UUID.randomUUID(), idem, txnIds);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        String disbursementId = parseDisbursementId(response.getBody());
        assertThat(parseStatus(response.getBody())).isEqualTo("PENDING_ADMIN_APPROVAL");

        // Sanity: claim is PENDING
        assertClaimStatuses(disbursementId, "PENDING", 1);

        // Anchor created_date to DB-relative time (NOW() - 2 minutes) to eliminate JVM vs Postgres
        // timezone / clock skew. 2 minutes is well within the 60-minute threshold — the job must NOT
        // expire this row. Pattern from DisbursementExpiryE2EIT.freshPendingConfirmation_isNotExpired.
        backdateDisbursement(disbursementId, 2);

        // Invoke the job.
        invokeAdminApprovalExpiryJob();

        // Disbursement must still be PENDING_ADMIN_APPROVAL
        String dbStatus = jdbcTemplate.queryForObject(
            "SELECT disbursement_status FROM main.disbursement WHERE disbursement_id = ?",
            String.class, disbursementId);
        assertThat(dbStatus).isEqualTo("PENDING_ADMIN_APPROVAL");

        // Claims must still be PENDING
        assertClaimStatuses(disbursementId, "PENDING", 1);

        // Provider STILL not called
        mtnServer.verify(0, postRequestedFor(urlPathEqualTo("/v1_0/transfer")));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helper methods
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Seed N collection transactions in SUCCESS state for use as backing
     * transactionIds in a claim-based disbursement. Pattern duplicated across
     * all five existing disbursement E2E IT classes.
     */
    private List<String> seedTxnsForClaim(Long tenantId, int count, BigDecimal eachAmount) {
        List<String> ids = new java.util.ArrayList<>();
        final ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            Long id = rng.nextLong();
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

    /** POST /v1/disbursements with X-Api-Key + Idempotency-Key headers. */
    private ResponseEntity<String> postDisbursement(String msisdn, BigDecimal amount,
                                                     String reference, String idempotencyKey,
                                                     List<String> transactionIds) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", rawApiKey);
        headers.set("Idempotency-Key", idempotencyKey);

        String txnIdsJson = transactionIds.stream()
            .map(id -> "\"" + id + "\"")
            .collect(Collectors.joining(",", "[", "]"));
        String json = String.format(
            "{\"recipientMsisdn\":\"%s\",\"amount\":%s,\"currency\":\"XAF\"," +
            "\"reference\":\"%s\",\"transactionIds\":%s}",
            msisdn, amount.toPlainString(), reference, txnIdsJson);

        return restTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/disbursements",
            HttpMethod.POST,
            new HttpEntity<>(json, headers),
            String.class);
    }

    /** GET /v1/disbursements/{disbursementId} — tenant-scoped via X-Api-Key. */
    private ResponseEntity<String> getDisbursement(String disbursementId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Api-Key", rawApiKey);
        return restTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/disbursements/" + disbursementId,
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class);
    }

    /**
     * Backdate the created_date of a disbursement row by {@code minutes} minutes
     * via Postgres INTERVAL arithmetic — keeps the comparison entirely within the DB engine,
     * avoiding JVM timezone vs. JDBC Timestamp binding skew.
     */
    private void backdateDisbursement(String disbursementId, long minutes) {
        transactionTemplate.execute(s -> {
            jdbcTemplate.update(
                "UPDATE main.disbursement SET " +
                "created_date = NOW() - CAST(? || ' minutes' AS INTERVAL), " +
                "last_modified_date = NOW() - CAST(? || ' minutes' AS INTERVAL) " +
                "WHERE disbursement_id = ?",
                minutes, minutes, disbursementId);
            return null;
        });
    }

    /**
     * Invoke {@code DisbursementAdminApprovalExpiryJob.executeInternal(null)} via reflection.
     *
     * <p>{@code executeInternal} is {@code protected} on {@link org.springframework.scheduling.quartz.QuartzJobBean},
     * so reflection is required from {@code com.softropic.payam.e2e.disbursement} (different package).
     * Same idiom as {@link DisbursementExpiryE2EIT#invokeExpiryJob()}.
     */
    private void invokeAdminApprovalExpiryJob() {
        try {
            java.lang.reflect.Method m =
                adminApprovalExpiryJob.getClass().getDeclaredMethod("executeInternal", org.quartz.JobExecutionContext.class);
            m.setAccessible(true);
            m.invoke(adminApprovalExpiryJob, (Object) null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke DisbursementAdminApprovalExpiryJob.executeInternal via reflection", e);
        }
    }

    /**
     * Assert that the disbursement_transaction_ref rows for the given disbursement UUID
     * all have the expected ref_status. Uses raw JDBC (not the JPA repository) to avoid
     * first-level cache returning stale entities. Pattern from DisbursementAdminApprovalExpiryJobIT.
     *
     * <p>NOTE: disbursement_transaction_ref.disbursement_id is the BIGINT PK of disbursement,
     * NOT the UUID disbursement_id column (Pitfall 7).
     */
    private void assertClaimStatuses(String disbursementId, String expectedStatus, int expectedCount) {
        List<String> statuses = jdbcTemplate.queryForList(
            "SELECT ref_status FROM main.disbursement_transaction_ref " +
            "WHERE disbursement_id = (SELECT id FROM main.disbursement WHERE disbursement_id = ?)",
            String.class, disbursementId);
        assertThat(statuses).hasSize(expectedCount);
        assertThat(statuses).containsOnly(expectedStatus);
    }

    private String parseDisbursementId(String body) throws Exception {
        return objectMapper.readTree(body).path("disbursementId").asText(null);
    }

    private String parseStatus(String body) throws Exception {
        return objectMapper.readTree(body).path("status").asText(null);
    }
}
