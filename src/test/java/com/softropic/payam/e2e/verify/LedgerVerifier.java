package com.softropic.payam.e2e.verify;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VERIF-05: Asserts ledger entry correctness for a given transactionId.
 *
 * Account codes are hardcoded constants matching production LedgerService:
 *   DEBIT  → "CUSTOMER_WALLET"
 *   CREDIT → "PROVIDER_CLEARING"
 */
public class LedgerVerifier {

    private static final String DEBIT_ACCOUNT  = "CUSTOMER_WALLET";
    private static final String CREDIT_ACCOUNT = "PROVIDER_CLEARING";

    private final JdbcTemplate jdbc;

    public LedgerVerifier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Asserts that exactly 2 ledger entries exist for the transactionId:
     * one DEBIT on CUSTOMER_WALLET and one CREDIT on PROVIDER_CLEARING, both with equal amounts.
     */
    public void assertLedgerBalanced(String transactionId) {
        List<Map<String, Object>> entries = jdbc.queryForList(
            "SELECT direction, account_code, amount FROM main.ledger_entry WHERE transaction_id = ?",
            transactionId);

        assertThat(entries)
            .as("ledger entry count for transactionId=%s", transactionId)
            .hasSize(2);

        Map<String, Object> debitEntry = entries.stream()
            .filter(e -> "DEBIT".equals(e.get("direction")))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "No DEBIT ledger entry found for transactionId=" + transactionId));

        Map<String, Object> creditEntry = entries.stream()
            .filter(e -> "CREDIT".equals(e.get("direction")))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "No CREDIT ledger entry found for transactionId=" + transactionId));

        assertThat(debitEntry.get("account_code"))
            .as("DEBIT account_code for transactionId=%s", transactionId)
            .isEqualTo(DEBIT_ACCOUNT);

        assertThat(creditEntry.get("account_code"))
            .as("CREDIT account_code for transactionId=%s", transactionId)
            .isEqualTo(CREDIT_ACCOUNT);

        BigDecimal debitAmount  = toBigDecimal(debitEntry.get("amount"));
        BigDecimal creditAmount = toBigDecimal(creditEntry.get("amount"));

        assertThat(debitAmount.compareTo(creditAmount))
            .as("DEBIT amount must equal CREDIT amount for transactionId=%s " +
                "(debit=%s, credit=%s)", transactionId, debitAmount, creditAmount)
            .isEqualTo(0);
    }

    /**
     * Asserts that exactly 3 ledger entries exist for the transactionId representing a DISBURSEMENT group:
     *   DEBIT  MERCHANT_WALLET  amount = principal + fee
     *   CREDIT CUSTOMER_WALLET  amount = principal
     *   CREDIT PROVIDER_FEE     amount = fee
     * All 3 share one entry_group_id and sum(CREDIT) equals DEBIT.
     * Used by Phase 49 cashout E2E tests.
     */
    public void assertDisbursementLedgerBalanced(String transactionId,
                                                 BigDecimal principal,
                                                 BigDecimal fee) {
        List<Map<String, Object>> entries = jdbc.queryForList(
            "SELECT direction, account_code, amount FROM main.ledger_entry WHERE transaction_id = ?",
            transactionId);

        assertThat(entries)
            .as("disbursement ledger entry count for transactionId=%s", transactionId)
            .hasSize(3);

        Map<String, Object> debit = entries.stream()
            .filter(e -> "DEBIT".equals(e.get("direction")))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "No DEBIT ledger entry found for transactionId=" + transactionId));

        List<Map<String, Object>> credits = entries.stream()
            .filter(e -> "CREDIT".equals(e.get("direction")))
            .toList();

        assertThat(credits)
            .as("disbursement CREDIT count for transactionId=%s", transactionId)
            .hasSize(2);

        BigDecimal gross = principal.add(fee);

        assertThat(debit.get("account_code"))
            .as("DEBIT account_code for transactionId=%s", transactionId)
            .isEqualTo("MERCHANT_WALLET");
        assertThat(toBigDecimal(debit.get("amount")))
            .as("DEBIT amount must equal principal + fee for transactionId=%s", transactionId)
            .isEqualByComparingTo(gross);

        BigDecimal creditSum = credits.stream()
            .map(e -> toBigDecimal(e.get("amount")))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(creditSum)
            .as("sum(CREDIT amounts) must equal DEBIT (balanced) for transactionId=%s", transactionId)
            .isEqualByComparingTo(gross);

        assertThat(credits)
            .as("one CREDIT must be CUSTOMER_WALLET = principal for transactionId=%s", transactionId)
            .anyMatch(e -> "CUSTOMER_WALLET".equals(e.get("account_code"))
                && toBigDecimal(e.get("amount")).compareTo(principal) == 0);
        assertThat(credits)
            .as("one CREDIT must be PROVIDER_FEE = fee for transactionId=%s", transactionId)
            .anyMatch(e -> "PROVIDER_FEE".equals(e.get("account_code"))
                && toBigDecimal(e.get("amount")).compareTo(fee) == 0);
    }

    /**
     * Asserts the exact number of ledger entries for the given transactionId.
     */
    public void assertEntryCount(String transactionId, int expectedCount) {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM main.ledger_entry WHERE transaction_id = ?",
            Integer.class, transactionId);
        assertThat(count)
            .as("ledger entry count for transactionId=%s", transactionId)
            .isEqualTo(expectedCount);
    }

    /**
     * Asserts 0 ledger entries exist for the transactionId.
     * Used for FAILED and fraud-blocked flows where no ledger posting should occur.
     */
    public void assertNoLedgerEntries(String transactionId) {
        assertEntryCount(transactionId, 0);
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        return new BigDecimal(value.toString());
    }
}
