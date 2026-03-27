package com.softropic.payam.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class TestDataCleaner {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;

    public void wipeAll() {
        transactionTemplate.execute(status -> {
            // Child tables first (FK order is mandatory — violating it causes constraint errors)
            jdbcTemplate.execute("DELETE FROM main.ledger_entry");
            jdbcTemplate.execute("DELETE FROM main.payment_event_log");
            jdbcTemplate.execute("DELETE FROM main.idempotency_key");
            jdbcTemplate.execute("DELETE FROM main.webhook_delivery_log");
            jdbcTemplate.execute("DELETE FROM main.reconciliation_discrepancy");
            jdbcTemplate.execute("DELETE FROM main.reconciliation_report");
            jdbcTemplate.execute("DELETE FROM main.transaction");
            // Preserve Flyway-seeded rows — deleting them breaks fee and fraud logic in subsequent tests
            jdbcTemplate.execute("DELETE FROM main.fee_rule WHERE id NOT IN (1)");
            jdbcTemplate.execute("DELETE FROM main.fraud_rule WHERE id NOT IN (1,2,3,4,5)");
            jdbcTemplate.execute("DELETE FROM main.tenant_api_key");
            jdbcTemplate.execute("DELETE FROM main.tenant");
            jdbcTemplate.execute("DELETE FROM main.sec");
            // DO NOT delete main.msisdn_prefix_route — Flyway V16 seeds it; all MSISDN routing fails without it
            return null;
        });
    }
}
