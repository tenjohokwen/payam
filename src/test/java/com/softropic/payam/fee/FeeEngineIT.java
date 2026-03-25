package com.softropic.payam.fee;

import com.softropic.payam.config.TestConfig;
import com.softropic.payam.fee.service.FeeEvaluationService;
import com.softropic.payam.fee.service.FeeRuleCache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the fee engine — fee CRUD via admin API, fee evaluation,
 * tenant-specific override, and zero-fee fallback.
 *
 * <p>Verifies four OPS-01 requirements:
 * <ol>
 *   <li>POST /v1/admin/fees creates a fee rule; GET /v1/admin/fees returns it</li>
 *   <li>Global fee rule is evaluated correctly via FeeEvaluationService</li>
 *   <li>Tenant-specific fee rule overrides the global rule</li>
 *   <li>No enabled fee rule → evaluateFee returns BigDecimal.ZERO</li>
 * </ol>
 *
 * <p>Authentication: uses real /authenticate login (same pattern as ReconciliationApiIT).
 * FeeEvaluationService is tested directly via @Autowired injection — avoids complexity of
 * full payment initiation flow in this scope.
 *
 * <p>Test isolation: @AfterEach deletes fee_rule rows added by tests (preserves seed row id=1).
 * Uses SimpleClientHttpRequestFactory (noRetryRestTemplate pattern) to avoid httpclient5
 * auto-retry masking.
 */
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "mtn.callback-ip-whitelist="
})
class FeeEngineIT {

    private static final String ADMIN_LOGIN    = "queb@yahoo.com";
    private static final String ADMIN_PASSWORD = "admin*123!";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    FeeEvaluationService feeEvaluationService;

    @Autowired
    FeeRuleCache feeRuleCache;

    /** noRetryRestTemplate — avoids httpclient5 auto-retry masking 4xx/5xx. */
    private RestTemplate restTemplate;

    /** JWT cookies obtained from POST /authenticate. */
    private HttpHeaders adminHeaders;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplateBuilder()
                .requestFactory(SimpleClientHttpRequestFactory.class)
                .build();

        // ----------------------------------------------------------------
        // Seed security prerequisites (sec + authority + user rows)
        // ----------------------------------------------------------------
        transactionTemplate.execute(status -> {
            jdbc.execute(
                "INSERT INTO main.sec (id, created_by, created_date, last_modified_by, last_modified_date, " +
                "request_id, session_id, status, bus_id, value, version) " +
                "VALUES ('659287191260154475','SYSTEM_ACCOUNT','2024-12-24 06:51:55.357352'," +
                "'SYSTEM_ACCOUNT','2024-12-24 06:51:55.357352'," +
                "'bed78f34-3e09-4fa8-81db-32326a528cca', null, 'ACTIVE', 'jot'," +
                "'loiI8oT2C1tWecrNXPDjN8fveYEU8rD6nb1k1NbVy92rwdd4/KO+aHhXh3A5zjsT5eSFL/xI+9Rqyj4RI6QCiFywn5nZLIwHGPNEY0F9lnDnGGmVjv/9rO5fgGt83+cxNDyGoCePaVEpBd7xHxyDdfpAoLxQs8mhKGqcEsh09Q+26qEiEm/a9bgDSbSQ0sX00VHBLd35OLmvN+ydjEluYxBTa6KzGb2CQ6Ttg4ZaELmbZOWpEjQ1Z7BbbYiXmWyaY+2HnkyhONoGbUpvVKl1c4e9IlQzeUYkekbUbADIm2LNK9Nhfv5/L5esvFrdVOUcUpLk/y8UT9f5xOMLFJ4Ct6s0eTKvNqYkSz2DFRI8Ip4p/ns6gA4V/1MUf9GeqPUWLiOa28Vw15+R8ycUMqb8NZHOP1oj9RunhSwA7EY84bZL3+yePc3n1b8ne8xzaYVEdK1WBu3J6s2AoBaOL/JLWfu8MuxXI+ub', 'v1') " +
                "ON CONFLICT DO NOTHING");

            jdbc.execute(
                "INSERT INTO main.authority (id, name, status, created_by, created_date, last_modified_by, last_modified_date, request_id) " +
                "VALUES (6747751741842104908, 'ROLE_ADMIN', 'ACTIVE', 'system', '2016-04-26 20:41:25', 'system', '2016-04-26 20:41:25', '') " +
                "ON CONFLICT DO NOTHING");
            jdbc.execute(
                "INSERT INTO main.authority (id, name, status, created_by, created_date, last_modified_by, last_modified_date, request_id) " +
                "VALUES (5418719445932238328, 'ROLE_USER', 'ACTIVE', 'system', '2016-04-26 20:41:25', 'system', '2016-04-26 20:41:25', '') " +
                "ON CONFLICT DO NOTHING");

            jdbc.execute(
                "INSERT INTO main.\"user\" " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
                " status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, phone_type, " +
                " title, activated, activation_date, activation_key, locked, login, login_id_type, " +
                " password_hash, reset_expiration, reset_key, otp_enabled) " +
                "VALUES " +
                "(675373350208068096, 'anonymousUser', '2025-02-06 16:12:34.516705', 'anonymousUser', '2025-02-06 16:12:35.198266', " +
                " 'd503b412-b576-48c2-8ead-ec9e10d42880', NULL, 'ACTIVE', '1990-02-20', 'queb@yahoo.com', " +
                " 'VAYM', 'MALE', 'en', 'FXFUOUQBUO', 'DE', '01724527687', 'MOBILE', NULL, " +
                " true, NULL, NULL, false, 'queb@yahoo.com', 'EMAIL', " +
                " '$2a$10$Sdo/qTAcMcYaIAV6XXw3dejlsDwL93g6zb.uPUwFohPpC8q3bEg5i', NULL, NULL, false) " +
                "ON CONFLICT DO NOTHING");
            jdbc.execute(
                "INSERT INTO main.user_authority (user_id, authority_id) VALUES (675373350208068096, 5418719445932238328) " +
                "ON CONFLICT DO NOTHING");
            jdbc.execute(
                "INSERT INTO main.user_authority (user_id, authority_id) VALUES (675373350208068096, 6747751741842104908) " +
                "ON CONFLICT DO NOTHING");
            return null;
        });

