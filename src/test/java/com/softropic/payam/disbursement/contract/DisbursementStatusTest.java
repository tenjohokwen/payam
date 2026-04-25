package com.softropic.payam.disbursement.contract;

import com.softropic.payam.transaction.contract.exception.IllegalStateTransitionException;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DisbursementStatusTest {

    @Test
    void allValuesDeclared() {
        assertThat(DisbursementStatus.values())
            .containsExactlyInAnyOrder(
                DisbursementStatus.INITIATED,
                DisbursementStatus.PENDING_CONFIRMATION,
                DisbursementStatus.PROCESSING,
                DisbursementStatus.SUCCESS,
                DisbursementStatus.FAILED,
                DisbursementStatus.EXPIRED);
    }

    @Test
    void initiatedAllowedTransitions() {
        assertThat(DisbursementStatus.INITIATED.allowedTransitions())
            .containsExactlyInAnyOrderElementsOf(
                EnumSet.of(DisbursementStatus.PENDING_CONFIRMATION,
                           DisbursementStatus.PROCESSING,
                           DisbursementStatus.FAILED));
    }

    @Test
    void pendingConfirmationAllowedTransitions() {
        assertThat(DisbursementStatus.PENDING_CONFIRMATION.allowedTransitions())
            .containsExactlyInAnyOrderElementsOf(
                EnumSet.of(DisbursementStatus.PROCESSING,
                           DisbursementStatus.EXPIRED,
                           DisbursementStatus.FAILED));
    }

    @Test
    void processingAllowedTransitions() {
        assertThat(DisbursementStatus.PROCESSING.allowedTransitions())
            .containsExactlyInAnyOrderElementsOf(
                EnumSet.of(DisbursementStatus.SUCCESS,
                           DisbursementStatus.FAILED,
                           DisbursementStatus.EXPIRED));
    }

    @Test
    void successIsTerminal() {
        assertThat(DisbursementStatus.SUCCESS.allowedTransitions()).isEmpty();
    }

    @Test
    void failedIsTerminal() {
        assertThat(DisbursementStatus.FAILED.allowedTransitions()).isEmpty();
    }

    @Test
    void expiredIsTerminal() {
        // BAL-03: EXPIRED is a terminal state; reserved balance is NOT released automatically.
        assertThat(DisbursementStatus.EXPIRED.allowedTransitions()).isEmpty();
    }

    @Test
    void legalTransitionReturnsNext() {
        assertThat(DisbursementStatus.INITIATED.transitionTo(DisbursementStatus.PROCESSING))
            .isEqualTo(DisbursementStatus.PROCESSING);
    }

    @Test
    void pendingConfirmationToExpiredSucceeds() {
        // SEC-04 step-up timeout: PENDING_CONFIRMATION -> EXPIRED must be legal
        assertThat(DisbursementStatus.PENDING_CONFIRMATION.transitionTo(DisbursementStatus.EXPIRED))
            .isEqualTo(DisbursementStatus.EXPIRED);
    }

    @Test
    void processingToExpiredSucceeds() {
        // BAL-03: provider accepted, internal error — PROCESSING -> EXPIRED must be legal
        assertThat(DisbursementStatus.PROCESSING.transitionTo(DisbursementStatus.EXPIRED))
            .isEqualTo(DisbursementStatus.EXPIRED);
    }

    @Test
    void initiatedToSuccessThrows() {
        assertThatThrownBy(() -> DisbursementStatus.INITIATED.transitionTo(DisbursementStatus.SUCCESS))
            .isInstanceOf(IllegalStateTransitionException.class)
            .hasMessageContaining("INITIATED")
            .hasMessageContaining("SUCCESS");
    }

    @Test
    void expiredTransitionToAnythingThrows() {
        for (DisbursementStatus next : DisbursementStatus.values()) {
            if (next == DisbursementStatus.EXPIRED) continue;
            assertThatThrownBy(() -> DisbursementStatus.EXPIRED.transitionTo(next))
                .as("EXPIRED -> %s must throw", next)
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("EXPIRED");
        }
    }

    @Test
    void successTransitionToAnythingThrows() {
        for (DisbursementStatus next : DisbursementStatus.values()) {
            if (next == DisbursementStatus.SUCCESS) continue;
            assertThatThrownBy(() -> DisbursementStatus.SUCCESS.transitionTo(next))
                .isInstanceOf(IllegalStateTransitionException.class);
        }
    }

    @Test
    void failedTransitionToProcessingThrows() {
        assertThatThrownBy(() -> DisbursementStatus.FAILED.transitionTo(DisbursementStatus.PROCESSING))
            .isInstanceOf(IllegalStateTransitionException.class);
    }
}
