package com.softropic.payam.disbursement.service;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.config.TestDataCleaner;
import com.softropic.payam.common.util.JsonUtil;
import com.softropic.payam.disbursement.contract.DisbursementOrchestratorError;
import com.softropic.payam.disbursement.contract.DisbursementRefStatus;
import com.softropic.payam.disbursement.contract.DisbursementRequest;
import com.softropic.payam.disbursement.contract.DisbursementResponse;
import com.softropic.payam.disbursement.contract.DisbursementStatus;
import com.softropic.payam.disbursement.repo.Disbursement;
import com.softropic.payam.disbursement.repo.DisbursementRepository;
import com.softropic.payam.disbursement.repo.DisbursementTransactionRefRepository;
import com.softropic.payam.platform.admin.service.PlatformConfigService;
import com.softropic.payam.platform.tenant.contract.ApiKeyEnvironment;
import com.softropic.payam.platform.tenant.service.TenantService;
import com.softropic.payam.transaction.contract.CachedResponse;

import io.hypersistence.tsid.TSID;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for DisbursementOrchestrator retry recovery path (Phase 57 IDEM-01/02/03).
 *
 * <p>Proves against real Postgres (Testcontainers) + Redis (Testcontainers) + WireMock:
 * <ul>
 *   <li>IDEM-02: retriable code + no active claims → FAILED→INITIATED, retry_count++,
 *       RELEASED→PENDING reactivation, provider re-dispatch</li>
 *   <li>IDEM-01: retriable code + active-claim conflict → TRANSACTION_CLAIMED returned,
 *       disbursement stays FAILED, ref stays RELEASED</li>
 *   <li>IDEM-03: terminal code → cached FAILED returned verbatim, no state change</li>
 * </ul>
 *
 * <p>Test data seeding uses direct JDBC inserts (not JPA save) to avoid silent JPA
 * transaction failures in test contexts (see Pitfall 6 in 51-RESEARCH.md).
 */
@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "mtn.callback-ip-whitelist=",
    "mtn.collection-token-url=http://localhost:${wiremock.mtn.port}/token/collection",
    "mtn.disbursement-token-url=http://localhost:${wiremock.mtn.port}/token/disbursement"
})
@EnableWireMock({
    @ConfigureWireMock(name = "mtn",
        baseUrlProperties = {"mtn.collection-base-url", "mtn.disbursement-base-url"},
        portProperties    = {"wiremock.mtn.port"})
})
class DisbursementIdempotencyRetryIT {

    private static final String MTN_MSISDN = "+237671234567";

    @InjectWireMock("mtn") WireMockServer mtnServer;

    @Autowired DisbursementOrchestrator orchestrator;
    @Autowired DisbursementRepository disbursementRepository;
    @Autowired DisbursementTransactionRefRepository refRepository;
    @Autowired TenantService tenantService;
    @Autowired DisbursementIdempotencyService idempotencyService;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired TestDataCleaner testDataCleaner;
    @Autowired PlatformConfigService platformConfigService;

    private Long tenantId;

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
        tenantId = tenantService.createTenant("dsb-retry-it-" + UUID.randomUUID(), ApiKeyEnvironment.PROD)
                .tenant().getId();

