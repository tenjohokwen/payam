package com.softropic.payam.payment.repo;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.common.persistence.AbstractAuditingEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;


/**
 * DB-backed MSISDN prefix routing rule.
 *
 * <p>Each row maps a two-character national MSISDN prefix (e.g. "65", "69") to a
 * {@link MobilePaymentProvider}. Rows are hot-reloaded by
 * {@link com.softropic.payam.payment.service.MsisdnPrefixRouteCache} without restart.
 *
 * <p>This entity replaces the hardcoded prefix logic in
 * {@link com.softropic.payam.payment.service.MsisdnRouter} (Phase 10 hardening — OPS-01).
 */
@Entity
@Table(name = "msisdn_prefix_route", schema = "main")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class MsisdnPrefixRoute extends AbstractAuditingEntity {

    /**
     * Two-character national MSISDN prefix (e.g. "65" for ORANGE, "60" for MTN).
     * Unique constraint enforced at DB level.
     */
    @Column(unique = true, nullable = false)
    private String prefix;

    /** Mobile payment provider this prefix routes to. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MobilePaymentProvider provider;

    /** Whether this route is active. Disabled routes are excluded from the cache. */
    @Column(nullable = false)
    private boolean enabled;
}
