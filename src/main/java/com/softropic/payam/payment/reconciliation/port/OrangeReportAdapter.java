package com.softropic.payam.payment.reconciliation.port;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.payment.provider.orange.service.OrangeMoneyPort;
import com.softropic.payam.payment.ledger.repo.Transaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.time.LocalDate;

/**
 * ProviderReportPort implementation for Orange Money.
 *
 * Uses OrangeMoneyPort status methods which call OrangeMoneyClient.getPaymentStatus.
 * PayResponse carries fields: payToken, status, txnid, message, inittxnmessage, inittxnstatus.
 *
 * IMPORTANT: PayResponse has no createtime field — WAT guard (OrangeTimeUtil.parseOrangeTimestamp)
 * is NOT applicable here; P5.1 WAT compliance is satisfied by the existing OrangeWebhookPayload
 * handler (separate code path). Do not add OrangeTimeUtil calls to this adapter.
 *
 * Resilience: ALL exceptions (including CallNotPermittedException when circuit-open) are caught
 * and degrade to UNCONFIRMED. The Orange adapter MUST NOT propagate exceptions to ReconciliationService
 * — one unreachable provider must not abort reconciliation for other providers.
 */
@Component
public class OrangeReportAdapter implements ProviderReportPort {

    private static final Logger log = LoggerFactory.getLogger(OrangeReportAdapter.class);

    private final OrangeMoneyPort orangeMoneyPort;

    public OrangeReportAdapter(OrangeMoneyPort orangeMoneyPort) {
        this.orangeMoneyPort = orangeMoneyPort;
    }

    @Override
    public MobilePaymentProvider provider() {
        return MobilePaymentProvider.ORANGE;
    }

    @Override
    public ProviderTransactionRecord fetchProviderRecord(Transaction tx, LocalDate reportDate) {
        String providerRef = tx.getProviderRef();
        try {
            // PayResponse has no createtime field — WAT guard not applicable here;
            // P5.1 is satisfied by OrangeWebhookPayload handler (separate code path)
            var result = tx.getEffectiveFlow() == com.softropic.payam.payment.ledger.contract.LedgerFlow.COLLECTION
                ? orangeMoneyPort.getCollectionTransactionStatus(providerRef)
                : orangeMoneyPort.getDisbursementTransactionStatus(providerRef);
            // Orange status API does not return amount in PayResponse; providerAmount stays null
            return new ProviderTransactionRecord(providerRef, result.rawStatus(), null, false);
        } catch (Exception e) {
            // Catch ALL exceptions including CallNotPermittedException (circuit-open).
            // Orange adapter MUST never propagate — UNCONFIRMED is the resilience fallback.
            log.warn("Orange reconciliation: failed to fetch status",
                kv("operation", "reconciliation_run"),
                kv("provider", "ORANGE"),
                kv("providerRef", providerRef),
                kv("status", "FETCH_ERROR"),
                e);
            return new ProviderTransactionRecord(providerRef, null, null, true);
        }
    }
}
