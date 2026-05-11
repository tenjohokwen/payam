package com.softropic.payam.payment.reconciliation.port;

import java.math.BigDecimal;

/**
 * Carrier record for the provider's view of a single transaction.
 * Returned by {@link ProviderReportPort#fetchProviderRecord(String, java.time.LocalDate)}.
 *
 * @param providerRef     The provider's transaction reference (same as the query input)
 * @param providerStatus  Raw status string from the provider API, or null if not found / unreachable
 * @param providerAmount  Amount as reported by provider; null if not returned by API or unavailable
 * @param unconfirmed     true when the provider API was unreachable — result should be treated as UNCONFIRMED
 */
public record ProviderTransactionRecord(
    String providerRef,
    String providerStatus,
    BigDecimal providerAmount,
    boolean unconfirmed
) {}
