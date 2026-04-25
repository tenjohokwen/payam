package com.softropic.payam.disbursement.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface DisbursementRepository extends JpaRepository<Disbursement, Long> {

    Optional<Disbursement> findByDisbursementId(String disbursementId);

    Optional<Disbursement> findByTenantIdAndIdempotencyKey(Long tenantId, String idempotencyKey);

    /**
     * Count prior disbursements to this recipient MSISDN for a given tenant.
     * Used by DisbursementFraudEvaluationService to detect new recipients (SEC-03).
     */
    long countByTenantIdAndRecipientMsisdn(Long tenantId, String recipientMsisdn);

    /**
     * Fetch all SUCCESS disbursement amounts for a tenant, sorted ascending.
     * Used by DisbursementFraudEvaluationService to compute the tenant median payout
     * for the amount-outlier signal (SEC-03). Only SUCCESS rows are included so
     * failed/pending disbursements do not skew the baseline.
     */
    @Query("SELECT d.amount FROM Disbursement d WHERE d.tenantId = :tenantId " +
           "AND d.disbursementStatus = com.softropic.payam.disbursement.contract.DisbursementStatus.SUCCESS " +
           "ORDER BY d.amount ASC")
    List<BigDecimal> findSuccessfulAmountsForTenant(@Param("tenantId") Long tenantId);
}
