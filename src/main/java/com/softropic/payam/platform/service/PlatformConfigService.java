package com.softropic.payam.platform.service;

import com.softropic.payam.common.exception.ResourceNotFoundException;
import com.softropic.payam.platform.contract.PinDto;
import com.softropic.payam.platform.contract.PlatformConfigDto;
import com.softropic.payam.platform.contract.event.PlatformConfigChangedEvent;
import com.softropic.payam.platform.repo.PlatformConfig;
import com.softropic.payam.platform.repo.PlatformConfigRepository;
import com.softropic.payam.platform.security.contract.util.Cryptopher;
import com.softropic.payam.platform.security.service.SecurityUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Business logic for reading and updating platform configuration (MSISDNs per provider).
 *
 * <p>The {@code update()} method conditionally publishes a {@link PlatformConfigChangedEvent}
 * carrying msisdnChanged/pinChanged flags and the admin username (PIN-10). The event is
 * suppressed when neither field changes, on first-time PIN creation, and on new-row creation.
 * {@code @TransactionalEventListener(AFTER_COMMIT)} listeners (e.g., the email notification)
 * fire after the DB commit.
 */
@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class PlatformConfigService {

    private final PlatformConfigRepository platformConfigRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Cryptopher pinCryptopher;
    private final SecurityUtil securityUtil;

    /**
     * Return all platform config rows (one per provider).
     *
     * @return list of DTOs; typically two elements (ORANGE and MTN)
     */
    @Transactional(readOnly = true)
    public List<PlatformConfigDto> findAll() {
        return platformConfigRepository.findAll().stream()
                .map(c -> new PlatformConfigDto(c.getProvider(), c.getPlatformMsisdn(), c.getPin() != null, null))
                .toList();
    }

    /**
     * Returns the platform config DTO for the given provider.
     *
     * @param provider provider key (case-insensitive; normalised to upper-case internally)
     * @return the DTO for the requested provider
     * @throws IllegalStateException if no config row exists for the given provider
     */
    @Transactional(readOnly = true)
    public PlatformConfigDto findByProvider(String provider) {
        String upper = provider.toUpperCase();
        return platformConfigRepository.findByProvider(upper)
                .map(c -> new PlatformConfigDto(c.getProvider(), c.getPlatformMsisdn(), c.getPin() != null, null))
                .orElseThrow(() -> new IllegalStateException(
                    "Platform MSISDN not configured for provider: " + provider));
    }

    /**
     * Update the platform MSISDN and (optionally) the PIN for the given provider (upsert).
     *
     * <p>If the provider exists, its MSISDN is updated; the PIN is encrypted and updated only
     * when {@code pin} is non-blank. If not, a new row is created and — when a non-blank
     * {@code pin} is supplied — the PIN is encrypted and persisted on the same insert
     * (PIN-09). No {@link PlatformConfigChangedEvent} is published on first-time row
     * creation regardless of whether a PIN was set (PIN-10).
     *
     * <p>MSISDN and PIN updates occur in the same {@code @Transactional} method, so they
     * commit atomically (PIN-03 atomicity guarantee).
     *
     * <p>Conditionally publishes a {@link PlatformConfigChangedEvent} carrying msisdnChanged/pinChanged
     * flags and the admin username (PIN-10). The event is suppressed when neither field changes,
     * on first-time PIN creation, and on new-row creation.
     *
     * @param provider   provider key (case-insensitive; normalised to upper-case)
     * @param newMsisdn  the new MSISDN value
     * @param pin        the new plaintext PIN (alphanumeric 4-8 chars), or {@code null} /
     *                   blank to leave the existing PIN untouched (PIN-08)
     * @return updated DTO reflecting the persisted state; {@code pin} is always {@code null}
     *         on the response, {@code pinConfigured} reflects whether a PIN is now stored
     */
    public PlatformConfigDto update(String provider, String newMsisdn, String pin) {
        String upper = provider.toUpperCase();
        return platformConfigRepository.findByProvider(upper)
                .map(config -> {
                    String oldMsisdn = config.getPlatformMsisdn();
                    String oldPin    = config.getPin();              // snapshot BEFORE any mutation (PIN-10)

                    config.updateMsisdn(newMsisdn);
                    if (StringUtils.isNotBlank(pin)) {
                        String ciphertext = pinCryptopher.encrypt(pin);
                        config.updatePin(ciphertext);
                    }
                    platformConfigRepository.save(config);

                    boolean msisdnChanged = !Objects.equals(oldMsisdn, newMsisdn);
                    boolean pinChanged    = StringUtils.isNotBlank(pin) && oldPin != null;

                    if (msisdnChanged || pinChanged) {
                        String rawName = securityUtil.getCurrentUserName();
                        String changedBy = rawName != null ? rawName : "unknown";
                        eventPublisher.publishEvent(new PlatformConfigChangedEvent(
                            upper, oldMsisdn, newMsisdn, msisdnChanged, pinChanged, changedBy));
                    }
                    log.info("Platform config updated", kv("provider", upper), kv("event", "platform_config_updated"));
                    return new PlatformConfigDto(upper, newMsisdn, config.getPin() != null, null);
                })
                .orElseGet(() -> {
                    PlatformConfig newConfig = PlatformConfig.builder()
                            .provider(upper)
                            .platformMsisdn(newMsisdn)
                            .status(com.softropic.payam.common.persistence.EntityStatus.ACTIVE)
                            .build();
                    if (StringUtils.isNotBlank(pin)) {
                        String ciphertext = pinCryptopher.encrypt(pin);
                        newConfig.updatePin(ciphertext);
                    }
                    platformConfigRepository.save(newConfig);
                    // PIN-10: first-time row creation does not publish an event.
                    log.info("Platform config created", kv("provider", upper), kv("event", "platform_config_created"));
                    return new PlatformConfigDto(upper, newMsisdn, newConfig.getPin() != null, null);
                });
    }

    /**
     * Convenience overload: update MSISDN only (no PIN change).
     */
    public PlatformConfigDto update(String provider, String newMsisdn) {
        return update(provider, newMsisdn, null);
    }

    /**
     * Returns the decrypted PIN for the given provider (PIN-05).
     *
     * <p>Throws {@link ResourceNotFoundException} (mapped to HTTP 404 by ApiAdvice) when a
     * config row exists but no PIN has been configured.
     *
     * <p>Throws {@link IllegalStateException} (mapped to HTTP 409 by ApiAdvice) when no
     * config row exists for the provider — same shape as {@link #findByProvider}.
     *
     * @param provider provider key (case-insensitive; normalised to upper-case)
     * @return a {@link PinDto} carrying the plaintext PIN
     * @throws ResourceNotFoundException when the row exists but {@code pin} is {@code null}
     * @throws IllegalStateException     when no row exists for the provider
     */
    @Transactional(readOnly = true)
    public PinDto findPinByProvider(String provider) {
        String upper = provider.toUpperCase();
        PlatformConfig config = platformConfigRepository.findByProvider(upper)
                .orElseThrow(() -> new IllegalStateException(
                    "Platform config not found for provider: " + upper));
        if (config.getPin() == null) {
            throw new ResourceNotFoundException(
                "No PIN configured for provider: " + upper, upper);
        }
        return new PinDto(pinCryptopher.decrypt(config.getPin()));
    }
}
