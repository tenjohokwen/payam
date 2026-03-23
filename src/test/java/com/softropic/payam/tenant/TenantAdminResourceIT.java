package com.softropic.payam.tenant;

import com.softropic.payam.config.TestConfig;
import com.softropic.payam.tenant.contract.ApiKeyDto;
import com.softropic.payam.tenant.service.ApiKeyService;
import com.softropic.payam.tenant.service.TenantService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"ledger.database.spy=true", "enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
class TenantAdminResourceIT {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private RestTemplate restTemplate;

    @LocalServerPort
    int port;

    @BeforeEach
    void setUp() {
        // Load the JWT secret required by SecurityAdviceFilter.addSecretToThread().
        // Without this, any request to /v1/** fails with SecException KEY_NOT_FOUND.
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
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("delete from main.idempotency_key");
            jdbcTemplate.execute("delete from main.tenant_api_key");
            jdbcTemplate.execute("delete from main.tenant");
            jdbcTemplate.execute("delete from main.sec");
            return null;
        });
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    // -------------------------------------------------------------------------
    // Test 1: rotate returns 200 with new non-null rawKey different from original
    // -------------------------------------------------------------------------
    @Test
    void rotateKey_returns200_withNewRawKey() {
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Rotate HTTP Corp", "LIVE");
        Long tenantId = result.tenant().getId();
        Long keyId = result.key().getId();
        String originalRawKey = result.rawKey();

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Api-Key", originalRawKey);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<ApiKeyDto> response = restTemplate.exchange(
            url("/v1/admin/tenants/" + tenantId + "/keys/" + keyId + "/rotate"),
            HttpMethod.POST, request, ApiKeyDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ApiKeyDto dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.rawKey()).isNotNull();
        assertThat(dto.rawKey()).isNotBlank();
        assertThat(dto.rawKey()).isNotEqualTo(originalRawKey);
        assertThat(dto.id()).isNotEqualTo(keyId);  // rotate creates a new DB row

        // Old key must still authenticate during grace period
        assertThat(apiKeyService.authenticate(originalRawKey)).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Test 2: revoke returns 204 and key is subsequently rejected
    // -------------------------------------------------------------------------
    @Test
    void revokeKey_returns204_andKeyIsUnusable() {
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Revoke HTTP Corp", "LIVE");
        Long tenantId = result.tenant().getId();
        Long keyId = result.key().getId();
        String rawKey = result.rawKey();

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Api-Key", rawKey);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<Void> response = restTemplate.exchange(
            url("/v1/admin/tenants/" + tenantId + "/keys/" + keyId),
            HttpMethod.DELETE, request, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();

        // Revoked key must be rejected by authenticate()
        assertThatThrownBy(() -> apiKeyService.authenticate(rawKey))
            .isInstanceOf(BadCredentialsException.class);
    }

    // -------------------------------------------------------------------------
    // Test 3: unknown keyId returns 404
    // -------------------------------------------------------------------------
    @Test
    void rotateKey_unknownKeyId_returns404() {
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Unknown Key Corp", "LIVE");
        Long tenantId = result.tenant().getId();
        String rawKey = result.rawKey();

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Api-Key", rawKey);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        assertThatThrownBy(() ->
            restTemplate.exchange(
                url("/v1/admin/tenants/" + tenantId + "/keys/999999/rotate"),
                HttpMethod.POST, request, Object.class))
            .isInstanceOf(HttpClientErrorException.NotFound.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
    }
}
