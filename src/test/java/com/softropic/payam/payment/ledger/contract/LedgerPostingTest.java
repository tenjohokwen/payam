package com.softropic.payam.payment.ledger.contract;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerPostingTest {

    @Test
    void collection_setsFlowAndZeroFee() {
        LedgerPosting p = LedgerPosting.collection(new BigDecimal("100.00"), "XAF");
        assertThat(p.flow()).isEqualTo(LedgerFlow.COLLECTION);
        assertThat(p.principal()).isEqualByComparingTo("100.00");
        assertThat(p.fee()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(p.currency()).isEqualTo("XAF");
    }

    @Test
    void disbursement_setsFlowAndFee() {
        LedgerPosting p = LedgerPosting.disbursement(new BigDecimal("100.00"), new BigDecimal("5.00"), "XAF");
        assertThat(p.flow()).isEqualTo(LedgerFlow.DISBURSEMENT);
        assertThat(p.principal()).isEqualByComparingTo("100.00");
        assertThat(p.fee()).isEqualByComparingTo("5.00");
        assertThat(p.currency()).isEqualTo("XAF");
    }

    @Test
    void disbursement_zeroFee_allowed() {
        LedgerPosting p = LedgerPosting.disbursement(new BigDecimal("100.00"), BigDecimal.ZERO, "XAF");
        assertThat(p.fee()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void constructor_rejectsNullFlow() {
        assertThatThrownBy(() -> new LedgerPosting(null, BigDecimal.ONE, BigDecimal.ZERO, "XAF"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flow");
    }

    @Test
    void constructor_rejectsNullCurrency() {
        assertThatThrownBy(() -> new LedgerPosting(LedgerFlow.COLLECTION, BigDecimal.ONE, BigDecimal.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
    }

    @Test
    void constructor_rejectsNullPrincipal() {
        assertThatThrownBy(() -> new LedgerPosting(LedgerFlow.COLLECTION, null, BigDecimal.ZERO, "XAF"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("principal");
    }

    @Test
    void constructor_rejectsZeroPrincipal() {
        assertThatThrownBy(() -> new LedgerPosting(LedgerFlow.COLLECTION, BigDecimal.ZERO, BigDecimal.ZERO, "XAF"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("principal");
    }

    @Test
    void constructor_rejectsNegativePrincipal() {
        assertThatThrownBy(() -> new LedgerPosting(LedgerFlow.COLLECTION, new BigDecimal("-1.00"), BigDecimal.ZERO, "XAF"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("principal");
    }

    @Test
    void constructor_rejectsNullFee() {
        assertThatThrownBy(() -> new LedgerPosting(LedgerFlow.COLLECTION, BigDecimal.ONE, null, "XAF"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fee");
    }

    @Test
    void constructor_rejectsNegativeFee() {
        assertThatThrownBy(() -> new LedgerPosting(LedgerFlow.COLLECTION, BigDecimal.ONE, new BigDecimal("-0.01"), "XAF"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fee");
    }
}
