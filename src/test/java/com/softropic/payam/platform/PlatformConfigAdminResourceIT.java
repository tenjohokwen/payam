package com.softropic.payam.platform;

import com.softropic.payam.common.AdminLogin;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.platform.contract.PinDto;
import com.softropic.payam.platform.contract.PlatformConfigDto;
import com.softropic.payam.security.service.LoginAttemptsService;

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

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Integration test for PlatformConfigAdminResource — covers PIN-03 / PIN-04 / PIN-05
 * end-to-end against the full Spring Boot + Postgres stack.
 *
 * <p>Mirrors the setup pattern from {@code TenantAdminResourceIT} (Phase 31): seeds the
 * {@code main.sec}, {@code main.authority}, and admin {@code main.user} rows, then
 * authenticates as the seeded admin via POST /authenticate to obtain JWT cookies for the
 * subsequent PUT/GET calls.
 *
 * <p>Pre-seeded data: Flyway V17 inserts ORANGE and MTN rows into {@code main.platform_config}
 * with empty MSISDN and null PIN. The tests use ORANGE.
 */
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true",
                              "payam.platform.pin-encryption-secret=test-pin-secret-for-tests"})
@Import(TestConfig.class)
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
class PlatformConfigAdminResourceIT {

    private static final String PROVIDER = "ORANGE";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private LoginAttemptsService loginAttemptsService;

    @LocalServerPort
    int port;

    @Autowired
    private com.softropic.payam.email.service.MailManager mailManager;

    private RestTemplate restTemplate;
    private HttpHeaders adminCookies;

    private com.softropic.payam.utils.TestMailManager testMailManager() {
        return (com.softropic.payam.utils.TestMailManager) mailManager;
    }

    @BeforeEach
    void setUp() {
        testMailManager().clear();
        cleanDb();
        loginAttemptsService.resetLoginRecording();

        restTemplate = new RestTemplateBuilder()
                .requestFactory(SimpleClientHttpRequestFactory.class)
                .rootUri("http://localhost:" + port)
                .build();

        seedAdminAndSecrets();

        // Authenticate once per test, reuse cookies for all subsequent calls
        adminCookies = AdminLogin.loginAsAdmin(
                "http://localhost:" + port + "/authenticate", restTemplate);
        adminCookies.setContentType(MediaType.APPLICATION_JSON);
        // Required by SecurityAdviceFilter — same value as TenantAdminResourceIT
        adminCookies.add("user-agent", AdminLogin.TEST_USER_AGENT);
    }

    @AfterEach
    void tearDown() {
        cleanDb();
    }

    private void cleanDb() {
        transactionTemplate.execute(status -> {
            // Reset platform_config rows back to the V17 seed shape (ORANGE + MTN with empty MSISDN, null pin)
            jdbcTemplate.execute("UPDATE main.platform_config SET platform_msisdn = '', pin = NULL");
            jdbcTemplate.execute("DELETE FROM main.user_authority");
            jdbcTemplate.execute("DELETE FROM main.\"user\"");
            jdbcTemplate.execute("DELETE FROM main.authority");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return 0;
        });
    }

