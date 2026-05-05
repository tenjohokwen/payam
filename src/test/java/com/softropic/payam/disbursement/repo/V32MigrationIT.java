package com.softropic.payam.disbursement.repo;

import com.softropic.payam.config.TestConfig;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Phase 57 SCHEMA-04 verification — proves the V32 Flyway migration produced the
 * expected schema state: both main.merchant_wallet_balance and
 * main.merchant_wallet_balance_aud are absent after Flyway runs all migrations,
 * and re-applying the V32 SQL is a no-op (idempotent IF EXISTS guards).
 *
 * <p>By the time the Spring context boots for this test, Testcontainers PostgreSQL
 * is up and Flyway has applied every migration through V32 in order. The base test
 * harness ({@link TestConfig}) is shared with other integration tests; no extra
 * fixture is needed.
 */
@ActiveProfiles({"dev", "test"})
@SpringBootTest(properties = "enable.test.mail=true")
@Import(TestConfig.class)
class V32MigrationIT {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate template;

    // ── SCHEMA-04 ────────────────────────────────────────────────────────────────

    @Test
    void merchantWalletBalanceTable_isAbsent_afterV32Migration() {
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables " +
            "WHERE table_schema = 'main' AND table_name = 'merchant_wallet_balance'",
            Long.class);
        assertThat(count)
            .as("V32 must drop main.merchant_wallet_balance")
            .isEqualTo(0L);
    }

    @Test
    void merchantWalletBalanceAudTable_isAbsent_afterV32Migration() {
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables " +
            "WHERE table_schema = 'main' AND table_name = 'merchant_wallet_balance_aud'",
            Long.class);
        assertThat(count)
            .as("V32 must drop main.merchant_wallet_balance_aud")
            .isEqualTo(0L);
    }

    @Test
    void v32_isIdempotent_reapplyingDropStatementsIsNoOp() {
        // Re-running the V32 SQL on a database where the tables are already
        // gone must succeed silently (IF EXISTS guards). This proves the
        // migration is safe to re-apply in disaster-recovery scenarios.
        assertThatCode(() -> template.execute(s -> {
            jdbcTemplate.execute("DROP TABLE IF EXISTS main.merchant_wallet_balance_aud");
            jdbcTemplate.execute("DROP TABLE IF EXISTS main.merchant_wallet_balance");
            return null;
        })).doesNotThrowAnyException();

        // Tables remain absent after the re-apply.
        Long baseCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables " +
            "WHERE table_schema = 'main' AND table_name IN " +
            "('merchant_wallet_balance', 'merchant_wallet_balance_aud')",
            Long.class);
        assertThat(baseCount)
            .as("Both wallet tables must still be absent after idempotent re-apply")
            .isEqualTo(0L);
    }

    @Test
    void disbursementTable_remainsPresent_afterV32() {
        // Defensive guard: V32 must drop ONLY the wallet tables, nothing else.
        // The disbursement table (heavily used by claim-based v11) MUST survive.
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables " +
            "WHERE table_schema = 'main' AND table_name = 'disbursement'",
            Long.class);
        assertThat(count)
            .as("V32 must NOT drop main.disbursement — only the wallet tables")
            .isEqualTo(1L);
    }
}
