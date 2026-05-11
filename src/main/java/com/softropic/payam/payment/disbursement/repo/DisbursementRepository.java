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
     * Find disbursements in a given status whose created_date is older than the given
     * number of minutes (computed entirely in PostgreSQL via NOW() to avoid JVM timezone
     * and JDBC Instant-binding issues against the TIMESTAMP WITHOUT TIME ZONE column).
     *
     * <p>Background: the {@code disbursement.created_date} column is {@code TIMESTAMP} (no tz).
     * Hibernate 6 maps {@code Instant} as {@code TIMESTAMP WITH TIME ZONE}. Comparing
     * {@code TIMESTAMPTZ} against {@code TIMESTAMP} via a prepared-statement parameter causes
     * silent timezone-offset skew depending on the PostgreSQL session timezone. Keeping the
     * threshold expression inside SQL eliminates the binding entirely and is safe on any JVM tz.
     *
     * <p>The {@code ageMinutes} parameter is a simple long — no timezone conversion.
     */
    @Query(value = "SELECT * FROM main.disbursement d " +
                   "WHERE d.disbursement_status = :status " +
                   "AND d.created_date < NOW() - CAST(:ageMinutes || ' minutes' AS INTERVAL)",
           nativeQuery = true)
    List<Disbursement> findExpiredCandidates(
            @Param("status") String status, @Param("ageMinutes") long ageMinutes);

    /**
     * Tenant-scoped lookup by disbursementId. Used by confirm() and the REST GET endpoint.
     * Enforces tenant isolation — a tenant can only see/confirm their own disbursements.
     */
    Optional<Disbursement> findByTenantIdAndDisbursementId(Long tenantId, String disbursementId);

    /**
     * Pageable tenant-scoped query for the REST GET /v1/disbursements list endpoint (Plan 04).
     * All filter params are optional — pass null to skip filtering.
     *
     * <p>Uses a native query with status and date params typed as String/varchar to avoid the
     * PostgreSQL "could not determine data type of parameter $N" error. PostgreSQL cannot infer
     * the column type when Hibernate binds a null {@code Instant} or null enum value in a
     * prepared statement — the driver needs a concrete type hint. Passing all optional filters
     * as {@code String} (ISO-8601 for dates, enum name for status) lets PostgreSQL cast
     * unambiguously while NULL checking works on plain varchars.
     *
     * <p>Callers must format date params as {@code Instant.toString()} (UTC ISO-8601) or null.
     * The CAST expression converts the ISO-8601 string to TIMESTAMPTZ for comparison against the
     * TIMESTAMP column; PostgreSQL handles the implicit timezone strip at session UTC.
     */
    @Query(value = "SELECT * FROM main.disbursement d " +
                   "WHERE d.tenant_id = :tenantId " +
                   "AND (:status IS NULL OR d.disbursement_status = CAST(:status AS VARCHAR)) " +
                   "AND (:fromStr IS NULL OR d.created_date >= CAST(CAST(:fromStr AS TIMESTAMPTZ) AS TIMESTAMP)) " +
                   "AND (:toStr IS NULL OR d.created_date <= CAST(CAST(:toStr AS TIMESTAMPTZ) AS TIMESTAMP)) " +
                   "ORDER BY d.created_date DESC",
           countQuery = "SELECT COUNT(*) FROM main.disbursement d " +
                        "WHERE d.tenant_id = :tenantId " +
                        "AND (:status IS NULL OR d.disbursement_status = CAST(:status AS VARCHAR)) " +
                        "AND (:fromStr IS NULL OR d.created_date >= CAST(CAST(:fromStr AS TIMESTAMPTZ) AS TIMESTAMP)) " +
                        "AND (:toStr IS NULL OR d.created_date <= CAST(CAST(:toStr AS TIMESTAMPTZ) AS TIMESTAMP))",
           nativeQuery = true)
    Page<Disbursement> findForTenant(@Param("tenantId") Long tenantId,
                                     @Param("status") String status,
                                     @Param("fromStr") String fromStr,
                                     @Param("toStr") String toStr,
                                     Pageable pageable);

    /**
     * Find PROCESSING disbursements for the given provider whose last_modified_date is older
     * than the cutoff Instant. Uses FOR UPDATE SKIP LOCKED so two cluster nodes never claim
     * the same rows. Mirrors TransactionRepository.findProcessingTransactionsSkipLocked.
     *
     * <p>Caller is DisbursementStatusPollerJob. Status param is a String to avoid
     * PostgreSQL "could not determine data type" errors on enum binding (see findForTenant).
     */
    @Query(value = "SELECT * FROM main.disbursement d " +
                   "WHERE d.disbursement_status = :status " +
                   "AND d.provider = :provider " +
                   "AND d.last_modified_date < :cutoff " +
                   "ORDER BY d.last_modified_date ASC " +
                   "LIMIT :batchSize " +
                   "FOR UPDATE SKIP LOCKED",
           nativeQuery = true)
    List<Disbursement> findProcessingDisbursementsForPolling(
            @Param("status") String status,
            @Param("provider") String provider,
            @Param("cutoff") Instant cutoff,
            @Param("batchSize") int batchSize);

    /** Lookup by providerRef — used by MtnDisbursementCallbackController for MTN callbacks. */
    Optional<Disbursement> findByProviderRef(String providerRef);

    /** Lookup by tenant-supplied reference — used by OrangeDisbursementCallbackController. */
    Optional<Disbursement> findByReference(String reference);
}
