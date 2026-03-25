package com.softropic.payam.alert;

import com.softropic.payam.alert.contract.AlertFiredEvent;
import com.softropic.payam.alert.service.AlertEvaluationService;
import com.softropic.payam.alert.service.AlertRuleCache;
import com.softropic.payam.config.TestConfig;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the alert rule admin API and threshold evaluation engine.
 *
 * <p>Verifies four requirements:
 * <ol>
 *   <li>POST /v1/admin/alerts creates an alert rule; GET /v1/admin/alerts returns it</li>
 *   <li>AlertEvaluationService fires AlertFiredEvent when failure rate exceeds threshold</li>
 *   <li>Alert does NOT fire when failure rate is below threshold</li>
 *   <li>Guard: no alert fires when total call count &lt; 10 (MINIMUM_SAMPLE_SIZE, Pitfall 8)</li>
 * </ol>
 *
 * <p>Uses the same real-login authentication pattern as ReconciliationApiIT.
 * Uses a @Component test listener (AlertFiredEventCaptor) to capture fired events — avoids
 * @SpyBean on ApplicationEventPublisher (an interface; unspyable in Spring Boot 3.5+).
 *
 * <p>Counter-based tests manipulate Micrometer counters directly to simulate accumulated
 * payment outcomes without running actual payments.
 */
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import({TestConfig.class, AlertRuleIT.CaptorConfig.class})
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "mtn.callback-ip-whitelist="
})
class AlertRuleIT {

    private static final String ADMIN_LOGIN    = "queb@yahoo.com";
    private static final String ADMIN_PASSWORD = "admin*123!";

    /**
     * In-process event capture — collects every AlertFiredEvent published in this Spring context.
     * Thread-safe (CopyOnWriteArrayList). Tests call clear() in @BeforeEach to reset state.
     */
    static class AlertFiredEventCaptor {
        private final CopyOnWriteArrayList<AlertFiredEvent> captured = new CopyOnWriteArrayList<>();

        @EventListener
        public void onEvent(AlertFiredEvent event) {
            captured.add(event);
        }

        public List<AlertFiredEvent> getAll() {
            return Collections.unmodifiableList(captured);
        }

        public void clear() {
            captured.clear();
        }
    }

    /**
     * TestConfiguration that registers the event captor as a Spring bean
     * so it participates in the application event mechanism.
     */
    @TestConfiguration
    static class CaptorConfig {
        @Bean
        AlertFiredEventCaptor alertFiredEventCaptor() {
            return new AlertFiredEventCaptor();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    AlertEvaluationService alertEvaluationService;

    @Autowired
    AlertRuleCache alertRuleCache;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    AlertFiredEventCaptor eventCaptor;

    private RestTemplate noRetryRestTemplate;
    private HttpHeaders adminHeaders;

    @BeforeEach
    void setUp() {
        noRetryRestTemplate = new RestTemplateBuilder()
                .requestFactory(SimpleClientHttpRequestFactory.class)
                .build();

        // Reset captured events before each test
        eventCaptor.clear();

        transactionTemplate.execute(status -> {
            // JWT secret required by SecurityAdviceFilter
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
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            // Remove test-created alert rules; preserve seeded rows (id <= 2)
            jdbc.execute("DELETE FROM main.alert_rule WHERE id > 2");
            jdbc.execute("DELETE FROM main.user_authority");
            jdbc.execute("DELETE FROM main.\"user\"");
            jdbc.execute("DELETE FROM main.authority");
            jdbc.execute("DELETE FROM main.sec");
            return null;
        });
    }

    // -------------------------------------------------------------------------
    // Test 1: CRUD — create and list alert rule
    // -------------------------------------------------------------------------

    @Test
    void test_createAndListAlertRule() {
        Map<String, Object> request = Map.of(
                "metricName", "FAILURE_RATE",
                "threshold", 0.30,
                "windowSeconds", 300,
                "notificationChannel", "LOG",
                "enabled", true,
                "description", "Test rule");

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, jsonHeaders());

        ResponseEntity<Map> createResponse = noRetryRestTemplate.exchange(
                url("/v1/admin/alerts"),
                HttpMethod.POST,
                entity,
                Map.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<List> listResponse = noRetryRestTemplate.exchange(
                url("/v1/admin/alerts"),
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                List.class);

        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).isNotNull();
        // Should contain at least the 2 seeded rules plus the one we just created
        boolean found = listResponse.getBody().stream()
                .anyMatch(r -> "FAILURE_RATE".equals(((Map<?, ?>) r).get("metricName")));
        assertThat(found).isTrue();
    }

