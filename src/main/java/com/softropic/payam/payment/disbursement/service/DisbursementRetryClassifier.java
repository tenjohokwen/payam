package com.softropic.payam.disbursement.service;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Classifies DisbursementOrchestratorError codes (stored in cached idempotency
 * responses for FAILED disbursements) as RETRIABLE or TERMINAL for the IDEM-01,
 * IDEM-02, IDEM-03 retry recovery flow in {@link DisbursementOrchestrator}.
 *
 * <p>Classification policy (RESEARCH Open Question 1, Option B — conservative):
 * <ul>
 *   <li>RETRIABLE: PROVIDER_ERROR, PROVIDER_UNAVAILABLE — transient provider-side failures
 *       that may clear on retry (HttpClientException 5xx, timeout, circuit-breaker open).</li>
 *   <li>TERMINAL: every other code — including RECIPIENT_NOT_FOUND, FRAUD_BLOCK,
 *       INVALID_TRANSACTION, TRANSACTION_CLAIMED, AMOUNT_MISMATCH, etc. Conservative
 *       default: unknown codes are terminal.</li>
 * </ul>
 *
 * <p>Why conservative: PROVIDER_ERROR is used for BOTH retriable 5xx provider errors AND
 * non-retriable 4xx ones. Pure code-based classification is ambiguous; treating only the
 * codes whose semantics are unambiguously transient as retriable avoids spurious retries
 * on permanent failures (e.g. fraud blocks). Phase 58 E2E tests will surface any
 * misclassification.
 */
@Component
public class DisbursementRetryClassifier {

    public enum Classification {
        RETRIABLE,
        TERMINAL
    }

    // Conservative retriable set — only unambiguously transient provider conditions.
    // Add codes here only after Phase 58 E2E confirms a code can be safely retried.
    private static final Set<String> RETRIABLE_CODES = Set.of(
            "PROVIDER_ERROR",
            "PROVIDER_UNAVAILABLE"
    );

    /**
     * Classify a DisbursementOrchestratorError code (or null) for retry decisioning.
     *
     * @param errorCode the {@code DisbursementResponse.errorCode()} from the cached
     *                  FAILED response; may be null
     * @return RETRIABLE if the code is in the retriable set; TERMINAL otherwise
     */
    public Classification classify(String errorCode) {
        if (errorCode == null) {
            return Classification.TERMINAL;
        }
        return RETRIABLE_CODES.contains(errorCode)
                ? Classification.RETRIABLE
                : Classification.TERMINAL;
    }
}
