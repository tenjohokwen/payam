package com.softropic.payam.disbursement.repo;

import com.softropic.payam.disbursement.contract.DisbursementStatus;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
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

    /**
     * Pessimistic-write lock query for state transitions. Must be called inside a transactional
     * boundary so the SELECT FOR UPDATE row lock is released only on commit.
     * Used by DisbursementOrchestrator to transition status after provider dispatch.
     * Also used by DisbursementService.transitionToFailed().
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Disbursement d WHERE d.disbursementId = :disbursementId")
    Optional<Disbursement> findByDisbursementIdForUpdate(@Param("disbursementId") String disbursementId);

    /**
     * Find disbursements in a given status created before a given instant.
     * Used by Plan 05's expiry job to find PENDING_CONFIRMATION rows that have aged past the
     * 15-minute confirmation window (SEC-04 expiry).
     */
    List<Disbursement> findByDisbursementStatusAndCreatedDateBefore(
            DisbursementStatus status, Instant before);

    /**
     * Tenant-scoped lookup by disbursementId. Used by confirm() and the REST GET endpoint.
     * Enforces tenant isolation — a tenant can only see/confirm their own disbursements.
     */
    Optional<Disbursement> findByTenantIdAndDisbursementId(Long tenantId, String disbursementId);

    /**
     * Pageable tenant-scoped query for the REST GET /v1/disbursements list endpoint (Plan 04).
     * All filter params are optional — null values are ignored by the IS NULL OR clause.
     */
    @Query("SELECT d FROM Disbursement d WHERE d.tenantId = :tenantId " +
           "AND (:status IS NULL OR d.disbursementStatus = :status) " +
           "AND (:from IS NULL OR d.createdDate >= :from) " +
           "AND (:to IS NULL OR d.createdDate <= :to) " +
           "ORDER BY d.createdDate DESC")
    Page<Disbursement> findForTenant(@Param("tenantId") Long tenantId,
                                     @Param("status") DisbursementStatus status,
                                     @Param("from") Instant from,
                                     @Param("to") Instant to,
                                     Pageable pageable);
}
