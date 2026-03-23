package com.softropic.payam.tenant.repo;

import com.softropic.payam.tenant.contract.TenantStatus;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;


public interface TenantRepository extends JpaRepository<Tenant, Long> {

    Optional<Tenant> findByTenantRef(String tenantRef);

    Optional<Tenant> findByTenantRefAndTenantStatus(String tenantRef, TenantStatus status);
}
