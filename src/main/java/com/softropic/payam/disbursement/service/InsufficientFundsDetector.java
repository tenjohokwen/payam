package com.softropic.payam.disbursement.service;

import com.softropic.payam.common.payment.ProviderResult;

import org.springframework.stereotype.Component;

/**
 * Best-effort detection of provider Insufficient Funds errors (ALERT-01).
 *
 * <p>MTN MoMo and Orange Money do not share a unified IF error code. This detector
 * uses conservative case-insensitive substring matching against
 * {@link ProviderResult#errorCode()} and {@link ProviderResult#errorMessage()}.
 *
 * <p>Known patterns (extend as new providers/codes are observed):
 * <ul>
 *   <li>"NOT_ENOUGH_FUNDS" — MTN convention</li>
 *   <li>"INSUFFICIENT_BALANCE" — Orange convention</li>
 *   <li>"INSUFFICIENT_FUNDS" — generic English fallback</li>
 * </ul>
 *
 * <p>Conservative philosophy (Pitfall 7 in 56-RESEARCH): if the signal is
 * uncertain, return false — do not alert. False negatives (missing an IF
 * alert) are recoverable; false positives (paging Ops for a timeout) erode trust.
 */
@Component
public class InsufficientFundsDetector {

    private static final String[] PATTERNS = {
        "NOT_ENOUGH_FUNDS",
        "NOT ENOUGH FUNDS",
        "INSUFFICIENT_BALANCE",
        "INSUFFICIENT BALANCE",
        "INSUFFICIENT_FUNDS",
        "INSUFFICIENT FUNDS"
    };

    public boolean isInsufficientFunds(ProviderResult result) {
        if (result == null) {
            return false;
        }
        String code = result.errorCode();
        String message = result.errorMessage();
        return matches(code) || matches(message);
    }

    private boolean matches(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String upper = text.toUpperCase();
        for (String pattern : PATTERNS) {
            if (upper.contains(pattern)) {
                return true;
            }
        }
        return false;
    }
}
