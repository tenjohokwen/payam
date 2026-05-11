package com.softropic.payam.e2e.disbursement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.config.TestDataCleaner;
import com.softropic.payam.payment.disbursement.contract.DisbursementOrchestratorError;
import com.softropic.payam.payment.disbursement.repo.DisbursementRepository;
import com.softropic.payam.payment.disbursement.repo.MerchantWalletBalanceRepository;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E integration test for disbursement fraud block and idempotency race scenarios.
 *
 * <p>Covers two TEST-01 / TEST-04 sub-clauses:
 * <ol>
 *   <li><b>TEST-01 fraud block</b>: POST /v1/disbursements to a MSISDN on the Redis blocklist
 *       ({@code fraud:dsb:msisdn:blocklist}) that is also a NEW recipient for the tenant produces
 *       HTTP 422 with {@code errorCode=FRAUD_BLOCK}, ZERO MTN /v1_0/transfer calls, and ZERO
 *       rows in {@code main.disbursement}.</li>
 *   <li><b>TEST-04 idempotency race</b>: 20 concurrent POSTs with the SAME Idempotency-Key header
 *       produce exactly 1 disbursement row and exactly 1 MTN /v1_0/transfer call; all 20 threads
 *       receive HTTP 202.</li>
 * </ol>
 *
 * <p>Topology: standalone IT (no {@code AbstractPayamE2ETest} extension) with dual WireMock servers
 * (mtn-collection + mtn-disbursement on same port, orange). Required because the collection-era
 * base class does not configure {@code mtn.disbursement-base-url}.
 *
 * <p>CRITICAL: A blocklist hit alone gives score=80 (NOT &gt; 80, so NOT blocking). A fresh
 * tenant + never-seen MSISDN adds NEW_RECIPIENT=+15, giving 95 &gt; 80 which triggers FRAUD_BLOCK.
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
class DisbursementFraudBlockE2EIT {

    // ── Constants ────────────────────────────────────────────────────────────────

    /** Redis SET key holding blocked disbursement recipient MSISDNs (matches DisbursementFraudEvaluationService.BLOCKLIST_KEY). */
    private static final String FRAUD_BLOCKLIST_KEY = "fraud:dsb:msisdn:blocklist";

    /** MTN-prefix MSISDN we will seed on the blocklist — must be unseen by the tenant for NEW_RECIPIENT signal. */
    private static final String FRAUD_MSISDN = "+237671999000";

    /** Regular MTN recipient for the idempotency race (distinct from FRAUD_MSISDN). */
    private static final String CLEAN_MSISDN = "+237671234567";

    private static final BigDecimal PRINCIPAL = new BigDecimal("5000");
    private static final int RACE_THREADS = 20;

    // ── Injected infrastructure ──────────────────────────────────────────────────

    @LocalServerPort
    private int serverPort;

    @InjectWireMock("mtn")    WireMockServer mtnServer;
    @InjectWireMock("orange") WireMockServer orangeServer;

    @Autowired TenantService tenantService;
    @Autowired DisbursementRepository disbursementRepository;
    @Autowired MerchantWalletBalanceRepository walletRepo;
    @Autowired PlatformConfigService platformConfigService;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired TestDataCleaner testDataCleaner;

    // ── Per-test state ────────────────────────────────────────────────────────────

    private Long tenantId;
    private String rawApiKey;

    // ── Lifecycle ─────────────────────────────────────────────────────────────────

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

        // Create FRESH tenant — guarantees FRAUD_MSISDN and CLEAN_MSISDN are both NEW recipients (+15 bonus)
        var created = tenantService.createTenant("fraud-e2e-" + UUID.randomUUID(), ApiKeyEnvironment.PROD);
        tenantId = created.tenant().getId();
        rawApiKey = created.rawKey();

        // Seed wallet with 1,000,000 XAF — large enough for 20 concurrent reservations of 5000 each
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

