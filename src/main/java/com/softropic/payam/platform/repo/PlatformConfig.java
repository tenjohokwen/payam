package com.softropic.payam.platform.repo;

import com.softropic.payam.infrastructure.persistence.AbstractAuditingEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;


/**
 * Platform configuration entity — stores provider-level MSISDNs managed by admin.
 *
 * <p>One row per provider (ORANGE, MTN). Pre-seeded by Flyway V17 with empty MSISDNs;
 * updated at runtime via {@link PlatformConfigRepository} without app restart.
 *
 * <p>Modelled after {@link com.softropic.payam.fee.repo.FeeRule} — same
 * {@link AbstractAuditingEntity} base and immutable-field pattern.
 */
@Entity
@Table(name = "platform_config", schema = "main")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class PlatformConfig extends AbstractAuditingEntity {

    /** Provider key: ORANGE or MTN. Unique per table. */
    @Column(name = "provider", nullable = false, unique = true)
    private String provider;

    /**
     * Platform-owned MSISDN for this provider.
     * Empty string until configured by admin; never null.
     */
    @Column(name = "platform_msisdn", nullable = false)
    private String platformMsisdn;

    /**
     * AES256-encrypted PIN for this provider.
     * NULL when no PIN has been set.
     * Holds ciphertext only - never plaintext.
     * Populated by Phase 42 via updatePin().
     */
    @Column(name = "pin")
    private String pin;

    /**
     * Update the platform MSISDN for this provider.
     * Called by {@link com.softropic.payam.platform.service.PlatformConfigService#update}
     * within a transaction; JPA dirty-checking persists the change on commit.
     *
     * @param newMsisdn the new MSISDN value; must not be null
     */
    public void updateMsisdn(String newMsisdn) {
        this.platformMsisdn = newMsisdn;
    }

    /**
     * Update the encrypted PIN for this provider (PIN-03).
     *
     * <p>Called by {@link com.softropic.payam.platform.service.PlatformConfigService#update}
     * within a transaction; JPA dirty-checking persists the change on commit.
     *
     * <p>The value MUST be the AES256 ciphertext produced by {@code Cryptopher.encrypt(pin)};
     * passing plaintext breaks the at-rest encryption contract (PIN-01).
     *
     * @param ciphertext the AES256-encrypted PIN; pass {@code null} only when explicitly
     *                   clearing the PIN (not exposed in the v8 API surface)
     */
    public void updatePin(String ciphertext) {
        this.pin = ciphertext;
    }
}
