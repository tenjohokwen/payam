package com.softropic.payam.platform.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link PlatformConfig}.
 *
 * <p>Provides {@link #findByProvider(String)} for lookup by provider key (ORANGE, MTN).
 * All other CRUD operations are inherited from {@link JpaRepository}.
 */
public interface PlatformConfigRepository extends JpaRepository<PlatformConfig, Long> {

    /**
     * Find the platform config row for the given provider key.
     *
     * @param provider the provider key, e.g. "ORANGE" or "MTN" (case-sensitive)
     * @return the matching config, or empty if provider is unknown
     */
    Optional<PlatformConfig> findByProvider(String provider);
}
