package com.softropic.payam.transaction;

import com.softropic.payam.config.TestConfig;
import com.softropic.payam.tenant.contract.ApiKeyEnvironment;
import com.softropic.payam.tenant.service.TenantService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("dev")
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

        // Insert two DEBIT rows for the same entry_group_id — violates uq_ledger_entry_group_direction.
        // Must wrap in TransactionTemplate so the DEFERRED constraint fires at commit.
        assertThatThrownBy(() ->
            transactionTemplate.execute(status -> {
                insertLedgerRow(groupId, "DEBIT", "CUSTOMER_WALLET");
                insertLedgerRow(groupId, "DEBIT", "CUSTOMER_WALLET");
                return null;
            })
        )
        .isInstanceOfAny(DataIntegrityViolationException.class,
                         org.springframework.transaction.TransactionSystemException.class)
        .satisfies(ex -> {
            // Unwrap cause chain to verify the constraint name appears somewhere
            Throwable root = ex;
            StringBuilder chain = new StringBuilder();
            while (root != null) {
                chain.append(root.getMessage() == null ? "" : root.getMessage()).append(" | ");
                root = root.getCause();
            }
            assertThat(chain.toString())
                .as("Constraint violation chain must reference uq_ledger_entry_group_direction")
                .contains("uq_ledger_entry_group_direction");
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

    private void insertLedgerRow(String groupId, String direction, String accountCode) {
        // Tsid id generation mimicked with a random long from UUID — unique, positive.
        long id = Math.abs(UUID.randomUUID().getMostSignificantBits());
        jdbcTemplate.update(
            "INSERT INTO main.ledger_entry " +
            "(id, transaction_id, entry_group_id, tenant_id, direction, account_code, amount, currency, created_date) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, transactionId, groupId, tenantId, direction, accountCode,
            new BigDecimal("100.00"), "XAF", java.sql.Timestamp.from(Instant.now()));
    }
}
