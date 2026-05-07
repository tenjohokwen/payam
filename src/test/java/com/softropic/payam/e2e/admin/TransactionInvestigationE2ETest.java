package com.softropic.payam.e2e.admin;

import com.softropic.payam.common.AdminLogin;
import com.softropic.payam.e2e.AbstractPayamE2ETest;
import com.softropic.payam.e2e.builder.TenantBuilder;
import com.softropic.payam.platform.security.service.LoginAttemptsService;
import com.softropic.payam.platform.tenant.repo.TenantRepository;
import com.softropic.payam.platform.tenant.service.TenantService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E tests for admin transaction investigation (FLOWS-ADMIN-01).
 *
 * <p>Covers:
 * <ul>
 *   <li>Search by transactionId</li>
 *   <li>Search by externalReference (MSISDN)</li>
 *   <li>Search by traceId</li>
 *   <li>Tenant isolation: tenant B credentials return 0 rows for tenant A's transaction</li>
 * </ul>
 *
 * <p>Admin user rows (user, authority, user_authority) are seeded in {@code @BeforeEach}.
 * {@code TestDataCleaner.wipeAll()} (called in inherited {@code @AfterEach}) does NOT
 * delete user/authority rows — those are cleaned manually in {@code tearDownAdmin()}.
 *
 * <p>Note: {@code AdminTransactionResource.search()} accepts {@code tenantId} as a
 * {@code Long} (the database PK), not a UUID string. The JPQL query adds
 * {@code (:tenantId IS NULL OR t.tenantId = :tenantId)} when the param is supplied.
 */
public class TransactionInvestigationE2ETest extends AbstractPayamE2ETest {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private LoginAttemptsService loginAttemptsService;

    private HttpHeaders withUserAgent(HttpHeaders headers) {
        HttpHeaders h = new HttpHeaders(headers);
        h.add("user-agent", AdminLogin.TEST_USER_AGENT);
        return h;
    }
    private RestTemplate noRetryRestTemplate;