    // -------------------------------------------------------------------------
    // Test 2: alert fires when threshold is breached (with sufficient sample size)
    // -------------------------------------------------------------------------

    @Test
    void test_alertFiresWhenThresholdBreached() {
        // Upsert a test rule with threshold=0.50
        transactionTemplate.execute(status -> {
            jdbc.execute(
                "INSERT INTO main.alert_rule (id, metric_name, threshold, window_seconds, " +
                "notification_channel, enabled, status, description) " +
                "VALUES (100, 'FAILURE_RATE', 0.50, 300, 'LOG', true, 'ACTIVE', 'Test breach rule') " +
                "ON CONFLICT (id) DO UPDATE SET threshold=EXCLUDED.threshold, enabled=EXCLUDED.enabled");
            return null;
        });

        // Force cache reload so the test rule is picked up
        alertRuleCache.refresh();
        eventCaptor.clear();

        // Increment counters: 15 failed + 5 success = 20 total, rate = 0.75 > 0.50 threshold
        for (int i = 0; i < 15; i++) {
            meterRegistry.counter("payment.failed.total").increment();
        }
        for (int i = 0; i < 5; i++) {
            meterRegistry.counter("payment.success.total").increment();
        }

        alertEvaluationService.evaluate();

        // Verify that an AlertFiredEvent was captured for FAILURE_RATE with breach
        boolean alertFired = eventCaptor.getAll().stream()
                .anyMatch(e -> "FAILURE_RATE".equals(e.metricName()) && e.actualValue() >= 0.50);
        assertThat(alertFired)
                .as("AlertFiredEvent must be published when FAILURE_RATE >= 0.50 with 20 samples")
                .isTrue();
    }

    // -------------------------------------------------------------------------
    // Test 3: alert does NOT fire when rate is below threshold
    // -------------------------------------------------------------------------

    @Test
    void test_alertDoesNotFireBelowThreshold() {
        // Use FRAUD_SPIKE_RATE for this test (counters start at 0 in this test).
        // fraud=0, success=10 → total=10 >= MINIMUM_SAMPLE_SIZE, rate=0.0 < threshold=0.05
        transactionTemplate.execute(status -> {
            jdbc.execute(
                "INSERT INTO main.alert_rule (id, metric_name, threshold, window_seconds, " +
                "notification_channel, enabled, status, description) " +
                "VALUES (101, 'FRAUD_SPIKE_RATE', 0.05, 300, 'LOG', true, 'ACTIVE', 'Below-threshold test rule') " +
                "ON CONFLICT (id) DO UPDATE SET threshold=EXCLUDED.threshold, enabled=EXCLUDED.enabled");
            return null;
        });

        // Force cache reload so the test rule is picked up
        alertRuleCache.refresh();

        // Capture baseline fraud counter (should be 0 or near 0 at test start)
        double baselineFraud   = meterRegistry.counter("payment.fraud.blocked.total").count();
        double baselineSuccess = meterRegistry.counter("payment.success.total").count();

        // Increment only success to get total >= 10 but fraud rate well below threshold
        for (int i = 0; i < 10; i++) {
            meterRegistry.counter("payment.success.total").increment();
        }

        eventCaptor.clear();
        alertEvaluationService.evaluate();

        // Must not publish AlertFiredEvent for FRAUD_SPIKE_RATE when fraud rate is near 0
        double fraudRate = baselineFraud / (baselineFraud + baselineSuccess + 10);
        boolean belowThresholdAlertFired = eventCaptor.getAll().stream()
                .anyMatch(e -> "FRAUD_SPIKE_RATE".equals(e.metricName())
                        && e.threshold() <= 0.05);
        assertThat(belowThresholdAlertFired)
                .as("AlertFiredEvent must NOT fire for FRAUD_SPIKE_RATE when fraud rate is below 5%%")
                .isFalse();
    }

    // -------------------------------------------------------------------------
    // Test 4: guard — no alert before minimum sample size
    // -------------------------------------------------------------------------

