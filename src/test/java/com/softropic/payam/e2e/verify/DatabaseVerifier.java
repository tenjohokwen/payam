package com.softropic.payam.e2e.verify;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VERIF-01: Asserts payment table state, event log counts, orphan detection,
 * and idempotency key uniqueness via raw JDBC (no JPA, no Spring context required).
 */
public class DatabaseVerifier {

    private final JdbcTemplate jdbc;

    public DatabaseVerifier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Asserts that a payment row with the given transactionId exists in main.transaction
     * and has the expected tx_status value.
     */
    public void assertPaymentRow(String transactionId, String expectedStatus) {
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT tx_status FROM main.transaction WHERE transaction_id = ?",
            transactionId);
        assertThat(row.get("tx_status"))
            .as("tx_status for transactionId=%s", transactionId)
            .isEqualTo(expectedStatus);
    }

    /**
     * Asserts the exact number of rows in main.payment_event_log for the given transactionId.
     */
    public void assertEventCount(String transactionId, int expectedCount) {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM main.payment_event_log WHERE transaction_id = ?",
            Integer.class, transactionId);
        assertThat(count)
            .as("event count for transactionId=%s", transactionId)
            .isEqualTo(expectedCount);
    }

    /**
     * Asserts that every row in payment_event_log for the given transactionId
     * has a corresponding row in main.transaction.
     */
    public void assertNoOrphanEvents(String transactionId) {
        Integer orphanCount = jdbc.queryForObject(
            "SELECT count(*) FROM main.payment_event_log el " +
            "WHERE el.transaction_id = ? " +
            "AND NOT EXISTS (SELECT 1 FROM main.transaction t WHERE t.transaction_id = el.transaction_id)",
            Integer.class, transactionId);
        assertThat(orphanCount)
            .as("orphan event count for transactionId=%s", transactionId)
            .isEqualTo(0);
    }

    /**
     * Asserts that the (tenantId, idempotencyKey) pair appears at most once in main.idempotency_key.
     * Note: idempotency uniqueness is enforced in main.idempotency_key, NOT in main.transaction.
     */
    public void assertNoDuplicateIdempotencyKey(Long tenantId, String idempotencyKey) {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM main.idempotency_key WHERE tenant_id = ? AND idempotency_key = ?",
            Integer.class, tenantId, idempotencyKey);
        assertThat(count)
            .as("idempotency_key count for tenantId=%s key=%s", tenantId, idempotencyKey)
            .isLessThanOrEqualTo(1);
    }
}
