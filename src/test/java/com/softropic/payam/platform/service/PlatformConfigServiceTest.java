package com.softropic.payam.platform.service;

import com.softropic.payam.common.persistence.EntityStatus;
import com.softropic.payam.platform.contract.PlatformConfigDto;
import com.softropic.payam.platform.contract.event.PlatformConfigChangedEvent;
import com.softropic.payam.platform.repo.PlatformConfig;
import com.softropic.payam.platform.repo.PlatformConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlatformConfigServiceTest {

    @Mock
    private PlatformConfigRepository platformConfigRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private com.softropic.payam.security.contract.util.Cryptopher pinCryptopher;

    @InjectMocks
    private PlatformConfigService platformConfigService;

    @Test
    void update_shouldUpdateExistingConfig() {
        // Given
        String provider = "ORANGE";
        String oldMsisdn = "123456";
        String newMsisdn = "654321";
        PlatformConfig existing = PlatformConfig.builder()
                .provider(provider)
                .platformMsisdn(oldMsisdn)
                .status(EntityStatus.ACTIVE)
                .build();

        when(platformConfigRepository.findByProvider(provider)).thenReturn(Optional.of(existing));

        // When
        PlatformConfigDto result = platformConfigService.update(provider, newMsisdn);

        // Then
        assertThat(result.provider()).isEqualTo(provider);
        assertThat(result.platformMsisdn()).isEqualTo(newMsisdn);
        assertThat(existing.getPlatformMsisdn()).isEqualTo(newMsisdn);
        assertThat(result.pinConfigured()).isFalse();   // existing builder sets no pin
        assertThat(result.pin()).isNull();              // service never returns pin on update path
        verify(platformConfigRepository).save(existing);
        verify(eventPublisher).publishEvent(any(PlatformConfigChangedEvent.class));
    }

    @Test
    void findByProvider_shouldReturnDtoWhenProviderExists() {
        // Given
        PlatformConfig config = PlatformConfig.builder()
                .provider("ORANGE")
                .platformMsisdn("652000001")
                .status(EntityStatus.ACTIVE)
                .build();
        when(platformConfigRepository.findByProvider("ORANGE")).thenReturn(Optional.of(config));

        // When
        PlatformConfigDto result = platformConfigService.findByProvider("ORANGE");

        // Then
        assertThat(result.provider()).isEqualTo("ORANGE");
        assertThat(result.platformMsisdn()).isEqualTo("652000001");
        assertThat(result.pinConfigured()).isFalse();
        assertThat(result.pin()).isNull();
    }

    @Test
    void findByProvider_shouldThrowIllegalStateWhenProviderNotFound() {
        // Given
        when(platformConfigRepository.findByProvider("ORANGE")).thenReturn(Optional.empty());

        // When / Then
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> platformConfigService.findByProvider("ORANGE"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Platform MSISDN not configured for provider: ORANGE");
    }

    @Test
    void update_shouldCreateNewConfigIfNotFound() {
        // Given
        String provider = "MTN";
        String newMsisdn = "987654";
        when(platformConfigRepository.findByProvider(provider)).thenReturn(Optional.empty());

        // When
        PlatformConfigDto result = platformConfigService.update(provider, newMsisdn);

        // Then
        assertThat(result.provider()).isEqualTo(provider);
        assertThat(result.platformMsisdn()).isEqualTo(newMsisdn);
        assertThat(result.pinConfigured()).isFalse();   // orElseGet branch builds fresh config without pin
        assertThat(result.pin()).isNull();
        verify(platformConfigRepository).save(any(PlatformConfig.class));
        verify(eventPublisher).publishEvent(any(PlatformConfigChangedEvent.class));
    }

    // ---------------------------------------------------------------------
    // PIN-03: update() with optional pin parameter — encrypt + persist or no-op
    // ---------------------------------------------------------------------

    @Test
    void update_shouldEncryptAndPersistPinWhenProvided() {
        // Given
        String provider = "ORANGE";
        String newMsisdn = "654321";
        String plaintextPin = "1234";
        String ciphertext = "ENC(1234)";
        PlatformConfig existing = PlatformConfig.builder()
                .provider(provider)
                .platformMsisdn("OLD")
                .status(EntityStatus.ACTIVE)
                .build();

        when(platformConfigRepository.findByProvider(provider)).thenReturn(Optional.of(existing));
        when(pinCryptopher.encrypt(plaintextPin)).thenReturn(ciphertext);

        // When
        PlatformConfigDto result = platformConfigService.update(provider, newMsisdn, plaintextPin);

        // Then
        verify(pinCryptopher).encrypt(plaintextPin);
        // The entity.updatePin(ciphertext) call must have set the pin field
        assertThat(existing.getPin()).isEqualTo(ciphertext);
        assertThat(existing.getPlatformMsisdn()).isEqualTo(newMsisdn);
        verify(platformConfigRepository).save(existing);
        verify(eventPublisher).publishEvent(any(PlatformConfigChangedEvent.class));
        // PIN-04: returned DTO reflects pin=null (no leakage) and pinConfigured=true (entity now has pin)
        assertThat(result.pin()).isNull();
        assertThat(result.pinConfigured()).isTrue();
    }

    @Test
    void update_shouldNotEncryptOrTouchPinWhenPinIsNull() {
        // Given
        String provider = "ORANGE";
        String newMsisdn = "654321";
        String existingCiphertext = "ENC(EXISTING)";
        PlatformConfig existing = PlatformConfig.builder()
                .provider(provider)
                .platformMsisdn("OLD")
                .pin(existingCiphertext)
                .status(EntityStatus.ACTIVE)
                .build();

        when(platformConfigRepository.findByProvider(provider)).thenReturn(Optional.of(existing));

        // When
        PlatformConfigDto result = platformConfigService.update(provider, newMsisdn, null);

        // Then
        verify(pinCryptopher, never()).encrypt(any());
        // Existing ciphertext preserved — service did not touch the pin field
        assertThat(existing.getPin()).isEqualTo(existingCiphertext);
        assertThat(existing.getPlatformMsisdn()).isEqualTo(newMsisdn);
        verify(platformConfigRepository).save(existing);
        // pinConfigured still true because the persisted pin remains
        assertThat(result.pinConfigured()).isTrue();
        assertThat(result.pin()).isNull();
    }

    @Test
    void update_shouldNotEncryptOrTouchPinWhenPinIsBlank() {
        // Given — same setup as the null case but with empty / whitespace-only pin
        String provider = "ORANGE";
        String existingCiphertext = "ENC(EXISTING)";
        PlatformConfig existing = PlatformConfig.builder()
                .provider(provider)
                .platformMsisdn("OLD")
                .pin(existingCiphertext)
                .status(EntityStatus.ACTIVE)
                .build();

        when(platformConfigRepository.findByProvider(provider)).thenReturn(Optional.of(existing));

        // When (empty string — the frontend sends "" to mean "do not change pin")
        PlatformConfigDto resultEmpty = platformConfigService.update(provider, "654321", "");

        // Then
        verify(pinCryptopher, never()).encrypt(any());
        assertThat(existing.getPin()).isEqualTo(existingCiphertext);
        assertThat(resultEmpty.pinConfigured()).isTrue();

        // When (whitespace-only — also treated as "do not change pin")
        PlatformConfigDto resultWhitespace = platformConfigService.update(provider, "654321", "   ");

        // Then (still untouched after the second call)
        verify(pinCryptopher, never()).encrypt(any());
        assertThat(existing.getPin()).isEqualTo(existingCiphertext);
        assertThat(resultWhitespace.pinConfigured()).isTrue();
    }

    // ---------------------------------------------------------------------
    // PIN-05: findPinByProvider() — decrypt + 404 + 409
    // ---------------------------------------------------------------------

    @Test
    void findPinByProvider_shouldReturnDecryptedPlaintext() {
        // Given
        String ciphertext = "ENC(1234)";
        String plaintext = "1234";
        PlatformConfig config = PlatformConfig.builder()
                .provider("ORANGE")
                .platformMsisdn("652000001")
                .pin(ciphertext)
                .status(EntityStatus.ACTIVE)
                .build();
        when(platformConfigRepository.findByProvider("ORANGE")).thenReturn(Optional.of(config));
        when(pinCryptopher.decrypt(ciphertext)).thenReturn(plaintext);

        // When
        com.softropic.payam.platform.contract.PinDto result =
            platformConfigService.findPinByProvider("ORANGE");

        // Then
        assertThat(result.pin()).isEqualTo(plaintext);
        verify(pinCryptopher).decrypt(ciphertext);
    }

    @Test
    void findPinByProvider_shouldThrowResourceNotFoundWhenPinIsNull() {
        // Given — config exists but pin is null
        PlatformConfig config = PlatformConfig.builder()
                .provider("ORANGE")
                .platformMsisdn("652000001")
                .status(EntityStatus.ACTIVE)
                .build();
        when(platformConfigRepository.findByProvider("ORANGE")).thenReturn(Optional.of(config));

        // When / Then — must be ResourceNotFoundException (not IllegalStateException)
        // so that ApiAdvice maps it to HTTP 404 (PIN-05 not-found semantics)
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> platformConfigService.findPinByProvider("ORANGE"))
            .isInstanceOf(com.softropic.payam.common.exception.ResourceNotFoundException.class)
            .hasMessageContaining("ORANGE");
        verify(pinCryptopher, never()).decrypt(any());
    }

    @Test
    void findPinByProvider_shouldThrowIllegalStateWhenProviderNotFound() {
        // Given — no config row at all
        when(platformConfigRepository.findByProvider("ORANGE")).thenReturn(Optional.empty());

        // When / Then — IllegalStateException → HTTP 409 (mirrors findByProvider semantics
        // for "config row absent"; reserve 404 for the more specific "row exists but no pin set")
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> platformConfigService.findPinByProvider("ORANGE"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ORANGE");
        verify(pinCryptopher, never()).decrypt(any());
    }
}
