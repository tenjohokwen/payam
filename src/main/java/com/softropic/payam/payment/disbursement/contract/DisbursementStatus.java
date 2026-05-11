package com.softropic.payam.payment.disbursement.contract;

import com.softropic.payam.payment.ledger.contract.exception.IllegalStateTransitionException;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle state machine for Disbursement.
 *
 * Terminal states: SUCCESS, EXPIRED — no outbound transitions. FAILED has one outbound
 * transition (INITIATED) reserved for IDEM-02 retry recovery.
 *
 * <p>Two distinct gating states co-exist as of v11 Phase 54:
 * <ul>
 *   <li><b>PENDING_CONFIRMATION</b> — merchant step-up: amount &gt; 500,000 XAF requires the
 *       tenant to call /confirm before provider dispatch (SEC-04, v10).</li>
 *   <li><b>PENDING_ADMIN_APPROVAL</b> — Platform Ops approval: amount &gt; admin-approval
 *       threshold gates dispatch behind an internal approval (Phase 56 ADMIN-01..03).
 *       Auto-expires after admin-approval-timeout-hours; releases claims to RELEASED on expiry.</li>
 * </ul>
 */
public enum DisbursementStatus {

    INITIATED {
        @Override
        public Set<DisbursementStatus> allowedTransitions() {
            return EnumSet.of(PENDING_CONFIRMATION, PENDING_ADMIN_APPROVAL, PROCESSING, FAILED);
        }
    },
    PENDING_CONFIRMATION {
        @Override
        public Set<DisbursementStatus> allowedTransitions() {
            return EnumSet.of(PROCESSING, EXPIRED, FAILED);
        }
    },
    PENDING_ADMIN_APPROVAL {
        @Override
        public Set<DisbursementStatus> allowedTransitions() {
            // PENDING_ADMIN_APPROVAL → PROCESSING: Phase 56 ADMIN-01 admin approval path.
            // PENDING_ADMIN_APPROVAL → EXPIRED: Phase 56 ADMIN-03 approval timeout path.
            return EnumSet.of(PROCESSING, EXPIRED);
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
            // IDEM-02: retry recovery transitions FAILED back to INITIATED.
            // FAILED remains terminal for SUCCESS/PROCESSING — only INITIATED is reachable
            // (and only via DisbursementOrchestrator.handleRetry under PESSIMISTIC_WRITE lock).
            return EnumSet.of(INITIATED);
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
