package com.softropic.payam.disbursement.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DisbursementRetryClassifier}.
 *
 * <p>No Spring context — manual instantiation per Pattern 1 (CONVENTIONS.md).
 * Covers all RETRIABLE and TERMINAL classification cases for IDEM-01/02/03.
 */
class DisbursementRetryClassifierTest {

    private final DisbursementRetryClassifier classifier = new DisbursementRetryClassifier();

    // ── RETRIABLE codes ──────────────────────────────────────────────────────────────

    @Test
    void classify_whenProviderError_thenRetriable() {
        assertThat(classifier.classify("PROVIDER_ERROR"))
            .isEqualTo(DisbursementRetryClassifier.Classification.RETRIABLE);
    }

    @Test
    void classify_whenProviderUnavailable_thenRetriable() {
        assertThat(classifier.classify("PROVIDER_UNAVAILABLE"))
            .isEqualTo(DisbursementRetryClassifier.Classification.RETRIABLE);
    }

    // ── TERMINAL codes ───────────────────────────────────────────────────────────────

    @Test
    void classify_whenRecipientNotFound_thenTerminal() {
        assertThat(classifier.classify("RECIPIENT_NOT_FOUND"))
            .isEqualTo(DisbursementRetryClassifier.Classification.TERMINAL);
    }

    @Test
    void classify_whenFraudBlock_thenTerminal() {
        assertThat(classifier.classify("FRAUD_BLOCK"))
            .isEqualTo(DisbursementRetryClassifier.Classification.TERMINAL);
    }

    @Test
    void classify_whenInvalidTransaction_thenTerminal() {
        assertThat(classifier.classify("INVALID_TRANSACTION"))
            .isEqualTo(DisbursementRetryClassifier.Classification.TERMINAL);
    }

    @Test
    void classify_whenTransactionClaimed_thenTerminal() {
        assertThat(classifier.classify("TRANSACTION_CLAIMED"))
            .isEqualTo(DisbursementRetryClassifier.Classification.TERMINAL);
    }

    @Test
    void classify_whenAmountMismatch_thenTerminal() {
        assertThat(classifier.classify("AMOUNT_MISMATCH"))
            .isEqualTo(DisbursementRetryClassifier.Classification.TERMINAL);
    }

    @Test
    void classify_whenInsufficientBalance_thenTerminal() {
        assertThat(classifier.classify("INSUFFICIENT_BALANCE"))
            .isEqualTo(DisbursementRetryClassifier.Classification.TERMINAL);
    }

    @Test
    void classify_whenVelocityExceeded_thenTerminal() {
        assertThat(classifier.classify("VELOCITY_EXCEEDED"))
            .isEqualTo(DisbursementRetryClassifier.Classification.TERMINAL);
    }

    @Test
    void classify_whenDailyLimitExceeded_thenTerminal() {
        assertThat(classifier.classify("DAILY_LIMIT_EXCEEDED"))
            .isEqualTo(DisbursementRetryClassifier.Classification.TERMINAL);
    }

    // ── Defensive cases ───────────────────────────────────────────────────────────────

    @Test
    void classify_whenNull_thenTerminal() {
        // null errorCode cannot be safely retried — conservative default
        assertThat(classifier.classify(null))
            .isEqualTo(DisbursementRetryClassifier.Classification.TERMINAL);
    }

    @Test
    void classify_whenUnknownCode_thenTerminal() {
        // Conservative default: unknown codes are TERMINAL (RESEARCH Open Question 1, Option B)
        assertThat(classifier.classify("FOO_BAR"))
            .isEqualTo(DisbursementRetryClassifier.Classification.TERMINAL);
    }
}
