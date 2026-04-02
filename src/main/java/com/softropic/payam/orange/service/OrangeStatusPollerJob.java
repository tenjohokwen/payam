package com.softropic.payam.orange.service;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.orange.config.OrangeMoneyConfig;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
public class OrangeStatusPollerJob extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(OrangeStatusPollerJob.class);

    /**
     * Stable advisory lock key that uniquely identifies this poller in the cluster.
     * Must differ from MtnStatusPollerJob's key (4_001L).
     * pg_try_advisory_xact_lock is transaction-level: auto-released on commit/rollback,
     * non-blocking (returns false instead of waiting), so there is no deadlock risk.
     */
    private static final long ORANGE_POLLER_LOCK_KEY = 4_002L;

    /**
     * Maximum transactions processed per poller invocation.
     * Prevents a backlog spike from overwhelming the JVM heap or exhausting the DB connection.
     * Smaller batches (100) allow better distribution across a multi-node cluster.
     */
    private static final int POLL_BATCH_SIZE = 100;

    private final TransactionRepository transactionRepository;
    private final OrangeMoneyPort orangeMoneyPort;
    private final EventLogService eventLogService;
    private final OrangeMoneyConfig config;
    private final JdbcTemplate jdbcTemplate;

    public OrangeStatusPollerJob(TransactionRepository transactionRepository,
                                 OrangeMoneyPort orangeMoneyPort,
                                 EventLogService eventLogService,
                                 OrangeMoneyConfig config,
                                 JdbcTemplate jdbcTemplate) {
        this.transactionRepository = transactionRepository;
        this.orangeMoneyPort = orangeMoneyPort;
        this.eventLogService = eventLogService;
        this.config = config;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Polls all PROCESSING Orange transactions that:
     * - Have been PROCESSING for at least N seconds (Orange may still be processing)
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
        // Non-blocking transaction-level advisory lock — only one node polls at a time.
        // pg_try_advisory_xact_lock returns false immediately if another session holds it,
        // and the lock is auto-released when this transaction ends (commit or rollback).
        Boolean locked = jdbcTemplate.queryForObject(
            "SELECT pg_try_advisory_xact_lock(?)", Boolean.class, ORANGE_POLLER_LOCK_KEY);
        if (!Boolean.TRUE.equals(locked)) {
            log.info("Orange poller skipped: lock held by another node",
                kv("operation", "orange_poller_scan"));
            return;
        }

        int initialDelaySeconds = config.getPoller().getInitialDelaySeconds();
        Instant cutoff = Instant.now().minus(initialDelaySeconds, ChronoUnit.SECONDS);

        List<Transaction> stuck = transactionRepository
            .findByTxStatusAndProviderAndLastModifiedDateBefore(
                TransactionStatus.PROCESSING, MobilePaymentProvider.ORANGE, cutoff,
                PageRequest.of(0, POLL_BATCH_SIZE));

        log.info("Poller scan",
            kv("operation", "orange_poller_scan"),
            kv("initialDelaySeconds", initialDelaySeconds),
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

            // Double-check status after locking to prevent race with webhook or another node
            if (locked.getTxStatus() != TransactionStatus.PROCESSING) {
                return;
            }

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
                "ORANGE_POLLER", "\"max_poll_attempts_exceeded\"");
            return;
        }

        try {
            var result = orangeMoneyPort.getTransactionStatus(tx.getPayToken());
            if (!result.pending()) {
                Transaction locked = transactionRepository.findByTransactionIdForUpdate(tx.getTransactionId())
                    .orElseThrow();

                // Double-check status after locking
                if (locked.getTxStatus() != TransactionStatus.PROCESSING) {
                    return;
                }

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
                // Wrap rawStatus in JSON double-quotes: metadata column is jsonb,
                // a bare string like SUCCESSFULL is invalid JSON.
                String metadata = "\"" + result.rawStatus() + "\"";
                eventLogService.append(tx.getTransactionId(), tx.getTraceId(), tx.getExternalReference(),
                    eventType, TransactionStatus.PROCESSING, next,
                    "ORANGE_POLLER", metadata);
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
