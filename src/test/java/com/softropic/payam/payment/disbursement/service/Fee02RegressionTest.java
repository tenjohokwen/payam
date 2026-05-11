package com.softropic.payam.payment.disbursement.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEE-02 structural regression: any {@code Transaction} row written for a disbursement
 * payout (i.e. with {@code flow = DISBURSEMENT}) MUST have {@code feeAmount = 0} and
 * {@code feeRuleId = null}.
 *
 * <p>This class contains two static-analysis tests:
 * <ol>
 *   <li>{@link #noTransactionBuilderWithDisbursementFlowViolatesFee02} — scans
 *       {@code src/main/java} for any {@code Transaction.builder()} chain that sets
 *       {@code .flow(LedgerFlow.DISBURSEMENT)} and asserts the same chain either
 *       explicitly zeroes {@code feeAmount} + nulls {@code feeRuleId}, or omits those
 *       fields (default null). The current codebase has zero such chains — the only
 *       production builder call in {@code TransactionService} creates COLLECTION-flow rows
 *       and the disbursement provider ports write ledger entries via {@code LedgerService}
 *       instead of Transaction rows. This test pins that structural property forward.</li>
 *   <li>{@link #disbursementProviderPortsDoNotCreateTransactionRows} — checks that neither
 *       {@code OrangeMoneyPort} nor {@code MtnMoMoPort} contains a {@code Transaction.builder()}
 *       call anywhere. These files handle the provider-side disbursement payout and must route
 *       through {@code LedgerService} only. Any future change that introduces a
 *       {@code Transaction.builder()} in those files must also satisfy FEE-02 or this test
 *       will fail first, prompting the developer to add the appropriate fee fields.</li>
 * </ol>
 *
 * <p>Both tests run as plain unit tests (no Spring context, no Docker) and complete in
 * milliseconds.
 */
class Fee02RegressionTest {

    private static final Path SRC_MAIN_JAVA = Paths.get("src/main/java");

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 1: FEE-02 — no Transaction.builder() with flow=DISBURSEMENT has non-zero fee
    // ────────────────────────────────────────────────────────────────────────────────

    /**
     * Scans all {@code .java} files under {@code src/main/java} for
     * {@code Transaction.builder()} chains that include {@code .flow(...DISBURSEMENT)}.
     *
     * <p>For each such chain, asserts FEE-02: the same chain must either explicitly set
     * {@code .feeAmount(BigDecimal.ZERO)} / {@code .feeAmount(null)}, or omit the field
     * (default null). Similarly for {@code feeRuleId}.
     *
     * <p>Currently the test passes trivially (zero DISBURSEMENT-flow Transaction.builder
     * chains exist). Future code that introduces such a chain MUST satisfy FEE-02 or this
     * test fails.
     */
    @Test
    void noTransactionBuilderWithDisbursementFlowViolatesFee02() throws IOException {
        // Pattern matches a Transaction.builder() chain up to .build() spanning multiple lines.
        // The {0,2000} bound prevents catastrophic backtracking on large files.
        Pattern builderChain = Pattern.compile(
            "Transaction\\.builder\\(\\)([\\s\\S]{0,2000}?)\\.build\\(\\)",
            Pattern.MULTILINE);

        try (Stream<Path> paths = Files.walk(SRC_MAIN_JAVA)) {
            List<String> violations = paths
                .filter(p -> p.toString().endsWith(".java"))
                .flatMap(p -> {
                    try {
                        String content = Files.readString(p);
                        Matcher m = builderChain.matcher(content);
                        return m.results().map(r -> {
                            String chain = r.group(0);
                            if (!chain.contains("DISBURSEMENT")) {
                                return null;  // not a DISBURSEMENT-flow chain
                            }
                            // Found a Transaction.builder() chain setting flow=DISBURSEMENT.
                            // FEE-02: feeAmount must be zero or absent; feeRuleId must be null or absent.
                            boolean zerosFeeAmount = chain.contains(".feeAmount(BigDecimal.ZERO)")
                                || chain.contains(".feeAmount(null)")
                                || !chain.contains(".feeAmount(");  // omitted = default null (FEE-02 compliant)
                            boolean nullsFeeRuleId = chain.contains(".feeRuleId(null)")
                                || !chain.contains(".feeRuleId(");  // omitted = default null (FEE-02 compliant)
                            if (!zerosFeeAmount || !nullsFeeRuleId) {
                                return p + ":\n" + chain;
                            }
                            return null;
                        }).filter(s -> s != null);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to read " + p, e);
                    }
                })
                .toList();

            assertThat(violations)
                .as("FEE-02: no flow=DISBURSEMENT Transaction.builder() with non-zero feeAmount or non-null feeRuleId")
                .isEmpty();
        }
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 2: disbursement provider ports must not create Transaction rows
    // ────────────────────────────────────────────────────────────────────────────────

    /**
     * FEE-02 structural property: the disbursement initiate path on both provider ports
     * ({@code OrangeMoneyPort.initiateDisbursement} and
     * {@code MtnMoMoPort.initiateDisbursement}) posts ledger entries via
     * {@code LedgerService} — it does NOT create {@code Transaction} rows.
     *
     * <p>If a future change introduces a {@code Transaction.builder()} call in either port
     * file, this test will fail, reminding the developer to also ensure FEE-02 compliance
     * (feeAmount=0, feeRuleId=null for any DISBURSEMENT-flow row).
     */
    @Test
    void disbursementProviderPortsDoNotCreateTransactionRows() throws IOException {
        Path orange = SRC_MAIN_JAVA.resolve(
            "com/softropic/payam/orange/service/OrangeMoneyPort.java");
        Path mtn = SRC_MAIN_JAVA.resolve(
            "com/softropic/payam/payment/provider/mtn/service/MtnMoMoPort.java");

        for (Path port : List.of(orange, mtn)) {
            String content = Files.readString(port);
            assertThat(content)
                .as("FEE-02: " + port.getFileName() + " must not create Transaction rows " +
                    "(no Transaction.builder() call in this file)")
                .doesNotContain("Transaction.builder()");
        }
    }
}
