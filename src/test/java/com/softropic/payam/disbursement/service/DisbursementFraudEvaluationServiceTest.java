package com.softropic.payam.disbursement.service;

import com.softropic.payam.disbursement.repo.DisbursementRepository;
import com.softropic.payam.fraud.contract.FraudDecision;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for DisbursementFraudEvaluationService.
 *
 * All tests use mocks — no Redis or database required.
 * Each test verifies one fraud signal independently (or the strict > 80 boundary).
 *
 * SEC-03 coverage:
 *  - New recipient +15
 *  - Amount outlier (>3x tenant median) +30
 *  - Blocklist MSISDN +80
 *  - Block threshold: strictly > 80
 */
@ExtendWith(MockitoExtension.class)
class DisbursementFraudEvaluationServiceTest {

    @Mock
    private DisbursementRepository disbursementRepository;

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private SetOperations<String, String> setOps;

    @InjectMocks
    private DisbursementFraudEvaluationService service;

    private static final Long TENANT_ID = 1L;
    private static final String MSISDN = "+237690000001";
    private static final BigDecimal AMOUNT_100 = BigDecimal.valueOf(100);

    @BeforeEach
    void setUp() {
        // Wire setOps to the redis template — lenient so tests that don't need blocklist still compile
        lenient().when(redis.opsForSet()).thenReturn(setOps);
        // Default: not on blocklist
        lenient().when(setOps.isMember(anyString(), anyString())).thenReturn(false);
        // Default: existing recipient (count > 0) — no new-recipient signal
        lenient().when(disbursementRepository.countByTenantIdAndRecipientMsisdn(anyLong(), anyString()))
                .thenReturn(1L);
        // Default: empty history — skips outlier signal
        lenient().when(disbursementRepository.findSuccessfulAmountsForTenant(anyLong()))
                .thenReturn(Collections.emptyList());
    }

    // ─────────────────────── Signal 1: new recipient ───────────────────────────

    @Test
    void evaluate_brandNewRecipient_addsFifteenPoints() {
        when(disbursementRepository.countByTenantIdAndRecipientMsisdn(TENANT_ID, MSISDN)).thenReturn(0L);

        FraudDecision decision = service.evaluate(TENANT_ID, MSISDN, AMOUNT_100);

        // 15 points alone does NOT exceed 80 — should allow through
        assertThat(decision.blocked()).isFalse();
        assertThat(decision.riskScore()).isEqualTo(15);
    }

    @Test
    void evaluate_existingRecipient_zeroForNewRecipientSignal() {
        when(disbursementRepository.countByTenantIdAndRecipientMsisdn(TENANT_ID, MSISDN)).thenReturn(5L);

        FraudDecision decision = service.evaluate(TENANT_ID, MSISDN, AMOUNT_100);

        assertThat(decision.blocked()).isFalse();
        assertThat(decision.riskScore()).isEqualTo(0);
    }

    // ─────────────────────── Signal 2: amount outlier ──────────────────────────

    @Test
    void evaluate_amountAboveThreeXMedian_addsThirtyPoints() {
        // Provide 10 SUCCESS rows with median 1000 → 4x amount (4000) triggers +30
        List<BigDecimal> history = buildHistoryOfSize10WithMedian1000();
        when(disbursementRepository.findSuccessfulAmountsForTenant(TENANT_ID)).thenReturn(history);

        BigDecimal amount4x = BigDecimal.valueOf(4000);
        FraudDecision decision = service.evaluate(TENANT_ID, MSISDN, amount4x);

        assertThat(decision.blocked()).isFalse();
        assertThat(decision.riskScore()).isEqualTo(30);
    }

    @Test
    void evaluate_amountAtThreeXMedian_doesNotAddOutlierPoints() {
        // Exactly 3x median should NOT add +30 (strictly > 3x required)
        List<BigDecimal> history = buildHistoryOfSize10WithMedian1000();
        when(disbursementRepository.findSuccessfulAmountsForTenant(TENANT_ID)).thenReturn(history);

        BigDecimal amount3x = BigDecimal.valueOf(3000); // exactly 3x
        FraudDecision decision = service.evaluate(TENANT_ID, MSISDN, amount3x);

        assertThat(decision.blocked()).isFalse();
        assertThat(decision.riskScore()).isEqualTo(0);
    }