    private void seedAdminAndSecrets() {
        // Seeds the JWT signing-key sec row, ROLE_ADMIN + ROLE_USER authorities, admin user
        // row (queb@yahoo.com / admin*123!), and user_authority join rows. All are required
        // for AdminLogin.loginAsAdmin to succeed against the real Spring Security filter
        // chain. The literal IDs, hashes, and the bcrypt password digest in this method are
        // paired with the JWT secret bytes in main.sec; they MUST be preserved byte-for-byte.
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute(
                "INSERT INTO main.sec (id, created_by, created_date, last_modified_by, last_modified_date, " +
                "request_id, session_id, status, bus_id, value, version) " +
                "VALUES ('659287191260154475','SYSTEM_ACCOUNT','2024-12-24 06:51:55.357352'," +
                "'SYSTEM_ACCOUNT','2024-12-24 06:51:55.357352'," +
                "'bed78f34-3e09-4fa8-81db-32326a528cca', null, 'ACTIVE', 'jot'," +
                "'loiI8oT2C1tWecrNXPDjN8fveYEU8rD6nb1k1NbVy92rwdd4/KO+aHhXh3A5zjsT5eSFL/xI+9Rqyj4RI6QCiFywn5nZLIwHGPNEY0F9lnDnGGmVjv/9rO5fgGt83+cxNDyGoCePaVEpBd7xHxyDdfpAoLxQs8mhKGqcEsh09Q+26qEiEm/a9bgDSbSQ0sX00VHBLd35OLmvN+ydjEluYxBTa6KzGb2CQ6Ttg4ZaELmbZOWpEjQ1Z7BbbYiXmWyaY+2HnkyhONoGbUpvVKl1c4e9IlQzeUYkekbUbADIm2LNK9Nhfv5/L5esvFrdVOUcUpLk/y8UT9f5xOMLFJ4Ct6s0eTKvNqYkSz2DFRI8Ip4p/ns6gA4V/1MUf9GeqPUWLiOa28Vw15+R8ycUMqb8NZHOP1oj9RunhSwA7EY84bZL3+yePc3n1b8ne8xzaYVEdK1WBu3J6s2AoBaOL/JLWfu8MuxXI+ub', 'v1') " +
                "ON CONFLICT DO NOTHING");
            jdbcTemplate.execute(
                "INSERT INTO main.authority (id, name, status, created_by, created_date, last_modified_by, last_modified_date, request_id) " +
                "VALUES (6747751741842104908, 'ROLE_ADMIN', 'ACTIVE', 'system', '2016-04-26 20:41:25', 'system', '2016-04-26 20:41:25', '') " +
                "ON CONFLICT DO NOTHING");
            jdbcTemplate.execute(
                "INSERT INTO main.authority (id, name, status, created_by, created_date, last_modified_by, last_modified_date, request_id) " +
                "VALUES (5418719445932238328, 'ROLE_USER', 'ACTIVE', 'system', '2016-04-26 20:41:25', 'system', '2016-04-26 20:41:25', '') " +
                "ON CONFLICT DO NOTHING");
            // Admin user — password hash corresponds to plaintext admin*123!
            jdbcTemplate.execute(
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
            jdbcTemplate.execute(
                "INSERT INTO main.user_authority (user_id, authority_id) VALUES (675373350208068096, 5418719445932238328) " +
                "ON CONFLICT DO NOTHING");
            jdbcTemplate.execute(
                "INSERT INTO main.user_authority (user_id, authority_id) VALUES (675373350208068096, 6747751741842104908) " +
                "ON CONFLICT DO NOTHING");
            return 0;
        });
    }

    // ---------------------------------------------------------------------
    // PIN-04: GET /{provider} returns pinConfigured boolean (no pin value)
    // ---------------------------------------------------------------------

    @Test
    void getProviderConfig_shouldReturnPinConfiguredFalseWhenNoPinSet() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/v1/admin/platform-config/" + PROVIDER,
                HttpMethod.GET,
                new HttpEntity<>(adminCookies),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("provider", PROVIDER);
        assertThat(response.getBody()).containsEntry("pinConfigured", false);
        // PIN-04: pin field MUST be absent from JSON (NON_NULL serialization)
        assertThat(response.getBody()).doesNotContainKey("pin");
    }

    @Test
    void getProviderConfig_shouldReturnPinConfiguredTrueAfterPinSet() {
        // Given — set a valid PIN
        putConfig(PROVIDER, "654321", "1234");

        // When
        ResponseEntity<Map> response = restTemplate.exchange(
                "/v1/admin/platform-config/" + PROVIDER,
                HttpMethod.GET,
                new HttpEntity<>(adminCookies),
                Map.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("pinConfigured", true);
        assertThat(response.getBody()).doesNotContainKey("pin");
    }

    // ---------------------------------------------------------------------
    // PIN-03: PUT validation — 400 on invalid PIN, 200 on valid, empty preserves
    // ---------------------------------------------------------------------

    @Test
    void putConfig_shouldReturn200AndAcceptValidPin() {
        ResponseEntity<Map> response = putConfig(PROVIDER, "654321", "abc1");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("pinConfigured", true);
        assertThat(response.getBody()).doesNotContainKey("pin");
    }

    @Test
    void putConfig_shouldReturn400OnInvalidPinFormat() {
        // Non-alphanumeric — must be rejected by @Pattern regex
        assertThatThrownBy(() -> putConfig(PROVIDER, "654321", "!!!!"))
            .isInstanceOf(HttpClientErrorException.class)
            .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST);

        // Too short (3 chars)
        assertThatThrownBy(() -> putConfig(PROVIDER, "654321", "abc"))
            .isInstanceOf(HttpClientErrorException.class)
            .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST);

        // Too long (9 chars)
        assertThatThrownBy(() -> putConfig(PROVIDER, "654321", "abcdefghi"))
            .isInstanceOf(HttpClientErrorException.class)
            .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST);
    }

    @Test
    void putConfig_shouldPreserveExistingPinWhenPinFieldIsEmpty() {
        // Given — set a valid PIN
        putConfig(PROVIDER, "654321", "1234");

        // When — PUT with empty pin (frontend sends "" to mean "do not change")
        ResponseEntity<Map> response = putConfig(PROVIDER, "999999", "");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Then — original PIN still revealable (not overwritten)
        ResponseEntity<PinDto> pinResponse = restTemplate.exchange(
                "/v1/admin/platform-config/" + PROVIDER + "/pin",
                HttpMethod.GET,
                new HttpEntity<>(adminCookies),
                PinDto.class);
        assertThat(pinResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pinResponse.getBody().pin()).isEqualTo("1234");
    }

    // ---------------------------------------------------------------------
    // PIN-05: GET /{provider}/pin reveal endpoint — 200 on success, 404 when no PIN
    // ---------------------------------------------------------------------

    @Test
    void getPin_shouldReturn200AndDecryptedPlaintextWhenPinConfigured() {
        // Given
        putConfig(PROVIDER, "654321", "abcd");

        // When
        ResponseEntity<PinDto> response = restTemplate.exchange(
                "/v1/admin/platform-config/" + PROVIDER + "/pin",
                HttpMethod.GET,
                new HttpEntity<>(adminCookies),
                PinDto.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().pin()).isEqualTo("abcd");
    }

    @Test
    void getPin_shouldReturn404WhenNoPinConfigured() {
        // Given — no PUT executed, ORANGE row exists from V17 seed but pin is null
        // When / Then
        assertThatThrownBy(() -> restTemplate.exchange(
                "/v1/admin/platform-config/" + PROVIDER + "/pin",
                HttpMethod.GET,
                new HttpEntity<>(adminCookies),
                PinDto.class))
            .isInstanceOf(HttpClientErrorException.class)
            .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------------
    // PIN-10 / PIN-11: email dispatch + data map shape + suppression rules
    // ---------------------------------------------------------------------

    @Test
    void putConfig_shouldDispatchEmailWithMsisdnChangedTrueOnMsisdnUpdate() {
        // Given — set an initial MSISDN to have a "before" state
        putConfig(PROVIDER, "111111", null);
        // Wait for the first envelope, then clear so we can detect only the second one
        waitForEnvelopeCount(1);
        testMailManager().clear();

        // When — MSISDN changes, PIN not supplied
        putConfig(PROVIDER, "222222", null);

        // Then
        await().atMost(Duration.ofSeconds(5)).until(() -> envelopeCount() > 0);
        Map<String, Object> data = latestEnvelopeData();
        assertThat(data).containsEntry("provider", PROVIDER);
        assertThat(data).containsEntry("oldMsisdn", "111111");
        assertThat(data).containsEntry("newMsisdn", "222222");
        assertThat(data).containsEntry("msisdnChanged", true);
        assertThat(data).containsEntry("pinChanged", false);
        assertThat(data).containsKey("changedBy");
        assertThat(data.get("changedBy").toString()).isNotBlank();
        assertThat(data).containsKey("changedAt");
        // PIN-11: no PIN value key in the data map
        assertThat(data.keySet().stream()
                .filter(k -> k.toLowerCase().contains("pin") && !"pinChanged".equals(k))
                .toList()).isEmpty();
    }

    @Test
    void putConfig_shouldNotDispatchEmailWhenMsisdnUnchangedAndPinBlank() {
        // Given — set MSISDN once
        putConfig(PROVIDER, "111111", null);
        waitForEnvelopeCount(1);
        int before = envelopeCount();

        // When — PUT with same MSISDN and empty pin (no-op)
        putConfig(PROVIDER, "111111", "");

        // Then — no new envelope dispatched within the await timeout window
        try {
            await().atMost(Duration.ofSeconds(3))
                   .until(() -> envelopeCount() > before);
            // If we reach here an envelope WAS dispatched — fail the test
            org.junit.jupiter.api.Assertions.fail(
                "Expected no envelope on no-op update, but envelopeCount rose above " + before);
        } catch (org.awaitility.core.ConditionTimeoutException expected) {
            // Expected — no envelope within 3 seconds confirms suppression
        }
        assertThat(envelopeCount()).isEqualTo(before);
    }

    @Test
    void putConfig_shouldNotDispatchEmailOnFirstTimePinCreation() {
        // Given — set MSISDN once without pin
        putConfig(PROVIDER, "111111", null);
        waitForEnvelopeCount(1);
        int before = envelopeCount();

        // When — PUT with SAME MSISDN (unchanged) but a new PIN (first-time PIN creation)
        putConfig(PROVIDER, "111111", "1234");

        // Then — no envelope dispatched (PIN-10: first-time PIN creation does not fire)
        try {
            await().atMost(Duration.ofSeconds(3))
                   .until(() -> envelopeCount() > before);
            org.junit.jupiter.api.Assertions.fail(
                "Expected no envelope on first-time PIN creation, but envelopeCount rose");
        } catch (org.awaitility.core.ConditionTimeoutException expected) {
            // Expected
        }
        assertThat(envelopeCount()).isEqualTo(before);

        // But PIN was still persisted — verify via reveal endpoint
        ResponseEntity<PinDto> pinResponse = restTemplate.exchange(
                "/v1/admin/platform-config/" + PROVIDER + "/pin",
                HttpMethod.GET, new HttpEntity<>(adminCookies), PinDto.class);
        assertThat(pinResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pinResponse.getBody().pin()).isEqualTo("1234");
    }

    // --- helpers ---

    private int envelopeCount() {
        return testMailManager().getEnvelopes().size();
    }

    private void waitForEnvelopeCount(int expected) {
        await().atMost(Duration.ofSeconds(5)).until(() -> envelopeCount() >= expected);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> latestEnvelopeData() {
        java.util.Collection<com.softropic.payam.email.contract.Envelope> all =
            testMailManager().getEnvelopes().values();
        return all.stream()
                .reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("No envelope captured"))
                .data();
    }

    // ---------------------------------------------------------------------
    // Helper: PUT /{provider} body — used by multiple tests
    // ---------------------------------------------------------------------

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ResponseEntity<Map> putConfig(String provider, String msisdn, String pin) {
        // Build body with @JsonInclude semantics — null pin would be omitted by Jackson on
        // serialization (matches frontend behaviour). For tests that need to send pin="",
        // we include it explicitly.
        PlatformConfigDto body = new PlatformConfigDto(provider, msisdn, false, pin);
        return restTemplate.exchange(
                "/v1/admin/platform-config/" + provider,
                HttpMethod.PUT,
                new HttpEntity<>(body, adminCookies),
                Map.class);
    }
}
