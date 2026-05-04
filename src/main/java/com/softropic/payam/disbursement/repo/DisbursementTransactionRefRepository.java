package com.softropic.payam.disbursement.repo;

import com.softropic.payam.disbursement.contract.DisbursementRefStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Spring Data repository for {@link DisbursementTransactionRef}.
 *
 * <p>Phase 55 query methods support TXN-03 (active-claim probe) via
 * {@link #findClaimedTransactionIds} and lay the groundwork for Phase 56
 * (CLAIM-02..05 batch transitions) and Phase 57 (IDEM-02 RELEASED reactivation).
 */
public interface DisbursementTransactionRefRepository
        extends JpaRepository<DisbursementTransactionRef, Long> {

    /**
     * TXN-03: returns the subset of {@code transactionIds} that already have at
     * least one DisbursementTransactionRef in the supplied statuses (typically
     * PENDING + CLAIMED — the active-claim set). An empty result means none of the
     * transactions are claimed.
     */
    @Query("SELECT DISTINCT r.transactionId FROM DisbursementTransactionRef r " +
           "WHERE r.transactionId IN :transactionIds " +
           "AND r.refStatus IN :statuses")
    List<String> findClaimedTransactionIds(
        @Param("transactionIds") List<String> transactionIds,
        @Param("statuses") Collection<DisbursementRefStatus> statuses);
}
