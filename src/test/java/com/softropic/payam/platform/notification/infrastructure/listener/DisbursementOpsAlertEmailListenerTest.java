package com.softropic.payam.platform.notification.infrastructure.listener;

import com.softropic.payam.payment.core.contract.MobilePaymentProvider;
import com.softropic.payam.payment.disbursement.contract.event.DisbursementAdminApprovalRequiredEvent;
import com.softropic.payam.payment.disbursement.contract.event.InsufficientFundsAlertEvent;
import com.softropic.payam.platform.notification.contract.EmailTemplate;
import com.softropic.payam.platform.notification.contract.Envelope;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DisbursementOpsAlertEmailListenerTest {

    private static final String OPS_EMAIL = "ops@example.com";

    private ApplicationEventPublisher publisher;
    private DisbursementOpsAlertEmailListener sut;

    @BeforeEach
    void setUp() {
        publisher = mock(ApplicationEventPublisher.class);
        sut = new DisbursementOpsAlertEmailListener(publisher, OPS_EMAIL);
    }

    @Test
    void onAdminApprovalRequired_publishesEnvelopeWithAdminApprovalTemplate() {
        DisbursementAdminApprovalRequiredEvent event = new DisbursementAdminApprovalRequiredEvent(
                "dsb-uuid-1", 7L, BigDecimal.valueOf(6_000_000), "XAF",
                "+237670000000", "ref-1", "Above threshold", Instant.now());

        sut.onAdminApprovalRequired(event);

        ArgumentCaptor<Envelope> captor = ArgumentCaptor.forClass(Envelope.class);
        verify(publisher).publishEvent(captor.capture());
        Envelope sent = captor.getValue();
        assertThat(sent.emailTemplate()).isEqualTo(EmailTemplate.DISBURSEMENT_ADMIN_APPROVAL_REQUIRED);
        assertThat(sent.recipients()).hasSize(1);
        assertThat(sent.recipients().get(0).getEmail()).isEqualTo(OPS_EMAIL);
        assertThat(sent.data()).containsEntry("disbursementId", "dsb-uuid-1");
        assertThat(sent.data()).containsEntry("tenantId", 7L);
        assertThat(sent.data()).containsEntry("currency", "XAF");
    }

    @Test
    void onInsufficientFunds_publishesEnvelopeWithInsufficientFundsTemplate() {
        InsufficientFundsAlertEvent event = new InsufficientFundsAlertEvent(
                "dsb-uuid-2", 8L, MobilePaymentProvider.MTN,
                BigDecimal.valueOf(50_000), "XAF",
                "NOT_ENOUGH_FUNDS", "Provider account low", Instant.now());

        sut.onInsufficientFunds(event);

        ArgumentCaptor<Envelope> captor = ArgumentCaptor.forClass(Envelope.class);
        verify(publisher).publishEvent(captor.capture());
        Envelope sent = captor.getValue();
        assertThat(sent.emailTemplate()).isEqualTo(EmailTemplate.DISBURSEMENT_INSUFFICIENT_FUNDS_ALERT);
        assertThat(sent.recipients()).hasSize(1);
        assertThat(sent.recipients().get(0).getEmail()).isEqualTo(OPS_EMAIL);
        assertThat(sent.data()).containsEntry("disbursementId", "dsb-uuid-2");
        assertThat(sent.data()).containsEntry("provider", "MTN");
        assertThat(sent.data()).containsEntry("providerErrorCode", "NOT_ENOUGH_FUNDS");
    }

    @Test
    void onAdminApprovalRequired_withNullOptionalFields_doesNotThrow() {
        DisbursementAdminApprovalRequiredEvent event = new DisbursementAdminApprovalRequiredEvent(
                "dsb-uuid-3", 9L, BigDecimal.valueOf(7_000_000), "XAF",
                "+237670000001", null, null, null);

        sut.onAdminApprovalRequired(event);

        ArgumentCaptor<Envelope> captor = ArgumentCaptor.forClass(Envelope.class);
        verify(publisher).publishEvent(captor.capture());
        Envelope sent = captor.getValue();
        assertThat(sent.emailTemplate()).isEqualTo(EmailTemplate.DISBURSEMENT_ADMIN_APPROVAL_REQUIRED);
        assertThat(sent.data()).containsEntry("reference", "");
        assertThat(sent.data()).containsEntry("adminNote", "");
        assertThat(sent.data()).containsEntry("submittedAt", "");
    }

    @Test
    void onInsufficientFunds_withNullOptionalFields_doesNotThrow() {
        InsufficientFundsAlertEvent event = new InsufficientFundsAlertEvent(
                "dsb-uuid-4", 10L, null,
                BigDecimal.valueOf(30_000), "XAF",
                null, null, null);

        sut.onInsufficientFunds(event);

        ArgumentCaptor<Envelope> captor = ArgumentCaptor.forClass(Envelope.class);
        verify(publisher).publishEvent(captor.capture());
        Envelope sent = captor.getValue();
        assertThat(sent.emailTemplate()).isEqualTo(EmailTemplate.DISBURSEMENT_INSUFFICIENT_FUNDS_ALERT);
        assertThat(sent.data()).containsEntry("provider", "");
        assertThat(sent.data()).containsEntry("providerErrorCode", "");
        assertThat(sent.data()).containsEntry("providerMessage", "");
        assertThat(sent.data()).containsEntry("failedAt", "");
    }
}
