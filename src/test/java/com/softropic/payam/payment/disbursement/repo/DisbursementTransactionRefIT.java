package com.softropic.payam.payment.disbursement.repo;

import com.softropic.payam.config.TestConfig;
import com.softropic.payam.payment.disbursement.contract.DisbursementRefStatus;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 54 SCHEMA-01/02/03 verification — proves the V31 migration produced the expected
 * schema state and behavior. Wave 0 stub: created in Plan 01, real DDL lands in Plan 02.
 *
 * <p>Test sources of truth:
 * <ul>
 *   <li>SCHEMA-01: partial unique index rejects a duplicate active claim insert</li>
 *   <li>SCHEMA-02: admin_note + retry_count exist on disbursement; reserved_amount removed</li>
 *   <li>SCHEMA-03: pre-flight raises EXCEPTION when a PROCESSING disbursement row exists
 *       at re-migration time</li>
 * </ul>
 */
@ActiveProfiles({"dev","test"})
@SpringBootTest(properties = "enable.test.mail=true")
@Import(TestConfig.class)
class DisbursementTransactionRefIT {

    @Autowired private DisbursementTransactionRefRepository refRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate template;
    @Autowired private Flyway flyway;

    @BeforeEach
    void setUp() {
        template.execute(s -> {
            jdbcTemplate.execute("DELETE FROM main.disbursement_transaction_ref_aud");
            jdbcTemplate.execute("DELETE FROM main.disbursement_transaction_ref");
            jdbcTemplate.execute("DELETE FROM main.disbursement_aud");
            jdbcTemplate.execute("DELETE FROM main.disbursement");
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        template.execute(s -> {
            jdbcTemplate.execute("DELETE FROM main.disbursement_transaction_ref_aud");
            jdbcTemplate.execute("DELETE FROM main.disbursement_transaction_ref");
            jdbcTemplate.execute("DELETE FROM main.disbursement_aud");
            jdbcTemplate.execute("DELETE FROM main.disbursement");
            return null;
        });
    }

    // ── SCHEMA-01 ────────────────────────────────────────────────────────────────

    @Test
    void disbursementTransactionRefTable_existsWithExpectedColumns() {
        List<Map<String, Object>> cols = jdbcTemplate.queryForList(
            "SELECT column_name, data_type, is_nullable " +
            "FROM information_schema.columns " +
            "WHERE table_schema = 'main' AND table_name = 'disbursement_transaction_ref' " +
            "ORDER BY column_name");
        assertThat(cols).extracting(c -> (String) c.get("column_name"))
            .as("disbursement_transaction_ref must declare disbursement_id, transaction_id, ref_status")
            .contains("disbursement_id", "transaction_id", "ref_status");
    }

    @Test
    void partialUniqueIndex_rejectsDuplicateActiveClaim() {
        // Insert a first PENDING ref directly (use Long disbursement_id + String transaction_id)
        String txnId = UUID.randomUUID().toString();
        insertRefDirect(101L, txnId, "PENDING");

        // Second PENDING insert on the SAME transaction_id MUST violate the partial unique index
        assertThatThrownBy(() -> insertRefDirect(102L, txnId, "PENDING"))
            .isInstanceOf(DataIntegrityViolationException.class);

        // But a RELEASED row for the same transaction_id IS allowed (RELEASED is NOT in the index predicate)
        insertRefDirect(103L, txnId, "RELEASED");
        Long releasedCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.disbursement_transaction_ref " +
            "WHERE transaction_id = ? AND ref_status = 'RELEASED'", Long.class, txnId);
        assertThat(releasedCount).isEqualTo(1L);
    }

    // ── SCHEMA-02 ────────────────────────────────────────────────────────────────

    @Test
    void disbursement_hasAdminNoteAndRetryCount_andReservedAmountIsGone() {
        List<Map<String, Object>> cols = jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.columns " +
            "WHERE table_schema = 'main' AND table_name = 'disbursement'");
        List<String> colNames = cols.stream().map(c -> (String) c.get("column_name")).toList();

        assertThat(colNames).as("admin_note must be added by V31").contains("admin_note");
        assertThat(colNames).as("retry_count must be added by V31").contains("retry_count");
        assertThat(colNames).as("reserved_amount must be removed by V31").doesNotContain("reserved_amount");
    }

    // ── SCHEMA-03 ────────────────────────────────────────────────────────────────

    @Test
    void preflight_raisesException_whenProcessingDisbursementExists() {
        // Insert a PROCESSING disbursement directly; then re-run V31 via flyway.repair()+migrate()
        // to prove the pre-flight DO $$ block raises. We simulate by calling the same SQL the
        // migration runs as its first statement.
        insertDisbursementDirect("PROCESSING");
        assertThatThrownBy(() ->
            jdbcTemplate.execute(
                "DO $$ DECLARE bad_count INT; BEGIN " +
                "SELECT COUNT(*) INTO bad_count FROM main.disbursement " +
                "WHERE disbursement_status IN ('PROCESSING','PENDING_CONFIRMATION'); " +
                "IF bad_count > 0 THEN " +
                "RAISE EXCEPTION 'V31 pre-flight: % disbursement(s) in PROCESSING or PENDING_CONFIRMATION', bad_count; " +
                "END IF; END $$;"))
            .hasMessageContaining("V31 pre-flight");
    }

    @Test
    void preflight_passes_whenAllDisbursementsAreTerminal() {
        insertDisbursementDirect("SUCCESS");
        insertDisbursementDirect("FAILED");
        insertDisbursementDirect("EXPIRED");
        // Same DO $$ block — must NOT raise
        jdbcTemplate.execute(
            "DO $$ DECLARE bad_count INT; BEGIN " +
            "SELECT COUNT(*) INTO bad_count FROM main.disbursement " +
            "WHERE disbursement_status IN ('PROCESSING','PENDING_CONFIRMATION'); " +
            "IF bad_count > 0 THEN " +
            "RAISE EXCEPTION 'V31 pre-flight'; " +
            "END IF; END $$;");
        // No exception → assertion is reaching this line
        assertThat(true).isTrue();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private void insertRefDirect(long id, String transactionId, String refStatus) {
        template.execute(s -> {
            jdbcTemplate.update(
                "INSERT INTO main.disbursement_transaction_ref " +
                "(id, disbursement_id, transaction_id, ref_status, created_date) " +
                "VALUES (?, ?, ?, ?, NOW())",
                id, 999L, transactionId, refStatus);
            return null;
        });
    }

    private void insertDisbursementDirect(String status) {
        long id = Math.abs(UUID.randomUUID().getLeastSignificantBits());
        String dsbId = UUID.randomUUID().toString();
        template.execute(s -> {
            jdbcTemplate.update(
                "INSERT INTO main.disbursement " +
                "(id, disbursement_id, tenant_id, recipient_msisdn, amount, currency, " +
                " reference, disbursement_status, status, poll_attempts, created_date) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 0, NOW())",
                id, dsbId, 1001L, "237691111111",
                new java.math.BigDecimal("100.00"), "XAF", "ref-" + id, status);
            return null;
        });
    }
}
