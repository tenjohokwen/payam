package com.softropic.payam.disbursement.service;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.common.payment.ProviderResult;
import com.softropic.payam.disbursement.contract.DisbursementStatus;
import com.softropic.payam.disbursement.repo.Disbursement;
import com.softropic.payam.disbursement.repo.DisbursementRepository;
import com.softropic.payam.transaction.contract.LedgerFlow;
import com.softropic.payam.transaction.contract.TransactionStatus;
import com.softropic.payam.webhook.contract.WebhookEnqueueRequestedEvent;
import com.softropic.payam.webhook.contract.WebhookReceivedEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DisbursementCallbackTransitionServiceTest {

    private DisbursementRepository repo;
    private ApplicationEventPublisher eventPublisher;
    private DisbursementCallbackTransitionService sut;

    @BeforeEach
    void setUp() {
        repo = mock(DisbursementRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        // FEE-01 / SCHEMA-03: wallet model retired in v11 — WalletBalanceService no longer injected
        sut = new DisbursementCallbackTransitionService(repo, eventPublisher);
    }

    private Disbursement disbursementInState(DisbursementStatus state, MobilePaymentProvider provider) {
        Disbursement d = Disbursement.builder()
            .disbursementId("dsb-001")
            .tenantId(1001L)
            .recipientMsisdn("237691111111")
            .amount(new BigDecimal("700.00"))
            .currency("XAF")
            .reference("merchant-ref-001")
            .disbursementStatus(state)
            .provider(provider)
            .pollAttempts(0)
            .build();
        return d;
    }

    private WebhookReceivedEvent event(MobilePaymentProvider provider) {
        return new WebhookReceivedEvent("dsb-001", provider, "ref-abc", "trace-1", LedgerFlow.DISBURSEMENT);
    }

    @Test
    void successPath_transitionsToSUCCESS_publishesCompletedEvent() {
        Disbursement d = disbursementInState(DisbursementStatus.PROCESSING, MobilePaymentProvider.MTN);
        when(repo.findByDisbursementIdForUpdate("dsb-001")).thenReturn(Optional.of(d));
        // MTN raw status SUCCESSFUL → TransactionStatus.SUCCESS via MtnStatusMapper
        ProviderResult result = ProviderResult.success("ref-abc", "SUCCESSFUL");

        sut.applyDisbursementTransition(event(MobilePaymentProvider.MTN), result);

        assertThat(d.getDisbursementStatus()).isEqualTo(DisbursementStatus.SUCCESS);

        ArgumentCaptor<WebhookEnqueueRequestedEvent> captor =
            ArgumentCaptor.forClass(WebhookEnqueueRequestedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("DISBURSEMENT_COMPLETED");
        assertThat(captor.getValue().status()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(captor.getValue().transactionId()).isEqualTo("dsb-001");
        assertThat(captor.getValue().tenantId()).isEqualTo(1001L);
        assertThat(captor.getValue().externalReference()).isEqualTo("merchant-ref-001");
    }

    @Test
    void failedPath_transitionsToFAILED_publishesFailedEvent() {
        // SCHEMA-03: wallet model retired — no wallet release on FAILED callback
        Disbursement d = disbursementInState(DisbursementStatus.PROCESSING, MobilePaymentProvider.MTN);
        when(repo.findByDisbursementIdForUpdate("dsb-001")).thenReturn(Optional.of(d));
        // MTN raw status FAILED → TransactionStatus.FAILED via MtnStatusMapper
        ProviderResult result = ProviderResult.success("ref-abc", "FAILED");

        sut.applyDisbursementTransition(event(MobilePaymentProvider.MTN), result);

        assertThat(d.getDisbursementStatus()).isEqualTo(DisbursementStatus.FAILED);

        ArgumentCaptor<WebhookEnqueueRequestedEvent> captor =
            ArgumentCaptor.forClass(WebhookEnqueueRequestedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("DISBURSEMENT_FAILED");
        assertThat(captor.getValue().status()).isEqualTo(TransactionStatus.FAILED);
    }

    @Test
    void replayGuard_alreadyTerminal_returnsWithoutSideEffects() {
        Disbursement d = disbursementInState(DisbursementStatus.SUCCESS, MobilePaymentProvider.MTN);
        when(repo.findByDisbursementIdForUpdate("dsb-001")).thenReturn(Optional.of(d));
        ProviderResult result = ProviderResult.success("ref-abc", "SUCCESSFUL");

        sut.applyDisbursementTransition(event(MobilePaymentProvider.MTN), result);

        // Status unchanged (still SUCCESS — no IllegalStateTransitionException thrown)
        assertThat(d.getDisbursementStatus()).isEqualTo(DisbursementStatus.SUCCESS);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void disbursementNotFound_throwsIllegalStateException() {
        when(repo.findByDisbursementIdForUpdate("dsb-001")).thenReturn(Optional.empty());
        ProviderResult result = ProviderResult.success("ref-abc", "SUCCESSFUL");

        assertThatThrownBy(() ->
                sut.applyDisbursementTransition(event(MobilePaymentProvider.MTN), result))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Disbursement not found");
    }

    @Test
    void unknownProviderResult_defaultsToFailed_publishesFailedEvent() {
        // SCHEMA-03: wallet release removed — only state transition + event publish
        Disbursement d = disbursementInState(DisbursementStatus.PROCESSING, MobilePaymentProvider.ORANGE);
        when(repo.findByDisbursementIdForUpdate("dsb-001")).thenReturn(Optional.of(d));
        // Orange raw status "PENDING" maps to PROCESSING — defensive fallback path
        // resolveTarget defaults non-SUCCESS mapped statuses to DisbursementStatus.FAILED
        ProviderResult result = ProviderResult.pending("ref-abc", "PENDING");

        sut.applyDisbursementTransition(event(MobilePaymentProvider.ORANGE), result);

        assertThat(d.getDisbursementStatus()).isEqualTo(DisbursementStatus.FAILED);
        ArgumentCaptor<WebhookEnqueueRequestedEvent> captor =
            ArgumentCaptor.forClass(WebhookEnqueueRequestedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("DISBURSEMENT_FAILED");
    }
}
