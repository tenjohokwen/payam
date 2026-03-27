package com.softropic.payam.e2e.verify;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VERIF-02: Verifies SHA-256 hash chain integrity for a given transactionId.
 *
 * Uses the identical canonical string as production PaymentEventLog.create():
 *   txId|eventType|statusFrom|statusTo|actor|previousHash
 *
 * Fields NOT included: traceId, metadata, createdDate, id.
 */
public class HashChainVerifier {

    private final JdbcTemplate jdbc;

    public HashChainVerifier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Loads all payment_event_log rows for the given transactionId (ordered by id ASC)
     * and verifies the full hash chain:
     * <ul>
     *   <li>Genesis row: previous_hash = "GENESIS"</li>
     *   <li>Each row: event_hash = sha256(txId|eventType|statusFrom|statusTo|actor|previousHash)</li>
     *   <li>Each non-genesis row: previous_hash = event_hash of prior row</li>
     * </ul>
     */
    public void assertChainValid(String transactionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, event_type, status_from, status_to, actor, previous_hash, event_hash " +
            "FROM main.payment_event_log " +
            "WHERE transaction_id = ? ORDER BY id ASC",
            transactionId);

        assertThat(rows)
            .as("payment_event_log must have at least one row for transactionId=%s", transactionId)
            .isNotEmpty();

        String previousHash = "GENESIS";

        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);

            String eventType   = (String) row.get("event_type");
            String statusFrom  = (String) row.get("status_from");
            String statusTo    = (String) row.get("status_to");
            String actor       = (String) row.get("actor");
            String storedPreviousHash = (String) row.get("previous_hash");
            String storedEventHash    = (String) row.get("event_hash");

            // Verify previous_hash linkage
            assertThat(storedPreviousHash)
                .as("previous_hash at row index %d for transactionId=%s", i, transactionId)
                .isEqualTo(previousHash);

            // Recompute event_hash using the identical canonical string as production
            String canonical = transactionId + "|"
                + eventType + "|"
                + (statusFrom != null ? statusFrom : "null") + "|"
                + statusTo + "|"
                + actor + "|"
                + storedPreviousHash;

            String expectedHash = DigestUtils.sha256Hex(canonical);

            assertThat(storedEventHash)
                .as("event_hash at row index %d for transactionId=%s", i, transactionId)
                .isEqualTo(expectedHash);

            previousHash = storedEventHash;
        }
    }
}
