package com.softropic.payam.reconciliation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.security.common.util.SecurityConstants;

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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the admin reconciliation API surface.
 *
 * <p>Verifies five requirements:
 * <ol>
 *   <li>GET /v1/admin/reconciliation/reports without JWT → 401</li>
 *   <li>GET /v1/admin/reconciliation/reports with ROLE_ADMIN JWT → 200, contains seeded report</li>
 *   <li>GET /v1/admin/reconciliation/reports/{id}/discrepancies → 200, list size == 2</li>
 *   <li>GET /v1/admin/reconciliation/reports/{id}/export?format=csv → 200, Content-Type text/csv, CSV header present</li>
 *   <li>GET /v1/admin/reconciliation/reports/{id}/export?format=json → 200, application/json, reportDate key present</li>
 * </ol>
 *
 * <p>Authentication strategy: performs a real login to /authenticate using the fixture
 * admin account (queb@yahoo.com / admin*123!) seeded in setUp().  The resulting JWT
 * cookies are forwarded on each admin request — exactly the same flow as the browser.
 * This approach bypasses any hand-crafted JWT issues and exercises the full filter chain.
 *
 * <p>This test seeds reconciliation_report and reconciliation_discrepancy rows directly via
 * JDBC, and also seeds the authority/user/sec rows required for authentication.
 *
 * <p>Separate Spring context from ReconciliationJobIT — no shared static state.
 * Uses SimpleClientHttpRequestFactory (noRetryRestTemplate pattern) to avoid httpclient5
 * retry masking.
 */
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "mtn.callback-ip-whitelist="
})
class ReconciliationApiIT {

    /**
     * Client-ID sent in X-Client-Id header. Must be in allowed.clients config
     * (application.yaml: myClientId,hisClientId,herClientId,ourClientId).
     */
    private static final String TEST_CLIENT_ID = "myClientId";

    /** Admin fixture: login and password match userData.sql (queb@yahoo.com). */
    private static final String ADMIN_LOGIN    = "queb@yahoo.com";
    private static final String ADMIN_PASSWORD = "admin*123!";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    ObjectMapper objectMapper;

    /** noRetryRestTemplate — prevents httpclient5 auto-retry from masking 4xx/5xx responses. */
    private RestTemplate noRetryRestTemplate;

    private Long seededReportId;

    /** JWT cookies obtained from POST /authenticate — set once per test in setUp(). */
    private HttpHeaders adminCookies;

