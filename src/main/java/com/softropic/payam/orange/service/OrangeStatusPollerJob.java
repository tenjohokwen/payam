package com.softropic.payam.orange.service;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.orange.contract.exception.OrangeApiException;
import com.softropic.payam.orange.contract.exception.PayTokenExpiredException;
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
public class OrangeStatusPollerJob extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(OrangeStatusPollerJob.class);
    private static final int POLL_DELAY_MINUTES = 2;

    private final TransactionRepository transactionRepository;
    private final OrangeMoneyPort orangeMoneyPort;
    private final EventLogService eventLogService;

    public OrangeStatusPollerJob(TransactionRepository transactionRepository,
                                 OrangeMoneyPort orangeMoneyPort,
                                 EventLogService eventLogService) {
        this.transactionRepository = transactionRepository;
        this.orangeMoneyPort = orangeMoneyPort;
        this.eventLogService = eventLogService;
    }

    /**
     * Polls all PROCESSING Orange transactions that:
     * - Have been PROCESSING for at least 2 minutes (Orange may still be processing)
     * - Have not been polled more than 15 times (max ~2 hours total)
     *
     * On SUCCESSFULL/FAILED status: applyTransition() + append event log
     * On PENDING: increment pollAttempts, log
     * On pollAttempts >= 15: mark FAILED (timeout)
     * Uses @Lock(PESSIMISTIC_WRITE) via findByTransactionIdForUpdate to prevent race with webhook (P1.2)
     */
    @Override
    @Transactional
    protected void executeInternal(JobExecutionContext context) {
        Instant cutoff = Instant.now().minus(POLL_DELAY_MINUTES, ChronoUnit.MINUTES);

        List<Transaction> stuck = transactionRepository
            .findByTxStatusAndProviderAndLastModifiedDateBefore(
                TransactionStatus.PROCESSING, MobilePaymentProvider.ORANGE, cutoff);

        log.info("Poller scan",
            kv("operation", "orange_poller_scan"),
            kv("stuckCount", stuck.size()));

        for (Transaction tx : stuck) {
            pollTransaction(tx);
        }
    }

    private void pollTransaction(Transaction tx) {
        if (tx.getPayToken() == null) {
            log.warn("Orange poller: transaction missing payToken",
                kv("operation", "orange_poller_scan"),
                kv("transactionId", tx.getTransactionId()),
                kv("status", "SKIPPED_NO_PAY_TOKEN"));
            return;
        }

        // Guard: skip poll if payToken is expired — re-initiation is Phase 5 orchestrator responsibility (P1.3)
        try {
            orangeMoneyPort.assertPayTokenFresh(tx.getTransactionId(), tx.getPayTokenIssuedAt());
        } catch (PayTokenExpiredException e) {
            log.warn("Orange poller: payToken expired",
                kv("operation", "orange_poller_scan"),
                kv("transactionId", tx.getTransactionId()),
                kv("status", "TOKEN_EXPIRED"));
            tx.incrementPollAttempts();
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
                kv("actor", "ORANGE_POLLER"));
            eventLogService.append(tx.getTransactionId(), tx.getTraceId(), tx.getExternalReference(),
                TransactionEventType.PROVIDER_FAILED,
                TransactionStatus.PROCESSING, TransactionStatus.FAILED,
                "ORANGE_POLLER", "max_poll_attempts_exceeded");
            return;
        }

        try {
            var result = orangeMoneyPort.getTransactionStatus(tx.getPayToken());
            if (!result.pending()) {
                Transaction locked = transactionRepository.findByTransactionIdForUpdate(tx.getTransactionId())
                    .orElseThrow();
                TransactionStatus next = OrangeStatusMapper.toInternal(result.rawStatus());
                locked.applyTransition(next);
                log.info("Transaction state changed",
                    kv("operation", "transaction_state_change"),
                    kv("transactionId", tx.getTransactionId()),
                    kv("fromState", TransactionStatus.PROCESSING.name()),
                    kv("toState", next.name()),
                    kv("actor", "ORANGE_POLLER"));
                TransactionEventType eventType = next == TransactionStatus.SUCCESS
                    ? TransactionEventType.PROVIDER_SUCCESS
                    : TransactionEventType.PROVIDER_FAILED;
                eventLogService.append(tx.getTransactionId(), tx.getTraceId(), tx.getExternalReference(),
                    eventType, TransactionStatus.PROCESSING, next,
                    "ORANGE_POLLER", result.rawStatus());
            } else {
                tx.incrementPollAttempts();
                log.info("Orange poller: still PENDING",
                    kv("operation", "orange_poller_scan"),
                    kv("transactionId", tx.getTransactionId()),
                    kv("pollAttempt", attempts + 1),
                    kv("status", "STILL_PENDING"));
            }
        } catch (OrangeApiException e) {
            log.warn("Orange poller: API error",
                kv("operation", "orange_poller_scan"),
                kv("transactionId", tx.getTransactionId()),
                kv("status", "ERROR"),
                e);
            tx.incrementPollAttempts();
        }
    }
}
