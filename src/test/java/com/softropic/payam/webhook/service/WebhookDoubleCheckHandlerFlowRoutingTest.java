package com.softropic.payam.webhook.service;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.common.payment.ProviderResult;
import com.softropic.payam.disbursement.service.DisbursementCallbackTransitionService;
import com.softropic.payam.mtn.service.MtnMoMoPort;
import com.softropic.payam.orange.service.OrangeMoneyPort;
import com.softropic.payam.transaction.contract.LedgerFlow;
import com.softropic.payam.webhook.contract.WebhookReceivedEvent;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookDoubleCheckHandlerFlowRoutingTest {

    private OrangeMoneyPort orangePort;
    private MtnMoMoPort mtnPort;
    private WebhookTransitionService webhookTransitionService;
    private DisbursementCallbackTransitionService dsbCallbackTransitionService;
    private WebhookDoubleCheckHandler sut;

    @BeforeEach
    void setUp() {
        orangePort = mock(OrangeMoneyPort.class);
        mtnPort = mock(MtnMoMoPort.class);
        webhookTransitionService = mock(WebhookTransitionService.class);
        dsbCallbackTransitionService = mock(DisbursementCallbackTransitionService.class);
        sut = new WebhookDoubleCheckHandler(
            orangePort, mtnPort, webhookTransitionService, dsbCallbackTransitionService);
    }

    private WebhookReceivedEvent event(LedgerFlow flow, MobilePaymentProvider prov) {
        return new WebhookReceivedEvent("id-1", prov, "ref-1", "trace-1", flow);
    }

    @Test
    void collectionFlow_callsWebhookTransitionService_notDisbursementService() {
        when(mtnPort.getCollectionTransactionStatus(anyString()))
            .thenReturn(ProviderResult.success("ref-1", "SUCCESSFUL"));

        sut.handleWebhookReceived(event(LedgerFlow.COLLECTION, MobilePaymentProvider.MTN));

        verify(webhookTransitionService).applyFinalTransition(any(), any());
        verify(dsbCallbackTransitionService, never()).applyDisbursementTransition(any(), any());
    }

    @Test
    void disbursementFlow_callsDisbursementCallbackTransitionService_notWebhookTransitionService() {
        when(mtnPort.getDisbursementTransactionStatus(anyString()))
            .thenReturn(ProviderResult.success("ref-1", "SUCCESSFUL"));

        sut.handleWebhookReceived(event(LedgerFlow.DISBURSEMENT, MobilePaymentProvider.MTN));

        verify(dsbCallbackTransitionService).applyDisbursementTransition(any(), any());
        verify(webhookTransitionService, never()).applyFinalTransition(any(), any());
    }

    @Test
    void pendingResult_neitherTransitionServiceCalled() {
        when(mtnPort.getDisbursementTransactionStatus(anyString()))
            .thenReturn(ProviderResult.pending("ref-1", "PENDING"));

        sut.handleWebhookReceived(event(LedgerFlow.DISBURSEMENT, MobilePaymentProvider.MTN));

        verify(dsbCallbackTransitionService, never()).applyDisbursementTransition(any(), any());
        verify(webhookTransitionService, never()).applyFinalTransition(any(), any());
    }

    @Test
    void circuitBreakerOpen_neitherTransitionServiceCalled() {
        when(mtnPort.getDisbursementTransactionStatus(anyString()))
            .thenThrow(CallNotPermittedException.createCallNotPermittedException(
                io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("test")));

        sut.handleWebhookReceived(event(LedgerFlow.DISBURSEMENT, MobilePaymentProvider.MTN));

        verify(dsbCallbackTransitionService, never()).applyDisbursementTransition(any(), any());
        verify(webhookTransitionService, never()).applyFinalTransition(any(), any());
    }
}
