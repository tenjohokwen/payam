package com.softropic.payam.disbursement.contract;

/**
 * Lifecycle state for a {@link com.softropic.payam.disbursement.repo.DisbursementTransactionRef}
 * row — the per-collection-transaction claim that backs a disbursement (v11 TXN-03, CLAIM-01..05).
 *
 * <p>State semantics:
 * <ul>
 *   <li><b>PENDING</b> — claim created at disbursement initiation; transaction is locked and
 *       cannot back another disbursement. The partial unique index on
 *       {@code (transaction_id) WHERE ref_status IN ('PENDING','CLAIMED')} enforces TXN-03 at
 *       the database layer.</li>
 *   <li><b>CLAIMED</b> — disbursement reached SUCCESS; the underlying collection transaction is
 *       permanently consumed and CANNOT back another disbursement (CLAIM-02).</li>
 *   <li><b>RELEASED</b> — disbursement reached FAILED, or PENDING_ADMIN_APPROVAL auto-expired;
 *       the transaction is released back to the available pool and may back a future
 *       disbursement (CLAIM-03, CLAIM-04). Reactivated to PENDING by IDEM-02 retry recovery.</li>
 * </ul>
 *
 * <p>Persisted as VARCHAR(30) via {@code @Enumerated(EnumType.STRING)} on
 * {@code DisbursementTransactionRef.refStatus} — consistent with project convention
 * (no PostgreSQL ENUM types, see CONVENTIONS.md).
 */
public enum DisbursementRefStatus {
    PENDING,
    CLAIMED,
    RELEASED
}
