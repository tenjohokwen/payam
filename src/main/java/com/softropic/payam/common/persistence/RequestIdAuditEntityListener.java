package com.softropic.payam.common.persistence;



import com.softropic.payam.security.exposed.util.RequestIdProvider;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;

/**
 * Puts the request id associated with the current thread in entity to be modified.
 */
//TODO move this to security/exposed
public class RequestIdAuditEntityListener {

    @PrePersist
    @PreUpdate
    @PreRemove
    public void recordRequestId(final AbstractAuditingEntity abstractAuditingEntity) {
        abstractAuditingEntity.setRequestId(RequestIdProvider.provideRequestId());
    }
}
