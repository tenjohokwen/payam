package com.softropic.payam.payment.ledger.service;

import com.softropic.payam.payment.core.contract.MobilePaymentProvider;
import com.softropic.payam.payment.ledger.contract.TransactionStatus;
import com.softropic.payam.payment.ledger.repo.Transaction;
import com.softropic.payam.payment.ledger.repo.TransactionRepository;

import io.micrometer.tracing.Tracer;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;


@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final Tracer tracer;

    public TransactionService(TransactionRepository transactionRepository, Tracer tracer) {
        this.transactionRepository = transactionRepository;
        this.tracer = tracer;
    }

    @Transactional
    public Transaction initiate(Long tenantId, MobilePaymentProvider provider,
                                BigDecimal amount, String currency,
                                String externalReference) {
        String transactionId = UUID.randomUUID().toString();
        String traceId = Optional.ofNullable(tracer.currentSpan())
            .map(span -> span.context().traceId())
            .orElse(transactionId);  // fallback: use transactionId if no active span

        MDC.put("transactionId", transactionId);
        // Note: "traceId" is injected automatically by micrometer-tracing-bridge-otel via the <mdc/> provider.
        // The local traceId variable is persisted in the Transaction entity (database column) only.
        // Do NOT call MDC.put("traceId", ...) here — the OTel bridge owns that MDC key.
        if (externalReference != null) {
            MDC.put("externalReference", externalReference);
        }

        Transaction tx = Transaction.builder()
            .transactionId(transactionId)
            .traceId(traceId)
            .externalReference(externalReference)
            .tenantId(tenantId)
            .provider(provider)
            .amount(amount)
            .currency(currency)
            .txStatus(TransactionStatus.INITIATED)
            .build();

        return transactionRepository.save(tx);
    }
}
