package com.softropic.payam.orange;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.common.payment.PaymentCommand;
import com.softropic.payam.common.payment.ProviderResult;
import com.softropic.payam.common.payment.SubscriberStatus;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.orange.contract.exception.PayTokenExpiredException;
import com.softropic.payam.orange.service.OrangeMoneyPort;
import com.softropic.payam.platform.service.PlatformConfigService;
import com.softropic.payam.tenant.contract.ApiKeyEnvironment;
import com.softropic.payam.tenant.service.TenantService;
import com.softropic.payam.transaction.service.TransactionService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
@EnableWireMock(@ConfigureWireMock(name = "orange", baseUrlProperties = {"orange.base-url", "orange.pay-url"}))
class OrangeMoneyPortIT {

    @InjectWireMock("orange")
    WireMockServer orangeServer;

    @Autowired OrangeMoneyPort orangeMoneyPort;
    @Autowired TenantService tenantService;
    @Autowired TransactionService transactionService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate   transactionTemplate;
    @Autowired PlatformConfigService platformConfigService;

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

        tenantId = tenantService.createTenant("orange-test-tenant", ApiKeyEnvironment.PROD).tenant().getId();

        // Seed platform config with PIN
        platformConfigService.update("ORANGE", "691301143", "2222");

        // Seed orange access token WireMock stub
        orangeServer.stubFor(post(urlPathEqualTo("/token"))
            .withHeader("Authorization", containing("Basic"))
            .withRequestBody(equalTo("grant_type=client_credentials"))
            .willReturn(okJson("{\"access_token\":\"test-bearer-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
    }

    @AfterEach
    void tearDown() {
        orangeServer.resetAll();
        // Delete in FK-safe order: payment_event_log -> transaction -> tenant -> platform_config -> sec
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM main.payment_event_log");
            jdbcTemplate.execute("DELETE FROM main.transaction");
            jdbcTemplate.execute("DELETE FROM main.tenant_api_key");
            jdbcTemplate.execute("DELETE FROM main.tenant");
            jdbcTemplate.execute("DELETE FROM main.platform_config_aud"); // delete audit rows too
            jdbcTemplate.execute("DELETE FROM main.platform_config");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void subscriber_validation_returns_active_for_known_msisdn() {
        orangeServer.stubFor(post(urlPathEqualTo("/infos/subscriber/customer/692954629"))
            .withHeader("Authorization", equalTo("Bearer test-bearer-token"))
            .willReturn(okJson("{\"data\":{\"firstname\":\"Jean\",\"lastname\":\"Dupont\"},\"message\":\"OK\"}")));

        SubscriberStatus status = orangeMoneyPort.validateSubscriber("+237692954629");

        assertThat(status.active()).isTrue();
        assertThat(status.msisdn()).isEqualTo("+237692954629");
        assertThat(status.rawStatus()).isEqualTo("OK");
    }

    @Test
    void subscriber_validation_returns_inactive_for_unknown_msisdn() {
        orangeServer.stubFor(post(urlPathEqualTo("/infos/subscriber/customer/692954629"))
            .withHeader("Authorization", equalTo("Bearer test-bearer-token"))
            .willReturn(okJson("{\"data\":null,\"message\":\"Not found\"}")));

        SubscriberStatus status = orangeMoneyPort.validateSubscriber("+237692954629");

        assertThat(status.active()).isFalse();
    }

    @Test
    void merchant_payment_initiation_returns_pending_result_with_pay_token() {
        orangeServer.stubFor(post(urlPathEqualTo("/mp/init"))
            .withHeader("Authorization", equalTo("Bearer test-bearer-token"))
            .withHeader("X-AUTH-TOKEN", equalTo("T01TQU5EQk9YQVBJOk9NU0BOREJPWEBQSQ=="))
            .willReturn(okJson("{\"data\":{\"payToken\":\"tok-abc-123\"},\"message\":\"OK\"}")));

        orangeServer.stubFor(post(urlPathMatching("/mp/pay"))
            .withHeader("Authorization", equalTo("Bearer test-bearer-token"))
            .withHeader("X-AUTH-TOKEN", equalTo("T01TQU5EQk9YQVBJOk9NU0BOREJPWEBQSQ=="))
            .withRequestBody(containing("\"pin\":\"2222\""))
            .withRequestBody(containing("\"payToken\":\"tok-abc-123\""))
            .willReturn(okJson("{\"payToken\":\"tok-abc-123\",\"status\":\"PENDING\",\"txnid\":\"TXN001\"}")));

        var tx = transactionService.initiate(tenantId, MobilePaymentProvider.ORANGE,
            BigDecimal.valueOf(1000), "XAF", "EXT-001");

        PaymentCommand cmd = new PaymentCommand(
            tx.getTransactionId(), tx.getTraceId(), tenantId,
            "+237692954629", BigDecimal.valueOf(1000), "XAF",
            "EXT-001", "IDEM-001", MobilePaymentProvider.ORANGE,
            null, null, null, null  // last null = description
        );

        ProviderResult result = orangeMoneyPort.initiateMerchantPayment(cmd);

        assertThat(result.pending()).isTrue();
        assertThat(result.providerRef()).isEqualTo("tok-abc-123");
    }

    @Test
    void status_poll_returns_success_for_successfull_response() {
        // Note: "SUCCESSFULL" double-L is correct Orange spelling
        orangeServer.stubFor(get(urlPathMatching("/mp/paymentstatus/.*"))
            .withHeader("Authorization", equalTo("Bearer test-bearer-token"))
            .willReturn(okJson("{\"status\":\"SUCCESSFULL\",\"payToken\":\"tok-abc-123\"}")));

        ProviderResult result = orangeMoneyPort.getTransactionStatus("tok-abc-123");

        assertThat(result.pending()).isFalse();
        assertThat(result.rawStatus()).isEqualTo("SUCCESSFULL");
    }

    @Test
    void payToken_expired_throws_PayTokenExpiredException() {
        // Set payTokenIssuedAt to 70 minutes ago — exceeds the 60-minute threshold (P1.3)
        Instant staleIssuedAt = Instant.now().minus(70, ChronoUnit.MINUTES);

        assertThatThrownBy(() ->
            orangeMoneyPort.assertPayTokenFresh("txn-stale-001", staleIssuedAt))
            .isInstanceOf(PayTokenExpiredException.class)
            .hasMessageContaining("txn-stale-001");
    }

    @Test
    void initiateCashout_throws_UnsupportedOperationException() {
        // ROADMAP SC-3 deviation: cashout is stubbed pending sandbox field verification.
        // This test documents the deviation explicitly — remove when cashout is implemented.
        var tx = transactionService.initiate(tenantId, MobilePaymentProvider.ORANGE,
            BigDecimal.valueOf(500), "XAF", "CASHOUT-001");

        PaymentCommand cmd = new PaymentCommand(
            tx.getTransactionId(), tx.getTraceId(), tenantId,
            "+237692954629", BigDecimal.valueOf(500), "XAF",
            "CASHOUT-001", "IDEM-003", MobilePaymentProvider.ORANGE,
            null, null, null, null  // last null = description
        );

        assertThatThrownBy(() -> orangeMoneyPort.initiateCashout(cmd))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("sandbox verification");
    }

    @Test
    void initiateC2C_throws_UnsupportedOperationException() {
        // ROADMAP SC-3 deviation: C2C is stubbed pending sandbox field verification.
        var tx = transactionService.initiate(tenantId, MobilePaymentProvider.ORANGE,
            BigDecimal.valueOf(200), "XAF", "C2C-001");

        PaymentCommand cmd = new PaymentCommand(
            tx.getTransactionId(), tx.getTraceId(), tenantId,
            "+237692954629", BigDecimal.valueOf(200), "XAF",
            "C2C-001", "IDEM-004", MobilePaymentProvider.ORANGE,
            null, null, null, null  // last null = description
        );

        assertThatThrownBy(() -> orangeMoneyPort.initiateC2C(cmd, "+237699000001"))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("sandbox verification");
    }
}