    @Test
    void test_noAlertBeforeMinimumSampleSize() {
        // Insert a rule with very low threshold 0.001 for CALLBACK_ANOMALY.
        // No callback counters are incremented in this test so received=0 < MINIMUM_SAMPLE_SIZE (10).
        // computeMetricValue returns -1.0 via the Pitfall 8 guard — alert must not fire.
        transactionTemplate.execute(status -> {
            jdbc.execute(
                "INSERT INTO main.alert_rule (id, metric_name, threshold, window_seconds, " +
                "notification_channel, enabled, status, description) " +
                "VALUES (102, 'CALLBACK_ANOMALY', 0.001, 300, 'LOG', true, 'ACTIVE', 'Pitfall-8 guard: insufficient samples') " +
                "ON CONFLICT (id) DO UPDATE SET threshold=EXCLUDED.threshold, enabled=EXCLUDED.enabled");
            return null;
        });

        // Also insert a second CALLBACK_ANOMALY rule to confirm the guard applies to all matching rules.
        transactionTemplate.execute(status -> {
            jdbc.execute(
                "INSERT INTO main.alert_rule (id, metric_name, threshold, window_seconds, " +
                "notification_channel, enabled, status, description) " +
                "VALUES (103, 'CALLBACK_ANOMALY', 0.001, 300, 'LOG', true, 'ACTIVE', 'Pitfall-8 guard second rule') " +
                "ON CONFLICT (id) DO UPDATE SET threshold=EXCLUDED.threshold, enabled=EXCLUDED.enabled");
            return null;
        });

        // Force cache reload so the test rules are picked up
        alertRuleCache.refresh();
        eventCaptor.clear();
        alertEvaluationService.evaluate();

        // CALLBACK_ANOMALY returns -1.0 when received < MINIMUM_SAMPLE_SIZE — must never fire
        boolean callbackAlertFired = eventCaptor.getAll().stream()
                .anyMatch(e -> "CALLBACK_ANOMALY".equals(e.metricName()));
        assertThat(callbackAlertFired)
                .as("CALLBACK_ANOMALY alert must not fire when callback.received.total < MINIMUM_SAMPLE_SIZE (Pitfall 8 guard)")
                .isFalse();
    }

    // -------------------------------------------------------------------------
    // Test 5: CALLBACK_ANOMALY fires when failed/received ratio exceeds threshold
    // -------------------------------------------------------------------------

    @Test
    void test_callbackAnomalyAlertFires() {
        // Seed a CALLBACK_ANOMALY rule with threshold=0.30 (30% failed callbacks triggers alert)
        transactionTemplate.execute(status -> {
            jdbc.execute(
                "INSERT INTO main.alert_rule (id, metric_name, threshold, window_seconds, " +
                "notification_channel, enabled, status, description) " +
                "VALUES (104, 'CALLBACK_ANOMALY', 0.30, 300, 'LOG', true, 'ACTIVE', 'Test callback anomaly rule') " +
                "ON CONFLICT (id) DO UPDATE SET threshold=EXCLUDED.threshold, enabled=EXCLUDED.enabled");
            return null;
        });

        alertRuleCache.refresh();
        eventCaptor.clear();

        // Simulate 12 total callbacks, 5 failed — rate = 5/12 ≈ 0.417 > threshold 0.30
        for (int i = 0; i < 12; i++) {
            meterRegistry.counter("callback.received.total").increment();
        }
        for (int i = 0; i < 5; i++) {
            meterRegistry.counter("callback.failed.total").increment();
        }

        alertEvaluationService.evaluate();

        boolean callbackAlertFired = eventCaptor.getAll().stream()
                .anyMatch(e -> "CALLBACK_ANOMALY".equals(e.metricName()) && e.actualValue() >= 0.30);
        assertThat(callbackAlertFired)
                .as("AlertFiredEvent must fire for CALLBACK_ANOMALY when failed/received >= 0.30 with 12 samples")
                .isTrue();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders(adminHeaders);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders loginAsAdmin() {
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        loginHeaders.add(HttpHeaders.COOKIE, "fcookie=fingerprintCookie");

        Map<String, String> credentials = Map.of("id", ADMIN_LOGIN, "password", ADMIN_PASSWORD);
        ResponseEntity<Map> loginResponse = noRetryRestTemplate.exchange(
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
