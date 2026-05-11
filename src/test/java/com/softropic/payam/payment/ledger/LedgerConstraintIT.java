package com.softropic.payam.payment.ledger;

import com.softropic.payam.config.TestConfig;
import com.softropic.payam.platform.tenant.contract.ApiKeyEnvironment;
import com.softropic.payam.platform.tenant.service.TenantService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"ledger.database.spy=true", "enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
class LedgerConstraintIT {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private TenantService tenantService;

    private Long tenantId;
    private String transactionId;

    @BeforeEach
    void setUp() {
        TenantService.TenantCreationResult t =
            tenantService.createTenant("LEDGER-01-Tenant", ApiKeyEnvironment.PROD);
        tenantId = t.tenant().getId();
        transactionId = UUID.randomUUID().toString();
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM main.ledger_entry");
            jdbcTemplate.execute("DELETE FROM main.tenant_api_key_aud");
            jdbcTemplate.execute("DELETE FROM main.tenant_aud");
            jdbcTemplate.execute("DELETE FROM main.revinfo");
            jdbcTemplate.execute("DELETE FROM main.tenant_api_key");
            jdbcTemplate.execute("DELETE FROM main.tenant");
            return null;
        });
    }

    @Test
    void unbalancedInsert_isRejectedByConstraint() {
        String groupId = UUID.randomUUID().toString();

        // Insert two DEBIT rows for the same entry_group_id — violates the balance-check trigger.
        // Must wrap in TransactionTemplate so the DEFERRABLE INITIALLY DEFERRED trigger fires at commit.
        assertThatThrownBy(() ->
            transactionTemplate.execute(status -> {
                insertLedgerRow(groupId, "DEBIT", "CUSTOMER_WALLET");
                insertLedgerRow(groupId, "DEBIT", "CUSTOMER_WALLET");
                return null;
            })
        )
        .isInstanceOfAny(DataIntegrityViolationException.class,
                         org.springframework.transaction.TransactionSystemException.class,
                         JpaSystemException.class)
        .satisfies(ex -> {
            // Unwrap cause chain to verify the trigger message appears somewhere
            Throwable root = ex;
            StringBuilder chain = new StringBuilder();
            while (root != null) {
                chain.append(root.getMessage() == null ? "" : root.getMessage()).append(" | ");
                root = root.getCause();
            }
            assertThat(chain.toString())
                .as("Trigger rejection must reference the balance-check trigger message")
                .contains("Ledger balance violation");
        });

        // No rows should be visible after the failed transaction rolled back
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.ledger_entry WHERE entry_group_id = ?",
            Integer.class, groupId);
        assertThat(count)
            .as("Failed transaction must leave zero rows for the violating entry_group_id")
            .isEqualTo(0);
    }

    @Test
    void balancedInsert_succeeds() {
        String groupId = UUID.randomUUID().toString();

        transactionTemplate.execute(status -> {
            insertLedgerRow(groupId, "DEBIT",  "CUSTOMER_WALLET");
            insertLedgerRow(groupId, "CREDIT", "PROVIDER_CLEARING");
            return null;
        });

        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.ledger_entry WHERE entry_group_id = ?",
            Integer.class, groupId);
        assertThat(count)
            .as("Balanced DEBIT+CREDIT insert must commit both rows")
            .isEqualTo(2);
    }

    @Test
    void threeEntryDisbursementGroup_succeeds() {
        String groupId = UUID.randomUUID().toString();
        transactionTemplate.execute(status -> {
            insertLedgerRow(groupId, "DEBIT",  "MERCHANT_WALLET",   new BigDecimal("110.00"));
            insertLedgerRow(groupId, "CREDIT", "CUSTOMER_WALLET",   new BigDecimal("100.00"));
            insertLedgerRow(groupId, "CREDIT", "PROVIDER_FEE",      new BigDecimal("10.00"));
            return null;
        });
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.ledger_entry WHERE entry_group_id = ?",
            Integer.class, groupId);
        assertThat(count).as("3-entry disbursement group must commit all rows").isEqualTo(3);

        BigDecimal debitSum = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(amount),0) FROM main.ledger_entry WHERE entry_group_id = ? AND direction = 'DEBIT'",
            BigDecimal.class, groupId);
        BigDecimal creditSum = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(amount),0) FROM main.ledger_entry WHERE entry_group_id = ? AND direction = 'CREDIT'",
            BigDecimal.class, groupId);
        assertThat(debitSum).isEqualByComparingTo("110.00");
        assertThat(creditSum).isEqualByComparingTo("110.00");
    }

    @Test
    void zeroAmountEntry_succeeds() {
        String groupId = UUID.randomUUID().toString();
        transactionTemplate.execute(status -> {
            insertLedgerRow(groupId, "DEBIT",  "MERCHANT_WALLET", new BigDecimal("100.00"));
            insertLedgerRow(groupId, "CREDIT", "CUSTOMER_WALLET", new BigDecimal("100.00"));
            insertLedgerRow(groupId, "CREDIT", "PROVIDER_FEE",    new BigDecimal("0.00"));
            return null;
        });
        Integer zeroCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.ledger_entry WHERE entry_group_id = ? AND amount = 0.00",
            Integer.class, groupId);
        assertThat(zeroCount).as("Zero-amount PROVIDER_FEE row must be inserted without CHECK violation").isEqualTo(1);
    }

    @Test
    void flowColumn_existsAndIsNullable() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT table_name, is_nullable, data_type, character_maximum_length " +
            "FROM information_schema.columns " +
            "WHERE table_schema = 'main' AND column_name = 'flow' " +
            "  AND table_name IN ('transaction', 'transaction_aud') " +
            "ORDER BY table_name");
        assertThat(rows).as("flow column must exist on both transaction and transaction_aud").hasSize(2);
        for (Map<String, Object> row : rows) {
            assertThat(row.get("is_nullable")).isEqualTo("YES");
            assertThat(row.get("data_type")).isEqualTo("character varying");
            assertThat(((Number) row.get("character_maximum_length")).intValue()).isEqualTo(20);
        }
    }

    private void insertLedgerRow(String groupId, String direction, String accountCode) {
        insertLedgerRow(groupId, direction, accountCode, new BigDecimal("100.00"));
    }

    private void insertLedgerRow(String groupId, String direction, String accountCode, BigDecimal amount) {
        long id = Math.abs(UUID.randomUUID().getMostSignificantBits());
        jdbcTemplate.update(
            "INSERT INTO main.ledger_entry " +
            "(id, transaction_id, entry_group_id, tenant_id, direction, account_code, amount, currency, created_date) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, transactionId, groupId, tenantId, direction, accountCode,
            amount, "XAF", java.sql.Timestamp.from(Instant.now()));
    }
}
