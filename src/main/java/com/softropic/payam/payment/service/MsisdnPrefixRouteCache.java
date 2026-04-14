package com.softropic.payam.payment.service;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.payment.repo.MsisdnPrefixRoute;
import com.softropic.payam.payment.repo.MsisdnPrefixRouteRepository;

import io.micrometer.observation.annotation.Observed;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;


/**
 * In-memory cache of enabled {@link MsisdnPrefixRoute} entities, hot-reloaded from the DB
 * on a configurable schedule.
 *
 * <p>Uses a {@code volatile} list for thread-safe visibility of list replacement without locking.
 * The {@code @Scheduled} refresh replaces the cached list — no JVM restart required.
 *
 * <p>Consumed by {@link MsisdnRouter} which falls back to hardcoded logic when this cache
 * returns empty (Pitfall 4 mitigation — hardcoded rules remain as last resort).
 */
@Service
public class MsisdnPrefixRouteCache {

    private final MsisdnPrefixRouteRepository msisdnPrefixRouteRepository;
    private volatile List<MsisdnPrefixRoute> cachedRoutes = List.of();

    public MsisdnPrefixRouteCache(MsisdnPrefixRouteRepository msisdnPrefixRouteRepository) {
        this.msisdnPrefixRouteRepository = msisdnPrefixRouteRepository;
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    /**
     * Reload enabled MSISDN prefix routes from DB and replace the cache.
     * Called on startup via {@link PostConstruct} and on a scheduled interval.
     */
    @Observed(name = "scheduler.cache-refresh", contextualName = "msisdn-prefix-cache")
    @Scheduled(fixedDelayString = "${msisdn.prefix-cache.refresh-interval-ms:60000}")
    public void refresh() {
        List<MsisdnPrefixRoute> routes = msisdnPrefixRouteRepository.findAllByEnabledTrue();
        cachedRoutes = routes;
    }

    /**
     * Find the provider for a given two-character MSISDN prefix.
     *
     * @param twoCharPrefix two-character national MSISDN prefix (e.g. "65", "69")
     * @return the provider, or empty if no enabled route found for this prefix
     */
    public Optional<MobilePaymentProvider> findByPrefix(String twoCharPrefix) {
        return cachedRoutes.stream()
                .filter(r -> r.isEnabled() && r.getPrefix().equals(twoCharPrefix))
                .map(MsisdnPrefixRoute::getProvider)
                .findFirst();
    }
}
