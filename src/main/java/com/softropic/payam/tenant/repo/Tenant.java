package com.softropic.payam.tenant.repo;

import com.softropic.payam.common.persistence.AbstractAuditingEntity;
import com.softropic.payam.tenant.contract.TenantStatus;

import org.hibernate.envers.Audited;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.experimental.SuperBuilder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;


@Audited
@Entity
@Table(name = "tenant", schema = "main")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Tenant extends AbstractAuditingEntity {

    @Column(name = "tenant_ref", unique = true, nullable = false, updatable = false)
    private String tenantRef;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "tenant_status", nullable = false)
    @Builder.Default
    private TenantStatus tenantStatus = TenantStatus.ACTIVE;

    @OneToMany(mappedBy = "tenant", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<TenantApiKey> apiKeys = new ArrayList<>();

    public String getTenantRef() {
        return tenantRef;
    }

    public void setTenantRef(String tenantRef) {
        this.tenantRef = tenantRef;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public TenantStatus getTenantStatus() {
        return tenantStatus;
    }

    public void setTenantStatus(TenantStatus tenantStatus) {
        this.tenantStatus = tenantStatus;
    }

    public List<TenantApiKey> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(List<TenantApiKey> apiKeys) {
        this.apiKeys = apiKeys;
    }
}
