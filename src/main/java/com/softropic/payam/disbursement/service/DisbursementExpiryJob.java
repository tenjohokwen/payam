package com.softropic.payam.disbursement.service;

import com.softropic.payam.disbursement.contract.DisbursementStatus;
import com.softropic.payam.disbursement.repo.Disbursement;
import com.softropic.payam.disbursement.repo.DisbursementRepository;

import static net.logstash.logback.argument.StructuredArguments.kv;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Quartz job: ages PENDING_CONFIRMATION disbursements to EXPIRED after 15 minutes (SEC-04).
 *
 * <p>CRITICAL invariant (BAL-03): this job MUST NOT call WalletBalanceService.release().
 * EXPIRED is NOT FAILED — the wallet reservation made at submission time is intentionally
 * retained to prevent overdraft if the provider has already accepted the transfer.
 *
 * <p>Race-safe pattern (mirrors MtnStatusPollerJob):
 * <ol>
 *   <li>Query candidates without lock (status=PENDING_CONFIRMATION AND createdDate &lt; now-15min)</li>
 *   <li>For each candidate: open a TransactionTemplate, fetch under PESSIMISTIC_WRITE lock,
 *       RE-CHECK status is still PENDING_CONFIRMATION (handles race with /confirm endpoint),
 *       apply EXPIRED transition</li>
 * </ol>
 *
 * <p>@DisallowConcurrentExecution prevents two Quartz nodes from running this job at the
 * same time (cluster-safe via JDBC store).
 */
@DisallowConcurrentExecution
@Component
public class DisbursementExpiryJob extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(DisbursementExpiryJob.class);

    /** SEC-04: PENDING_CONFIRMATION disbursements older than this are EXPIRED. */
    static final Duration EXPIRY_AGE = Duration.ofMinutes(15);

    /** Cap candidates per tick to bound transaction time and JDBC pool usage. */
    static final int BATCH_SIZE = 200;

    private final DisbursementRepository disbursementRepository;
    private final TransactionTemplate transactionTemplate;
    private final ObservationRegistry observationRegistry;

    public DisbursementExpiryJob(DisbursementRepository disbursementRepository,
                                 TransactionTemplate transactionTemplate,
                                 ObservationRegistry observationRegistry) {
        this.disbursementRepository = disbursementRepository;
        this.transactionTemplate = transactionTemplate;
        this.observationRegistry = observationRegistry;
    }

    /**
     * Quartz entry point. Mirrors MtnStatusPollerJob — uses programmatic Observation since
     * @Observed cannot AOP-advise the parent class's reflective dispatch.
     */
    @Override
    protected void executeInternal(JobExecutionContext context) {
        Observation.createNotStarted("quartz.disbursement-expiry", observationRegistry)
                .lowCardinalityKeyValue("job", "DisbursementExpiryJob")
                .observe(this::run);
    }

    private void run() {
        Instant threshold = Instant.now().minus(EXPIRY_AGE);
        List<Disbursement> candidates = disbursementRepository
                .findByDisbursementStatusAndCreatedDateBefore(DisbursementStatus.PENDING_CONFIRMATION, threshold);

        // Bound the batch
        if (candidates.size() > BATCH_SIZE) {
            candidates = candidates.subList(0, BATCH_SIZE);
        }

        log.info("Disbursement expiry scan",
                kv("operation", "dsb_expiry_scan"),
                kv("threshold", threshold.toString()),
                kv("candidateCount", candidates.size()));

        int expired = 0;
        int skipped = 0;
        for (Disbursement candidate : candidates) {
            String dsbId = candidate.getDisbursementId();
            try {
                Boolean changed = transactionTemplate.execute(status -> {
                    Disbursement locked = disbursementRepository
                            .findByDisbursementIdForUpdate(dsbId).orElse(null);
                    if (locked == null) return Boolean.FALSE;

                    // Re-check under lock (Pitfall 7 from 51-RESEARCH): another node or the
                    // /confirm endpoint may have transitioned this row already.
                    if (locked.getDisbursementStatus() != DisbursementStatus.PENDING_CONFIRMATION) {
                        return Boolean.FALSE;
                    }

                    locked.applyTransition(DisbursementStatus.EXPIRED);
                    // INTENTIONAL: NOT calling walletBalanceService.release() here — BAL-03.
                    return Boolean.TRUE;
                });

                if (Boolean.TRUE.equals(changed)) {
                    expired++;
                    log.info("Disbursement expired",
                            kv("operation", "dsb_expiry_transition"),
                            kv("disbursementId", dsbId),
                            kv("fromState", DisbursementStatus.PENDING_CONFIRMATION.name()),
                            kv("toState", DisbursementStatus.EXPIRED.name()),
                            kv("actor", "DSB_EXPIRY_JOB"));
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                log.warn("Disbursement expiry skipped — race or illegal state",
                        kv("operation", "dsb_expiry_transition"),
                        kv("disbursementId", dsbId),
                        kv("status", "SKIPPED"), e);
                skipped++;
            }
        }

        log.info("Disbursement expiry summary",
                kv("operation", "dsb_expiry_summary"),
                kv("expired", expired),
                kv("skipped", skipped));
    }
}
