package com.softropic.payam.mtn.service;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.mtn.contract.exception.MtnApiException;
import com.softropic.payam.transaction.contract.TransactionEventType;
import com.softropic.payam.transaction.contract.TransactionStatus;
import com.softropic.payam.transaction.repo.Transaction;
import com.softropic.payam.transaction.repo.TransactionRepository;
import com.softropic.payam.transaction.service.EventLogService;

import static net.logstash.logback.argument.StructuredArguments.kv;

import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
public class MtnStatusPollerJob extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(MtnStatusPollerJob.class);
    private static final int POLL_DELAY_MINUTES = 2;

    private final TransactionRepository transactionRepository;
    private final MtnMoMoPort mtnMoMoPort;
    private final EventLogService eventLogService;

    public MtnStatusPollerJob(TransactionRepository transactionRepository,
                               MtnMoMoPort mtnMoMoPort,
                               EventLogService eventLogService) {
        this.transactionRepository = transactionRepository;
        this.mtnMoMoPort = mtnMoMoPort;
        this.eventLogService = eventLogService;
    }

    /**
     * Polls all PROCESSING MTN transactions that:
     * - Have been PROCESSING for at least 2 minutes
     * - Have not been polled more than 15 times (max ~75 minutes total at 5-min intervals)
     *
     * On SUCCESSFUL status: applyTransition(SUCCESS) + append event log
     * On FAILED status: applyTransition(FAILED) + append event log
     * On PENDING: increment pollAttempts
     * On pollAttempts >= 15: mark FAILED (timeout)
     *
     * NOTE: MTN has NO payToken expiry concern — providerRef is a stable merchant-generated UUID.
     * Do NOT add assertPayTokenFresh() here (that is Orange-specific).
     */
    @Override
    @Transactional
    protected void executeInternal(JobExecutionContext context) {
        Instant cutoff = Instant.now().minus(POLL_DELAY_MINUTES, ChronoUnit.MINUTES);

        List<Transaction> stuck = transactionRepository
            .findByTxStatusAndProviderAndLastModifiedDateBefore(
                TransactionStatus.PROCESSING, MobilePaymentProvider.MTN, cutoff);

        log.info("Poller scan",
            kv("operation", "mtn_poller_scan"),
            kv("stuckCount", stuck.size()));

        for (Transaction tx : stuck) {
            pollTransaction(tx);
        }
    }

    private void pollTransaction(Transaction tx) {
        // Guard: providerRef is null if initiateMerchantPayment crashed before persistProviderRef committed
        if (tx.getProviderRef() == null) {
            log.warn("MTN poller: transaction missing providerRef",
                kv("operation", "mtn_poller_scan"),
                kv("transactionId", tx.getTransactionId()),
                kv("status", "SKIPPED_NO_PROVIDER_REF"));
            return;
        }

        // Check max attempts
        int attempts = tx.getPollAttempts() != null ? tx.getPollAttempts() : 0;
        if (attempts >= 15) {
            Transaction locked = transactionRepository.findByTransactionIdForUpdate(tx.getTransactionId())
                .orElseThrow();
            locked.applyTransition(TransactionStatus.FAILED);
            log.info("Transaction state changed",
                kv("operation", "transaction_state_change"),
                kv("transactionId", tx.getTransactionId()),
                kv("fromState", TransactionStatus.PROCESSING.name()),
                kv("toState", TransactionStatus.FAILED.name()),
                kv("actor", "MTN_POLLER"));
            eventLogService.append(tx.getTransactionId(), tx.getTraceId(), tx.getExternalReference(),
                TransactionEventType.PROVIDER_FAILED,
                TransactionStatus.PROCESSING, TransactionStatus.FAILED,
                "MTN_POLLER", "max_poll_attempts_exceeded");
            return;
        }

        try {
            var result = mtnMoMoPort.getTransactionStatus(tx.getProviderRef());
            if (!result.pending()) {
                Transaction locked = transactionRepository.findByTransactionIdForUpdate(tx.getTransactionId())
                    .orElseThrow();
                TransactionStatus next = MtnStatusMapper.toInternal(result.rawStatus());
                locked.applyTransition(next);
                log.info("Transaction state changed",
                    kv("operation", "transaction_state_change"),
                    kv("transactionId", tx.getTransactionId()),
                    kv("fromState", TransactionStatus.PROCESSING.name()),
                    kv("toState", next.name()),
                    kv("actor", "MTN_POLLER"));
                TransactionEventType eventType = next == TransactionStatus.SUCCESS
                    ? TransactionEventType.PROVIDER_SUCCESS
                    : TransactionEventType.PROVIDER_FAILED;
                eventLogService.append(tx.getTransactionId(), tx.getTraceId(), tx.getExternalReference(),
                    eventType, TransactionStatus.PROCESSING, next,
                    "MTN_POLLER", result.rawStatus());
            } else {
                tx.incrementPollAttempts();
                log.info("MTN poller: still PENDING",
                    kv("operation", "mtn_poller_scan"),
                    kv("transactionId", tx.getTransactionId()),
                    kv("pollAttempt", attempts + 1),
                    kv("status", "STILL_PENDING"));
            }
        } catch (MtnApiException e) {
            log.warn("MTN poller: API error",
                kv("operation", "mtn_poller_scan"),
                kv("transactionId", tx.getTransactionId()),
                kv("status", "ERROR"),
                e);
            tx.incrementPollAttempts();
        }
    }
}
