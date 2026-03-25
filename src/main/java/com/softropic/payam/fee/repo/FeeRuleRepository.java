package com.softropic.payam.fee.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;


/**
 * Repository for {@link FeeRule} entities.
 *
 * <p>Used by {@link com.softropic.payam.fee.service.FeeRuleCache} to load enabled rules
 * on startup and on scheduled refresh intervals.
 */
public interface FeeRuleRepository extends JpaRepository<FeeRule, Long> {

    /** Return all enabled fee rules — used by FeeRuleCache for hot-reload. */
    List<FeeRule> findAllByEnabledTrue();
}
