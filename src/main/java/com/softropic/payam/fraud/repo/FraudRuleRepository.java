package com.softropic.payam.fraud.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;


/**
 * Repository for {@link FraudRule} entities.
 *
 * <p>Used by {@link com.softropic.payam.fraud.service.FraudRuleCache} to load enabled rules
 * on startup and on scheduled refresh intervals.
 */
public interface FraudRuleRepository extends JpaRepository<FraudRule, Long> {

    /** Return all enabled fraud rules — used by FraudRuleCache for hot-reload. */
    List<FraudRule> findByEnabledTrue();
}
