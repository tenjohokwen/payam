package com.softropic.payam.mtn.service;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.mtn.config.MtnMoMoConfig;
import com.softropic.payam.mtn.contract.exception.MtnApiException;
import com.softropic.payam.transaction.contract.TransactionEventType;
import com.softropic.payam.transaction.contract.TransactionStatus;
import com.softropic.payam.transaction.repo.Transaction;
import com.softropic.payam.transaction.repo.TransactionRepository;
import com.softropic.payam.transaction.service.EventLogService;

import static net.logstash.logback.argument.StructuredArguments.kv;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
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
public class MtnStatusPollerJob extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(MtnStatusPollerJob.class);

    /**
     * Stable advisory lock key that uniquely identifies this poller in the cluster.
     * Must differ from OrangeStatusPollerJob's key (4_002L).
     * pg_try_advisory_xact_lock is transaction-level: auto-released on commit/rollback,
     * non-blocking (returns false instead of waiting), so there is no deadlock risk.
     */
    private static final long MTN_POLLER_LOCK_KEY = 4_001L;

    /**
     * Maximum transactions processed per poller invocation.
     * Prevents a backlog spike from overwhelming the JVM heap or exhausting the DB connection.
     * Smaller batches (100) allow better distribution across a multi-node cluster.
     */
    private static final int POLL_BATCH_SIZE = 100;

    /**
     * Wall-clock transaction timeout (in SECONDS, not milliseconds) for a single poller tick.
     * Bounds the worst-case pg_try_advisory_xact_lock hold time: when the 300-second budget
     * is exceeded, Postgres raises 57014 query_canceled, Spring rolls the transaction back,
     * and the transaction-level advisory lock is released automatically. Matches the Quartz
     * re-fire interval (5 minutes) so a tick cannot overlap its successor.
     */
    private static final int POLLER_TRANSACTION_TIMEOUT_SECONDS = 300;

    private final TransactionRepository transactionRepository;
    private final MtnMoMoPort mtnMoMoPort;
    private final EventLogService eventLogService;
    private final MtnMoMoConfig config;
    private final JdbcTemplate jdbcTemplate;
    private final ObservationRegistry observationRegistry;

    public MtnStatusPollerJob(TransactionRepository transactionRepository,
                               MtnMoMoPort mtnMoMoPort,
                               EventLogService eventLogService,
                               MtnMoMoConfig config,
                               JdbcTemplate jdbcTemplate,
                               ObservationRegistry observationRegistry) {
        this.transactionRepository = transactionRepository;
        this.mtnMoMoPort = mtnMoMoPort;
        this.eventLogService = eventLogService;
        this.config = config;
        this.jdbcTemplate = jdbcTemplate;
        this.observationRegistry = observationRegistry;
    }

    /**
     * Polls all PROCESSING MTN transactions that:
     * - Have been PROCESSING for at least N seconds
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
    /**
     * Quartz entry point. @Observed cannot advise this protected method via Spring AOP
     * (the parent class calls it via self-invocation, bypassing the proxy), so we use
     * a programmatic Observation that produces the same span + timer as @Observed would.
     */
    @Override
    @Transactional(timeout = POLLER_TRANSACTION_TIMEOUT_SECONDS)
    protected void executeInternal(JobExecutionContext context) {
        Observation.createNotStarted("quartz.mtn-poller", observationRegistry)
                .lowCardinalityKeyValue("job", "MtnStatusPollerJob")
                .observe(this::runPoller);
    }

    private void runPoller() {
        // Non-blocking transaction-level advisory lock — only one node polls at a time.
        Boolean locked = jdbcTemplate.queryForObject(
            "SELECT pg_try_advisory_xact_lock(?)", Boolean.class, MTN_POLLER_LOCK_KEY);
        if (!Boolean.TRUE.equals(locked)) {
            log.info("MTN poller skipped: lock held by another node",
                kv("operation", "mtn_poller_scan"));
            return;
        }

        int initialDelaySeconds = config.getPoller().getInitialDelaySeconds();

        // If delay is 0 (test-time), use a 1-hour FUTURE cutoff to pick up ALL transactions
        // including those created/modified in the current millisecond or shifted by auditing.
        Instant cutoff = initialDelaySeconds == 0
            ? Instant.now().plus(1, ChronoUnit.HOURS)
            : Instant.now().minus(initialDelaySeconds, ChronoUnit.SECONDS);

        List<Transaction> stuck = transactionRepository
            .findByTxStatusAndProviderAndLastModifiedDateBefore(
                TransactionStatus.PROCESSING, MobilePaymentProvider.MTN, cutoff,
                PageRequest.of(0, POLL_BATCH_SIZE));

        log.info("Poller scan",
            kv("operation", "mtn_poller_scan"),
            kv("initialDelaySeconds", initialDelaySeconds),
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
                kv("actor", "MTN_POLLER"));
            eventLogService.append(tx.getTransactionId(), tx.getTraceId(), tx.getExternalReference(),
                TransactionEventType.PROVIDER_FAILED,
                TransactionStatus.PROCESSING, TransactionStatus.FAILED,
                "MTN_POLLER", "\"max_poll_attempts_exceeded\"");
            return;
        }

        try {
            var result = mtnMoMoPort.getTransactionStatus(tx.getProviderRef());
            if (!result.pending()) {
                Transaction locked = transactionRepository.findByTransactionIdForUpdate(tx.getTransactionId())
                    .orElseThrow();
                
                // Double-check status after locking
                if (locked.getTxStatus() != TransactionStatus.PROCESSING) {
                    return;
                }

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
                // Wrap rawStatus in JSON double-quotes: metadata column is jsonb,
                // a bare string like SUCCESSFUL is invalid JSON.
                String metadata = "\"" + result.rawStatus() + "\"";
                eventLogService.append(tx.getTransactionId(), tx.getTraceId(), tx.getExternalReference(),
                    eventType, TransactionStatus.PROCESSING, next,
                    "MTN_POLLER", metadata);
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
