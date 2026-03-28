package com.softropic.payam.domain;

import com.softropic.payam.transaction.contract.TransactionStatus;
import com.softropic.payam.transaction.contract.exception.IllegalStateTransitionException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MUT-02: INITIATED -> SUCCESS guard mutation kill.
 *
 * Pure unit test — no @SpringBootTest, no Testcontainers, starts in milliseconds.
 * PITest targetTests points to com.softropic.payam.domain.* — this test is a PITest target.
 *
 * Kills the mutation that removes the INITIATED->SUCCESS guard in the state machine,
 * e.g., changes allowedTransitions() to include SUCCESS for INITIATED.
 */
class TransactionStatusGuardTest {

    @Test
    void initiatedToSuccess_isIllegal() {
        // INITIATED -> SUCCESS is not in INITIATED.allowedTransitions() (only AUTH_PENDING, FAILED)
        // If the mutation removes this guard, transitionTo() would succeed — this test catches it.
        assertThatThrownBy(() -> TransactionStatus.INITIATED.transitionTo(TransactionStatus.SUCCESS))
            .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void initiatedToAuthPending_isLegal() {
        // Control assertion — proves the guard does not block valid transitions
        TransactionStatus result = TransactionStatus.INITIATED.transitionTo(TransactionStatus.AUTH_PENDING);
        org.assertj.core.api.Assertions.assertThat(result).isEqualTo(TransactionStatus.AUTH_PENDING);
    }
}
