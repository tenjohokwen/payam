package com.softropic.payam.disbursement.service;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.common.payment.ProviderResult;
import com.softropic.payam.common.payment.SubscriberStatus;
import com.softropic.payam.disbursement.contract.DisbursementOrchestratorError;
import com.softropic.payam.disbursement.contract.DisbursementRequest;
import com.softropic.payam.disbursement.contract.DisbursementResponse;
import com.softropic.payam.disbursement.contract.DisbursementStatus;
import com.softropic.payam.disbursement.contract.exception.DailyLimitExceededException;
import com.softropic.payam.disbursement.contract.exception.VelocityExceededException;
import com.softropic.payam.disbursement.repo.Disbursement;
import com.softropic.payam.disbursement.repo.DisbursementRepository;
import com.softropic.payam.fraud.contract.FraudDecision;
import com.softropic.payam.mtn.service.MtnMoMoPort;
import com.softropic.payam.orange.service.OrangeMoneyPort;
import com.softropic.payam.payment.contract.exception.UnknownMsisdnPrefixException;
import com.softropic.payam.payment.service.MsisdnRouter;
import com.softropic.payam.transaction.contract.CachedResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DisbursementOrchestratorTest {

    private static final Long TENANT_ID = 1L;
    private static final String MTN_MSISDN = "+237671234567";
    private static final String ORANGE_MSISDN = "+237691234567";
    private static final BigDecimal SMALL_AMOUNT = BigDecimal.valueOf(5000);
    private static final BigDecimal STEP_UP_AMOUNT = BigDecimal.valueOf(600_000);
    private static final String DSB_ID = "test-dsb-id-123";

    @Mock DisbursementIdempotencyService idempotencyService;
    @Mock MsisdnRouter msisdnRouter;
    @Mock DisbursementVelocityService velocityService;
    @Mock DisbursementFraudEvaluationService fraudService;
    @Mock DisbursementService dsbService;
    @Mock DisbursementRepository disbursementRepository;
    @Mock MtnMoMoPort mtnPort;
    @Mock OrangeMoneyPort orangePort;
    @Mock TransactionTemplate transactionTemplate;

    @InjectMocks DisbursementOrchestrator orchestrator;

    private DisbursementRequest validRequest(BigDecimal amount, String msisdn) {
        return new DisbursementRequest(msisdn, amount, "XAF", "REF-001", null, null, "IDEM-001");
    }

    private Disbursement mockDisbursement(String id, DisbursementStatus status, BigDecimal amount) {
        Disbursement dsb = mock(Disbursement.class);
        when(dsb.getDisbursementId()).thenReturn(id);
        when(dsb.getDisbursementStatus()).thenReturn(status);
        when(dsb.getAmount()).thenReturn(amount);
        when(dsb.getRecipientMsisdn()).thenReturn(MTN_MSISDN);
        when(dsb.getCurrency()).thenReturn("XAF");
        when(dsb.getReference()).thenReturn("REF-001");
        when(dsb.getDescription()).thenReturn(null);
        when(dsb.getMetadata()).thenReturn(null);
        when(dsb.getIdempotencyKey()).thenReturn("IDEM-001");
        when(dsb.getProvider()).thenReturn(MobilePaymentProvider.MTN);
        return dsb;
    }

    @BeforeEach
    void setUpDefaults() {
        // Use lenient stubs for defaults that not every test triggers.
        // Tests that DO need these will hit the stub; tests that don't won't fail.

        // Default: idempotency key is new (not a replay)
        lenient().when(idempotencyService.checkAndReserve(any(), any())).thenReturn(Optional.empty());

        // Default: MTN MSISDN routes to MTN; Orange MSISDN routes to Orange
        lenient().when(msisdnRouter.resolve(eq(MTN_MSISDN))).thenReturn(MobilePaymentProvider.MTN);
        lenient().when(msisdnRouter.resolve(eq(ORANGE_MSISDN))).thenReturn(MobilePaymentProvider.ORANGE);

        // Default: fraud allows through
        lenient().when(fraudService.evaluate(any(), any(), any())).thenReturn(FraudDecision.allow(0));

        // Default: MTN subscriber active
        lenient().when(mtnPort.validateSubscriber(any())).thenReturn(new SubscriberStatus(true, MTN_MSISDN, "ACTIVE"));

        // Default: Orange subscriber active
        lenient().when(orangePort.validateSubscriber(any())).thenReturn(new SubscriberStatus(true, ORANGE_MSISDN, "ACTIVE"));

        // Default: MTN initiateDisbursement returns pending
        lenient().when(mtnPort.initiateDisbursement(any())).thenReturn(ProviderResult.pending("MTN-REF-1", "PENDING"));

        // Default: Orange initiateDisbursement returns success
        lenient().when(orangePort.initiateDisbursement(any())).thenReturn(ProviderResult.success("ORANGE-REF-1", "SUCCESS"));

        // Default: dsbService.create returns a mocked disbursement (FEE-01: no fee)
        Disbursement defaultDsb = mockDisbursement(DSB_ID, DisbursementStatus.INITIATED, SMALL_AMOUNT);
        lenient().when(dsbService.create(any(), any(), any(), any())).thenReturn(defaultDsb);

        // Default: TransactionTemplate executes the lambda inline
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });

        // Default: disbursementRepository.findByDisbursementIdForUpdate returns a mockable dsb (for transition)
        Disbursement lockedDsb = mock(Disbursement.class);
        lenient().when(disbursementRepository.findByDisbursementIdForUpdate(any())).thenReturn(Optional.of(lockedDsb));
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 1: Happy path — MTN → PROCESSING
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void initiate_happyPath_mtn_returnsAcceptedProcessing() {
        DisbursementResponse response = orchestrator.initiate(TENANT_ID, validRequest(SMALL_AMOUNT, MTN_MSISDN));

        assertThat(response.status()).isEqualTo("PROCESSING");
        assertThat(response.disbursementId()).isEqualTo(DSB_ID);
        assertThat(response.errorCode()).isNull();
        verify(mtnPort).initiateDisbursement(any());
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 2: Happy path — Orange → PROCESSING
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void initiate_happyPath_orange_returnsAcceptedProcessing() {
        Disbursement orangeDsb = mockDisbursement(DSB_ID, DisbursementStatus.INITIATED, SMALL_AMOUNT);
        when(dsbService.create(any(), any(), any(), any())).thenReturn(orangeDsb);

        DisbursementResponse response = orchestrator.initiate(TENANT_ID, validRequest(SMALL_AMOUNT, ORANGE_MSISDN));

        assertThat(response.status()).isEqualTo("PROCESSING");
        assertThat(response.errorCode()).isNull();
        verify(orangePort).initiateDisbursement(any());
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 3: Idempotency hit — return cached response WITHOUT calling provider
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void initiate_idempotencyHit_returnsCachedResponseWithoutCallingProvider() {
        // Simulate a completed cached response
        String cachedJson = "{\"disbursementId\":\"cached-dsb\",\"status\":\"PROCESSING\","
                + "\"providerRef\":\"PROV-REF\",\"recipientMsisdn\":\"+237671234567\","
                + "\"amount\":5000,\"fee\":0,\"currency\":\"XAF\",\"reference\":\"REF-001\","
                + "\"provider\":\"MTN\",\"errorCode\":null,\"errorMessage\":null}";
        when(idempotencyService.checkAndReserve(any(), any()))
                .thenReturn(Optional.of(new CachedResponse(202, cachedJson)));

        DisbursementResponse response = orchestrator.initiate(TENANT_ID, validRequest(SMALL_AMOUNT, MTN_MSISDN));

        assertThat(response.status()).isEqualTo("PROCESSING");
        assertThat(response.disbursementId()).isEqualTo("cached-dsb");
        // None of the downstream services should be called
        verifyNoInteractions(msisdnRouter, velocityService, fraudService, mtnPort, orangePort);
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 4: Idempotency key in-flight (RESERVED) → DISBURSEMENT_ALREADY_PROCESSING
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void initiate_idempotencyInFlight_returnsAlreadyProcessing() {
        when(idempotencyService.checkAndReserve(any(), any()))
                .thenReturn(Optional.of(new CachedResponse(0, "RESERVED")));

        DisbursementResponse response = orchestrator.initiate(TENANT_ID, validRequest(SMALL_AMOUNT, MTN_MSISDN));

        assertThat(response.errorCode()).isEqualTo(DisbursementOrchestratorError.DISBURSEMENT_ALREADY_PROCESSING.getErrorCode());
        verifyNoInteractions(msisdnRouter, velocityService, fraudService, mtnPort, orangePort);
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 5: Unknown MSISDN prefix → UNKNOWN_MSISDN_PREFIX
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void initiate_unknownMsisdnPrefix_returnsFailed() {
        String unknownMsisdn = "+237999999999";
        when(msisdnRouter.resolve(eq(unknownMsisdn))).thenThrow(new UnknownMsisdnPrefixException(unknownMsisdn));

        DisbursementResponse response = orchestrator.initiate(TENANT_ID, validRequest(SMALL_AMOUNT, unknownMsisdn));

        assertThat(response.errorCode()).isEqualTo(DisbursementOrchestratorError.UNKNOWN_MSISDN_PREFIX.getErrorCode());
        verifyNoInteractions(mtnPort, orangePort);
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 6: Velocity exceeded → VELOCITY_EXCEEDED
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void initiate_velocityExceeded_returnsFailed() {
        doThrow(new VelocityExceededException("minute limit"))
                .when(velocityService).checkTenantVelocity(any());

        DisbursementResponse response = orchestrator.initiate(TENANT_ID, validRequest(SMALL_AMOUNT, MTN_MSISDN));

        assertThat(response.errorCode()).isEqualTo(DisbursementOrchestratorError.VELOCITY_EXCEEDED.getErrorCode());
        verifyNoInteractions(mtnPort, orangePort);
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 7: Daily MSISDN limit exceeded → DAILY_LIMIT_EXCEEDED
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void initiate_dailyLimitExceeded_returnsFailed() {
        doThrow(new DailyLimitExceededException("daily limit"))
                .when(velocityService).checkMsisdnDailyLimit(any(), any());

        DisbursementResponse response = orchestrator.initiate(TENANT_ID, validRequest(SMALL_AMOUNT, MTN_MSISDN));

        assertThat(response.errorCode()).isEqualTo(DisbursementOrchestratorError.DAILY_LIMIT_EXCEEDED.getErrorCode());
        verifyNoInteractions(mtnPort, orangePort);
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 8: Fraud blocked → FRAUD_BLOCK
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void initiate_fraudBlocked_returnsFailed() {
        when(fraudService.evaluate(any(), any(), any())).thenReturn(FraudDecision.block(95, "BLOCKLIST_MSISDN"));

        DisbursementResponse response = orchestrator.initiate(TENANT_ID, validRequest(SMALL_AMOUNT, MTN_MSISDN));

        assertThat(response.errorCode()).isEqualTo(DisbursementOrchestratorError.FRAUD_BLOCK.getErrorCode());
        verifyNoInteractions(mtnPort, orangePort);
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 9: Recipient inactive → RECIPIENT_NOT_FOUND; transitionToFailed called
    // (FEE-01 / SCHEMA-03: wallet model retired; no balance release)
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void initiate_recipientInactive_transitionsToFailed() {
        when(mtnPort.validateSubscriber(any())).thenReturn(new SubscriberStatus(false, MTN_MSISDN, "INACTIVE"));

        DisbursementResponse response = orchestrator.initiate(TENANT_ID, validRequest(SMALL_AMOUNT, MTN_MSISDN));

        assertThat(response.errorCode()).isEqualTo(DisbursementOrchestratorError.RECIPIENT_NOT_FOUND.getErrorCode());
        verify(dsbService).transitionToFailed(eq(DSB_ID));
        verify(mtnPort, never()).initiateDisbursement(any());
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 10: Provider throws RuntimeException → PROVIDER_ERROR; transitionToFailed called
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void initiate_providerThrows_transitionsToFailed() {
        when(mtnPort.initiateDisbursement(any())).thenThrow(new RuntimeException("provider boom"));

        DisbursementResponse response = orchestrator.initiate(TENANT_ID, validRequest(SMALL_AMOUNT, MTN_MSISDN));

        assertThat(response.errorCode()).isEqualTo(DisbursementOrchestratorError.PROVIDER_ERROR.getErrorCode());
        verify(dsbService).transitionToFailed(eq(DSB_ID));
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 11: Amount > 500,000 → PENDING_CONFIRMATION; no provider call
    // (FEE-01 / SCHEMA-03: no wallet reservation — wallet model retired)
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void initiate_amountAbove500000_returnsPendingConfirmation_skipsProvider() {
        Disbursement stepUpDsb = mockDisbursement(DSB_ID, DisbursementStatus.PENDING_CONFIRMATION, STEP_UP_AMOUNT);
        when(dsbService.create(any(), any(), any(), eq(DisbursementStatus.PENDING_CONFIRMATION)))
                .thenReturn(stepUpDsb);

        DisbursementResponse response = orchestrator.initiate(TENANT_ID, validRequest(STEP_UP_AMOUNT, MTN_MSISDN));

        assertThat(response.status()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(response.errorCode()).isNull();
        // Provider is NOT called
        verify(mtnPort, never()).initiateDisbursement(any());
        verify(mtnPort, never()).validateSubscriber(any());
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 12: confirm — PENDING_CONFIRMATION → validates subscriber + dispatches → PROCESSING
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void confirm_pendingConfirmationStatus_validatesAndDispatches() {
        BigDecimal principal = BigDecimal.valueOf(600_000);
        Disbursement pendingDsb = mockDisbursement(DSB_ID, DisbursementStatus.PENDING_CONFIRMATION, principal);

        when(disbursementRepository.findByTenantIdAndDisbursementId(eq(TENANT_ID), eq(DSB_ID)))
                .thenReturn(Optional.of(pendingDsb));

        DisbursementResponse response = orchestrator.confirm(TENANT_ID, DSB_ID);

        assertThat(response.status()).isEqualTo("PROCESSING");
        assertThat(response.errorCode()).isNull();
        verify(mtnPort).validateSubscriber(any());
        verify(mtnPort).initiateDisbursement(any());
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 13: confirm — non-PENDING_CONFIRMATION status → INVALID_STATE
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void confirm_nonPendingStatus_returnsInvalidState() {
        Disbursement processingDsb = mockDisbursement(DSB_ID, DisbursementStatus.PROCESSING, SMALL_AMOUNT);
        when(disbursementRepository.findByTenantIdAndDisbursementId(eq(TENANT_ID), eq(DSB_ID)))
                .thenReturn(Optional.of(processingDsb));

        DisbursementResponse response = orchestrator.confirm(TENANT_ID, DSB_ID);

        assertThat(response.errorCode()).isEqualTo(DisbursementOrchestratorError.INVALID_STATE.getErrorCode());
        verifyNoInteractions(mtnPort, orangePort);
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 14: confirm — disbursement not found → INVALID_STATE
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void confirm_disbursementNotFound_returnsInvalidState() {
        when(disbursementRepository.findByTenantIdAndDisbursementId(eq(TENANT_ID), eq(DSB_ID)))
                .thenReturn(Optional.empty());

        DisbursementResponse response = orchestrator.confirm(TENANT_ID, DSB_ID);

        assertThat(response.errorCode()).isEqualTo(DisbursementOrchestratorError.INVALID_STATE.getErrorCode());
        verifyNoInteractions(mtnPort, orangePort);
    }
}
