package com.softropic.payam.platform.service;

import com.softropic.payam.common.persistence.EntityStatus;
import com.softropic.payam.platform.contract.PlatformConfigDto;
import com.softropic.payam.platform.contract.event.PlatformConfigChangedEvent;
import com.softropic.payam.platform.repo.PlatformConfig;
import com.softropic.payam.platform.repo.PlatformConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    private com.softropic.payam.platform.security.contract.util.Cryptopher pinCryptopher;

    @Mock
    private com.softropic.payam.platform.security.service.SecurityUtil securityUtil;

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
        when(securityUtil.getCurrentUserName()).thenReturn("admin@test");

        // When
        PlatformConfigDto result = platformConfigService.update(provider, newMsisdn, null);

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
        PlatformConfigDto result = platformConfigService.update(provider, newMsisdn, null);

        // Then
        assertThat(result.provider()).isEqualTo(provider);
        assertThat(result.platformMsisdn()).isEqualTo(newMsisdn);
        assertThat(result.pinConfigured()).isFalse();   // orElseGet branch builds fresh config without pin
        assertThat(result.pin()).isNull();
        verify(platformConfigRepository).save(any(PlatformConfig.class));
        verifyNoInteractions(eventPublisher);
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
        when(securityUtil.getCurrentUserName()).thenReturn("admin@test");

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
        when(securityUtil.getCurrentUserName()).thenReturn("admin@test");

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
        when(securityUtil.getCurrentUserName()).thenReturn("admin@test");

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

    // ---------------------------------------------------------------------
    // PIN-10: conditional event publishing — msisdnChanged / pinChanged flags
    // ---------------------------------------------------------------------

    @Test
    void update_shouldFireEventWithMsisdnChangedFlagWhenMsisdnChanges() {
        // Given
        String provider = "ORANGE";
        PlatformConfig existing = PlatformConfig.builder()
                .provider(provider)
                .platformMsisdn("111")
                .status(EntityStatus.ACTIVE)
                .build();
        when(platformConfigRepository.findByProvider(provider)).thenReturn(Optional.of(existing));
        when(securityUtil.getCurrentUserName()).thenReturn("admin@test");

        // When
        platformConfigService.update(provider, "222", null);

        // Then
        ArgumentCaptor<PlatformConfigChangedEvent> captor =
            ArgumentCaptor.forClass(PlatformConfigChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        PlatformConfigChangedEvent event = captor.getValue();
        assertThat(event.provider()).isEqualTo("ORANGE");
        assertThat(event.oldMsisdn()).isEqualTo("111");
        assertThat(event.newMsisdn()).isEqualTo("222");
        assertThat(event.msisdnChanged()).isTrue();
        assertThat(event.pinChanged()).isFalse();
        assertThat(event.changedBy()).isEqualTo("admin@test");
    }

    @Test
    void update_shouldFireEventWithPinChangedFlagWhenExistingPinIsUpdated() {
        // Given — existing config has MSISDN "111" AND an existing PIN
        String provider = "ORANGE";
        PlatformConfig existing = PlatformConfig.builder()
                .provider(provider)
                .platformMsisdn("111")
                .pin("ENC(OLD)")
                .status(EntityStatus.ACTIVE)
                .build();
        when(platformConfigRepository.findByProvider(provider)).thenReturn(Optional.of(existing));
        when(pinCryptopher.encrypt("9999")).thenReturn("ENC(9999)");
        when(securityUtil.getCurrentUserName()).thenReturn("admin@test");

        // When — MSISDN unchanged (same value), but PIN is being replaced
        platformConfigService.update(provider, "111", "9999");

        // Then
        ArgumentCaptor<PlatformConfigChangedEvent> captor =
            ArgumentCaptor.forClass(PlatformConfigChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        PlatformConfigChangedEvent event = captor.getValue();
        assertThat(event.msisdnChanged()).isFalse();
        assertThat(event.pinChanged()).isTrue();
        assertThat(event.changedBy()).isEqualTo("admin@test");
    }

    @Test
    void update_shouldFireEventWithBothFlagsWhenBothChange() {
        // Given — both MSISDN changes and PIN is being replaced (existing PIN present)
        String provider = "ORANGE";
        PlatformConfig existing = PlatformConfig.builder()
                .provider(provider)
                .platformMsisdn("111")
                .pin("ENC(OLD)")
                .status(EntityStatus.ACTIVE)
                .build();
        when(platformConfigRepository.findByProvider(provider)).thenReturn(Optional.of(existing));
        when(pinCryptopher.encrypt("9999")).thenReturn("ENC(9999)");
        when(securityUtil.getCurrentUserName()).thenReturn("admin@test");

        // When
        platformConfigService.update(provider, "222", "9999");

        // Then
        ArgumentCaptor<PlatformConfigChangedEvent> captor =
            ArgumentCaptor.forClass(PlatformConfigChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        PlatformConfigChangedEvent event = captor.getValue();
        assertThat(event.msisdnChanged()).isTrue();
        assertThat(event.pinChanged()).isTrue();
    }

    @Test
    void update_shouldNotFireEventWhenMsisdnUnchangedAndPinBlank() {
        // Given — MSISDN same, no PIN change
        PlatformConfig existing = PlatformConfig.builder()
                .provider("ORANGE").platformMsisdn("111").pin("ENC(OLD)").status(EntityStatus.ACTIVE).build();
        when(platformConfigRepository.findByProvider("ORANGE")).thenReturn(Optional.of(existing));

        // When
        platformConfigService.update("ORANGE", "111", "");

        // Then
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void update_shouldNotFireEventWhenMsisdnUnchangedAndPinNull() {
        // Given — MSISDN same, PIN null (leave untouched)
        PlatformConfig existing = PlatformConfig.builder()
                .provider("ORANGE").platformMsisdn("111").pin("ENC(OLD)").status(EntityStatus.ACTIVE).build();
        when(platformConfigRepository.findByProvider("ORANGE")).thenReturn(Optional.of(existing));

        // When
        platformConfigService.update("ORANGE", "111", null);

        // Then
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void update_shouldNotFireEventOnFirstTimePinCreation() {
        // Given — MSISDN same, PIN is first-time (oldPin was null)
        PlatformConfig existing = PlatformConfig.builder()
                .provider("ORANGE").platformMsisdn("111").status(EntityStatus.ACTIVE).build();
        when(platformConfigRepository.findByProvider("ORANGE")).thenReturn(Optional.of(existing));
        when(pinCryptopher.encrypt("1234")).thenReturn("ENC(1234)");

        // When — MSISDN unchanged, PIN is first-time creation
        platformConfigService.update("ORANGE", "111", "1234");

        // Then — no event (first-time PIN suppressed per PIN-10)
        verifyNoInteractions(eventPublisher);
        // But PIN was still encrypted and persisted
        verify(pinCryptopher).encrypt("1234");
    }

    @Test
    void update_shouldFireEventWithFirstTimePinButMsisdnAlsoChanged() {
        // Given — MSISDN changes AND first-time PIN (oldPin was null)
        PlatformConfig existing = PlatformConfig.builder()
                .provider("ORANGE").platformMsisdn("111").status(EntityStatus.ACTIVE).build();
        when(platformConfigRepository.findByProvider("ORANGE")).thenReturn(Optional.of(existing));
        when(pinCryptopher.encrypt("1234")).thenReturn("ENC(1234)");
        when(securityUtil.getCurrentUserName()).thenReturn("admin@test");

        // When
        platformConfigService.update("ORANGE", "222", "1234");

        // Then — event fires because MSISDN changed; pinChanged=false (first-time PIN per PIN-10)
        ArgumentCaptor<PlatformConfigChangedEvent> captor =
            ArgumentCaptor.forClass(PlatformConfigChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        PlatformConfigChangedEvent event = captor.getValue();
        assertThat(event.msisdnChanged()).isTrue();
        assertThat(event.pinChanged()).isFalse();
    }

    @Test
    void update_shouldNotFireEventFromOrElseGetBranch() {
        // Given — repository returns empty (new row creation path)
        when(platformConfigRepository.findByProvider("MTN")).thenReturn(Optional.empty());

        // When
        platformConfigService.update("MTN", "987", null);

        // Then — orElseGet (new-row creation) must not publish any event
        verifyNoInteractions(eventPublisher);
    }

    // ---------------------------------------------------------------------
    // PIN-09 (GAP-01): orElseGet branch persists PIN on first-row creation
    // ---------------------------------------------------------------------

    @Test
    void update_shouldEncryptAndPersistPinOnNewRowCreation() {
        // Given — no existing row; PIN provided
        String provider = "MTN";
        String newMsisdn = "987654";
        String plainPin = "abcd";
        String ciphertext = "ENC(abcd)";
        when(platformConfigRepository.findByProvider(provider)).thenReturn(Optional.empty());
        when(pinCryptopher.encrypt(plainPin)).thenReturn(ciphertext);

        // When
        PlatformConfigDto result = platformConfigService.update(provider, newMsisdn, plainPin);

        // Then — response reflects persisted PIN
        assertThat(result.provider()).isEqualTo(provider);
        assertThat(result.platformMsisdn()).isEqualTo(newMsisdn);
        assertThat(result.pinConfigured()).isTrue();       // NOT the hardcoded false
        assertThat(result.pin()).isNull();                  // PIN-04: never returned in DTO

        // And — ciphertext went through pinCryptopher.encrypt()
        verify(pinCryptopher).encrypt(plainPin);

        // And — the saved entity carries the ciphertext (captured from save())
        ArgumentCaptor<PlatformConfig> captor = ArgumentCaptor.forClass(PlatformConfig.class);
        verify(platformConfigRepository).save(captor.capture());
        assertThat(captor.getValue().getPin()).isEqualTo(ciphertext);

        // And — PIN-10 rule: no event on first-time row creation
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void update_shouldCreateNewRowWithNoPinWhenPinIsBlank() {
        // Given — no existing row; blank PIN (both null and empty string)
        when(platformConfigRepository.findByProvider("MTN")).thenReturn(Optional.empty());

        // When — null pin
        PlatformConfigDto nullResult = platformConfigService.update("MTN", "987654", null);
        // Then
        assertThat(nullResult.pinConfigured()).isFalse();

        // When — empty-string pin
        PlatformConfigDto emptyResult = platformConfigService.update("MTN", "987654", "");
        // Then
        assertThat(emptyResult.pinConfigured()).isFalse();

        // pinCryptopher.encrypt() was never called for either blank case
        verify(pinCryptopher, never()).encrypt(any());
        // No event for either case
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void update_shouldResolveChangedByFromSecurityUtilForEventPayload() {
        // Given
        PlatformConfig existing = PlatformConfig.builder()
                .provider("ORANGE").platformMsisdn("111").status(EntityStatus.ACTIVE).build();
        when(platformConfigRepository.findByProvider("ORANGE")).thenReturn(Optional.of(existing));
        when(securityUtil.getCurrentUserName()).thenReturn("admin@test");

        // When
        platformConfigService.update("ORANGE", "222", null);

        // Then — changedBy must equal the mocked SecurityUtil return value
        ArgumentCaptor<PlatformConfigChangedEvent> captor =
            ArgumentCaptor.forClass(PlatformConfigChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().changedBy()).isEqualTo("admin@test");
    }

    @Test
    void update_shouldFallBackToUnknownWhenSecurityUtilReturnsNull() {
        // Given
        PlatformConfig existing = PlatformConfig.builder()
                .provider("ORANGE").platformMsisdn("111").status(EntityStatus.ACTIVE).build();
        when(platformConfigRepository.findByProvider("ORANGE")).thenReturn(Optional.of(existing));
        when(securityUtil.getCurrentUserName()).thenReturn(null);

        // When
        platformConfigService.update("ORANGE", "222", null);

        // Then — changedBy must be "unknown" (never null) when SecurityUtil returns null
        ArgumentCaptor<PlatformConfigChangedEvent> captor =
            ArgumentCaptor.forClass(PlatformConfigChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().changedBy()).isEqualTo("unknown");
    }
}
