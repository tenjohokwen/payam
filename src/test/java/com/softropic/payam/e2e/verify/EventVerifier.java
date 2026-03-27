package com.softropic.payam.e2e.verify;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VERIF-04: Asserts payment_event_log row counts and event type sequences per transactionId.
 *
 * Note: This verifier queries main.payment_event_log — the application's own event log.
 * Spring Modulith outbox (spring_modulith_events) is NOT used in this project.
 */
public class EventVerifier {

    private final JdbcTemplate jdbc;

    public EventVerifier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Asserts the exact number of event log rows for the given transactionId.
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
     * Loads event_type values in id ASC order and asserts they exactly match expectedEventTypes
     * (order and count both matter).
     */
    public void assertEventSequence(String transactionId, List<String> expectedEventTypes) {
        List<String> actualTypes = jdbc.queryForList(
            "SELECT event_type FROM main.payment_event_log WHERE transaction_id = ? ORDER BY id ASC",
            String.class, transactionId);
        assertThat(actualTypes)
            .as("event sequence for transactionId=%s", transactionId)
            .isEqualTo(expectedEventTypes);
    }

    /**
     * Asserts at least one event_log row with the given event_type exists for the transactionId.
     */
    public void assertEventPresent(String transactionId, String eventType) {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM main.payment_event_log WHERE transaction_id = ? AND event_type = ?",
            Integer.class, transactionId, eventType);
        assertThat(count)
            .as("event_type=%s count for transactionId=%s", eventType, transactionId)
            .isGreaterThanOrEqualTo(1);
    }
}
