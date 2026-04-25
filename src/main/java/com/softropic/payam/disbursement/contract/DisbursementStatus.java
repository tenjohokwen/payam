package com.softropic.payam.disbursement.contract;

import com.softropic.payam.transaction.contract.exception.IllegalStateTransitionException;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle state machine for Disbursement.
 *
 * Terminal states: SUCCESS, FAILED, EXPIRED — no outbound transitions.
 *
 * EXPIRED semantics (BAL-03): distinct from FAILED.
 *  - FAILED: provider never accepted or definitively rejected; reserved balance MUST be released.
 *  - EXPIRED: either (a) PENDING_CONFIRMATION aged past the 15-minute confirm window (SEC-04),
 *             or (b) provider accepted but an internal error (e.g. ledger write failure) prevents
 *             clean state update. Reserved balance is held pending manual ops resolution.
 */
public enum DisbursementStatus {

    INITIATED {
        @Override
        public Set<DisbursementStatus> allowedTransitions() {
            return EnumSet.of(PENDING_CONFIRMATION, PROCESSING, FAILED);
        }
    },
    PENDING_CONFIRMATION {
        @Override
        public Set<DisbursementStatus> allowedTransitions() {
            return EnumSet.of(PROCESSING, EXPIRED, FAILED);
        }
    },
    PROCESSING {
        @Override
        public Set<DisbursementStatus> allowedTransitions() {
            return EnumSet.of(SUCCESS, FAILED, EXPIRED);
        }
    },
    SUCCESS {
        @Override
        public Set<DisbursementStatus> allowedTransitions() {
            return EnumSet.noneOf(DisbursementStatus.class);
        }
    },
    FAILED {
        @Override
        public Set<DisbursementStatus> allowedTransitions() {
            return EnumSet.noneOf(DisbursementStatus.class);
        }
    },
    EXPIRED {
        @Override
        public Set<DisbursementStatus> allowedTransitions() {
            return EnumSet.noneOf(DisbursementStatus.class);
        }
    };

    public abstract Set<DisbursementStatus> allowedTransitions();

    public DisbursementStatus transitionTo(DisbursementStatus next) {
        if (!allowedTransitions().contains(next)) {
            throw new IllegalStateTransitionException(
                "Invalid disbursement state transition: " + this.name() + " -> " + next.name()
                + ". Allowed transitions from " + this.name() + ": " + allowedTransitions()
            );
        }
        return next;
    }
}
