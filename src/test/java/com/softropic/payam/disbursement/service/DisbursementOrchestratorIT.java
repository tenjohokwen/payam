package com.softropic.payam.disbursement.service;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.config.TestDataCleaner;
import com.softropic.payam.disbursement.contract.DisbursementOrchestratorError;
import com.softropic.payam.disbursement.contract.DisbursementRefStatus;
import com.softropic.payam.disbursement.contract.DisbursementRequest;
import com.softropic.payam.disbursement.contract.DisbursementResponse;
import com.softropic.payam.disbursement.repo.DisbursementRepository;
import com.softropic.payam.disbursement.repo.DisbursementTransactionRefRepository;
import com.softropic.payam.disbursement.repo.MerchantWalletBalanceRepository;
import com.softropic.payam.platform.service.PlatformConfigService;
import com.softropic.payam.tenant.contract.ApiKeyEnvironment;
import com.softropic.payam.tenant.service.TenantService;
import com.softropic.payam.transaction.contract.LedgerFlow;
import com.softropic.payam.transaction.contract.TransactionStatus;
import com.softropic.payam.transaction.repo.Transaction;
import com.softropic.payam.transaction.repo.TransactionRepository;

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
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for DisbursementOrchestrator against real WireMock + Postgres + Redis.
 *
 * <p>Tests prove:
 * <ul>
 *   <li>MTN happy path: small disbursement routes to MTN, transitions to PROCESSING, wallet debited</li>
 *   <li>Orange happy path: small disbursement routes to Orange, transitions to PROCESSING</li>
 *   <li>Step-up gating: amount > 500,000 XAF lands in PENDING_CONFIRMATION; provider NOT called; wallet IS reserved</li>
 *   <li>Confirm dispatch: confirm on PENDING_CONFIRMATION dispatches to MTN provider</li>
 *   <li>Invalid-state confirm: confirm on PROCESSING returns INVALID_STATE; no extra provider calls</li>
 *   <li>Insufficient balance: amount exceeds wallet; INSUFFICIENT_BALANCE response; provider NOT called</li>
 * </ul>
 *
 * <p>CRITICAL: @ConfigureWireMock includes both mtn.collection-base-url AND mtn.disbursement-base-url
 * — without the disbursement URL, MtnMoMoPort.initiateDisbursement uses the wrong endpoint (Pitfall 5
 * from 51-RESEARCH.md).
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
class DisbursementOrchestratorIT {

    private static final String MTN_MSISDN    = "+237671234567";
    private static final String ORANGE_MSISDN = "+237691234567";

    @InjectWireMock("mtn")    WireMockServer mtnServer;
    @InjectWireMock("orange") WireMockServer orangeServer;

    @Autowired DisbursementOrchestrator orchestrator;
    @Autowired TenantService tenantService;
    @Autowired DisbursementRepository disbursementRepository;
    @Autowired MerchantWalletBalanceRepository walletRepo;
    @Autowired PlatformConfigService platformConfigService;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired TestDataCleaner testDataCleaner;
    @Autowired TransactionRepository transactionRepository;
    @Autowired DisbursementTransactionRefRepository transactionRefRepository;

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
        tenantId = tenantService.createTenant("dsb-it-" + UUID.randomUUID(), ApiKeyEnvironment.PROD)
                .tenant().getId();

        // Seed wallet with 1,000,000 XAF balance (large enough for step-up tests that use 600,000+)
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

