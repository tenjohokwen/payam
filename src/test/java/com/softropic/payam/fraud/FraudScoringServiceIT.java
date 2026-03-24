package com.softropic.payam.fraud;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.common.payment.PaymentCommand;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.fraud.contract.FraudDecision;
import com.softropic.payam.fraud.service.FraudRuleCache;
import com.softropic.payam.fraud.service.FraudScoringService;
import com.softropic.payam.tenant.service.TenantService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;


/**
 * Integration test for {@link FraudScoringService} — verifies velocity blocking and risk score
 * computation against real Redis (Testcontainer from TestConfig) and PostgreSQL (Testcontainer).
 *
 * <p>FraudScoringService does NOT write to main.transaction — all DB cleanup is tenant-only.
 */
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestConfig.class)
class FraudScoringServiceIT {

    @Autowired
    FraudScoringService fraudScoringService;

    @Autowired
    FraudRuleCache fraudRuleCache;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    TenantService tenantService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TransactionTemplate transactionTemplate;

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

        // Seed fraud rules — dev profile uses create-drop so Hibernate drops/recreates tables empty;
        // Flyway seed data is wiped. We insert rules here to ensure cache has data for each test.
        transactionTemplate.execute(status -> {
            buildRule(1L, "IP_VELOCITY",      40, 10, 60,   true);
            buildRule(2L, "MSISDN_VELOCITY",  35, 5,  60,   true);
            buildRule(3L, "APP_VELOCITY",     25, 20, 60,   true);
            buildRule(4L, "MSISDN_HOUSEHOLD", 15, 8,  3600, true);
            buildRule(5L, "BLOCK_THRESHOLD",  0,  70, 0,    true);
            return null;
        });

        // Create a tenant to obtain a valid tenantId
        TenantService.TenantCreationResult provision =
                tenantService.createTenant("fraud-scoring-test-" + UUID.randomUUID(), "dev");
        tenantId = provision.tenant().getId();

        // Load newly seeded rules into cache
        fraudRuleCache.refreshRules();

