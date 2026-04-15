package com.softropic.payam.transaction;

import com.softropic.payam.config.TestConfig;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"ledger.database.spy=true", "enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
class LedgerConstraintIT {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    @Test
    void unbalancedInsert_isRejectedByConstraint() {
        // Intentionally empty — Task 2 fills the body. Placeholder for Failsafe discovery.
        org.junit.jupiter.api.Assertions.assertTrue(true, "stub — real body in Task 2");
    }

    @Test
    void balancedInsert_succeeds() {
        // Intentionally empty — Task 2 fills the body.
        org.junit.jupiter.api.Assertions.assertTrue(true, "stub — real body in Task 2");
    }
}
