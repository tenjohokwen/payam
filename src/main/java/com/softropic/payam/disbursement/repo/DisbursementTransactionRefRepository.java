package com.softropic.payam.disbursement.repo;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link DisbursementTransactionRef}.
 *
 * <p>Phase 54 stub — only declares the JpaRepository inheritance so the entity can be
 * persisted by the {@code DisbursementTransactionRefIT} integration test in Plan 02 and
 * the bean is wired by Spring at startup. Phase 55 will add query methods such as
 * {@code findByTransactionIdAndRefStatusIn(...)} for claim-active checks (TXN-03) and
 * {@code findByDisbursementId(...)} for retry-recovery reactivation (IDEM-02).
 */
public interface DisbursementTransactionRefRepository
        extends JpaRepository<DisbursementTransactionRef, Long> {
}
