package com.softropic.payam.payment.disbursement.service;

import com.softropic.payam.payment.disbursement.config.DisbursementProperties;
import com.softropic.payam.payment.disbursement.contract.DisbursementRefStatus;
import com.softropic.payam.payment.disbursement.contract.DisbursementStatus;
import com.softropic.payam.payment.disbursement.repo.Disbursement;
import com.softropic.payam.payment.disbursement.repo.DisbursementRepository;

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

import java.util.List;

/**
 * Quartz job: ages PENDING_ADMIN_APPROVAL disbursements to EXPIRED after the
 * configurable {@link DisbursementProperties#getAdminApprovalTimeoutHours()} window
 * (ADMIN-03), and atomically releases all associated PENDING claim rows to
 * RELEASED (CLAIM-04).
 *
 * <p>Mirrors the structure of {@link DisbursementExpiryJob} (PENDING_CONFIRMATION /
 * 15-minute hardcoded age) — they are sibling jobs with intentionally separate
 * lifecycles per Pitfall 6 in 56-RESEARCH.
 *
 * <p>Atomic boundary: each candidate is processed inside transactionTemplate.execute.
 * applyTransition(EXPIRED) and claimTransitionService.transitionClaims(...) join the
 * same transaction (Pitfall 4 — REQUIRED propagation), so if either fails the entire
 * candidate is rolled back consistently.
 *
 * <p>This job MUST NOT call WalletBalanceService.release() — wallet model retired
 * in V31 (SCHEMA-03). Claims are released; wallet is not touched.
 */
@DisallowConcurrentExecution
@Component
public class DisbursementAdminApprovalExpiryJob extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(DisbursementAdminApprovalExpiryJob.class);

    /** Cap candidates per tick to bound transaction time and JDBC pool usage. */
    static final int BATCH_SIZE = 200;

    private final DisbursementRepository disbursementRepository;
    private final DisbursementClaimTransitionService claimTransitionService;
    private final DisbursementProperties disbursementProperties;
    private final TransactionTemplate transactionTemplate;
    private final ObservationRegistry observationRegistry;

    public DisbursementAdminApprovalExpiryJob(
            DisbursementRepository disbursementRepository,
            DisbursementClaimTransitionService claimTransitionService,
            DisbursementProperties disbursementProperties,
            TransactionTemplate transactionTemplate,
            ObservationRegistry observationRegistry) {
        this.disbursementRepository = disbursementRepository;
        this.claimTransitionService = claimTransitionService;
        this.disbursementProperties = disbursementProperties;
        this.transactionTemplate = transactionTemplate;
        this.observationRegistry = observationRegistry;
    }

    @Override
    protected void executeInternal(JobExecutionContext context) {
        Observation.createNotStarted("quartz.disbursement-admin-approval-expiry", observationRegistry)
                .lowCardinalityKeyValue("job", "DisbursementAdminApprovalExpiryJob")
                .observe(this::run);
    }

    private void run() {
        // ADMIN-03: timeout window is configurable via payam.disbursement.admin-approval-timeout-hours.
        // Multiply by 60L (long) to feed the native query that takes age in minutes.
        long ageMinutes = disbursementProperties.getAdminApprovalTimeoutHours() * 60L;

        List<Disbursement> candidates = disbursementRepository
                .findExpiredCandidates(DisbursementStatus.PENDING_ADMIN_APPROVAL.name(), ageMinutes);

        if (candidates.size() > BATCH_SIZE) {
            candidates = candidates.subList(0, BATCH_SIZE);
        }

        log.info("Disbursement admin-approval expiry scan",
                kv("operation", "dsb_admin_approval_expiry_scan"),
                kv("ageMinutes", ageMinutes),
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

                    // Re-check under lock — handles race with future admin /approve or /reject endpoints
                    if (locked.getDisbursementStatus() != DisbursementStatus.PENDING_ADMIN_APPROVAL) {
                        return Boolean.FALSE;
                    }

                    locked.applyTransition(DisbursementStatus.EXPIRED);

                    // CLAIM-04: release all PENDING claims to RELEASED, atomic with the EXPIRED transition.
                    // Returns 0 if (somehow) no claim rows exist — that is NOT an error.
                    int released = claimTransitionService.transitionClaims(
                            locked.getId(),
                            DisbursementRefStatus.PENDING,
                            DisbursementRefStatus.RELEASED);

                    log.info("Disbursement admin-approval expired",
                            kv("operation", "dsb_admin_approval_expiry_transition"),
                            kv("disbursementId", dsbId),
                            kv("fromState", DisbursementStatus.PENDING_ADMIN_APPROVAL.name()),
                            kv("toState", DisbursementStatus.EXPIRED.name()),
                            kv("claimsReleased", released),
                            kv("actor", "DSB_ADMIN_APPROVAL_EXPIRY_JOB"));
                    return Boolean.TRUE;
                });

                if (Boolean.TRUE.equals(changed)) {
                    expired++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                log.warn("Disbursement admin-approval expiry skipped — race or illegal state",
                        kv("operation", "dsb_admin_approval_expiry_transition"),
                        kv("disbursementId", dsbId),
                        kv("status", "SKIPPED"), e);
                skipped++;
            }
        }

        log.info("Disbursement admin-approval expiry summary",
                kv("operation", "dsb_admin_approval_expiry_summary"),
                kv("expired", expired),
                kv("skipped", skipped));
    }
}
