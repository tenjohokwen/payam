package com.softropic.payam.payment.service;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.payment.contract.exception.UnknownMsisdnPrefixException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.util.Optional;

/**
 * Resolves a Cameroonian MSISDN to the corresponding {@link MobilePaymentProvider}.
 *
 * <p>Resolution order (Phase 10 hardening — OPS-01):
 * <ol>
 *   <li>DB-backed prefix table via {@link MsisdnPrefixRouteCache#findByPrefix(String)}</li>
 *   <li>Hardcoded fallback (retained for last-resort resilience — Pitfall 4 mitigation)</li>
 * </ol>
 *
 * <p>When the hardcoded fallback fires, a WARN log is emitted so the operator knows the
 * DB routing table has a gap.
 *
 * <p>Hardcoded fallback routing rules (national number after stripping +237 / 237 country code):
 * <ul>
 *   <li>Starts with "65" or "69" → {@link MobilePaymentProvider#ORANGE}</li>
 *   <li>Starts with "6" (any other two-digit prefix 60-64, 66-68) → {@link MobilePaymentProvider#MTN}</li>
 *   <li>Anything else → {@link UnknownMsisdnPrefixException}</li>
 * </ul>
 */
@Service
public class MsisdnRouter {

    private static final Logger log = LoggerFactory.getLogger(MsisdnRouter.class);

    private final MsisdnPrefixRouteCache msisdnPrefixRouteCache;

    public MsisdnRouter(MsisdnPrefixRouteCache msisdnPrefixRouteCache) {
        this.msisdnPrefixRouteCache = msisdnPrefixRouteCache;
    }

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

        // Step 1: DB-backed routing via cache
        String prefix = national.length() >= 2 ? national.substring(0, 2) : national;
        Optional<MobilePaymentProvider> dbRoute = msisdnPrefixRouteCache.findByPrefix(prefix);
        if (dbRoute.isPresent()) {
            return dbRoute.get();
        }

        // Step 2: Hardcoded fallback — log WARN when fired (Pitfall 4 guard)
        log.warn("MSISDN prefix not found in route table",
                kv("operation", "route_msisdn"),
                kv("status", "PREFIX_NOT_FOUND"));

        if (national.startsWith("65") || national.startsWith("69")) {
            return MobilePaymentProvider.ORANGE;
        }

        if (national.startsWith("6")) {
            return MobilePaymentProvider.MTN;
        }

        throw new UnknownMsisdnPrefixException(msisdn);
    }
}
