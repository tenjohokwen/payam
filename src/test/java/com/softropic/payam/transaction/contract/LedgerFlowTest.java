package com.softropic.payam.transaction.contract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerFlowTest {

    @Test
    void values_containsExactlyCollectionAndDisbursement() {
        assertThat(LedgerFlow.values()).containsExactly(LedgerFlow.COLLECTION, LedgerFlow.DISBURSEMENT);
    }

    @Test
    void valueOf_parsesCollection() {
        assertThat(LedgerFlow.valueOf("COLLECTION")).isEqualTo(LedgerFlow.COLLECTION);
    }

    @Test
    void valueOf_parsesDisbursement() {
        assertThat(LedgerFlow.valueOf("DISBURSEMENT")).isEqualTo(LedgerFlow.DISBURSEMENT);
    }
}
