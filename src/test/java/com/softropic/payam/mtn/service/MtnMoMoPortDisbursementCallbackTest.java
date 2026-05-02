package com.softropic.payam.mtn.service;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.disbursement.repo.Disbursement;
import com.softropic.payam.disbursement.repo.DisbursementRepository;
import com.softropic.payam.mtn.config.MtnMoMoConfig;
import com.softropic.payam.mtn.contract.MtnCallbackPayload;
import com.softropic.payam.mtn.infrastructure.MtnMoMoClient;
import com.softropic.payam.transaction.contract.LedgerFlow;
import com.softropic.payam.transaction.repo.TransactionRepository;
import com.softropic.payam.transaction.service.EventLogService;
import com.softropic.payam.transaction.service.LedgerService;
import com.softropic.payam.webhook.contract.WebhookReceivedEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MtnMoMoPortDisbursementCallbackTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private DisbursementRepository disbursementRepository;
    private ApplicationEventPublisher eventPublisher;
    private TransactionTemplate transactionTemplate;
    private MtnMoMoPort sut;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        disbursementRepository = mock(DisbursementRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        transactionTemplate = mock(TransactionTemplate.class);
        // execute(callback) just runs the callback inline so publishEvent is invoked
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
        sut = new MtnMoMoPort(
            mock(MtnMoMoClient.class),
            mock(MtnTokenService.class),
            mock(TransactionRepository.class),
            mock(EventLogService.class),
            mock(MtnMoMoConfig.class),
            redis,
            eventPublisher,
            transactionTemplate,
            mock(LedgerService.class),
            disbursementRepository      // 10th arg — added by Plan 02 Task 2
        );
    }

    private MtnCallbackPayload payload(String externalId, String status) {
        MtnCallbackPayload p = new MtnCallbackPayload();
        p.setExternalId(externalId);
        p.setStatus(status);
        return p;
    }

    private Disbursement disbursement(String dsbId, String providerRef) {
        return Disbursement.builder()
            .disbursementId(dsbId).tenantId(1001L).recipientMsisdn("237691111111")
            .amount(new BigDecimal("100")).currency("XAF").reference("ref-001")
            .provider(MobilePaymentProvider.MTN).providerRef(providerRef)
            .pollAttempts(0).build();
    }

    @Test
    void firstCallback_dedupKeyAbsent_publishesWebhookReceivedEvent_DISBURSEMENT_flow() {
        when(ops.setIfAbsent(eq("callbacks:dsb:ref-uuid:SUCCESSFUL"), eq("SEEN"), eq(Duration.ofHours(24))))
            .thenReturn(Boolean.TRUE);
        when(disbursementRepository.findByProviderRef("ref-uuid"))
            .thenReturn(Optional.of(disbursement("dsb-001", "ref-uuid")));

        sut.processDisbursementCallback(payload("dsb-001", "SUCCESSFUL"), "ref-uuid");

        ArgumentCaptor<WebhookReceivedEvent> captor = ArgumentCaptor.forClass(WebhookReceivedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().transactionId()).isEqualTo("dsb-001");
        assertThat(captor.getValue().provider()).isEqualTo(MobilePaymentProvider.MTN);
        assertThat(captor.getValue().providerRef()).isEqualTo("ref-uuid");
        assertThat(captor.getValue().flow()).isEqualTo(LedgerFlow.DISBURSEMENT);
    }

    @Test
    void duplicateCallback_dedupKeyPresent_returnsWithoutPublishing() {
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(Boolean.FALSE);

        sut.processDisbursementCallback(payload("dsb-001", "SUCCESSFUL"), "ref-uuid");

        verify(disbursementRepository, never()).findByProviderRef(anyString());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void disbursementNotFound_logsAndReturnsWithoutPublishing() {
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(Boolean.TRUE);
        when(disbursementRepository.findByProviderRef("ref-missing")).thenReturn(Optional.empty());

        sut.processDisbursementCallback(payload("dsb-x", "FAILED"), "ref-missing");

        verify(eventPublisher, never()).publishEvent(any());
    }
}
