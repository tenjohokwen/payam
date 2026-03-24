package com.softropic.payam.orange.service;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.orange.contract.exception.OrangeApiException;
import com.softropic.payam.orange.contract.exception.PayTokenExpiredException;
import com.softropic.payam.transaction.contract.TransactionEventType;
import com.softropic.payam.transaction.contract.TransactionStatus;
import com.softropic.payam.transaction.repo.Transaction;
import com.softropic.payam.transaction.repo.TransactionRepository;
import com.softropic.payam.transaction.service.EventLogService;

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

        log.info("OrangeStatusPollerJob: found {} stuck PROCESSING transactions", stuck.size());

        for (Transaction tx : stuck) {
            pollTransaction(tx);
        }
    }

    private void pollTransaction(Transaction tx) {
        if (tx.getPayToken() == null) {
            log.warn("PROCESSING transaction {} has no payToken — skipping poll", tx.getTransactionId());
            return;
        }

        // Guard: skip poll if payToken is expired — re-initiation is Phase 5 orchestrator responsibility (P1.3)
        try {
            orangeMoneyPort.assertPayTokenFresh(tx.getTransactionId(), tx.getPayTokenIssuedAt());
        } catch (PayTokenExpiredException e) {
            log.warn("payToken expired for transaction {} — skipping poll; Phase 5 orchestrator must re-initiate",
                     tx.getTransactionId());
            tx.incrementPollAttempts();
            return;
        }

        // Check max attempts
        int attempts = tx.getPollAttempts() != null ? tx.getPollAttempts() : 0;
        if (attempts >= 15) {
            log.warn("Transaction {} exceeded max poll attempts — marking FAILED", tx.getTransactionId());
            Transaction locked = transactionRepository.findByTransactionIdForUpdate(tx.getTransactionId())
                .orElseThrow();
            locked.applyTransition(TransactionStatus.FAILED);
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
                TransactionEventType eventType = next == TransactionStatus.SUCCESS
                    ? TransactionEventType.PROVIDER_SUCCESS
                    : TransactionEventType.PROVIDER_FAILED;
                eventLogService.append(tx.getTransactionId(), tx.getTraceId(), tx.getExternalReference(),
                    eventType, TransactionStatus.PROCESSING, next,
                    "ORANGE_POLLER", result.rawStatus());
                log.info("Transaction {} transitioned to {} via polling", tx.getTransactionId(), next);
            } else {
                tx.incrementPollAttempts();
                log.debug("Transaction {} still PENDING after poll attempt {}", tx.getTransactionId(), attempts + 1);
            }
        } catch (OrangeApiException e) {
            log.error("Orange API error polling transaction {}", tx.getTransactionId(), e);
            tx.incrementPollAttempts();
        }
    }
}
