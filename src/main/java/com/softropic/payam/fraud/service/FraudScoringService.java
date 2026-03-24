package com.softropic.payam.fraud.service;

import com.softropic.payam.common.payment.PaymentCommand;
import com.softropic.payam.fraud.contract.FraudDecision;
import com.softropic.payam.fraud.contract.FraudSignal;
import com.softropic.payam.fraud.repo.FraudRule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;


/**
 * Fraud scoring service — orchestrates velocity checks and risk score computation.
 *
 * <p>Evaluates four fraud signals for each payment:
 * <ol>
 *   <li>IP velocity — too many payments from the same client IP</li>
 *   <li>MSISDN velocity — too many payments to the same mobile number</li>
 *   <li>App velocity — too many payments from the same tenant application</li>
 *   <li>MSISDN household — SIM-sharing pattern (lower weight to reduce false positives)</li>
 * </ol>
 *
 * <p>Risk score is computed as the weighted sum of triggered signals (clamped to 0–100).
 * Payment is blocked when score reaches the BLOCK_THRESHOLD (default 70). Blocking before
 * provider dispatch ensures no double-charges on fraudulent attempts.
 *
 * <p><strong>NOT @Transactional</strong> — follows PaymentOrchestrator pattern (decision 05-01);
 * no DB connection held during Redis velocity checks.
 */
@Service
public class FraudScoringService {

    private static final Logger log = LoggerFactory.getLogger(FraudScoringService.class);
    private static final int DEFAULT_BLOCK_THRESHOLD = 70;

    private final VelocityCheckService velocityCheckService;
    private final FraudRuleCache fraudRuleCache;

    public FraudScoringService(VelocityCheckService velocityCheckService,
                               FraudRuleCache fraudRuleCache) {
        this.velocityCheckService = velocityCheckService;
        this.fraudRuleCache = fraudRuleCache;
    }

    /**
     * Evaluate all fraud signals for the given payment command.
     *
     * @param cmd payment command carrying clientIp, msisdn, tenantId, deviceFingerprint
     * @return FraudDecision with blocked flag, risk score, and reason if blocked
     */
    public FraudDecision evaluate(PaymentCommand cmd) {
        // Step 1: Check IP velocity
        boolean ipAllowed = velocityCheckService.checkVelocity(FraudSignal.IP_VELOCITY,
                cmd.clientIp() != null ? cmd.clientIp() : "unknown");

        // Step 2: Check MSISDN velocity
        boolean msisdnAllowed = velocityCheckService.checkVelocity(FraudSignal.MSISDN_VELOCITY,
                cmd.msisdn());

        // Step 3: Check App (tenant) velocity
        boolean appAllowed = velocityCheckService.checkVelocity(FraudSignal.APP_VELOCITY,
                cmd.tenantId().toString());

        // Step 4: Check MSISDN household — use first 9 digits as SIM-sharing group key
        String msisdnPrefix = cmd.msisdn().length() >= 9
                ? cmd.msisdn().substring(0, 9)
                : cmd.msisdn();
        boolean householdAllowed = velocityCheckService.checkVelocity(FraudSignal.MSISDN_HOUSEHOLD,
                msisdnPrefix);

        // Step 5: Compute weighted risk score from violated signals.
        // ALSO track direct velocity violations — any single velocity signal exceeded triggers
        // an immediate block (the "must have" truth: exceeding a velocity limit triggers a block).
        int rawScore = 0;
        String firstBlockedSignal = null;
        List<FraudRule> rules = fraudRuleCache.getRules();

        for (FraudRule rule : rules) {
            boolean violated = isViolated(rule.getSignalName(), ipAllowed, msisdnAllowed, appAllowed, householdAllowed);
            if (violated) {
                rawScore += rule.getWeight();
                if (firstBlockedSignal == null) {
                    firstBlockedSignal = rule.getSignalName();
                }
            }
        }
        int riskScore = Math.min(rawScore, 100);

        // Step 6: Direct velocity block — if any velocity signal was exceeded, block immediately.
        // This is separate from score-based blocking: a single exceeded velocity limit is always
        // a block, regardless of weight sum (e.g., MSISDN_VELOCITY=35 < BLOCK_THRESHOLD=70 alone).
        boolean anyVelocityViolated = !ipAllowed || !msisdnAllowed || !appAllowed || !householdAllowed;
        if (anyVelocityViolated && firstBlockedSignal != null) {
            String reason = "Velocity limit exceeded: " + firstBlockedSignal;
            log.warn("Payment blocked by fraud engine (direct velocity): transactionId={}, riskScore={}, reason={}",
                    cmd.transactionId(), riskScore, reason);
            return FraudDecision.block(riskScore, reason);
        }

        // Step 7: Score-based block — if weighted score exceeds the configured threshold.
        int blockThreshold = fraudRuleCache.findBySignalName("BLOCK_THRESHOLD")
                .map(FraudRule::getThreshold)
                .orElse(DEFAULT_BLOCK_THRESHOLD);

        if (riskScore >= blockThreshold) {
            String reason = "Risk score exceeded: " + firstBlockedSignal;
            log.warn("Payment blocked by fraud engine (score): transactionId={}, riskScore={}, reason={}",
                    cmd.transactionId(), riskScore, reason);
            return FraudDecision.block(riskScore, reason);
        }

        log.debug("Fraud evaluation allowed: transactionId={}, riskScore={}", cmd.transactionId(), riskScore);
        return FraudDecision.allow(riskScore);
    }

    /**
     * Determine whether a named signal was violated based on the velocity check results.
     * Returns false (not violated) for unknown signal names — fail-open for score safety.
     */
    private boolean isViolated(String signalName,
                               boolean ipAllowed,
                               boolean msisdnAllowed,
                               boolean appAllowed,
                               boolean householdAllowed) {
        return switch (signalName) {
            case "IP_VELOCITY"      -> !ipAllowed;
            case "MSISDN_VELOCITY"  -> !msisdnAllowed;
            case "APP_VELOCITY"     -> !appAllowed;
            case "MSISDN_HOUSEHOLD" -> !householdAllowed;
            default                 -> false;
        };
    }
}
