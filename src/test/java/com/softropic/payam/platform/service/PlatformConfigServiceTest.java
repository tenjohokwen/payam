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
}
