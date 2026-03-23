package com.softropic.payam.transaction.service;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.transaction.contract.TransactionStatus;
import com.softropic.payam.transaction.repo.Transaction;
import com.softropic.payam.transaction.repo.TransactionRepository;

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
            .orElse(transactionId);  // fallback: use transaction_id if no active span

        MDC.put("transaction_id", transactionId);
        MDC.put("trace_id", traceId);
        if (externalReference != null) {
            MDC.put("external_reference", externalReference);
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
