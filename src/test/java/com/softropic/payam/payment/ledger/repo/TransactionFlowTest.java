package com.softropic.payam.payment.ledger.repo;

import com.softropic.payam.payment.core.contract.MobilePaymentProvider;
import com.softropic.payam.payment.ledger.contract.LedgerFlow;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SERVICE-06: Transaction.flow is nullable; getEffectiveFlow() returns COLLECTION when null.
 *
 * Pure in-memory unit test — does not touch the database. Validates the Java-side
 * null-coalescing contract described in the Phase 47 requirements.
 */
class TransactionFlowTest {

    private Transaction baseBuilder() {
        return Transaction.builder()
                .transactionId("txn-flow-test")
                .traceId("trace-flow-test")
                .tenantId(1L)
                .provider(MobilePaymentProvider.ORANGE)
                .amount(new BigDecimal("100.00"))
                .currency("XAF")
                .build();
    }

    @Test
    void getEffectiveFlow_returnsCollectionWhenFlowNull() {
        Transaction tx = baseBuilder();

        assertThat(tx.getFlow())
            .as("flow must remain null for builders that omit .flow(...) — pre-v9 row semantics")
            .isNull();
        assertThat(tx.getEffectiveFlow())
            .as("getEffectiveFlow() must default to COLLECTION when flow is null")
            .isEqualTo(LedgerFlow.COLLECTION);
    }

    @Test
    void getEffectiveFlow_returnsStoredValueWhenDisbursement() {
        Transaction tx = Transaction.builder()
                .transactionId("txn-flow-disb")
                .traceId("trace-flow-disb")
                .tenantId(1L)
                .provider(MobilePaymentProvider.ORANGE)
                .amount(new BigDecimal("100.00"))
                .currency("XAF")
                .flow(LedgerFlow.DISBURSEMENT)
                .build();

        assertThat(tx.getFlow()).isEqualTo(LedgerFlow.DISBURSEMENT);
        assertThat(tx.getEffectiveFlow()).isEqualTo(LedgerFlow.DISBURSEMENT);
    }

    @Test
    void getEffectiveFlow_returnsStoredValueWhenCollection() {
        Transaction tx = Transaction.builder()
                .transactionId("txn-flow-coll")
                .traceId("trace-flow-coll")
                .tenantId(1L)
                .provider(MobilePaymentProvider.ORANGE)
                .amount(new BigDecimal("100.00"))
                .currency("XAF")
                .flow(LedgerFlow.COLLECTION)
                .build();

        assertThat(tx.getFlow()).isEqualTo(LedgerFlow.COLLECTION);
        assertThat(tx.getEffectiveFlow()).isEqualTo(LedgerFlow.COLLECTION);
    }
}
