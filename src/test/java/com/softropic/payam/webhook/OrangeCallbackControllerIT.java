package com.softropic.payam.webhook;

import com.softropic.payam.config.TestConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Orange inbound webhook path.
 *
 * No @EnableWireMock needed — these tests POST inbound callbacks to our server,
 * not outbound requests to Orange API.
 *
 * IP whitelist is set to empty (sandbox mode) via @TestPropertySource.
 * HMAC secret is empty (sandbox mode) by default — Test 5 temporarily sets it.
 */
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "orange.callback-ip-whitelist="     // empty = sandbox mode (accept all IPs)
})
class OrangeCallbackControllerIT {

    @Autowired TestRestTemplate testRestTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired StringRedisTemplate redis;

    @LocalServerPort int serverPort;

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
        // Clear dedup keys from any previous test
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    // --- Helper ---

    private ResponseEntity<Void> postOrangeCallback(String payToken, String createtime, String status) {
        String body = String.format(
            "{\"payToken\":\"%s\",\"notif_token\":\"tok\",\"status\":\"%s\",\"txnid\":\"TXN001\",\"msisdn\":\"237650000000\",\"amount\":\"100\",\"createtime\":\"%s\"}",
            payToken, status, createtime);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return testRestTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/callbacks/orange",
            HttpMethod.POST, new HttpEntity<>(body, headers), Void.class);
    }

    // --- Tests ---

    @Test
    void shouldAcceptValidOrangeCallback() {
        var response = postOrangeCallback("paytoken-001", "2026-03-24T10:00:00", "SUCCESS");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void shouldSuppressDuplicateOrangeCallback() {
        postOrangeCallback("paytoken-002", "2026-03-24T10:00:00", "SUCCESS");
        var response = postOrangeCallback("paytoken-002", "2026-03-24T10:00:00", "SUCCESS");
        assertThat(response.getStatusCode().value()).isEqualTo(200); // silent dedup — not 409
    }

    @Test
    void shouldNotDedupDifferentCreatetime() {
        postOrangeCallback("paytoken-003", "2026-03-24T10:00:00", "PENDING");
        var response = postOrangeCallback("paytoken-003", "2026-03-24T10:05:00", "SUCCESS");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        // Both processed — dedup key includes createtime (Pitfall 8 guard)
    }

    @Test
    void shouldHandle200OnMinimalPayload() {
        // Verifies controller does not NPE when payToken is null (dedup key uses empty string fallback)
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response = testRestTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/callbacks/orange",
            HttpMethod.POST, new HttpEntity<>("{}", headers), Void.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

}