        adminHeaders = loginAsAdmin();

        // Seed the global default rule (id=1) — dev profile wipes Flyway seed data;
        // no version column — Hibernate create-drop creates table from entity mapping (no @Version field)
        transactionTemplate.execute(status -> {
            jdbc.update(
                "INSERT INTO main.fee_rule (id, fee_type, fixed_amount, currency, rule_name, enabled, status) " +
                "VALUES (1, 'FEE_FIXED', 0.00, 'XAF', 'Default (no fee)', true, 'ACTIVE') " +
                "ON CONFLICT DO NOTHING");
            return null;
        });
        feeRuleCache.refresh();
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            // Preserve seed row id=1 to avoid FK issues on transaction rows from other tests
            jdbc.execute("DELETE FROM main.fee_rule WHERE id > 1");
            jdbc.execute("DELETE FROM main.transaction");
            jdbc.execute("DELETE FROM main.user_authority");
            jdbc.execute("DELETE FROM main.\"user\"");
            jdbc.execute("DELETE FROM main.authority");
            jdbc.execute("DELETE FROM main.sec");
            return null;
        });
        // Also clean the default seed row after test
        transactionTemplate.execute(status -> {
            jdbc.execute("DELETE FROM main.fee_rule");
            return null;
        });
        feeRuleCache.refresh();
    }

    // -------------------------------------------------------------------------
    // Test 1: POST /v1/admin/fees creates rule; GET /v1/admin/fees returns it
    // -------------------------------------------------------------------------
    @Test
    void test_createAndListFeeRule() {
        Map<String, Object> body = Map.of(
            "feeType", "FEE_FIXED",
            "fixedAmount", 100.00,
            "currency", "XAF",
            "ruleName", "Test fixed fee",
            "enabled", true
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.addAll(adminHeaders);

        // Create
        ResponseEntity<Map> createResp = restTemplate.exchange(
            url("/v1/admin/fees"),
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            Map.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResp.getBody()).isNotNull();
        assertThat(createResp.getBody().get("ruleName")).isEqualTo("Test fixed fee");

        // List
        ResponseEntity<List> listResp = restTemplate.exchange(
            url("/v1/admin/fees"),
            HttpMethod.GET,
            new HttpEntity<>(adminHeaders),
            List.class);
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResp.getBody()).isNotNull();
        assertThat(listResp.getBody()).hasSizeGreaterThanOrEqualTo(1);

        boolean found = listResp.getBody().stream()
            .anyMatch(r -> "Test fixed fee".equals(((Map<?, ?>) r).get("ruleName")));
        assertThat(found).as("Created rule must appear in list").isTrue();
    }

    // -------------------------------------------------------------------------
    // Test 2: Global fee rule is applied to payment evaluation
    // -------------------------------------------------------------------------
    @Test
    void test_globalFeeAppliedToPayment() {
        // Remove the default 0.00 seed rule so only the new 50 XAF rule applies globally
        transactionTemplate.execute(status -> {
            jdbc.execute("DELETE FROM main.fee_rule WHERE id = 1");
            return null;
        });

        // Create a global 50 XAF fee rule via admin API
        Map<String, Object> body = Map.of(
            "feeType", "FEE_FIXED",
            "fixedAmount", 50.00,
            "currency", "XAF",
            "ruleName", "Global 50 XAF fee",
            "enabled", true
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.addAll(adminHeaders);

        restTemplate.exchange(url("/v1/admin/fees"), HttpMethod.POST,
            new HttpEntity<>(body, headers), Map.class);

        // Cache is refreshed by the admin resource on POST — verify evaluateFee returns 50
        BigDecimal fee = feeEvaluationService.evaluateFee(9999L, new BigDecimal("1000"));
        assertThat(fee).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    // -------------------------------------------------------------------------
    // Test 3: Tenant-specific fee overrides global
    // -------------------------------------------------------------------------
    @Test
    void test_tenantFeeOverridesGlobal() {
        long specificTenantId = 42L;

        // Global rule: 0.00 (already seeded as id=1)
        // Tenant-specific rule: 200.00 for specificTenantId
        transactionTemplate.execute(status -> {
            jdbc.update(
                "INSERT INTO main.fee_rule (id, fee_type, fixed_amount, currency, rule_name, tenant_id, enabled, status) " +
                "VALUES (2, 'FEE_FIXED', 200.00, 'XAF', 'Tenant 42 fee', ?, true, 'ACTIVE') " +
                "ON CONFLICT DO NOTHING",
                specificTenantId);
            return null;
        });
        feeRuleCache.refresh();

        // Tenant-specific should return 200.00
        BigDecimal tenantFee = feeEvaluationService.evaluateFee(specificTenantId, new BigDecimal("1000"));
        assertThat(tenantFee).isEqualByComparingTo(new BigDecimal("200.00"));

        // Other tenant falls back to global (0.00)
        BigDecimal globalFee = feeEvaluationService.evaluateFee(99999L, new BigDecimal("1000"));
        assertThat(globalFee).isEqualByComparingTo(new BigDecimal("0.00"));
    }

    // -------------------------------------------------------------------------
    // Test 4: No enabled rule → evaluateFee returns 0
    // -------------------------------------------------------------------------
    @Test
    void test_noFeeRuleReturnsZero() {
        // Disable all rules
        transactionTemplate.execute(status -> {
            jdbc.execute("UPDATE main.fee_rule SET enabled = false");
            return null;
        });
        feeRuleCache.refresh();

        BigDecimal fee = feeEvaluationService.evaluateFee(1L, new BigDecimal("500"));
        assertThat(fee).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders loginAsAdmin() {
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        loginHeaders.add(HttpHeaders.COOKIE, "fcookie=fingerprintCookie");

        Map<String, String> credentials = Map.of("id", ADMIN_LOGIN, "password", ADMIN_PASSWORD);
        ResponseEntity<Map> loginResponse = restTemplate.exchange(
            url("/authenticate"),
            HttpMethod.POST,
            new HttpEntity<>(credentials, loginHeaders),
            Map.class);

        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<String> setCookies = loginResponse.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).isNotNull().isNotEmpty();

        String cookieHeader = String.join("; ",
            setCookies.stream()
                .map(c -> c.split(";", 2)[0])
                .toList());

        HttpHeaders adminH = new HttpHeaders();
        adminH.set(HttpHeaders.COOKIE, cookieHeader);
        return adminH;
    }
}