        // Stub MTN account validation (subscriber check) + transfer (the idempotency-race winner needs this)
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*"))
            .willReturn(okJson("{}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/transfer"))
            .willReturn(aResponse().withStatus(202)));

        // Seed Orange platform config (channelMsisdn required by OrangeMoneyPort)
        platformConfigService.update("ORANGE", "691000000");

        // Flush Redis to ensure clean state (no leftover blocklist/idempotency keys)
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

    // ── Test 1: Fraud block via Redis blocklist + new-recipient combo ─────────────

    /**
     * TEST-01 sub-clause: blocklist + new-recipient → FRAUD_BLOCK.
     *
     * <p>BLOCKLIST_WEIGHT=80 + NEW_RECIPIENT_WEIGHT=15 = 95 &gt; BLOCK_THRESHOLD=80.
     * Score of 80 alone would NOT block (threshold is strictly greater).
     *
     * <p>Asserts: HTTP 422, errorCode=FRAUD_BLOCK, 0 provider calls, 0 disbursement rows.
     */
    @Test
    void fraudBlocklistMsisdn_returns422FraudBlock_noProviderCall_noPersistedRow() throws Exception {
        // Seed the blocklist
        redis.opsForSet().add(FRAUD_BLOCKLIST_KEY, FRAUD_MSISDN);

        // Verify seeding succeeded
        assertThat(Boolean.TRUE.equals(redis.opsForSet().isMember(FRAUD_BLOCKLIST_KEY, FRAUD_MSISDN)))
            .as("FRAUD_MSISDN must be in the blocklist before the request")
            .isTrue();

        // Submit disbursement to blocklisted MSISDN
        RestTemplate rt = noErrorRestTemplate();
        String idem = "FRAUD-" + UUID.randomUUID();
        List<String> fraudTxnIds = seedTxnsForClaim(tenantId, 1, PRINCIPAL);
        ResponseEntity<String> r = postDisbursement(rt, FRAUD_MSISDN, "REF-FRAUD-" + UUID.randomUUID(), idem, fraudTxnIds);

        // Assert HTTP 422
        assertThat(r.getStatusCode().value())
            .as("Blocklisted+new-recipient MSISDN must be rejected with HTTP 422")
            .isEqualTo(422);

        // Assert errorCode = FRAUD_BLOCK
        String errorCode = parseErrorCode(r.getBody());
        assertThat(errorCode)
            .as("Error code must be FRAUD_BLOCK")
            .isEqualTo(DisbursementOrchestratorError.FRAUD_BLOCK.getErrorCode());

        // Verify ZERO MTN transfer calls — fraud fires before provider dispatch
        mtnServer.verify(0, postRequestedFor(urlPathEqualTo("/v1_0/transfer")));

        // Verify NO disbursement row persisted — FRAUD_BLOCK fires before persistence
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM main.disbursement WHERE tenant_id = ?",
            Integer.class, tenantId);
        assertThat(count)
            .as("No disbursement row must exist when FRAUD_BLOCK fires")
            .isZero();
    }

    // ── Test 2: Idempotency race — 20 concurrent requests with the same key ────────

    /**
     * TEST-04 sub-clause: idempotency race — same Idempotency-Key, 20 threads → exactly 1 row.
     *
     * <p>All 20 threads use the SAME {@code Idempotency-Key} header. The first to obtain the
     * Redis NX idempotency lock writes 1 disbursement row and calls MTN. The other 19 receive
     * the cached response (errorCode=null OR DISBURSEMENT_ALREADY_PROCESSING, both mapped to 202
     * by DisbursementResource). So all 20 must receive HTTP 202.
     *
     * <p>Asserts: all 20 receive 202, exactly 1 disbursement row, exactly 1 MTN transfer call.
     */
    @Test
    void twentyConcurrentRequestsWithSameIdempotencyKey_produceExactlyOneDisbursementRow() throws Exception {
        final String SHARED_IDEM = "RACE-IDEM-" + UUID.randomUUID();

        // Pre-seed unique transaction IDs for each thread before launching the race.
        // Each thread needs its own txnId to pass @NotEmpty validation; the idempotency
        // guard ensures only 1 disbursement row is created regardless.
        List<List<String>> perThreadTxnIds = new ArrayList<>();
        for (int i = 0; i < RACE_THREADS; i++) {
            perThreadTxnIds.add(seedTxnsForClaim(tenantId, 1, PRINCIPAL));
        }

        CyclicBarrier barrier = new CyclicBarrier(RACE_THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(RACE_THREADS);
        AtomicInteger accepted = new AtomicInteger(0);
        AtomicInteger other = new AtomicInteger(0);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < RACE_THREADS; i++) {
            final List<String> threadTxnIds = perThreadTxnIds.get(i);
            pool.submit(() -> {
                RestTemplate rt = noErrorRestTemplate();
                try {
                    barrier.await(15, TimeUnit.SECONDS);
                    // Same idempotency key, same MSISDN, same amount — different reference
                    // (controller reads ONLY the Idempotency-Key header for idempotency)
                    ResponseEntity<String> r = postDisbursement(rt, CLEAN_MSISDN,
                        "REF-RACE-" + UUID.randomUUID(), SHARED_IDEM, threadTxnIds);
                    if (r.getStatusCode().value() == 202) {
                        accepted.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }

        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS))
            .as("All 20 threads must complete within 120 seconds")
            .isTrue();

        assertThat(failures)
            .as("No thread must throw an unexpected exception")
            .isEmpty();

        // All 20 threads must receive 202 — winner persists, others get cached response
        assertThat(accepted.get())
            .as("All 20 threads must receive HTTP 202 (winner persists, others get cached)")
            .isEqualTo(RACE_THREADS);

        assertThat(other.get())
            .as("No thread must receive a non-202 status")
            .isZero();

        // Exactly 1 disbursement row — idempotency guard blocked all duplicates
        Integer rowCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM main.disbursement WHERE tenant_id = ?",
            Integer.class, tenantId);
        assertThat(rowCount)
            .as("Exactly 1 disbursement row must exist (idempotency race guard)")
            .isEqualTo(1);

        // Exactly 1 MTN /v1_0/transfer call — all subsequent 19 threads were served from cache
        mtnServer.verify(1, postRequestedFor(urlPathEqualTo("/v1_0/transfer")));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    /**
     * Build a {@link RestTemplate} that does NOT throw on 4xx/5xx responses.
     * Required for capturing HTTP 422 without HttpClientErrorException.
     */
    private RestTemplate noErrorRestTemplate() {
        RestTemplate rt = new RestTemplate();
        rt.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(HttpStatusCode statusCode) { return false; }

            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException { return false; }
        });
        return rt;
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

    /**
     * POST a disbursement request to {@code /v1/disbursements} with the given MSISDN, reference,
     * and idempotency key. Uses the tenant's raw API key for authentication.
     */
    private ResponseEntity<String> postDisbursement(RestTemplate rt, String msisdn,
                                                     String reference, String idempotencyKey,
                                                     List<String> transactionIds) {
        String txnIdsJson = transactionIds.stream()
            .map(id -> "\"" + id + "\"")
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        String body = String.format(
            "{\"recipientMsisdn\":\"%s\",\"amount\":5000,\"currency\":\"XAF\"," +
            "\"reference\":\"%s\",\"transactionIds\":%s}",
            msisdn, reference, txnIdsJson);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", rawApiKey);
        headers.set("Idempotency-Key", idempotencyKey);

        return rt.exchange(
            "http://localhost:" + serverPort + "/v1/disbursements",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            String.class);
    }

    private String parseErrorCode(String body) throws Exception {
        if (body == null || body.isBlank()) {
            return null;
        }
        return new ObjectMapper().readTree(body).path("errorCode").asText(null);
    }
}
