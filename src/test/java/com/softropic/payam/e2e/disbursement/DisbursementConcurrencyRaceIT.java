package com.softropic.payam.e2e.disbursement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.config.TestDataCleaner;
import com.softropic.payam.disbursement.repo.MerchantWalletBalance;
import com.softropic.payam.disbursement.repo.MerchantWalletBalanceRepository;
import com.softropic.payam.platform.admin.service.PlatformConfigService;
import com.softropic.payam.platform.tenant.contract.ApiKeyEnvironment;
import com.softropic.payam.platform.tenant.service.TenantService;

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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.HttpStatusCode;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 20-thread HTTP-level race test proving TEST-04: BAL-01 invariant under load.
 *
 * <p>With a wallet balance covering exactly 1 disbursement of PRINCIPAL (1000 XAF),
 * exactly 1 of 20 simultaneous POST /v1/disbursements requests succeeds (PROCESSING)
 * and the other 19 receive 422 INSUFFICIENT_BALANCE — no overdraft.
 *
 * <p>Complementary to WalletBalanceConcurrencyIT (service layer). This proves the
 * pessimistic-write-lock balance gate at the HTTP boundary.
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
class DisbursementConcurrencyRaceIT {

    private static final int THREADS = 20;
    private static final BigDecimal PRINCIPAL = new BigDecimal("1000");
    private static final BigDecimal WALLET_BALANCE = new BigDecimal("1000");

    /**
     * Generate a unique MTN MSISDN for a given thread index.
     *
     * <p>MSISDNs like +23767X000001 (X=0..19) all route to MTN via the hardcoded fallback
     * (national prefix "67" — not "65" or "69"). Using a distinct MSISDN per thread avoids
     * the per-MSISDN daily velocity limit (capacity=10) which would fire HTTP 422
     * DAILY_LIMIT_EXCEEDED after the 10th request to the same MSISDN, contaminating the
     * INSUFFICIENT_BALANCE count.
     */
    private static String mtnMsisdnForThread(int threadIndex) {
        // Format: +23767{threadIndex:02d}000001 — e.g. +2376700000001, +2376701000001
        // All have national prefix "67" → MTN
        return String.format("+23767%02d00001", threadIndex);
    }

    @InjectWireMock("mtn")
    private WireMockServer mtnServer;

    @InjectWireMock("orange")
    private WireMockServer orangeServer;

    @Autowired
    private TestRestTemplate testRestTemplate;

    @LocalServerPort
    private int serverPort;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private MerchantWalletBalanceRepository walletRepo;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private TestDataCleaner testDataCleaner;

    @Autowired
    private PlatformConfigService platformConfigService;

    private Long tenantId;
    private String rawApiKey;

