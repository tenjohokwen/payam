package com.softropic.payam.disbursement.service;

import com.softropic.payam.disbursement.contract.DisbursementRefStatus;
import com.softropic.payam.disbursement.repo.DisbursementTransactionRefRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Bulk claim-status transition orchestrator (v11 CLAIM-02, CLAIM-03, CLAIM-04).
 *
 * <p>Wraps the @Modifying UPDATE on DisbursementTransactionRefRepository in a
 * @Transactional(REQUIRED) boundary. When called from inside an existing transaction
 * (the typical case — DisbursementCallbackTransitionService is REQUIRES_NEW;
 * DisbursementOrchestrator wraps in transactionTemplate.execute; the admin-approval
 * expiry job wraps in transactionTemplate.execute), Spring joins the existing
 * transaction so the disbursement state change and the claim transition commit
 * atomically (Pitfall 4 in 56-RESEARCH).
 *
 * <p>Returning 0 is NOT an error — releaseAndFail() may be invoked when no claim
 * rows exist yet (claim creation itself threw before any insert).
 */
@Service
public class DisbursementClaimTransitionService {

    private static final Logger log = LoggerFactory.getLogger(DisbursementClaimTransitionService.class);

    private final DisbursementTransactionRefRepository refRepository;

    public DisbursementClaimTransitionService(DisbursementTransactionRefRepository refRepository) {
        this.refRepository = refRepository;
    }

    /**
     * Bulk-update all claims for the given disbursement from {@code current} to {@code target}.
     *
     * @param disbursementId BIGINT PK of the parent Disbursement (Disbursement#getId(),
     *                       NOT the UUID Disbursement#getDisbursementId())
     * @param current        the source status to match (typically PENDING)
     * @param target         the destination status (CLAIMED, RELEASED)
     * @return number of rows updated; 0 is valid (no claims existed yet)
     */
    @Transactional
    public int transitionClaims(Long disbursementId,
                                DisbursementRefStatus current,
                                DisbursementRefStatus target) {
        int updated = refRepository.updateRefStatusForDisbursement(disbursementId, current, target);
        log.info("Disbursement claim transition",
                kv("operation", "dsb_claim_transition"),
                kv("disbursementId", disbursementId),
                kv("fromStatus", current.name()),
                kv("toStatus", target.name()),
                kv("rowsAffected", updated));
        return updated;
    }
}
