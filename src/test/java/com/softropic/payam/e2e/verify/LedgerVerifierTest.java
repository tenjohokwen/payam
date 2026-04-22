package com.softropic.payam.e2e.verify;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LedgerVerifier}.
 *
 * These tests mock {@link JdbcTemplate} — the goal is to prove the verifier's
 * assertion logic is correct, independent of any DB wiring.
 *
 * TEST-07: confirms that {@code assertDisbursementLedgerBalanced} correctly shapes
 * its assertions for the 3-entry DISBURSEMENT ledger group, and that the pre-existing
 * {@code assertLedgerBalanced} (COLLECTION, 2 entries) is unchanged.
 */
class LedgerVerifierTest {

    private static final String TXN_ID = "txn-verifier-001";

    private static Map<String, Object> row(String direction, String accountCode, String amount) {
        Map<String, Object> r = new HashMap<>();
        r.put("direction", direction);
        r.put("account_code", accountCode);
        r.put("amount", new BigDecimal(amount));
        return r;
    }

    @Test
    void assertDisbursementLedgerBalanced_acceptsBalancedThreeEntryGroup() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
            .thenReturn(List.of(
                row("DEBIT",  "MERCHANT_WALLET", "1050.00"),
                row("CREDIT", "CUSTOMER_WALLET", "1000.00"),
                row("CREDIT", "PROVIDER_FEE",    "50.00")
            ));

        LedgerVerifier verifier = new LedgerVerifier(jdbc);

        // happy path — no exception thrown
        verifier.assertDisbursementLedgerBalanced(
            TXN_ID,
            new BigDecimal("1000.00"),
            new BigDecimal("50.00"));
    }

    @Test
    void assertDisbursementLedgerBalanced_acceptsZeroFeeThreeEntryGroup() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
            .thenReturn(List.of(
                row("DEBIT",  "MERCHANT_WALLET", "1000.00"),
                row("CREDIT", "CUSTOMER_WALLET", "1000.00"),
                row("CREDIT", "PROVIDER_FEE",    "0.00")
            ));

        LedgerVerifier verifier = new LedgerVerifier(jdbc);

        // fee = 0 path — should also succeed (V25 CHECK (amount >= 0))
        verifier.assertDisbursementLedgerBalanced(
            TXN_ID,
            new BigDecimal("1000.00"),
            BigDecimal.ZERO);
    }

    @Test
    void assertDisbursementLedgerBalanced_rejectsWrongEntryCount() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
            .thenReturn(List.of(
                row("DEBIT",  "CUSTOMER_WALLET",   "1000.00"),
                row("CREDIT", "PROVIDER_CLEARING", "1000.00")
            ));

        LedgerVerifier verifier = new LedgerVerifier(jdbc);

        assertThatThrownBy(() -> verifier.assertDisbursementLedgerBalanced(
                TXN_ID, new BigDecimal("1000.00"), new BigDecimal("50.00")))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("disbursement ledger entry count")
            .hasMessageContaining(TXN_ID);
    }

    @Test
    void assertDisbursementLedgerBalanced_rejectsUnbalancedAmounts() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // CREDITs sum to 999.00, DEBIT is 1050.00 -> unbalanced
        when(jdbc.queryForList(anyString(), any(Object[].class)))
            .thenReturn(List.of(
                row("DEBIT",  "MERCHANT_WALLET", "1050.00"),
                row("CREDIT", "CUSTOMER_WALLET", "999.00"),
                row("CREDIT", "PROVIDER_FEE",    "0.00")
            ));

        LedgerVerifier verifier = new LedgerVerifier(jdbc);

        assertThatThrownBy(() -> verifier.assertDisbursementLedgerBalanced(
                TXN_ID, new BigDecimal("1000.00"), new BigDecimal("50.00")))
            .isInstanceOf(AssertionError.class);
    }

    @Test
    void assertLedgerBalanced_stillAcceptsCollectionPair() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
            .thenReturn(List.of(
                row("DEBIT",  "CUSTOMER_WALLET",   "500.00"),
                row("CREDIT", "PROVIDER_CLEARING", "500.00")
            ));

        LedgerVerifier verifier = new LedgerVerifier(jdbc);

        // Regression: proves existing collection helper is unchanged by this plan.
        verifier.assertLedgerBalanced(TXN_ID);

        // sanity — the verifier under test is the same instance; no static state leaks
        assertThat(verifier).isNotNull();
    }
}
