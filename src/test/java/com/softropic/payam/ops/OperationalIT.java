package com.softropic.payam.ops;

import com.softropic.payam.config.TestConfig;
import com.softropic.payam.transaction.contract.TransactionEventType;
import com.softropic.payam.transaction.contract.TransactionStatus;
import com.softropic.payam.transaction.service.EventLogService;

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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for operational endpoints:
 * <ol>
 *   <li>GET /v1/admin/providers/status without auth → 401/403</li>
 *   <li>GET /v1/admin/providers/status with ROLE_ADMIN JWT → 200, contains orange+mtn keys</li>
 *   <li>GET /v1/admin/audit/hash-chain/{txId} for intact chain → 200, valid:true</li>
 *   <li>GET /v1/admin/audit/hash-chain/{txId} for tampered chain → 200, valid:false</li>
 *   <li>TLS assertion skipped in dev profile — context loads successfully</li>
 * </ol>
 *
 * <p>Follows the ReconciliationApiIT pattern: real login via POST /authenticate,
 * seeds auth rows in @BeforeEach, tears down in @AfterEach.
 *
 * <p>Uses SimpleClientHttpRequestFactory to avoid httpclient5 auto-retry masking.
 */
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "mtn.callback-ip-whitelist="
})
class OperationalIT {

    private static final String ADMIN_LOGIN    = "queb@yahoo.com";
    private static final String ADMIN_PASSWORD = "admin*123!";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    EventLogService eventLogService;

    /** noRetryRestTemplate — prevents httpclient5 auto-retry from masking 4xx/5xx responses. */
    private RestTemplate restTemplate;

    /** JWT cookies obtained from POST /authenticate */
    private HttpHeaders adminHeaders;

    /** transactionId used for hash-chain tests — tracked for teardown */
    private String chainTestTxId;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplateBuilder()
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

            // Roles
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

        // Authenticate as admin
        adminHeaders = loginAsAdmin();
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            // Clean up hash-chain test data
            if (chainTestTxId != null) {
                jdbc.update("DELETE FROM main.payment_event_log WHERE transaction_id = ?", chainTestTxId);
            }
            // Clean up auth rows seeded in setUp
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
    void test_providerStatusRequiresAuth() {
        assertThatThrownBy(() ->
            restTemplate.getForEntity(url("/v1/admin/providers/status"), Object.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> {
                int statusCode = ((HttpClientErrorException) e).getStatusCode().value();
                assertThat(statusCode).isIn(401, 403);
            });
    }

    // -------------------------------------------------------------------------
    // Test 2: authenticated ROLE_ADMIN → 200, contains orange+mtn keys
    // -------------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    @Test
    void test_providerStatusReturnsCircuitBreakerState() {
        ResponseEntity<Map> response = restTemplate.exchange(
            url("/v1/admin/providers/status"),
            HttpMethod.GET,
            new HttpEntity<>(adminHeaders),
            Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsKey("orange");
        assertThat(body).containsKey("mtn");

        // Each entry should have a "state" field with a non-null string
        Map<String, Object> orangeEntry = (Map<String, Object>) body.get("orange");
        assertThat(orangeEntry).isNotNull();
        assertThat(orangeEntry.get("state")).isNotNull().isInstanceOf(String.class);
        assertThat((String) orangeEntry.get("state")).isNotBlank();
    }

    // -------------------------------------------------------------------------
    // Test 3: hash chain verify — intact chain → valid:true
    // -------------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    @Test
    void test_hashChainVerifyValidChain() {
        // Seed a valid chain entry using EventLogService.append() — correct hashing guaranteed
        chainTestTxId = "ops-it-" + UUID.randomUUID();
        eventLogService.append(
                chainTestTxId,
                "trace-ops-it",
                "233501234567",
                TransactionEventType.PAYMENT_INITIATED,
                null,  // statusFrom: null for genesis event
                TransactionStatus.INITIATED,
                "ops-it-test",
                null);

        ResponseEntity<Map> response = restTemplate.exchange(
            url("/v1/admin/audit/hash-chain/" + chainTestTxId),
            HttpMethod.GET,
            new HttpEntity<>(adminHeaders),
            Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("transactionId")).isEqualTo(chainTestTxId);
        assertThat(body.get("valid")).isEqualTo(true);
    }

    // -------------------------------------------------------------------------
    // Test 4: hash chain verify — tampered chain → valid:false
    // -------------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    @Test
    void test_hashChainDetectsTamperedEntry() {
        // Seed a valid chain entry
        chainTestTxId = "ops-it-tamper-" + UUID.randomUUID();
        eventLogService.append(
                chainTestTxId,
                "trace-ops-it-tamper",
                "233501234567",
                TransactionEventType.PAYMENT_INITIATED,
                null,
                TransactionStatus.INITIATED,
                "ops-it-test",
                null);

        // Tamper with the event_hash to simulate record manipulation
        transactionTemplate.execute(status -> {
            jdbc.update(
                "UPDATE main.payment_event_log SET event_hash = 'tampered_hash_value_xyz' " +
                "WHERE transaction_id = ?",
                chainTestTxId);
            return null;
        });

        ResponseEntity<Map> response = restTemplate.exchange(
            url("/v1/admin/audit/hash-chain/" + chainTestTxId),
            HttpMethod.GET,
            new HttpEntity<>(adminHeaders),
            Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("transactionId")).isEqualTo(chainTestTxId);
        assertThat(body.get("valid")).isEqualTo(false);
    }

    // -------------------------------------------------------------------------
    // Test 5: TLS assertion skipped in dev profile
    // -------------------------------------------------------------------------
    @Test
    void test_tlsAssertionSkippedInDevProfile() {
        // If we reach this line, the Spring context started successfully without
        // TlsStartupAssertion throwing — the dev profile guard is working correctly.
        // (application.yaml has checkCertificate:false via *DEFAULT_TCP anchor)
        assertTrue(true, "Spring context started — TLS assertion correctly skipped in dev profile");
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
     */
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

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, cookieHeader);
        return headers;
    }
}