        // Seed Orange platform config for subscriber validation (channelMsisdn needed by OrangeMoneyPort)
        platformConfigService.update("ORANGE", "691000000");
    }

    @AfterEach
    void tearDown() {
        mtnServer.resetAll();
        orangeServer.resetAll();
        // Clear Redis — wipe all keys to avoid idempotency/velocity key bleed between tests
        try {
            redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        } catch (Exception ignored) {}
        testDataCleaner.wipeAll();
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────────────

    /**
     * Seeds {@code count} Transaction rows with txStatus=SUCCESS, flow=COLLECTION for the
     * current test tenant. Used by tests 1-5 so that Step 7.5 claim validation succeeds.
     * Each transaction has {@code amount=eachAmount} so the total for the DisbursementRequest
     * must be {@code count * eachAmount}.
     */
    private List<String> seedTxnsForClaim(Long tenantId, int count, BigDecimal eachAmount) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> {
            String id = UUID.randomUUID().toString();
            Transaction tx = Transaction.builder()
                    .transactionId(id).traceId(id).tenantId(tenantId)
                    .provider(MobilePaymentProvider.MTN)
                    .txStatus(TransactionStatus.SUCCESS)
                    .flow(LedgerFlow.COLLECTION)
                    .amount(eachAmount).currency("XAF")
                    .feeAmount(BigDecimal.ZERO)
                    .build();
            transactionTemplate.execute(s -> {
                transactionRepository.save(tx);
                return null;
            });
            return id;
        }).toList();
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 1: MTN happy path — small disbursement → PROCESSING, wallet reduced
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void mtn_happy_path_returns_processing_and_persists_disbursement() {
        // Stub MTN account validation (validateSubscriber calls validateAccountHolder)
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*"))
            .willReturn(okJson("{}")));
        // Stub MTN transfer → 202 Accepted
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/transfer"))
            .willReturn(aResponse().withStatus(202)));

        // Seed 1 SUCCESS/COLLECTION transaction matching the request amount (Step 7.5 validation)
        BigDecimal requestAmount = new BigDecimal("5000");
        List<String> txnIds = seedTxnsForClaim(tenantId, 1, requestAmount);

        DisbursementRequest request = new DisbursementRequest(
            MTN_MSISDN, requestAmount, "XAF", "REF-MTN-001",
            null, null, txnIds, "IDEM-MTN-HAPPY-" + UUID.randomUUID());

        DisbursementResponse response = orchestrator.initiate(tenantId, request);

        assertThat(response.status()).isEqualTo("PROCESSING");
        assertThat(response.disbursementId()).isNotBlank();
        assertThat(response.errorCode()).isNull();

        // Verify disbursement row persisted in PROCESSING
        String dbStatus = jdbcTemplate.queryForObject(
            "SELECT disbursement_status FROM main.disbursement WHERE disbursement_id = ?",
            String.class, response.disbursementId());
        assertThat(dbStatus).isEqualTo("PROCESSING");

        // Verify wallet balance reduced (5000 principal + fee reserved)
        BigDecimal balance = jdbcTemplate.queryForObject(
            "SELECT balance FROM main.merchant_wallet_balance WHERE tenant_id = ?",
            BigDecimal.class, tenantId);
        assertThat(balance).isLessThan(new BigDecimal("1000000"));

        // CLAIM-01: verify PENDING DisbursementTransactionRef rows exist after initiate
        long pendingRefs = transactionRefRepository.findAll().stream()
                .filter(r -> r.getRefStatus() == DisbursementRefStatus.PENDING)
                .count();
        assertThat(pendingRefs).as("CLAIM-01: PENDING ref per txn").isEqualTo(txnIds.size());
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 2: Orange happy path — small disbursement → PROCESSING
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void orange_happy_path_returns_processing() {
        // Stub Orange subscriber info (validateSubscriber → /infos/subscriber/customer/{national_msisdn})
        // Orange national MSISDN: 237691234567 → strip +237 → 691234567
        orangeServer.stubFor(post(urlPathMatching("/infos/subscriber/customer/.*"))
            .willReturn(okJson("{\"data\":{\"firstname\":\"Jean\"},\"message\":\"OK\"}")));
        // Stub Orange cashout endpoint
        orangeServer.stubFor(post(urlPathEqualTo("/cashout"))
            .willReturn(okJson("{\"status\":\"SUCCESS\"}")));

        // Seed 1 SUCCESS/COLLECTION transaction matching the request amount (Step 7.5 validation)
        BigDecimal requestAmount = new BigDecimal("5000");
        List<String> txnIds = seedTxnsForClaim(tenantId, 1, requestAmount);

        DisbursementRequest request = new DisbursementRequest(
            ORANGE_MSISDN, requestAmount, "XAF", "REF-ORANGE-001",
            null, null, txnIds, "IDEM-ORANGE-HAPPY-" + UUID.randomUUID());

        DisbursementResponse response = orchestrator.initiate(tenantId, request);

        assertThat(response.status()).isEqualTo("PROCESSING");
        assertThat(response.errorCode()).isNull();

        // Verify disbursement row persisted
        String dbStatus = jdbcTemplate.queryForObject(
            "SELECT disbursement_status FROM main.disbursement WHERE disbursement_id = ?",
            String.class, response.disbursementId());
        assertThat(dbStatus).isEqualTo("PROCESSING");

        // CLAIM-01: verify PENDING DisbursementTransactionRef rows exist after initiate
        long pendingRefs = transactionRefRepository.findAll().stream()
                .filter(r -> r.getRefStatus() == DisbursementRefStatus.PENDING)
                .count();
        assertThat(pendingRefs).as("CLAIM-01: PENDING ref per txn").isEqualTo(txnIds.size());
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 3: Step-up — amount > 500,000 XAF → PENDING_CONFIRMATION; provider NOT called
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void step_up_amount_returns_pending_confirmation_no_provider_call() {
        // Seed 1 SUCCESS/COLLECTION transaction for 600000 XAF (Step 7.5 runs BEFORE step-up return)
        BigDecimal requestAmount = new BigDecimal("600000");
        List<String> txnIds = seedTxnsForClaim(tenantId, 1, requestAmount);

        DisbursementRequest request = new DisbursementRequest(
            MTN_MSISDN, requestAmount, "XAF", "REF-STEPUP-001",
            null, null, txnIds, "IDEM-STEPUP-" + UUID.randomUUID());

        DisbursementResponse response = orchestrator.initiate(tenantId, request);

        assertThat(response.status()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(response.providerRef()).isNull();
        assertThat(response.errorCode()).isNull();

        // Provider was NOT called (no transfer request posted)
        assertThat(mtnServer.findAll(postRequestedFor(urlPathEqualTo("/v1_0/transfer")))).isEmpty();

        // Wallet IS reduced (reservation made before confirmation)
        BigDecimal balance = jdbcTemplate.queryForObject(
            "SELECT balance FROM main.merchant_wallet_balance WHERE tenant_id = ?",
            BigDecimal.class, tenantId);
        assertThat(balance).isLessThan(new BigDecimal("1000000"));

        // Disbursement row in PENDING_CONFIRMATION
        String dbStatus = jdbcTemplate.queryForObject(
            "SELECT disbursement_status FROM main.disbursement WHERE disbursement_id = ?",
            String.class, response.disbursementId());
        assertThat(dbStatus).isEqualTo("PENDING_CONFIRMATION");

        // CLAIM-01: claims are created even for PENDING_CONFIRMATION (Step 7.5 runs before step-up return)
        long pendingRefs = transactionRefRepository.findAll().stream()
                .filter(r -> r.getRefStatus() == DisbursementRefStatus.PENDING)
                .count();
        assertThat(pendingRefs).as("CLAIM-01: PENDING ref exists even for PENDING_CONFIRMATION").isEqualTo(txnIds.size());
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 4: Confirm on PENDING_CONFIRMATION → dispatches to MTN → PROCESSING
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void confirm_pending_disbursement_dispatches_to_provider() {
        // Seed 1 SUCCESS/COLLECTION transaction for the initiate phase (600000 XAF, step-up path)
        // confirm() uses pseudoRequest with transactionIds=null (Plan 01 Step 4) — no extra seeding needed.
        BigDecimal initAmount = new BigDecimal("600000");
        List<String> txnIds = seedTxnsForClaim(tenantId, 1, initAmount);

        // First: create a step-up disbursement in PENDING_CONFIRMATION
        DisbursementRequest initRequest = new DisbursementRequest(
            MTN_MSISDN, initAmount, "XAF", "REF-CONFIRM-001",
            null, null, txnIds, "IDEM-CONFIRM-" + UUID.randomUUID());

        DisbursementResponse initResponse = orchestrator.initiate(tenantId, initRequest);
        assertThat(initResponse.status()).isEqualTo("PENDING_CONFIRMATION");
        String disbursementId = initResponse.disbursementId();

        // Now stub MTN for the confirm dispatch
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*"))
            .willReturn(okJson("{}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/transfer"))
            .willReturn(aResponse().withStatus(202)));

        DisbursementResponse confirmResponse = orchestrator.confirm(tenantId, disbursementId);

        assertThat(confirmResponse.status()).isEqualTo("PROCESSING");
        assertThat(confirmResponse.errorCode()).isNull();

        // Exactly 1 MTN transfer call made (during confirm, not during initiate)
        assertThat(mtnServer.findAll(postRequestedFor(urlPathEqualTo("/v1_0/transfer")))).hasSize(1);

        // DB row in PROCESSING
        String dbStatus = jdbcTemplate.queryForObject(
            "SELECT disbursement_status FROM main.disbursement WHERE disbursement_id = ?",
            String.class, disbursementId);
        assertThat(dbStatus).isEqualTo("PROCESSING");
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 5: Confirm on already-PROCESSING disbursement → INVALID_STATE
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void confirm_already_processed_disbursement_returns_invalid_state() {
        // Create a small-amount disbursement (will go directly to PROCESSING)
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*"))
            .willReturn(okJson("{}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/transfer"))
            .willReturn(aResponse().withStatus(202)));

        // Seed 1 SUCCESS/COLLECTION transaction for the initiate (5000 XAF, non-step-up → PROCESSING)
        BigDecimal requestAmount = new BigDecimal("5000");
        List<String> txnIds = seedTxnsForClaim(tenantId, 1, requestAmount);

        DisbursementRequest request = new DisbursementRequest(
            MTN_MSISDN, requestAmount, "XAF", "REF-INVALID-001",
            null, null, txnIds, "IDEM-INVALID-" + UUID.randomUUID());

        DisbursementResponse initResponse = orchestrator.initiate(tenantId, request);
        assertThat(initResponse.status()).isEqualTo("PROCESSING");
        String disbursementId = initResponse.disbursementId();

        // Reset WireMock to detect if any extra calls are made
        mtnServer.resetAll();

        // Attempt to confirm a PROCESSING disbursement
        DisbursementResponse confirmResponse = orchestrator.confirm(tenantId, disbursementId);

        assertThat(confirmResponse.errorCode())
            .isEqualTo(DisbursementOrchestratorError.INVALID_STATE.getErrorCode());

        // No extra MTN calls were made during confirm
        assertThat(mtnServer.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 6: Insufficient balance → INSUFFICIENT_BALANCE; provider NOT called
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void insufficient_balance_returns_failed_no_provider_call() {
        // transactionIds: dummy is acceptable here — INSUFFICIENT_BALANCE fires at Step 3
        // (velocity/wallet check), strictly BEFORE Step 7.5 claim validation runs. The
        // Phase 54 wallet-balance gate may be retired in a separate revision; this test
        // is preserved for that follow-up.
        //
        // NOTE: The v11 refactor (SCHEMA-03) retired the wallet-reservation model. The
        // INSUFFICIENT_BALANCE path no longer exists in DisbursementOrchestrator — the
        // wallet check was removed as part of claim-based locking. This test is kept as a
        // placeholder to track the diverged behavior; a separate Phase 54/57 cleanup plan
        // should either re-introduce the check or remove this test entirely.
        DisbursementRequest request = new DisbursementRequest(
            MTN_MSISDN, new BigDecimal("2000000"), "XAF", "REF-INSUF-001",
            null, null, List.of("dummy-txn-id"), "IDEM-INSUF-" + UUID.randomUUID());

        DisbursementResponse response = orchestrator.initiate(tenantId, request);

        assertThat(response.errorCode())
            .isEqualTo(DisbursementOrchestratorError.INSUFFICIENT_BALANCE.getErrorCode());

        // Provider was NOT called
        assertThat(mtnServer.findAll(postRequestedFor(urlPathEqualTo("/v1_0/transfer")))).isEmpty();
    }
}
