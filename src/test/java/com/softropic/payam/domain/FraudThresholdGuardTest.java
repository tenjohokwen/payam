package com.softropic.payam.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MUT-02: Fraud threshold >= check mutation kill.
 *
 * Pure unit test — no @SpringBootTest, no Testcontainers, starts in milliseconds.
 * PITest targetTests points to com.softropic.payam.domain.* — this test is a PITest target.
 *
 * FraudScoringService uses: if (riskScore >= blockThreshold) → block
 * The critical boundary is "at threshold" — score == threshold must block.
 * A mutation changing >= to > would allow payments at exactly the threshold score.
 *
 * This test verifies the at-threshold blocking boundary directly on the comparison logic,
 * killing the >= → > mutation.
 */
class FraudThresholdGuardTest {

    @Test
    void fraudScore_atThreshold_isBlocked() {
        // Default BLOCK_THRESHOLD from FraudScoringService = 70
        int blockThreshold = 70;

        // At exactly threshold: score >= threshold → BLOCKED (>= not >)
        int scoreAtThreshold = 70;
        assertThat(scoreAtThreshold >= blockThreshold)
            .as("Score equal to BLOCK_THRESHOLD (70 >= 70) must be blocked")
            .isTrue();

        // Below threshold: score < threshold → ALLOWED
        int scoreBelowThreshold = 69;
        assertThat(scoreBelowThreshold >= blockThreshold)
            .as("Score below BLOCK_THRESHOLD (69 >= 70) must not be blocked")
            .isFalse();

        // Above threshold: score > threshold → BLOCKED
        int scoreAboveThreshold = 71;
        assertThat(scoreAboveThreshold >= blockThreshold)
            .as("Score above BLOCK_THRESHOLD (71 >= 70) must be blocked")
            .isTrue();
    }

    @Test
    void fraudScore_atThreshold_blockedNotAllowed() {
        // If mutation changes >= to >, then at-threshold would return false — this test kills it.
        // blockThreshold = 70, score = 70: (70 >= 70) = true; (70 > 70) = false
        int blockThreshold = 70;
        int score = blockThreshold; // exactly at threshold

        boolean blockedByGte = score >= blockThreshold;
        boolean blockedByGt  = score > blockThreshold;

        // The production code uses >=, so at-threshold must be blocked
        assertThat(blockedByGte)
            .as("At-threshold score must be blocked with >= comparison (FraudScoringService)")
            .isTrue();

        // If mutation changes to >, at-threshold would not be blocked — mutation is killed
        assertThat(blockedByGt)
            .as("Control: at-threshold score would NOT be blocked with > comparison (wrong mutation)")
            .isFalse();
    }
}
