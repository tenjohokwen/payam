package com.softropic.payam.reconciliation.port;

import com.softropic.payam.common.payment.MobilePaymentProvider;

import java.time.LocalDate;

/**
 * Port interface for fetching a provider's view of a single transaction during reconciliation.
 *
 * Implementations MUST return a {@link ProviderTransactionRecord} with {@code unconfirmed=true}
 * rather than throwing exceptions when the provider API is unreachable. This ensures the
 * reconciliation job continues processing all transactions even when one provider is unavailable.
 */
public interface ProviderReportPort {

    /**
     * Fetch the provider's view of one transaction identified by providerRef.
     *
     * @param providerRef The provider reference stored on the Payam transaction
     * @param reportDate  The date being reconciled (for context/logging)
     * @return A record with the provider's status and amount, or unconfirmed=true if unreachable
     */
    ProviderTransactionRecord fetchProviderRecord(String providerRef, LocalDate reportDate);

    /**
     * The provider this port implementation handles.
     * Used by ReconciliationService to build the provider-to-port map.
     */
    MobilePaymentProvider provider();
}
