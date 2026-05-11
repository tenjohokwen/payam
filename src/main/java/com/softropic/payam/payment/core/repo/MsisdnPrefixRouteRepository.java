package com.softropic.payam.payment.core.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;


/**
 * Repository for {@link MsisdnPrefixRoute} entities.
 *
 * <p>Used by {@link com.softropic.payam.payment.core.service.MsisdnPrefixRouteCache} to load
 * enabled routing rules on startup and on scheduled refresh intervals.
 */
public interface MsisdnPrefixRouteRepository extends JpaRepository<MsisdnPrefixRoute, Long> {

    /** Return all enabled MSISDN prefix routes — used by MsisdnPrefixRouteCache for hot-reload. */
    List<MsisdnPrefixRoute> findAllByEnabledTrue();
}
