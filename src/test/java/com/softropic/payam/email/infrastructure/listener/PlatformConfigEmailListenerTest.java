package com.softropic.payam.email.infrastructure.listener;

import com.softropic.payam.email.contract.EmailTemplate;
import com.softropic.payam.email.contract.Envelope;
import com.softropic.payam.platform.contract.event.PlatformConfigChangedEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PlatformConfigEmailListenerTest {

    private static final String ADMIN_EMAIL = "admin@payam.test";

    @Mock
    private ApplicationEventPublisher publisher;

    @Captor
    private ArgumentCaptor<Envelope> envelopeCaptor;

    private PlatformConfigEmailListener listener;

    @BeforeEach
    void setUp() {
        listener = new PlatformConfigEmailListener(publisher, ADMIN_EMAIL);
    }

    @Test
    void onConfigChanged_populatesFullDataMapForMsisdnOnlyChange() {
        PlatformConfigChangedEvent event = new PlatformConfigChangedEvent(
            "ORANGE", "111", "222", true, false, "admin@test");

        listener.onConfigChanged(event);

        verify(publisher).publishEvent(envelopeCaptor.capture());
        Envelope envelope = envelopeCaptor.getValue();
        assertThat(envelope.data())
            .containsEntry("provider", "ORANGE")
            .containsEntry("oldMsisdn", "111")
            .containsEntry("newMsisdn", "222")
            .containsEntry("msisdnChanged", true)
            .containsEntry("pinChanged", false)
            .containsEntry("changedBy", "admin@test")
            .containsKey("changedAt");
        assertThat(envelope.data().get("changedAt")).isNotNull();
        assertThat(envelope.data().get("changedAt").toString()).isNotBlank();
    }

    @Test
    void onConfigChanged_populatesFullDataMapForPinOnlyChange() {
        PlatformConfigChangedEvent event = new PlatformConfigChangedEvent(
            "MTN", "333", "333", false, true, "alice");
        listener.onConfigChanged(event);
        verify(publisher).publishEvent(envelopeCaptor.capture());
        assertThat(envelopeCaptor.getValue().data())
            .containsEntry("msisdnChanged", false)
            .containsEntry("pinChanged", true);
    }

    @Test
    void onConfigChanged_populatesFullDataMapForBothFieldsChange() {
        PlatformConfigChangedEvent event = new PlatformConfigChangedEvent(
            "ORANGE", "111", "222", true, true, "bob");
        listener.onConfigChanged(event);
        verify(publisher).publishEvent(envelopeCaptor.capture());
        assertThat(envelopeCaptor.getValue().data())
            .containsEntry("msisdnChanged", true)
            .containsEntry("pinChanged", true);
    }

    @Test
    void onConfigChanged_neverPutsPinValueInDataMap() {
        PlatformConfigChangedEvent event = new PlatformConfigChangedEvent(
            "ORANGE", "111", "222", true, true, "bob");
        listener.onConfigChanged(event);
        verify(publisher).publishEvent(envelopeCaptor.capture());
        Envelope envelope = envelopeCaptor.getValue();

        // Only the boolean "pinChanged" key is allowed; any other key whose name
        // case-insensitively contains "pin" is a leak.
        List<String> leakingKeys = envelope.data().keySet().stream()
            .filter(k -> k.toLowerCase().contains("pin") && !"pinChanged".equals(k))
            .toList();
        assertThat(leakingKeys).isEmpty();

        // No value in the map should look like ciphertext (AES-encrypted values start with "ENC(")
        assertThat(envelope.data().values())
            .noneMatch(v -> v != null && v.toString().startsWith("ENC("));
    }

    @Test
    void onConfigChanged_usesPlatformConfigChangedTemplate() {
        PlatformConfigChangedEvent event = new PlatformConfigChangedEvent(
            "ORANGE", "111", "222", true, false, "admin");
        listener.onConfigChanged(event);
        verify(publisher).publishEvent(envelopeCaptor.capture());
        assertThat(envelopeCaptor.getValue().emailTemplate())
            .isEqualTo(EmailTemplate.PLATFORM_CONFIG_CHANGED);
    }

    @Test
    void onConfigChanged_sendsToConfiguredNotificationEmail() {
        PlatformConfigChangedEvent event = new PlatformConfigChangedEvent(
            "ORANGE", "111", "222", true, false, "admin");
        listener.onConfigChanged(event);
        verify(publisher).publishEvent(envelopeCaptor.capture());
        Envelope envelope = envelopeCaptor.getValue();
        assertThat(envelope.recipients()).hasSize(1);
        assertThat(envelope.recipients().get(0).getEmail()).isEqualTo(ADMIN_EMAIL);
    }

    @Test
    void onConfigChanged_fallsBackToEmptyStringForNullOldMsisdn() {
        PlatformConfigChangedEvent event = new PlatformConfigChangedEvent(
            "ORANGE", null, "222", true, false, "admin");
        listener.onConfigChanged(event);
        verify(publisher).publishEvent(envelopeCaptor.capture());
        assertThat(envelopeCaptor.getValue().data()).containsEntry("oldMsisdn", "");
    }

    @Test
    void onConfigChanged_populatesChangedByUnknownWhenEventChangedByIsNull() {
        PlatformConfigChangedEvent event = new PlatformConfigChangedEvent(
            "ORANGE", "111", "222", true, false, null);
        listener.onConfigChanged(event);
        verify(publisher).publishEvent(envelopeCaptor.capture());
        assertThat(envelopeCaptor.getValue().data()).containsEntry("changedBy", "unknown");
    }
}