        // Stub MTN token endpoints
        mtnServer.stubFor(post(urlPathEqualTo("/token/collection"))
            .willReturn(okJson("{\"access_token\":\"mtn-coll-bearer\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
        mtnServer.stubFor(post(urlPathEqualTo("/token/disbursement"))
            .willReturn(okJson("{\"access_token\":\"mtn-disb-bearer\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
    }

    @AfterEach
    void cleanup() {
        mtnServer.resetAll();
        try {
            redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        } catch (Exception ignored) {}
        testDataCleaner.wipeAll();
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Private seeding helpers
    // ────────────────────────────────────────────────────────────────────────────────

    /**
     * Seeds one SUCCESS/COLLECTION Transaction row directly via JDBC.
     * Returns the transaction_id (VARCHAR(36) UUID).
     */
    private String seedSuccessfulCollectionTransaction(BigDecimal amount) {
        String txnId = UUID.randomUUID().toString();
        long id = TSID.fast().toLong();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.transaction " +
                "(id, transaction_id, trace_id, tenant_id, provider, tx_status, flow, amount, currency, fee_amount, " +
                " created_by, created_date, last_modified_by, last_modified_date, status) " +
                "VALUES (?, ?, ?, ?, 'MTN', 'SUCCESS', 'COLLECTION', ?, 'XAF', 0, " +
                "        'TEST', NOW(), 'TEST', NOW(), 'ACTIVE')",
                id, txnId, txnId, tenantId, amount);
            return null;
        });
        return txnId;
    }

    /**
     * Seeds a FAILED Disbursement row with one RELEASED DisbursementTransactionRef and
     * the corresponding cached idempotency entry in both Postgres and Redis.
     *
     * @param idempotencyKey  the idempotency key to seed
     * @param errorCode       the DisbursementOrchestratorError code to embed in the cached response
     * @param txnId           the transaction_id the RELEASED ref points to
     * @param amount          the disbursement principal amount
     * @return the disbursementId (UUID string) of the seeded disbursement
     */
    private String seedFailedDisbursementWithReleasedClaim(String idempotencyKey,
                                                             String errorCode,
                                                             String txnId,
                                                             BigDecimal amount) {
        String disbursementId = UUID.randomUUID().toString();
        long dsbPkId = TSID.fast().toLong();
        long refPkId = TSID.fast().toLong();

        // 1. Insert FAILED disbursement row
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.disbursement " +
                "(id, disbursement_id, tenant_id, recipient_msisdn, amount, currency, reference, " +
                " disbursement_status, provider, idempotency_key, retry_count, poll_attempts, " +
                " created_by, created_date, last_modified_by, last_modified_date, status) " +
                "VALUES (?, ?, ?, ?, ?, 'XAF', ?, 'FAILED', 'MTN', ?, 0, 0, " +
                "        'TEST', NOW(), 'TEST', NOW(), 'ACTIVE')",
                dsbPkId, disbursementId, tenantId, MTN_MSISDN, amount,
                "REF-" + disbursementId.substring(0, 8), idempotencyKey);
            return null;
        });

        // 2. Insert RELEASED DisbursementTransactionRef row
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.disbursement_transaction_ref " +
                "(id, disbursement_id, transaction_id, ref_status, " +
                " created_by, created_date, last_modified_by, last_modified_date, status) " +
                "VALUES (?, ?, ?, 'RELEASED', 'TEST', NOW(), 'TEST', NOW(), 'ACTIVE')",
                refPkId, dsbPkId, txnId);
            return null;
        });

        // 3. Seed idempotency response (both Postgres and Redis)
        DisbursementResponse failedResponse = DisbursementResponse.failed(
                disbursementId, errorCode, "prior failure");
        String responseBody = JsonUtil.toJson(failedResponse);
        String cachedJson = CachedResponse.toJson(202, responseBody);

        // Postgres idempotency_key row
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.idempotency_key " +
                "(id, tenant_id, idempotency_key, http_status, response_body, expires_at, created_date) " +
                "VALUES (?, ?, ?, 202, ?, NOW() + INTERVAL '24 hours', NOW()) " +
                "ON CONFLICT (tenant_id, idempotency_key) DO UPDATE " +
                "SET http_status = EXCLUDED.http_status, response_body = EXCLUDED.response_body, " +
                "    expires_at = EXCLUDED.expires_at",
                TSID.fast().toLong(), tenantId, idempotencyKey, responseBody);
            return null;
        });

        // Redis idempotency:dsb:<tenantId>:<key>
        String redisKey = "idempotency:dsb:" + tenantId + ":" + idempotencyKey;
        redis.opsForValue().set(redisKey, cachedJson, java.time.Duration.ofHours(24));

        return disbursementId;
    }

    /**
     * Seeds a SECOND PENDING DisbursementTransactionRef row for the same transaction_id but
     * for a DIFFERENT disbursement (D2). This simulates IDEM-01's claim-conflict scenario —
     * another disbursement has actively claimed the transaction in the interim.
     */
    private void seedActivePendingClaimForDifferentDisbursement(String txnId) {
        // D2: a different disbursement that has this transaction as PENDING
        String d2DisbursementId = UUID.randomUUID().toString();
        long d2PkId = TSID.fast().toLong();
        long d2RefPkId = TSID.fast().toLong();

        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.disbursement " +
                "(id, disbursement_id, tenant_id, recipient_msisdn, amount, currency, reference, " +
                " disbursement_status, provider, idempotency_key, retry_count, poll_attempts, " +
                " created_by, created_date, last_modified_by, last_modified_date, status) " +
                "VALUES (?, ?, ?, ?, 5000, 'XAF', ?, 'PROCESSING', 'MTN', ?, 0, 0, " +
                "        'TEST', NOW(), 'TEST', NOW(), 'ACTIVE')",
                d2PkId, d2DisbursementId, tenantId, MTN_MSISDN,
                "REF-D2-" + d2DisbursementId.substring(0, 8),
                "IDEM-D2-" + UUID.randomUUID());
            return null;
        });

        // D2's PENDING ref for the same txnId
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.disbursement_transaction_ref " +
                "(id, disbursement_id, transaction_id, ref_status, " +
                " created_by, created_date, last_modified_by, last_modified_date, status) " +
                "VALUES (?, ?, ?, 'PENDING', 'TEST', NOW(), 'TEST', NOW(), 'ACTIVE')",
                d2RefPkId, d2PkId, txnId);
            return null;
        });
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 1: IDEM-02 happy path — retriable retry reactivates claims + increments retry_count
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void retriableRetry_reactivatesClaimsAndIncrementsRetryCount_andDispatchesProvider() {
        // 1. Seed a SUCCESS COLLECTION transaction
        BigDecimal amount = new BigDecimal("5000");
        String txnId = seedSuccessfulCollectionTransaction(amount);

        // 2. Seed FAILED disbursement with one RELEASED ref + cached FAILED idempotency response
        String idempotencyKey = "IDEM-RETRY-001-" + UUID.randomUUID();
        String disbursementId = seedFailedDisbursementWithReleasedClaim(
                idempotencyKey, "PROVIDER_ERROR", txnId, amount);

        // 3. Stub MTN for subscriber validation + disbursement dispatch
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*"))
            .willReturn(okJson("{}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/transfer"))
            .willReturn(aResponse().withStatus(202)));

        // 4. Build request with the same idempotencyKey and same txnIds
        DisbursementRequest request = new DisbursementRequest(
                MTN_MSISDN, amount, "XAF", "REF-RETRY-001", null, null,
                List.of(txnId), idempotencyKey);

        // 5. Call initiate — should hit idempotency cache, detect FAILED+RETRIABLE, retry
        DisbursementResponse response = orchestrator.initiate(tenantId, request);

        // 6. Assert PROCESSING response
        assertThat(response.status()).isEqualTo("PROCESSING");
        assertThat(response.errorCode()).isNull();
        assertThat(response.disbursementId()).isEqualTo(disbursementId);

        // 7. Reload Disbursement from DB: disbursementStatus == PROCESSING, retryCount == 1
        Optional<Disbursement> dsbAfter = disbursementRepository.findByDisbursementId(disbursementId);
        assertThat(dsbAfter).isPresent();
        assertThat(dsbAfter.get().getDisbursementStatus()).isEqualTo(DisbursementStatus.PROCESSING);
        assertThat(dsbAfter.get().getRetryCount()).isEqualTo(1);

        // 8. Reload DisbursementTransactionRef: refStatus == PENDING (was RELEASED, reactivated)
        var refsAfter = refRepository.findAll().stream()
                .filter(r -> r.getTransactionId().equals(txnId))
                .toList();
        assertThat(refsAfter).hasSize(1);  // no new row inserted (IDEM-02 audit-trail invariant)
        assertThat(refsAfter.get(0).getRefStatus()).isEqualTo(DisbursementRefStatus.PENDING);

        // 9. WireMock received exactly 1 disbursement POST (provider re-dispatch)
        assertThat(mtnServer.findAll(postRequestedFor(urlPathEqualTo("/v1_0/transfer")))).hasSize(1);
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 2: IDEM-01 guard — retriable but transaction already claimed by another disbursement
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void retriableRetry_butTransactionAlreadyClaimed_returnsTransactionClaimed_doesNotReactivate() {
        // 1. Seed a SUCCESS COLLECTION transaction
        BigDecimal amount = new BigDecimal("5000");
        String txnId = seedSuccessfulCollectionTransaction(amount);

        // 2. Seed FAILED disbursement (D1) with one RELEASED ref (idempotencyKey IDEM-RETRY-002)
        String idempotencyKey = "IDEM-RETRY-002-" + UUID.randomUUID();
        String disbursementId = seedFailedDisbursementWithReleasedClaim(
                idempotencyKey, "PROVIDER_ERROR", txnId, amount);

        // 3. Seed D2: another PROCESSING disbursement with a PENDING ref for the SAME txnId
        seedActivePendingClaimForDifferentDisbursement(txnId);

        // 4. Build request with same idempotencyKey + txnIds
        DisbursementRequest request = new DisbursementRequest(
                MTN_MSISDN, amount, "XAF", "REF-RETRY-002", null, null,
                List.of(txnId), idempotencyKey);

        // 5. Call initiate — D2 has PENDING claim on txnId → IDEM-01 guard fires
        DisbursementResponse response = orchestrator.initiate(tenantId, request);

        // 6. Assert TRANSACTION_CLAIMED error
        assertThat(response.errorCode())
                .isEqualTo(DisbursementOrchestratorError.TRANSACTION_CLAIMED.getErrorCode());

        // 7. Reload D1: disbursementStatus == FAILED (unchanged), retryCount == 0 (unchanged)
        Optional<Disbursement> d1After = disbursementRepository.findByDisbursementId(disbursementId);
        assertThat(d1After).isPresent();
        assertThat(d1After.get().getDisbursementStatus()).isEqualTo(DisbursementStatus.FAILED);
        assertThat(d1After.get().getRetryCount()).isEqualTo(0);

        // 8. D1's DisbursementTransactionRef: refStatus == RELEASED (unchanged — no reactivation)
        var d1Refs = refRepository.findAll().stream()
                .filter(r -> r.getTransactionId().equals(txnId) && r.getRefStatus() == DisbursementRefStatus.RELEASED)
                .toList();
        assertThat(d1Refs).hasSize(1);

        // 9. WireMock received 0 disbursement POSTs
        assertThat(mtnServer.findAll(postRequestedFor(urlPathEqualTo("/v1_0/transfer")))).isEmpty();
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 3: IDEM-03 — terminal error code → cached FAILED returned verbatim, no side effects
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void terminalRetry_returnsCachedFailedResponse_doesNotTouchDisbursement() {
        // 1. Seed a SUCCESS COLLECTION transaction
        BigDecimal amount = new BigDecimal("5000");
        String txnId = seedSuccessfulCollectionTransaction(amount);

        // 2. Seed FAILED disbursement with terminal error code RECIPIENT_NOT_FOUND
        String idempotencyKey = "IDEM-RETRY-003-" + UUID.randomUUID();
        String disbursementId = seedFailedDisbursementWithReleasedClaim(
                idempotencyKey, "RECIPIENT_NOT_FOUND", txnId, amount);

        // 3. Build request with same idempotencyKey + txnIds
        DisbursementRequest request = new DisbursementRequest(
                MTN_MSISDN, amount, "XAF", "REF-RETRY-003", null, null,
                List.of(txnId), idempotencyKey);

        // 4. Call initiate — terminal code → cached response returned verbatim
        DisbursementResponse response = orchestrator.initiate(tenantId, request);

        // 5. Assert FAILED + RECIPIENT_NOT_FOUND
        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.errorCode()).isEqualTo("RECIPIENT_NOT_FOUND");
        assertThat(response.disbursementId()).isEqualTo(disbursementId);

        // 6. Reload Disbursement: disbursementStatus == FAILED (unchanged), retryCount == 0 (unchanged)
        Optional<Disbursement> dsbAfter = disbursementRepository.findByDisbursementId(disbursementId);
        assertThat(dsbAfter).isPresent();
        assertThat(dsbAfter.get().getDisbursementStatus()).isEqualTo(DisbursementStatus.FAILED);
        assertThat(dsbAfter.get().getRetryCount()).isEqualTo(0);

        // 7. DisbursementTransactionRef: refStatus == RELEASED (unchanged)
        var refsAfter = refRepository.findAll().stream()
                .filter(r -> r.getTransactionId().equals(txnId))
                .toList();
        assertThat(refsAfter).hasSize(1);
        assertThat(refsAfter.get(0).getRefStatus()).isEqualTo(DisbursementRefStatus.RELEASED);

        // 8. WireMock received 0 disbursement POSTs
        assertThat(mtnServer.findAll(postRequestedFor(urlPathEqualTo("/v1_0/transfer")))).isEmpty();
    }
}