    @Test
    void evaluate_lessThanTenHistoricalDisbursements_skipsOutlierSignal() {
        // Only 9 rows — outlier signal must be skipped (< OUTLIER_MIN_HISTORY = 10)
        List<BigDecimal> nineRows = List.of(
                BigDecimal.valueOf(1), BigDecimal.valueOf(2), BigDecimal.valueOf(3),
                BigDecimal.valueOf(4), BigDecimal.valueOf(5), BigDecimal.valueOf(6),
                BigDecimal.valueOf(7), BigDecimal.valueOf(8), BigDecimal.valueOf(9)
        );
        when(disbursementRepository.findSuccessfulAmountsForTenant(TENANT_ID)).thenReturn(nineRows);

        // Even an extreme amount should not add +30 when history < 10
        BigDecimal extremeAmount = BigDecimal.valueOf(1_000_000);
        FraudDecision decision = service.evaluate(TENANT_ID, MSISDN, extremeAmount);

        assertThat(decision.blocked()).isFalse();
        assertThat(decision.riskScore()).isEqualTo(0);
    }

    // ─────────────────────── Signal 3: blocklist ───────────────────────────────

    @Test
    void evaluate_blocklistMsisdn_addsEightyPoints() {
        when(setOps.isMember("fraud:dsb:msisdn:blocklist", MSISDN)).thenReturn(true);

        FraudDecision decision = service.evaluate(TENANT_ID, MSISDN, AMOUNT_100);

        // 80 points — exactly at threshold (not > 80), so allowed through
        assertThat(decision.blocked()).isFalse();
        assertThat(decision.riskScore()).isEqualTo(80);
    }

    // ─────────────────────── Block boundary tests ──────────────────────────────

    @Test
    void evaluate_scoreOver80_returnsBlock() {
        // Blocklist (80) + new recipient (15) = 95 → blocked
        when(disbursementRepository.countByTenantIdAndRecipientMsisdn(TENANT_ID, MSISDN)).thenReturn(0L);
        when(setOps.isMember("fraud:dsb:msisdn:blocklist", MSISDN)).thenReturn(true);

        FraudDecision decision = service.evaluate(TENANT_ID, MSISDN, AMOUNT_100);

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.riskScore()).isEqualTo(95);
    }

    @Test
    void evaluate_scoreExactly80_returnsAllow() {
        // Blocklist alone = 80 — exactly at threshold, must allow (strictly > 80 to block)
        when(setOps.isMember("fraud:dsb:msisdn:blocklist", MSISDN)).thenReturn(true);

        FraudDecision decision = service.evaluate(TENANT_ID, MSISDN, AMOUNT_100);

        assertThat(decision.blocked()).isFalse();
        assertThat(decision.riskScore()).isEqualTo(80);
    }

    // ─────────────────────── Fail-open for Redis unavailability ────────────────

    @Test
    void evaluate_redisUnavailable_failsOpenForBlocklist() {
        // Redis throws an exception — blocklist signal contributes 0, no block
        when(redis.opsForSet()).thenThrow(new RuntimeException("Redis connection refused"));

        FraudDecision decision = service.evaluate(TENANT_ID, MSISDN, AMOUNT_100);

        assertThat(decision.blocked()).isFalse();
        // Blocklist signal contributes 0 due to fail-open
        assertThat(decision.riskScore()).isEqualTo(0);
    }

    // ─────────────────────── Helpers ───────────────────────────────────────────

    /**
     * Build a sorted list of 10 BigDecimal values whose median (average of 5th and 6th
     * elements for even count) equals 1000. Values: 100..900, 1000, 1000.
     */
    private List<BigDecimal> buildHistoryOfSize10WithMedian1000() {
        return List.of(
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(200),
                BigDecimal.valueOf(300),
                BigDecimal.valueOf(400),
                BigDecimal.valueOf(900),   // 5th element
                BigDecimal.valueOf(1100),  // 6th element — median = (900+1100)/2 = 1000
                BigDecimal.valueOf(1200),
                BigDecimal.valueOf(1300),
                BigDecimal.valueOf(1400),
                BigDecimal.valueOf(1500)
        );
    }
}
