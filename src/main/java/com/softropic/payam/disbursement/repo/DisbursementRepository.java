package com.softropic.payam.disbursement.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DisbursementRepository extends JpaRepository<Disbursement, Long> {

    Optional<Disbursement> findByDisbursementId(String disbursementId);

    Optional<Disbursement> findByTenantIdAndIdempotencyKey(Long tenantId, String idempotencyKey);
}
