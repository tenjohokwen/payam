package com.softropic.payam.platform.service;

import com.softropic.payam.platform.contract.PlatformConfigDto;
import com.softropic.payam.platform.contract.event.PlatformConfigChangedEvent;
import com.softropic.payam.platform.repo.PlatformConfig;
import com.softropic.payam.platform.repo.PlatformConfigRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Business logic for reading and updating platform configuration (MSISDNs per provider).
 *
 * <p>The {@code update()} method publishes a {@link PlatformConfigChangedEvent} inside
 * the {@code @Transactional} boundary so that {@code @TransactionalEventListener(AFTER_COMMIT)}
 * listeners (e.g., the email notification in plan 24-02) fire after the DB commit.
 */
@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class PlatformConfigService {

    private final PlatformConfigRepository platformConfigRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Return all platform config rows (one per provider).
     *
     * @return list of DTOs; typically two elements (ORANGE and MTN)
     */
    @Transactional(readOnly = true)
    public List<PlatformConfigDto> findAll() {
        return platformConfigRepository.findAll().stream()
                .map(c -> new PlatformConfigDto(c.getProvider(), c.getPlatformMsisdn()))
                .toList();
    }

    /**
     * Update the platform MSISDN for the given provider (upsert).
     *
     * <p>If the provider exists, its MSISDN is updated. If not, a new row is created.
     * Publishes a {@link PlatformConfigChangedEvent}.
     *
     * @param provider   provider key (case-insensitive; normalised to upper-case)
     * @param newMsisdn  the new MSISDN value
     * @return updated DTO reflecting the persisted state
     */
    public PlatformConfigDto update(String provider, String newMsisdn) {
        String upper = provider.toUpperCase();
        return platformConfigRepository.findByProvider(upper)
                .map(config -> {
                    String oldMsisdn = config.getPlatformMsisdn();
                    config.updateMsisdn(newMsisdn);
                    platformConfigRepository.save(config);
                    eventPublisher.publishEvent(new PlatformConfigChangedEvent(upper, oldMsisdn, newMsisdn));
                    log.info("Platform MSISDN updated", kv("provider", upper), kv("event", "platform_config_updated"));
                    return new PlatformConfigDto(upper, newMsisdn);
                })
                .orElseGet(() -> {
                    PlatformConfig newConfig = PlatformConfig.builder()
                            .provider(upper)
                            .platformMsisdn(newMsisdn)
                            .status(com.softropic.payam.common.persistence.EntityStatus.ACTIVE)
                            .build();
                    platformConfigRepository.save(newConfig);
                    eventPublisher.publishEvent(new PlatformConfigChangedEvent(upper, "", newMsisdn));
                    log.info("Platform MSISDN created", kv("provider", upper), kv("event", "platform_config_created"));
                    return new PlatformConfigDto(upper, newMsisdn);
                });
    }
}
