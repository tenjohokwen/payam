package com.softropic.payam.orange.service;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.disbursement.repo.Disbursement;
import com.softropic.payam.disbursement.repo.DisbursementRepository;
import com.softropic.payam.orange.config.OrangeMoneyConfig;
import com.softropic.payam.orange.contract.OrangeWebhookPayload;
import com.softropic.payam.orange.infrastructure.OrangeMoneyClient;
import com.softropic.payam.platform.service.PlatformConfigService;
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

import java.lang.reflect.Field;
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

class OrangeMoneyPortDisbursementCallbackTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private DisbursementRepository disbursementRepository;
    private ApplicationEventPublisher eventPublisher;
    private TransactionTemplate transactionTemplate;
    private OrangeMoneyPort sut;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        disbursementRepository = mock(DisbursementRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
        sut = new OrangeMoneyPort(
            mock(OrangeMoneyClient.class),
            mock(OrangeTokenService.class),
            mock(TransactionRepository.class),
            mock(EventLogService.class),
            mock(OrangeMoneyConfig.class),
            eventPublisher,
            transactionTemplate,
            mock(PlatformConfigService.class),
            mock(LedgerService.class),
            redis,                          // 10th arg — added by Plan 02 Task 2
            disbursementRepository          // 11th arg — added by Plan 02 Task 2
        );
    }

    private OrangeWebhookPayload payload(String payToken, String status, String notifToken) throws Exception {
        OrangeWebhookPayload p = new OrangeWebhookPayload();
        // OrangeWebhookPayload has no setters — use reflection
        setField(p, "payToken", payToken);
        setField(p, "status", status);
        setField(p, "notifToken", notifToken);
        return p;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private Disbursement disbursement(String dsbId, String providerRef) {
        return Disbursement.builder()
            .disbursementId(dsbId).tenantId(1001L).recipientMsisdn("237691111111")
            .amount(new BigDecimal("100")).currency("XAF").reference("merchant-ref-1")
            .provider(MobilePaymentProvider.ORANGE).providerRef(providerRef)
            .reservedAmount(new BigDecimal("100")).pollAttempts(0).build();
    }

    @Test
    void firstCallback_publishesWebhookReceivedEvent_byPayToken() throws Exception {
        when(ops.setIfAbsent(eq("callbacks:dsb:pt-001:SUCCESS"), eq("SEEN"), eq(Duration.ofHours(24))))
            .thenReturn(Boolean.TRUE);
        when(disbursementRepository.findByProviderRef("pt-001"))
            .thenReturn(Optional.of(disbursement("dsb-001", "pt-001")));

        String returned = sut.processDisbursementCallback(payload("pt-001", "SUCCESS", "tok"), "tok");

        assertThat(returned).isEqualTo("pt-001");
        ArgumentCaptor<WebhookReceivedEvent> captor = ArgumentCaptor.forClass(WebhookReceivedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().transactionId()).isEqualTo("dsb-001");
        assertThat(captor.getValue().provider()).isEqualTo(MobilePaymentProvider.ORANGE);
        assertThat(captor.getValue().providerRef()).isEqualTo("pt-001");
        assertThat(captor.getValue().flow()).isEqualTo(LedgerFlow.DISBURSEMENT);
    }

    @Test
    void duplicateCallback_returnsWithoutPublishing() throws Exception {
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(Boolean.FALSE);

        sut.processDisbursementCallback(payload("pt-002", "SUCCESS", "tok"), "tok");

        verify(disbursementRepository, never()).findByProviderRef(anyString());
        verify(eventPublisher, never()).publishEvent(any());
    }
}