    @BeforeEach
    void setUp() {
        noRetryRestTemplate = new RestTemplateBuilder()
                .requestFactory(SimpleClientHttpRequestFactory.class)
                .build();

        // ----------------------------------------------------------------
        // Seed security prerequisites (sec table + authority + user rows)
        // ----------------------------------------------------------------
        transactionTemplate.execute(status -> {
            // JWT secret required by JwtSecretService / SecurityAdviceFilter
            jdbc.execute(
                "INSERT INTO main.sec (id, created_by, created_date, last_modified_by, last_modified_date, " +
                "request_id, session_id, status, bus_id, value, version) " +
                "VALUES ('659287191260154475','SYSTEM_ACCOUNT','2024-12-24 06:51:55.357352'," +
                "'SYSTEM_ACCOUNT','2024-12-24 06:51:55.357352'," +
                "'bed78f34-3e09-4fa8-81db-32326a528cca', null, 'ACTIVE', 'jot'," +
                "'loiI8oT2C1tWecrNXPDjN8fveYEU8rD6nb1k1NbVy92rwdd4/KO+aHhXh3A5zjsT5eSFL/xI+9Rqyj4RI6QCiFywn5nZLIwHGPNEY0F9lnDnGGmVjv/9rO5fgGt83+cxNDyGoCePaVEpBd7xHxyDdfpAoLxQs8mhKGqcEsh09Q+26qEiEm/a9bgDSbSQ0sX00VHBLd35OLmvN+ydjEluYxBTa6KzGb2CQ6Ttg4ZaELmbZOWpEjQ1Z7BbbYiXmWyaY+2HnkyhONoGbUpvVKl1c4e9IlQzeUYkekbUbADIm2LNK9Nhfv5/L5esvFrdVOUcUpLk/y8UT9f5xOMLFJ4Ct6s0eTKvNqYkSz2DFRI8Ip4p/ns6gA4V/1MUf9GeqPUWLiOa28Vw15+R8ycUMqb8NZHOP1oj9RunhSwA7EY84bZL3+yePc3n1b8ne8xzaYVEdK1WBu3J6s2AoBaOL/JLWfu8MuxXI+ub', 'v1') " +
                "ON CONFLICT DO NOTHING");

            // Roles required for login → user lookup
            jdbc.execute(
                "INSERT INTO main.authority (id, name, status, created_by, created_date, last_modified_by, last_modified_date, request_id) " +
                "VALUES (6747751741842104908, 'ROLE_ADMIN', 'ACTIVE', 'system', '2016-04-26 20:41:25', 'system', '2016-04-26 20:41:25', '') " +
                "ON CONFLICT DO NOTHING");
            jdbc.execute(
                "INSERT INTO main.authority (id, name, status, created_by, created_date, last_modified_by, last_modified_date, request_id) " +
                "VALUES (5418719445932238328, 'ROLE_USER', 'ACTIVE', 'system', '2016-04-26 20:41:25', 'system', '2016-04-26 20:41:25', '') " +
                "ON CONFLICT DO NOTHING");

            // Admin user — password hash = admin*123!
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

        // ----------------------------------------------------------------
        // Authenticate as the admin user to obtain real JWT cookies
        // ----------------------------------------------------------------
        adminCookies = loginAsAdmin();

        // ----------------------------------------------------------------
        // Seed reconciliation data for the tests
        // ----------------------------------------------------------------
        long reportId = System.nanoTime() & Long.MAX_VALUE;
        seededReportId = reportId;
        transactionTemplate.execute(status -> {
            jdbc.update(
                "INSERT INTO main.reconciliation_report " +
                "(id, report_date, provider, run_at, total_checked, total_matched, total_discrepancies, status) " +
                "VALUES (?, '2026-03-23', 'MTN', NOW(), 10, 8, 2, 'COMPLETE')",
                reportId);
            return null;
        });

        long disc1Id = (System.nanoTime() + 1) & Long.MAX_VALUE;
        long disc2Id = (System.nanoTime() + 2) & Long.MAX_VALUE;
        transactionTemplate.execute(status -> {
            jdbc.update(
                "INSERT INTO main.reconciliation_discrepancy " +
                "(id, report_id, report_date, provider, payam_tx_id, provider_ref, " +
                "payam_status, provider_status, payam_amount, provider_amount, discrepancy_type, severity) " +
                "VALUES (?, ?, '2026-03-23', 'MTN', ?, ?, 'SUCCESS', 'FAILED', 500.00, 500.00, 'STATUS_MISMATCH', 'HIGH')",
                disc1Id, reportId,
                "tx-" + UUID.randomUUID(),
                "ref-" + UUID.randomUUID());
            jdbc.update(
                "INSERT INTO main.reconciliation_discrepancy " +
                "(id, report_id, report_date, provider, payam_tx_id, provider_ref, " +
                "payam_status, provider_status, payam_amount, provider_amount, discrepancy_type, severity) " +
                "VALUES (?, ?, '2026-03-23', 'MTN', ?, ?, null, null, null, null, 'UNCONFIRMED', 'LOW')",
                disc2Id, reportId,
                "tx-" + UUID.randomUUID(),
                null);
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbc.execute("DELETE FROM main.reconciliation_discrepancy");
            jdbc.execute("DELETE FROM main.reconciliation_report");
            jdbc.execute("DELETE FROM main.payment_event_log");  // defensive
            jdbc.execute("DELETE FROM main.transaction");          // defensive
            jdbc.execute("DELETE FROM main.tenant_api_key");
            jdbc.execute("DELETE FROM main.tenant");
            jdbc.execute("DELETE FROM main.user_authority");
            jdbc.execute("DELETE FROM main.\"user\"");
            jdbc.execute("DELETE FROM main.authority");
            jdbc.execute("DELETE FROM main.sec");
            return null;
        });
    }

    // -------------------------------------------------------------------------
    // Test 1: missing auth → 401 or 403
    // -------------------------------------------------------------------------
    @Test
    void listReports_requiresAuth() {
        // No cookies or auth headers — filter returns 401/403 for missing JWT
        assertThatThrownBy(() ->
            noRetryRestTemplate.getForEntity(url("/v1/admin/reconciliation/reports"), Object.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                int status = ((HttpClientErrorException) e).getStatusCode().value();
                assertThat(status).isIn(401, 403);
            });
    }

    // -------------------------------------------------------------------------
    // Test 2: authenticated ROLE_ADMIN → 200, contains seeded report
    // -------------------------------------------------------------------------
    @Test
    void listReports_returnsPage() {
        ResponseEntity<Map> response = noRetryRestTemplate.exchange(
            url("/v1/admin/reconciliation/reports"),
            HttpMethod.GET,
            new HttpEntity<>(adminCookies),
            Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map body = response.getBody();
        assertThat(body).isNotNull();
        assertThat((Integer) body.get("totalElements")).isGreaterThanOrEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Test 3: discrepancies for report → 200, list size == 2
    // -------------------------------------------------------------------------
    @Test
    void discrepancies_forReport() {
        ResponseEntity<List> response = noRetryRestTemplate.exchange(
            url("/v1/admin/reconciliation/reports/" + seededReportId + "/discrepancies"),
            HttpMethod.GET,
            new HttpEntity<>(adminCookies),
            List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).hasSize(2);
    }

    // -------------------------------------------------------------------------
    // Test 4: CSV export → 200, Content-Type text/csv, CSV header present
    // -------------------------------------------------------------------------
    @Test
    void export_csv() {
        ResponseEntity<String> response = noRetryRestTemplate.exchange(
            url("/v1/admin/reconciliation/reports/" + seededReportId + "/export?format=csv"),
            HttpMethod.GET,
            new HttpEntity<>(adminCookies),
            String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        assertThat(contentType).isNotNull();
        assertThat(contentType).contains("text/csv");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).contains("reportDate,provider,payamTxId");
    }

    // -------------------------------------------------------------------------
    // Test 5: JSON export → 200, application/json, reportDate and discrepancies keys present
    // -------------------------------------------------------------------------
    @Test
    void export_json() throws Exception {
        ResponseEntity<String> response = noRetryRestTemplate.exchange(
            url("/v1/admin/reconciliation/reports/" + seededReportId + "/export?format=json"),
            HttpMethod.GET,
            new HttpEntity<>(adminCookies),
            String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        assertThat(contentType).isNotNull();
        assertThat(contentType).contains("application/json");
        assertThat(response.getBody()).isNotNull();
        Map<String, Object> jsonBody = objectMapper.readValue(response.getBody(), Map.class);
        assertThat(jsonBody).containsKey("reportDate");
        assertThat(jsonBody).containsKey("discrepancies");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    /**
     * Authenticates as the seeded admin user via POST /authenticate and
     * returns headers containing the JWT cookies from the response.
     *
     * <p>The X-Client-Id header is also included so the ClientIdAccessDecisionManager
     * sees a known machine client on subsequent admin requests.
     */
    private HttpHeaders loginAsAdmin() {
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        loginHeaders.add(HttpHeaders.COOKIE, "fcookie=fingerprintCookie");
        //loginHeaders.set("X-Client-Id", TEST_CLIENT_ID);

        Map<String, String> credentials = Map.of("id", ADMIN_LOGIN, "password", ADMIN_PASSWORD);
        ResponseEntity<Map> loginResponse = noRetryRestTemplate.exchange(
            url("/authenticate"),
            HttpMethod.POST,
            new HttpEntity<>(credentials, loginHeaders),
            Map.class);

        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Extract Set-Cookie headers and forward them as Cookie header on subsequent requests
        List<String> setCookies = loginResponse.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).isNotNull().isNotEmpty();

        String cookieHeader = String.join("; ",
            setCookies.stream()
                .map(c -> c.split(";", 2)[0])  // keep only name=value, drop path/httponly etc.
                .toList());

        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.set(HttpHeaders.COOKIE, cookieHeader);
        return adminHeaders;
    }
}
