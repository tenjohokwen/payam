package com.softropic.payam.disbursement.service;

import com.softropic.payam.disbursement.repo.DisbursementRepository;
import com.softropic.payam.fraud.contract.FraudDecision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Disbursement-specific fraud evaluator (SEC-03).
 *
 * <p>Computes a risk score from three independent signals on top of the existing
 * collection-side {@code FraudScoringService} (which MUST NOT be modified):
 * <ul>
 *   <li><b>New recipient MSISDN</b> — no prior successful disbursement to this MSISDN for
 *       this tenant: +{@value #NEW_RECIPIENT_WEIGHT}</li>
 *   <li><b>Amount outlier</b> — amount &gt; 3x tenant median SUCCESS payout: +{@value #OUTLIER_WEIGHT}
 *       (skipped when tenant has fewer than {@value #OUTLIER_MIN_HISTORY} historical SUCCESS rows
 *       to avoid penalising new tenants)</li>
 *   <li><b>Blocklist MSISDN</b> — recipient is a member of Redis SET
 *       {@code fraud:dsb:msisdn:blocklist}: +{@value #BLOCKLIST_WEIGHT}</li>
 * </ul>
 *
 * <p>Block threshold is <em>strictly greater than</em> {@value #BLOCK_THRESHOLD} — a score of
 * exactly 80 ALLOWS through. The blocklist signal alone (80 points) is therefore not blocking
 * by itself; combine with any other signal to trigger a block.
 *
 * <p>Redis failures are handled fail-open: if {@code isMember} throws, the blocklist signal
 * contributes 0 points and a WARN is logged. This preserves availability when Redis is
 * temporarily unreachable.
 *
 * <p>NOT {@code @Transactional} — read-only DB query + Redis SISMEMBER; no DB writes.
 */
@Service
public class DisbursementFraudEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(DisbursementFraudEvaluationService.class);

    /** Strictly-greater threshold: score > BLOCK_THRESHOLD → block. */
    static final int BLOCK_THRESHOLD = 80;

    /** Points added when recipient MSISDN has no prior disbursements for this tenant. */
    static final int NEW_RECIPIENT_WEIGHT = 15;

    /** Points added when amount exceeds 3x the tenant's median payout. */
    static final int OUTLIER_WEIGHT = 30;

    /** Points added when recipient MSISDN is on the Redis blocklist. */
    static final int BLOCKLIST_WEIGHT = 80;

    /** Minimum number of historical SUCCESS rows required to compute the outlier signal. */
    static final int OUTLIER_MIN_HISTORY = 10;

    /** Amount must be strictly > median * OUTLIER_MULTIPLIER to trigger the outlier signal. */
    static final BigDecimal OUTLIER_MULTIPLIER = BigDecimal.valueOf(3);

    /** Redis SET key holding blocked disbursement recipient MSISDNs. */
    static final String BLOCKLIST_KEY = "fraud:dsb:msisdn:blocklist";

    private final DisbursementRepository disbursementRepository;
    private final StringRedisTemplate redis;

    public DisbursementFraudEvaluationService(DisbursementRepository disbursementRepository,
                                              StringRedisTemplate redis) {
        this.disbursementRepository = disbursementRepository;
        this.redis = redis;
    }

    /**
     * Evaluate the three disbursement-specific fraud signals for a candidate disbursement.
     *
     * @param tenantId        the owning tenant
     * @param recipientMsisdn the destination MSISDN
     * @param amount          the requested disbursement amount
     * @return {@link FraudDecision#block} when score &gt; 80; {@link FraudDecision#allow} otherwise
     */
    public FraudDecision evaluate(Long tenantId, String recipientMsisdn, BigDecimal amount) {
        int score = 0;
        String firstReason = null;

        // ── Signal 1: new recipient ──────────────────────────────────────────────────
        long priorCount = disbursementRepository.countByTenantIdAndRecipientMsisdn(tenantId, recipientMsisdn);
        if (priorCount == 0) {
            score += NEW_RECIPIENT_WEIGHT;
            firstReason = "NEW_RECIPIENT";
        }

        // ── Signal 2: amount outlier vs tenant SUCCESS median ─────────────────────────
        List<BigDecimal> historical = disbursementRepository.findSuccessfulAmountsForTenant(tenantId);
        if (historical.size() >= OUTLIER_MIN_HISTORY) {
            BigDecimal median = computeMedian(historical);
            BigDecimal threshold = median.multiply(OUTLIER_MULTIPLIER);
            if (amount.compareTo(threshold) > 0) {
                score += OUTLIER_WEIGHT;
                if (firstReason == null) {
                    firstReason = "AMOUNT_OUTLIER";
                }
            }
        }

        // ── Signal 3: blocklist MSISDN ────────────────────────────────────────────────
        try {
            Boolean isMember = redis.opsForSet().isMember(BLOCKLIST_KEY, recipientMsisdn);
            if (Boolean.TRUE.equals(isMember)) {
                score += BLOCKLIST_WEIGHT;
                if (firstReason == null) {
                    firstReason = "BLOCKLIST_MSISDN";
                } else {
                    firstReason = firstReason + "+BLOCKLIST_MSISDN";
                }
            }
        } catch (Exception e) {
            log.warn("Disbursement blocklist check failed — fail-open",
                kv("operation", "dsb_fraud_blocklist"),
                kv("tenantId", tenantId),
                kv("recipientMsisdn", recipientMsisdn),
                kv("status", "REDIS_UNAVAILABLE"), e);
        }

        // ── Decision: strictly > 80 blocks ───────────────────────────────────────────
        if (score > BLOCK_THRESHOLD) {
            log.warn("Disbursement blocked by fraud evaluation",
                kv("operation", "dsb_fraud_evaluate"),
                kv("tenantId", tenantId),
                kv("score", score),
                kv("reason", firstReason),
                kv("blocked", true));
            return FraudDecision.block(score, firstReason);
        }

        return FraudDecision.allow(score);
    }

    /**
     * Compute median of a list already sorted ascending (as returned by
     * {@code findSuccessfulAmountsForTenant} ORDER BY amount ASC).
     *
     * <ul>
     *   <li>Odd count  — middle element</li>
     *   <li>Even count — average of the two middle elements (HALF_UP rounding)</li>
     * </ul>
     */
    private BigDecimal computeMedian(List<BigDecimal> sorted) {
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        BigDecimal lower = sorted.get(n / 2 - 1);
        BigDecimal upper = sorted.get(n / 2);
        return lower.add(upper).divide(BigDecimal.valueOf(2), RoundingMode.HALF_UP);
    }
}
