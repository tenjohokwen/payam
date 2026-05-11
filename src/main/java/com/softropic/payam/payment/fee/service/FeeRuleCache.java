package com.softropic.payam.payment.fee.service;

import com.softropic.payam.payment.fee.repo.FeeRule;
import com.softropic.payam.payment.fee.repo.FeeRuleRepository;

import io.micrometer.observation.annotation.Observed;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;


/**
 * In-memory cache of enabled {@link FeeRule} entities, hot-reloaded from the DB on a
 * configurable schedule.
 *
 * <p>Uses a {@code volatile} list for thread-safe visibility of list replacement without locking.
 * The {@code @Scheduled} refresh calls {@link FeeRuleRepository#findAllByEnabledTrue()} and
 * replaces the cached list — no JVM restart required when DB rows are updated.
 *
 * <p>Rule resolution priority:
 * <ol>
 *   <li>Tenant-specific rule (tenantId matches)</li>
 *   <li>Global rule (tenantId is null)</li>
 * </ol>
 */
@Service
public class FeeRuleCache {

    private final FeeRuleRepository feeRuleRepository;
    private volatile List<FeeRule> cachedRules = List.of();

    public FeeRuleCache(FeeRuleRepository feeRuleRepository) {
        this.feeRuleRepository = feeRuleRepository;
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    /**
     * Reload enabled fee rules from DB and replace the cache.
     * Called on startup via {@link PostConstruct} and on a scheduled interval.
     */
    @Observed(name = "scheduler.cache-refresh", contextualName = "fee-rule-cache")
    @Scheduled(fixedDelayString = "${fee.rule-cache.refresh-interval-ms:60000}")
    public void refresh() {
        List<FeeRule> rules = feeRuleRepository.findAllByEnabledTrue();
        cachedRules = rules;
    }

    /**
     * Find the applicable fee rule for a given tenant.
     *
     * <p>Priority: tenant-specific rule first, then global rule (tenantId null).
     * Returns empty if no enabled rule exists for the tenant or globally.
     *
     * @param tenantId the tenant to look up a rule for
     * @return most specific enabled rule, or empty
     */
    public Optional<FeeRule> findForTenant(Long tenantId) {
        List<FeeRule> snapshot = cachedRules;

        // 1. Tenant-specific rule
        Optional<FeeRule> tenantRule = snapshot.stream()
                .filter(r -> tenantId.equals(r.getTenantId()))
                .findFirst();
        if (tenantRule.isPresent()) {
            return tenantRule;
        }

        // 2. Global fallback (tenantId is null)
        return snapshot.stream()
                .filter(r -> r.getTenantId() == null)
                .findFirst();
    }
}
