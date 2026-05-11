package com.softropic.payam.payment.fraud.service;

import com.softropic.payam.payment.fraud.repo.FraudRule;
import com.softropic.payam.payment.fraud.repo.FraudRuleRepository;

import io.micrometer.observation.annotation.Observed;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;


/**
 * In-memory cache of enabled {@link FraudRule} entities, hot-reloaded from the DB on a
 * configurable schedule.
 *
 * <p>Uses {@link AtomicReference} for thread-safe visibility of list replacement without locking.
 * The {@code @Scheduled} refresh calls {@link FraudRuleRepository#findByEnabledTrue()} and
 * atomically replaces the cached list — no JVM restart is required when DB rows are updated.
 *
 * <p>This class is annotated {@code @Component} (not {@code @Service}) to signal it is
 * infrastructure caching, not business logic.
 */
@Component
public class FraudRuleCache {

    private final FraudRuleRepository fraudRuleRepository;
    private final AtomicReference<List<FraudRule>> rulesCache = new AtomicReference<>(List.of());

    public FraudRuleCache(FraudRuleRepository fraudRuleRepository) {
        this.fraudRuleRepository = fraudRuleRepository;
    }

    @PostConstruct
    public void init() {
        refreshRules();
    }

    /**
     * Reload enabled fraud rules from DB and atomically replace the cache.
     * Called on startup via {@link PostConstruct} and on a scheduled interval.
     */
    @Observed(name = "scheduler.cache-refresh", contextualName = "fraud-rule-cache")
    @Scheduled(fixedDelayString = "${fraud.rule-cache.refresh-interval-ms:60000}")
    public void refreshRules() {
        List<FraudRule> rules = fraudRuleRepository.findByEnabledTrue();
        rulesCache.set(rules);
    }

    /**
     * Return the current snapshot of enabled fraud rules.
     * Thread-safe — reads from AtomicReference.
     */
    public List<FraudRule> getRules() {
        return rulesCache.get();
    }

    /**
     * Find an enabled rule by signal name. Returns empty if not found or not cached.
     *
     * @param signalName the signal_name DB value (e.g. "IP_VELOCITY", "BLOCK_THRESHOLD")
     */
    public Optional<FraudRule> findBySignalName(String signalName) {
        return rulesCache.get().stream()
                .filter(r -> r.getSignalName().equals(signalName))
                .findFirst();
    }
}