        // Flush velocity keys from Redis to ensure test isolation
        deleteVelocityKeys();
    }

    @AfterEach
    void tearDown() {
        // Delete velocity Redis keys
        deleteVelocityKeys();

        // Clean up tenant (no transaction rows written by FraudScoringService)
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM main.tenant_api_key WHERE tenant_id = " + tenantId);
            jdbcTemplate.execute("DELETE FROM main.tenant WHERE id = " + tenantId);
            jdbcTemplate.execute("DELETE FROM main.fraud_rule");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    private void deleteVelocityKeys() {
        Set<String> keys = redis.keys("fraud:velocity:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    // ---- helpers ----

    /**
     * Seed a fraud rule row via JDBC. Uses ON CONFLICT DO UPDATE to handle re-runs.
     * JDBC is used instead of FraudRuleRepository.save() to avoid TSID auto-generation
     * overwriting the explicit id — @Tsid generates new IDs on each save() call.
     */
    private void buildRule(Long id, String signalName, int weight, int threshold,
                           int windowSeconds, boolean enabled) {
        jdbcTemplate.update(
            "INSERT INTO main.fraud_rule (id, signal_name, weight, threshold, window_seconds, enabled, status) " +
            "VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE') ON CONFLICT (signal_name) DO UPDATE SET " +
            "weight=EXCLUDED.weight, threshold=EXCLUDED.threshold, " +
            "window_seconds=EXCLUDED.window_seconds, enabled=EXCLUDED.enabled",
            id, signalName, weight, threshold, windowSeconds, enabled
        );
    }

    private PaymentCommand buildCommand(String clientIp, String msisdn) {
        return new PaymentCommand(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                tenantId,
                msisdn,
                BigDecimal.valueOf(500),
                "XAF",
                "REF-001",
                "IDP-" + UUID.randomUUID(),
                MobilePaymentProvider.MTN,
                clientIp,
                "Mozilla/5.0",
                null
        );
    }

    // ---- tests ----

    /**
     * IP velocity block — after exceeding IP_VELOCITY threshold (10 requests in 60s),
     * the next request must be blocked.
     *
     * <p>Uses a unique MSISDN per call to avoid MSISDN_VELOCITY exhaustion (threshold=5)
     * confounding the IP threshold test. Each call has the same IP but a different MSISDN
     * so only the IP bucket is consumed across calls.
     */
    @Test
    void ipVelocityBlock() {
        String clientIp = "10.0.0.1";

        // Consume all 10 allowed IP tokens — use unique MSISDN AND unique household prefix per call
        // to avoid MSISDN_VELOCITY (threshold=5) and MSISDN_HOUSEHOLD (threshold=8) exhaustion.
        // MSISDN format: +237{6-prefix}{3-unique-middle}{3-counter} — ensures 9-digit prefix differs.
        for (int i = 0; i < 10; i++) {
            // Different 9-digit prefix per call: "+237691" + 2-digit counter + "00"
            String uniqueMsisdn = "+237691" + String.format("%02d", i) + "00000";
            FraudDecision decision = fraudScoringService.evaluate(buildCommand(clientIp, uniqueMsisdn));
            assertThat(decision.blocked())
                    .as("Request %d should be allowed (IP threshold not yet reached)", i + 1)
                    .isFalse();
        }

        // 11th request — IP bucket exhausted, should be blocked (unique MSISDN prefix)
        FraudDecision blocked = fraudScoringService.evaluate(buildCommand(clientIp, "+2376911000099"));

        assertThat(blocked.blocked())
                .as("11th request should be blocked by IP velocity")
                .isTrue();
        assertThat(blocked.reason())
                .as("Reason should reference IP_VELOCITY signal")
                .contains("IP_VELOCITY");
        assertThat(blocked.riskScore())
                .as("Risk score should be at least IP_VELOCITY weight (40)")
                .isGreaterThanOrEqualTo(40);
    }

    /**
     * MSISDN velocity block — after exceeding MSISDN_VELOCITY threshold (5 requests in 60s),
     * the next request must be blocked.
     */
    @Test
    void msisdnVelocityBlock() {
        // Use different IP from ipVelocityBlock test to avoid cross-test contamination
        String clientIp = "10.0.0.2";
        String msisdn = "+237699000001";

        // Consume all 5 allowed MSISDN tokens
        for (int i = 0; i < 5; i++) {
            FraudDecision decision = fraudScoringService.evaluate(buildCommand(clientIp, msisdn));
            assertThat(decision.blocked())
                    .as("Request %d should be allowed (MSISDN threshold not yet reached)", i + 1)
                    .isFalse();
        }

        // 6th request — MSISDN bucket exhausted
        FraudDecision blocked = fraudScoringService.evaluate(buildCommand(clientIp, msisdn));

        assertThat(blocked.blocked())
                .as("6th request should be blocked by MSISDN velocity")
                .isTrue();
        assertThat(blocked.reason())
                .as("Reason should reference MSISDN_VELOCITY signal")
                .contains("MSISDN_VELOCITY");
    }

    /**
     * Score computation — single request with no prior velocity should have risk score
     * within the valid 0–100 range and should not be blocked.
     */
    @Test
    void scoreComputedWithinRange() {
        // Use unique IP and MSISDN to ensure no pre-existing velocity state
        String clientIp = "10.0.0.99";
        String msisdn = "+237699999999";

        FraudDecision decision = fraudScoringService.evaluate(buildCommand(clientIp, msisdn));

        assertThat(decision.blocked())
                .as("Single request with no prior velocity should not be blocked")
                .isFalse();
        assertThat(decision.riskScore())
                .as("Risk score must be within valid range 0-100")
                .isBetween(0, 100);
    }
}