    @BeforeEach
    void setUp() {
        testDataCleaner.wipeAll();

        // Seed JWT secret
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

        var created = tenantService.createTenant("race-e2e-" + UUID.randomUUID(), ApiKeyEnvironment.PROD);
        tenantId = created.tenant().getId();
        rawApiKey = created.rawKey();

        // Seed wallet with exactly WALLET_BALANCE (1000 XAF — enough for exactly 1 disbursement)
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.merchant_wallet_balance " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, " +
                "request_id, status, tenant_id, balance, reserved_amount, currency, version) " +
                "VALUES (?, 'TEST', NOW(), 'TEST', NOW(), gen_random_uuid()::text, 'ACTIVE', ?, ?, 0, 'XAF', 0)",
                System.currentTimeMillis(), tenantId, WALLET_BALANCE);
            return null;
        });

        // Stub MTN tokens
        mtnServer.stubFor(post(urlPathEqualTo("/token/collection"))
            .willReturn(okJson("{\"access_token\":\"mtn-coll-bearer\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
        mtnServer.stubFor(post(urlPathEqualTo("/token/disbursement"))
            .willReturn(okJson("{\"access_token\":\"mtn-disb-bearer\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));

        // Stub Orange token
        orangeServer.stubFor(post(urlPathEqualTo("/token"))
            .withHeader("Authorization", containing("Basic"))
            .willReturn(okJson("{\"access_token\":\"orange-bearer\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));

        // Stub MTN account validation + transfer (winner needs these to complete initiation)
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*")).willReturn(okJson("{}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/transfer")).willReturn(aResponse().withStatus(202)));

        platformConfigService.update("ORANGE", "691000000");

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

    private RestTemplate noErrorRestTemplate() {
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
        return rt;
    }

    private ResponseEntity<String> postDisbursementWithKey(RestTemplate rt, String idempotencyKey, String msisdn) {
        String body = String.format(
            "{\"recipientMsisdn\":\"%s\",\"amount\":%s,\"currency\":\"XAF\",\"reference\":\"%s\"}",
            msisdn, PRINCIPAL.toPlainString(), "REF-RACE-" + UUID.randomUUID());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", rawApiKey);
        headers.set("Idempotency-Key", idempotencyKey);
        return rt.exchange(
            "http://localhost:" + serverPort + "/v1/disbursements",
            HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private String parseStatus(String body) {
        try {
            return new ObjectMapper().readTree(body).path("status").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String parseErrorCode(String body) {
        try {
            return new ObjectMapper().readTree(body).path("errorCode").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    @Disabled("Wallet-balance concurrency race retired in SCHEMA-03 — claim-based locking replaced wallet gating. Covered by DisbursementClaimConcurrencyIT.")
    @Test
    void twentyConcurrentDisbursements_exactlyOneSucceeds_nineteenInsufficient_noOverdraft() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger insufficients = new AtomicInteger(0);
        AtomicInteger other = new AtomicInteger(0);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < THREADS; i++) {
            final int threadIndex = i;
            pool.submit(() -> {
                RestTemplate rt = noErrorRestTemplate();
                // Each thread uses a UNIQUE Idempotency-Key so the idempotency cache doesn't collapse requests.
                // Each thread also uses a UNIQUE MSISDN to avoid the per-MSISDN daily velocity limit
                // (capacity=10), which would cause 10 threads to receive DAILY_LIMIT_EXCEEDED instead of
                // INSUFFICIENT_BALANCE — all MSISDNs route to MTN (prefix "67").
                String idem = "RACE-" + UUID.randomUUID();
                String msisdn = mtnMsisdnForThread(threadIndex);
                try {
                    barrier.await(15, TimeUnit.SECONDS);
                    ResponseEntity<String> r = postDisbursementWithKey(rt, idem, msisdn);
                    int code = r.getStatusCode().value();
                    String status = parseStatus(r.getBody());
                    String errorCode = parseErrorCode(r.getBody());
                    if (code == 202 && "PROCESSING".equals(status)) {
                        successes.incrementAndGet();
                    } else if (code == 422 && "INSUFFICIENT_BALANCE".equals(errorCode)) {
                        insufficients.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }

        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).as("all threads complete within 120s").isTrue();
        assertThat(failures).as("no thread threw an exception").isEmpty();

        // Race outcome: exactly 1 winner, 19 INSUFFICIENT_BALANCE
        assertThat(successes.get()).as("exactly 1 PROCESSING").isEqualTo(1);
        assertThat(insufficients.get()).as("19 INSUFFICIENT_BALANCE").isEqualTo(THREADS - 1);
        assertThat(other.get()).as("no unexpected status codes").isZero();

        // BAL-01 wallet invariant — no overdraft
        MerchantWalletBalance wallet = walletRepo.findByTenantId(tenantId).orElseThrow();
        assertThat(wallet.getBalance()).as("balance is 0 after winner reserves it").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(wallet.getReservedAmount()).as("reserved == winner principal").isEqualByComparingTo(PRINCIPAL);

        // Provider called exactly once (only by the winner)
        mtnServer.verify(1, postRequestedFor(urlPathEqualTo("/v1_0/transfer")));

        // DB: exactly 1 PROCESSING row
        Integer processingRows = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM main.disbursement WHERE tenant_id = ? AND disbursement_status = 'PROCESSING'",
            Integer.class, tenantId);
        assertThat(processingRows).as("exactly 1 PROCESSING row in DB").isEqualTo(1);
    }
}