    @BeforeEach
    void setUpAdmin() {
        loginAttemptsService.resetLoginRecording();
        testDataCleaner.wipeAll();
        noRetryRestTemplate = new RestTemplate(new SimpleClientHttpRequestFactory());
        noRetryRestTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.HttpStatusCode statusCode) {
                return false;
            }

            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) throws IOException {
                return false;
            }
        });

        // Seed admin user rows — E2ESecurityConfig only seeds main.sec (JWT secret).
        // All inserts are in one transaction to satisfy FK constraints (user_authority → authority).
        // TestDataCleaner.wipeAll() does NOT delete user/authority rows, so these inserts
        // are idempotent across tests in the same JVM run.
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
            return null;
        });
    }

    // -------------------------------------------------------------------------
    // FLOWS-ADMIN-01 part 1: Search by transactionId
    // -------------------------------------------------------------------------

    @Test
    void searchByTransactionId() {
        TenantBuilder.CreatedTenant tenantA =
            new TenantBuilder().withName("Admin-Search-A").create(tenantService, tenantRepository);

        String transactionId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        String extRef = "+237672000001";

        insertTransaction(tenantA.tenantId(), transactionId, traceId, extRef);

        HttpHeaders adminHeaders = AdminLogin.loginAsAdmin(
            "http://localhost:" + serverPort + "/authenticate", noRetryRestTemplate);

        ResponseEntity<Map> response = noRetryRestTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/admin/transactions?transactionId=" + transactionId,
            HttpMethod.GET,
            new HttpEntity<>(withUserAgent(adminHeaders)),
            Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        List<?> content = (List<?>) response.getBody().get("content");
        assertThat(content).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // FLOWS-ADMIN-01 part 2: Search by externalReference (MSISDN)
    // -------------------------------------------------------------------------

    @Test
    void searchByExternalReference() {
        TenantBuilder.CreatedTenant tenantA =
            new TenantBuilder().withName("Admin-ExtRef-A").create(tenantService, tenantRepository);

        String transactionId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        String msisdn = "+237672000099";

        insertTransaction(tenantA.tenantId(), transactionId, traceId, msisdn);

        HttpHeaders adminHeaders = AdminLogin.loginAsAdmin(
            "http://localhost:" + serverPort + "/authenticate", noRetryRestTemplate);

        // Use URI.create() with an already-encoded string so RestTemplate does not re-encode.
        // %2B in the query string is sent as %2B to the server, and Spring's @RequestParam
        // decodes it to the literal '+' character, matching the stored external_reference value.
        java.net.URI uri = java.net.URI.create(
            "http://localhost:" + serverPort +
            "/v1/admin/transactions?externalReference=" + msisdn.replace("+", "%2B"));
        ResponseEntity<Map> response = noRetryRestTemplate.exchange(
            uri,
            HttpMethod.GET,
            new HttpEntity<>(withUserAgent(adminHeaders)),
            Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        List<?> content = (List<?>) response.getBody().get("content");
        assertThat(content).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // FLOWS-ADMIN-01 part 3: Search by traceId
    // -------------------------------------------------------------------------

    @Test
    void searchByTraceId() {
        TenantBuilder.CreatedTenant tenantA =
            new TenantBuilder().withName("Admin-Trace-A").create(tenantService, tenantRepository);

        String transactionId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();

        insertTransaction(tenantA.tenantId(), transactionId, traceId, "ref-trace-test");

        HttpHeaders adminHeaders = AdminLogin.loginAsAdmin(
            "http://localhost:" + serverPort + "/authenticate", noRetryRestTemplate);

        ResponseEntity<Map> response = noRetryRestTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/admin/transactions?traceId=" + traceId,
            HttpMethod.GET,
            new HttpEntity<>(withUserAgent(adminHeaders)),
            Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        List<?> content = (List<?>) response.getBody().get("content");
        assertThat(content).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // FLOWS-ADMIN-01 tenant isolation: tenant B returns 0 rows for tenant A's transaction
    // -------------------------------------------------------------------------

    /**
     * Verifies that the admin search {@code tenantId} filter scopes results to a specific tenant.
     *
     * <p>{@code AdminTransactionResource.search()} accepts {@code tenantId} as a {@code Long}
     * (database PK). The JPQL adds {@code (:tenantId IS NULL OR t.tenantId = :tenantId)}.
     * When tenant B's PK is passed, tenant A's transaction must not appear.
     */
    @Test
    void tenantIsolation() {
        TenantBuilder.CreatedTenant tenantA =
            new TenantBuilder().withName("Admin-Iso-A").create(tenantService, tenantRepository);
        TenantBuilder.CreatedTenant tenantB =
            new TenantBuilder().withName("Admin-Iso-B").create(tenantService, tenantRepository);

        String transactionId = UUID.randomUUID().toString();
        insertTransaction(tenantA.tenantId(), transactionId, UUID.randomUUID().toString(), "ref-iso");

        HttpHeaders adminHeaders = AdminLogin.loginAsAdmin(
            "http://localhost:" + serverPort + "/authenticate", noRetryRestTemplate);

        // Query with tenant B's Long PK — must return 0 results (tenant A's transaction is invisible)
        ResponseEntity<Map> responseB = noRetryRestTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/admin/transactions?tenantId=" + tenantB.tenantId(),
            HttpMethod.GET,
            new HttpEntity<>(withUserAgent(adminHeaders)),
            Map.class);

        assertThat(responseB.getStatusCode().value()).isEqualTo(200);
        List<?> contentB = (List<?>) responseB.getBody().get("content");
        assertThat(contentB).isEmpty();

        // Query with tenant A's Long PK — must return 1 result
        ResponseEntity<Map> responseA = noRetryRestTemplate.exchange(
            "http://localhost:" + serverPort + "/v1/admin/transactions?tenantId=" + tenantA.tenantId(),
            HttpMethod.GET,
            new HttpEntity<>(withUserAgent(adminHeaders)),
            Map.class);

        assertThat(responseA.getStatusCode().value()).isEqualTo(200);
        List<?> contentA = (List<?>) responseA.getBody().get("content");
        assertThat(contentA).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Inserts a transaction row via JDBC with the given fields set.
     * The {@code external_reference} column maps to the admin search {@code externalReference} param.
     */
    private String insertTransaction(long tenantId, String transactionId, String traceId, String externalReference) {
        String providerRef = "pref-" + UUID.randomUUID();
        long id = System.nanoTime() & Long.MAX_VALUE;
        String createdDate = Instant.now().toString();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.transaction " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, " +
                "transaction_id, trace_id, tenant_id, tx_status, status, provider, amount, currency, provider_ref, external_reference) " +
                "VALUES (?, 'TEST', ?::TIMESTAMPTZ, 'TEST', ?::TIMESTAMPTZ, ?, ?, ?, 'SUCCESS', 'ACTIVE', 'MTN', 500.00, 'XAF', ?, ?)",
                id, createdDate, createdDate,
                transactionId, traceId, tenantId, providerRef, externalReference);
            return null;
        });
        return transactionId;
    }
}
