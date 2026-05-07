package com.softropic.payam.mtn;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.common.payment.PaymentCommand;
import com.softropic.payam.common.payment.ProviderResult;
import com.softropic.payam.common.payment.SubscriberStatus;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.mtn.service.MtnMoMoPort;
import com.softropic.payam.platform.tenant.contract.ApiKeyEnvironment;
import com.softropic.payam.platform.tenant.service.TenantService;
import com.softropic.payam.transaction.service.TransactionService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "mtn.callback-ip-whitelist=", // empty = sandbox mode in MtnIpWhitelistInterceptor (accepts all)
    "mtn.collection-token-url=http://localhost:${wiremock.server.port}/token/collection",
    "mtn.disbursement-token-url=http://localhost:${wiremock.server.port}/token/disbursement"
})
@EnableWireMock(@ConfigureWireMock(name = "mtn", baseUrlProperties = {"mtn.collection-base-url", "mtn.disbursement-base-url"}))
class MtnMoMoPortIT {

    @InjectWireMock("mtn")
    WireMockServer mtnServer;

    @Autowired MtnMoMoPort mtnMoMoPort;
    @Autowired TenantService tenantService;
    @Autowired TransactionService transactionService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired StringRedisTemplate redis;
    @Autowired TestRestTemplate testRestTemplate;

    private Long tenantId;

    @BeforeEach
    void setUp() {
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

        tenantId = tenantService.createTenant("mtn-test-tenant", ApiKeyEnvironment.PROD).tenant().getId();

        // Stub MTN collection token endpoint
        mtnServer.stubFor(post(urlPathEqualTo("/token/collection"))
            .willReturn(okJson("{\"access_token\":\"mtn-coll-bearer\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
        
        // Stub MTN disbursement token endpoint
        mtnServer.stubFor(post(urlPathEqualTo("/token/disbursement"))
            .willReturn(okJson("{\"access_token\":\"mtn-disb-bearer\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
    }

    @AfterEach
    void tearDown() {
        mtnServer.resetAll();
        redis.delete("mtn:token:coll");
        redis.delete("mtn:token:disb");
        // Delete in FK-safe order: ledger_entry -> payment_event_log -> transaction -> tenant -> sec
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM main.ledger_entry");
            jdbcTemplate.execute("DELETE FROM main.payment_event_log");
            jdbcTemplate.execute("DELETE FROM main.transaction");
            jdbcTemplate.execute("DELETE FROM main.tenant_api_key");
            jdbcTemplate.execute("DELETE FROM main.tenant");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void account_holder_validation_returns_active_for_200_response() {
        mtnServer.stubFor(get(urlPathEqualTo("/v1_0/accountholder/MSISDN/237692954629/basicuserinfo"))
            .willReturn(okJson("{}")));

        SubscriberStatus status = mtnMoMoPort.validateSubscriber("+237692954629");

        assertThat(status.active()).isTrue();
    }

    @Test
    void request_to_pay_initiation_returns_pending_result_with_provider_ref() {
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*"))
            .willReturn(okJson("{}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));

        var tx = transactionService.initiate(tenantId, MobilePaymentProvider.MTN,
            BigDecimal.valueOf(1000), "XAF", "EXT-MTN-001");

        PaymentCommand cmd = new PaymentCommand(
            tx.getTransactionId(), tx.getTraceId(), tenantId,
            "+237692954629", BigDecimal.valueOf(1000), "XAF",
            "EXT-MTN-001", "IDEM-MTN-001", MobilePaymentProvider.MTN,
            null, null, null, null
        );

        ProviderResult result = mtnMoMoPort.initiateMerchantPayment(cmd);

        assertThat(result.pending()).isTrue();
        assertThat(result.providerRef()).isNotNull();
    }

    @Test
    void initiate_disbursement_returns_pending_and_posts_ledger() {
        // Stub account validation
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*"))
            .willReturn(okJson("{}")));
        // Stub transfer: MTN returns 202 Accepted
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/transfer"))
            .willReturn(aResponse().withStatus(202)));

        var tx = transactionService.initiate(tenantId, MobilePaymentProvider.MTN,
            BigDecimal.valueOf(5000), "XAF", "EXT-DISB-001");

        PaymentCommand cmd = new PaymentCommand(
            tx.getTransactionId(), tx.getTraceId(), tenantId,
            "+237692954629", BigDecimal.valueOf(5000), "XAF",
            "EXT-DISB-001", "IDEM-DISB-001", MobilePaymentProvider.MTN,
            null, null, null, null,
            BigDecimal.valueOf(100) // fee
        );

        ProviderResult result = mtnMoMoPort.initiateDisbursement(cmd);

        assertThat(result.pending()).isTrue();
        assertThat(result.providerRef()).isNotNull();

        // Verify ledger entries
        Integer entryCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.ledger_entry WHERE transaction_id = ?",
            Integer.class, tx.getTransactionId());
        assertThat(entryCount).isEqualTo(3); // DISBURSEMENT has 3 entries: principal, fee, total
    }

    @Test
    void get_collection_status_returns_success() {
        mtnServer.stubFor(get(urlPathMatching("/v1_0/requesttopay/.*"))
            .willReturn(okJson("{\"status\":\"SUCCESSFUL\",\"financialTransactionId\":\"FIN-002\"}")));

        ProviderResult result = mtnMoMoPort.getCollectionTransactionStatus("some-uuid-ref");

        assertThat(result.pending()).isFalse();
        assertThat(result.rawStatus()).isEqualTo("SUCCESSFUL");
    }

    @Test
    void get_disbursement_status_returns_success() {
        mtnServer.stubFor(get(urlPathMatching("/v1_0/transfer/.*"))
            .willReturn(okJson("{\"status\":\"SUCCESSFUL\",\"financialTransactionId\":\"FIN-DISB-002\"}")));

        ProviderResult result = mtnMoMoPort.getDisbursementTransactionStatus("some-uuid-ref");

        assertThat(result.pending()).isFalse();
        assertThat(result.rawStatus()).isEqualTo("SUCCESSFUL");
    }
}
