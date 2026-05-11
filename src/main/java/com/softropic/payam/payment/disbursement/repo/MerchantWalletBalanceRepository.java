package com.softropic.payam.payment.disbursement.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MerchantWalletBalanceRepository extends JpaRepository<MerchantWalletBalance, Long> {

    Optional<MerchantWalletBalance> findByTenantId(Long tenantId);

    /**
     * Pessimistic-write lock query for BAL-01. MUST be called inside a transactional boundary
     * so the SELECT FOR UPDATE row lock is released only on commit. Optimistic retry is NOT
     * sufficient — see STATE.md ("optimistic retry allows second drain after first succeeds").
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM MerchantWalletBalance w WHERE w.tenantId = :tenantId")
    Optional<MerchantWalletBalance> findByTenantIdForUpdate(@Param("tenantId") Long tenantId);
}
