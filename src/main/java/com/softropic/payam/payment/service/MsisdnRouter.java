package com.softropic.payam.payment.service;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.payment.contract.exception.UnknownMsisdnPrefixException;

import org.springframework.stereotype.Service;

/**
 * Resolves a Cameroonian MSISDN to the corresponding {@link MobilePaymentProvider}.
 *
 * <p>Routing rules (national number after stripping +237 / 237 country code):
 * <ul>
 *   <li>Starts with "65" or "69" → {@link MobilePaymentProvider#ORANGE}</li>
 *   <li>Starts with "6" (any other two-digit prefix 60-64, 66-68) → {@link MobilePaymentProvider#MTN}</li>
 *   <li>Anything else → {@link UnknownMsisdnPrefixException}</li>
 * </ul>
 *
 * <p>Implementation is hardcoded (no config-based prefix table) — Phase 10 hardening concern.
 * See RESEARCH.md Pattern 1 and Pitfall 3 mitigation notes.
 */
@Service
public class MsisdnRouter {

    /**
     * Resolve MSISDN to mobile money provider.
     *
     * @param msisdn E.164 format (e.g. +237692954629) or national format (e.g. 692954629)
     * @return the {@link MobilePaymentProvider} for the MSISDN
     * @throws IllegalArgumentException      if msisdn is null or blank
     * @throws UnknownMsisdnPrefixException  if the prefix is not recognized
     */
    public MobilePaymentProvider resolve(String msisdn) {
        if (msisdn == null || msisdn.isBlank()) {
            throw new IllegalArgumentException("MSISDN must not be blank");
        }

        // Strip country code: +237692954629 -> 692954629 or 237692954629 -> 692954629
        String national = msisdn.replaceFirst("^\\+?237", "");

        if (national.startsWith("65") || national.startsWith("69")) {
            return MobilePaymentProvider.ORANGE;
        }

        if (national.startsWith("6")) {
            return MobilePaymentProvider.MTN;
        }

        throw new UnknownMsisdnPrefixException(msisdn);
    }
}
