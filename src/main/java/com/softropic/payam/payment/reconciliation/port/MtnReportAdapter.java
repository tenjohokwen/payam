package com.softropic.payam.payment.reconciliation.port;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.mtn.service.MtnMoMoPort;
import com.softropic.payam.payment.ledger.repo.Transaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.time.LocalDate;

/**
 * ProviderReportPort implementation for MTN Mobile Money.
 *
 * Uses MtnMoMoPort status methods to fetch the provider's view of a single transaction
 * identified by providerRef (the merchant-generated UUID stored by initiateMerchantPayment).
 *
 * MTN status API does not return transaction amount — providerAmount is always null.
 * On any exception (HttpClientException, circuit-open, network error), degrades to UNCONFIRMED.
 */
@Component
public class MtnReportAdapter implements ProviderReportPort {

    private static final Logger log = LoggerFactory.getLogger(MtnReportAdapter.class);

    private final MtnMoMoPort mtnMoMoPort;

    public MtnReportAdapter(MtnMoMoPort mtnMoMoPort) {
        this.mtnMoMoPort = mtnMoMoPort;
    }

    @Override
    public MobilePaymentProvider provider() {
        return MobilePaymentProvider.MTN;
    }

    @Override
    public ProviderTransactionRecord fetchProviderRecord(Transaction tx, LocalDate reportDate) {
        String providerRef = tx.getProviderRef();
        try {
            var result = tx.getEffectiveFlow() == com.softropic.payam.payment.ledger.contract.LedgerFlow.COLLECTION
                ? mtnMoMoPort.getCollectionTransactionStatus(providerRef)
                : mtnMoMoPort.getDisbursementTransactionStatus(providerRef);
            // MTN status API does not return amount; providerAmount stays null
            return new ProviderTransactionRecord(providerRef, result.rawStatus(), null, false);
        } catch (Exception e) {
            log.warn("MTN reconciliation: failed to fetch status",
                kv("operation", "reconciliation_run"),
                kv("provider", "MTN"),
                kv("providerRef", providerRef),
                kv("status", "FETCH_ERROR"),
                e);
            return new ProviderTransactionRecord(providerRef, null, null, true);
        }
    }
}
