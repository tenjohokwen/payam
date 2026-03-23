package com.softropic.payam.transaction.contract;

import com.softropic.payam.transaction.contract.exception.IllegalStateTransitionException;

import java.util.EnumSet;
import java.util.Set;

public enum TransactionStatus {

    INITIATED {
        @Override
        public Set<TransactionStatus> allowedTransitions() {
            return EnumSet.of(AUTH_PENDING, FAILED);
        }
    },
    AUTH_PENDING {
        @Override
        public Set<TransactionStatus> allowedTransitions() {
            return EnumSet.of(AUTHORIZED, FAILED);
        }
    },
    AUTHORIZED {
        @Override
        public Set<TransactionStatus> allowedTransitions() {
            return EnumSet.of(PROCESSING, FAILED);
        }
    },
    PROCESSING {
        @Override
        public Set<TransactionStatus> allowedTransitions() {
            return EnumSet.of(SUCCESS, FAILED, REVERSED);
        }
    },
    SUCCESS {
        @Override
        public Set<TransactionStatus> allowedTransitions() {
            return EnumSet.noneOf(TransactionStatus.class);
        }
    },
    FAILED {
        @Override
        public Set<TransactionStatus> allowedTransitions() {
            return EnumSet.noneOf(TransactionStatus.class);
        }
    },
    REVERSED {
        @Override
        public Set<TransactionStatus> allowedTransitions() {
            return EnumSet.noneOf(TransactionStatus.class);
        }
    };

    public abstract Set<TransactionStatus> allowedTransitions();

    public TransactionStatus transitionTo(TransactionStatus next) {
        if (!allowedTransitions().contains(next)) {
            throw new IllegalStateTransitionException(
                "Invalid state transition: " + this.name() + " -> " + next.name()
                + ". Allowed transitions from " + this.name() + ": " + allowedTransitions()
            );
        }
        return next;
    }
}
