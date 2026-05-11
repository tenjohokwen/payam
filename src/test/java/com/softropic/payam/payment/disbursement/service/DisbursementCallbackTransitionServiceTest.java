package com.softropic.payam.payment.disbursement.service;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.common.payment.ProviderResult;
import com.softropic.payam.payment.disbursement.contract.DisbursementRefStatus;
import com.softropic.payam.payment.disbursement.contract.DisbursementStatus;
import com.softropic.payam.payment.disbursement.contract.event.InsufficientFundsAlertEvent;
import com.softropic.payam.payment.disbursement.repo.Disbursement;
import com.softropic.payam.payment.disbursement.repo.DisbursementRepository;
import com.softropic.payam.payment.ledger.contract.LedgerFlow;
import com.softropic.payam.payment.ledger.contract.TransactionStatus;
import com.softropic.payam.payment.webhook.contract.WebhookEnqueueRequestedEvent;
import com.softropic.payam.payment.webhook.contract.WebhookReceivedEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DisbursementCallbackTransitionServiceTest {

    private static final Long DSB_PK_ID = 42L;

    private DisbursementRepository repo;
    private ApplicationEventPublisher eventPublisher;
    private DisbursementClaimTransitionService claimTransitionService;
    private InsufficientFundsDetector insufficientFundsDetector;
    private DisbursementCallbackTransitionService sut;

    @BeforeEach
    void setUp() {
        repo = mock(DisbursementRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        claimTransitionService = mock(DisbursementClaimTransitionService.class);
        insufficientFundsDetector = mock(InsufficientFundsDetector.class);
        sut = new DisbursementCallbackTransitionService(
                repo, eventPublisher, claimTransitionService, insufficientFundsDetector);
    }

    private Disbursement disbursementInState(DisbursementStatus state, BigDecimal reserved,
                                             MobilePaymentProvider provider) {
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
        // Set the BIGINT PK (from BaseEntity.setId) — required for claimTransitionService calls
        d.setId(DSB_PK_ID);
        return d;
    }

    private WebhookReceivedEvent event(MobilePaymentProvider provider) {
        return new WebhookReceivedEvent("dsb-001", provider, "ref-abc", "trace-1", LedgerFlow.DISBURSEMENT);
    }

    @Test
    void successPath_transitionsToSUCCESS_publishesCompletedEvent() {
        Disbursement d = disbursementInState(DisbursementStatus.PROCESSING,
            new BigDecimal("750.00"), MobilePaymentProvider.MTN);
        when(repo.findByDisbursementIdForUpdate("dsb-001")).thenReturn(Optional.of(d));
        // MTN raw status SUCCESSFUL → TransactionStatus.SUCCESS via MtnStatusMapper
        ProviderResult result = ProviderResult.success("ref-abc", "SUCCESSFUL");

        sut.applyDisbursementTransition(event(MobilePaymentProvider.MTN), result);

        assertThat(d.getDisbursementStatus()).isEqualTo(DisbursementStatus.SUCCESS);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        Object publishedEvent = captor.getValue();
        assertThat(publishedEvent).isInstanceOf(WebhookEnqueueRequestedEvent.class);
        WebhookEnqueueRequestedEvent webhookEvent = (WebhookEnqueueRequestedEvent) publishedEvent;
        assertThat(webhookEvent.eventType()).isEqualTo("DISBURSEMENT_COMPLETED");
        assertThat(webhookEvent.status()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(webhookEvent.transactionId()).isEqualTo("dsb-001");
        assertThat(webhookEvent.tenantId()).isEqualTo(1001L);
        assertThat(webhookEvent.externalReference()).isEqualTo("merchant-ref-001");
    }

    @Test
    void failedPath_transitionsToFAILED_releasesClaimsToReleased_publishesFailedEvent() {
        Disbursement d = disbursementInState(DisbursementStatus.PROCESSING,
            new BigDecimal("750.00"), MobilePaymentProvider.MTN);
        when(repo.findByDisbursementIdForUpdate("dsb-001")).thenReturn(Optional.of(d));
        // MTN raw status FAILED → TransactionStatus.FAILED via MtnStatusMapper
        ProviderResult result = ProviderResult.success("ref-abc", "FAILED");
        when(insufficientFundsDetector.isInsufficientFunds(result)).thenReturn(false);

        sut.applyDisbursementTransition(event(MobilePaymentProvider.MTN), result);

        assertThat(d.getDisbursementStatus()).isEqualTo(DisbursementStatus.FAILED);
        // CLAIM-03: claims released
        verify(claimTransitionService).transitionClaims(eq(DSB_PK_ID),
                eq(DisbursementRefStatus.PENDING), eq(DisbursementRefStatus.RELEASED));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        Object publishedEvent = captor.getValue();
        assertThat(publishedEvent).isInstanceOf(WebhookEnqueueRequestedEvent.class);
        WebhookEnqueueRequestedEvent webhookEvent = (WebhookEnqueueRequestedEvent) publishedEvent;
        assertThat(webhookEvent.eventType()).isEqualTo("DISBURSEMENT_FAILED");
        assertThat(webhookEvent.status()).isEqualTo(TransactionStatus.FAILED);
    }

    @Test
    void replayGuard_alreadyTerminal_returnsWithoutSideEffects() {
        Disbursement d = disbursementInState(DisbursementStatus.SUCCESS,
            new BigDecimal("750.00"), MobilePaymentProvider.MTN);
        when(repo.findByDisbursementIdForUpdate("dsb-001")).thenReturn(Optional.of(d));
        ProviderResult result = ProviderResult.success("ref-abc", "SUCCESSFUL");

        sut.applyDisbursementTransition(event(MobilePaymentProvider.MTN), result);

        // Status unchanged (still SUCCESS — no IllegalStateTransitionException thrown)
        assertThat(d.getDisbursementStatus()).isEqualTo(DisbursementStatus.SUCCESS);
        verify(claimTransitionService, never()).transitionClaims(anyLong(), any(), any());
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
    void unknownProviderResult_defaultsToFailed_releasesClaimsNoWallet() {
        Disbursement d = disbursementInState(DisbursementStatus.PROCESSING,
            new BigDecimal("750.00"), MobilePaymentProvider.ORANGE);
        when(repo.findByDisbursementIdForUpdate("dsb-001")).thenReturn(Optional.of(d));
        // Orange raw status "PENDING" maps to PROCESSING — defensive fallback path
        // resolveTarget defaults non-SUCCESS mapped statuses to DisbursementStatus.FAILED
        ProviderResult result = ProviderResult.pending("ref-abc", "PENDING");
        when(insufficientFundsDetector.isInsufficientFunds(result)).thenReturn(false);

        sut.applyDisbursementTransition(event(MobilePaymentProvider.ORANGE), result);

        assertThat(d.getDisbursementStatus()).isEqualTo(DisbursementStatus.FAILED);
        verify(claimTransitionService).transitionClaims(eq(DSB_PK_ID),
                eq(DisbursementRefStatus.PENDING), eq(DisbursementRefStatus.RELEASED));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(WebhookEnqueueRequestedEvent.class);
        assertThat(((WebhookEnqueueRequestedEvent) captor.getValue()).eventType()).isEqualTo("DISBURSEMENT_FAILED");
    }

    // ── New tests for Phase 56-02 ────────────────────────────────────────────────────

    @Test
    void applyTransition_success_transitionsClaimsToClaimed() {
        Disbursement d = disbursementInState(DisbursementStatus.PROCESSING,
                new BigDecimal("700.00"), MobilePaymentProvider.MTN);
        when(repo.findByDisbursementIdForUpdate("dsb-001")).thenReturn(Optional.of(d));
        ProviderResult result = ProviderResult.success("ref-abc", "SUCCESSFUL");

        sut.applyDisbursementTransition(event(MobilePaymentProvider.MTN), result);

        assertThat(d.getDisbursementStatus()).isEqualTo(DisbursementStatus.SUCCESS);
        // CLAIM-02: PENDING claims → CLAIMED
        verify(claimTransitionService).transitionClaims(eq(DSB_PK_ID),
                eq(DisbursementRefStatus.PENDING), eq(DisbursementRefStatus.CLAIMED));
    }

    @Test
    void applyTransition_failed_transitionsClaimsToReleased() {
        Disbursement d = disbursementInState(DisbursementStatus.PROCESSING,
                new BigDecimal("700.00"), MobilePaymentProvider.MTN);
        when(repo.findByDisbursementIdForUpdate("dsb-001")).thenReturn(Optional.of(d));
        ProviderResult result = ProviderResult.failure("TIMEOUT", "Request timed out");
        when(insufficientFundsDetector.isInsufficientFunds(result)).thenReturn(false);

        sut.applyDisbursementTransition(event(MobilePaymentProvider.MTN), result);

        assertThat(d.getDisbursementStatus()).isEqualTo(DisbursementStatus.FAILED);
        // CLAIM-03: PENDING claims → RELEASED
        verify(claimTransitionService).transitionClaims(eq(DSB_PK_ID),
                eq(DisbursementRefStatus.PENDING), eq(DisbursementRefStatus.RELEASED));
    }

    @Test
    void applyTransition_failed_insufficientFunds_publishesAlertEventAndReleasesClaimsToReleased() {
        Disbursement d = disbursementInState(DisbursementStatus.PROCESSING,
                new BigDecimal("700.00"), MobilePaymentProvider.MTN);
        when(repo.findByDisbursementIdForUpdate("dsb-001")).thenReturn(Optional.of(d));
        ProviderResult result = ProviderResult.failure("NOT_ENOUGH_FUNDS", "Account has insufficient balance");
        when(insufficientFundsDetector.isInsufficientFunds(result)).thenReturn(true);

        sut.applyDisbursementTransition(event(MobilePaymentProvider.MTN), result);

        // Claims are released to RELEASED (CLAIM-03)
        verify(claimTransitionService).transitionClaims(eq(DSB_PK_ID),
                eq(DisbursementRefStatus.PENDING), eq(DisbursementRefStatus.RELEASED));

        // ALERT-01: InsufficientFundsAlertEvent is published (in addition to WebhookEnqueueRequestedEvent)
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, org.mockito.Mockito.atLeast(1)).publishEvent(captor.capture());
        boolean alertPublished = captor.getAllValues().stream()
                .anyMatch(e -> e instanceof InsufficientFundsAlertEvent);
        assertThat(alertPublished).as("InsufficientFundsAlertEvent must be published").isTrue();

        // Verify alert event content
        InsufficientFundsAlertEvent alert = captor.getAllValues().stream()
                .filter(e -> e instanceof InsufficientFundsAlertEvent)
                .map(e -> (InsufficientFundsAlertEvent) e)
                .findFirst().orElseThrow();
        assertThat(alert.disbursementId()).isEqualTo("dsb-001");
        assertThat(alert.tenantId()).isEqualTo(1001L);
        assertThat(alert.provider()).isEqualTo(MobilePaymentProvider.MTN);
        assertThat(alert.providerErrorCode()).isEqualTo("NOT_ENOUGH_FUNDS");
    }

    @Test
    void applyTransition_failed_normalError_doesNotPublishAlertEvent() {
        Disbursement d = disbursementInState(DisbursementStatus.PROCESSING,
                new BigDecimal("700.00"), MobilePaymentProvider.MTN);
        when(repo.findByDisbursementIdForUpdate("dsb-001")).thenReturn(Optional.of(d));
        ProviderResult result = ProviderResult.failure("TIMEOUT", "Request timed out");
        when(insufficientFundsDetector.isInsufficientFunds(result)).thenReturn(false);

        sut.applyDisbursementTransition(event(MobilePaymentProvider.MTN), result);

        // No InsufficientFundsAlertEvent published — only WebhookEnqueueRequestedEvent
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        boolean alertPublished = captor.getAllValues().stream()
                .anyMatch(e -> e instanceof InsufficientFundsAlertEvent);
        assertThat(alertPublished)
                .as("InsufficientFundsAlertEvent must NOT be published for normal errors").isFalse();
        // WebhookEnqueueRequestedEvent IS still published
        boolean webhookPublished = captor.getAllValues().stream()
                .anyMatch(e -> e instanceof WebhookEnqueueRequestedEvent);
        assertThat(webhookPublished).as("WebhookEnqueueRequestedEvent must be published").isTrue();
    }

    @Test
    void applyTransition_terminalState_skipsClaimTransition() {
        // SUCCESS is a terminal state — replay guard fires
        Disbursement d = disbursementInState(DisbursementStatus.SUCCESS,
                new BigDecimal("700.00"), MobilePaymentProvider.MTN);
        when(repo.findByDisbursementIdForUpdate("dsb-001")).thenReturn(Optional.of(d));
        ProviderResult result = ProviderResult.success("ref-abc", "SUCCESSFUL");

        sut.applyDisbursementTransition(event(MobilePaymentProvider.MTN), result);

        // Replay guard short-circuits — claimTransitionService NEVER called (CLAIM-05 replay safety)
        verify(claimTransitionService, never()).transitionClaims(anyLong(), any(), any());
        // No events published either
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void applyTransition_success_does_not_publish_insufficientFundsAlert() {
        Disbursement d = disbursementInState(DisbursementStatus.PROCESSING,
                new BigDecimal("700.00"), MobilePaymentProvider.MTN);
        when(repo.findByDisbursementIdForUpdate("dsb-001")).thenReturn(Optional.of(d));
        ProviderResult result = ProviderResult.success("ref-abc", "SUCCESSFUL");

        sut.applyDisbursementTransition(event(MobilePaymentProvider.MTN), result);

        assertThat(d.getDisbursementStatus()).isEqualTo(DisbursementStatus.SUCCESS);
        // insufficientFundsDetector is NOT called on SUCCESS path
        verify(insufficientFundsDetector, never()).isInsufficientFunds(any());
        // No IF alert published
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        boolean alertPublished = captor.getAllValues().stream()
                .anyMatch(e -> e instanceof InsufficientFundsAlertEvent);
        assertThat(alertPublished).isFalse();
    }
}
