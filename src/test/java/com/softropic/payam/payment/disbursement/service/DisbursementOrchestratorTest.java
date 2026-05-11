package com.softropic.payam.payment.disbursement.service;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.common.payment.ProviderResult;
import com.softropic.payam.common.payment.SubscriberStatus;
import com.softropic.payam.payment.disbursement.config.DisbursementProperties;
import com.softropic.payam.payment.disbursement.contract.DisbursementOrchestratorError;
import com.softropic.payam.payment.disbursement.contract.DisbursementRefStatus;
import com.softropic.payam.payment.disbursement.contract.DisbursementRequest;
import com.softropic.payam.payment.disbursement.repo.DisbursementTransactionRefRepository;
import com.softropic.payam.payment.disbursement.contract.DisbursementResponse;
import com.softropic.payam.payment.disbursement.contract.DisbursementStatus;
import com.softropic.payam.payment.disbursement.contract.event.DisbursementAdminApprovalRequiredEvent;
import com.softropic.payam.payment.disbursement.contract.exception.AmountMismatchException;
import com.softropic.payam.payment.disbursement.contract.exception.DailyLimitExceededException;
import com.softropic.payam.payment.disbursement.contract.exception.InvalidTransactionException;
import com.softropic.payam.payment.disbursement.contract.exception.TransactionClaimedException;
import com.softropic.payam.payment.disbursement.contract.exception.VelocityExceededException;
import com.softropic.payam.payment.disbursement.repo.Disbursement;
import com.softropic.payam.payment.disbursement.repo.DisbursementRepository;
import com.softropic.payam.payment.fraud.contract.FraudDecision;
import com.softropic.payam.mtn.service.MtnMoMoPort;
import com.softropic.payam.orange.service.OrangeMoneyPort;
import com.softropic.payam.payment.core.contract.exception.UnknownMsisdnPrefixException;
import com.softropic.payam.payment.core.service.MsisdnRouter;
import com.softropic.payam.payment.ledger.contract.CachedResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DisbursementOrchestratorTest {

    private static final Long TENANT_ID = 1L;
    private static final Long DSB_PK_ID = 42L;
    private static final String MTN_MSISDN = "+237671234567";
    private static final String ORANGE_MSISDN = "+237691234567";
    private static final BigDecimal SMALL_AMOUNT = BigDecimal.valueOf(5000);
    private static final BigDecimal STEP_UP_AMOUNT = BigDecimal.valueOf(600_000);
    private static final BigDecimal ADMIN_APPROVAL_AMOUNT = BigDecimal.valueOf(6_000_000);
    private static final BigDecimal ADMIN_THRESHOLD = BigDecimal.valueOf(5_000_000);
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
    @Mock TransactionClaimValidationService transactionClaimValidationService;
    @Mock DisbursementProperties properties;
    @Mock DisbursementClaimTransitionService claimTransitionService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock DisbursementRetryClassifier retryClassifier;
    @Mock DisbursementTransactionRefRepository refRepository;

    @InjectMocks DisbursementOrchestrator orchestrator;

    private DisbursementRequest validRequest(BigDecimal amount, String msisdn) {
        return new DisbursementRequest(msisdn, amount, "XAF", "REF-001", null, null,
                java.util.List.of("txn-001"), "IDEM-001");
    }

    private Disbursement mockDisbursement(String id, DisbursementStatus status, BigDecimal amount) {
        Disbursement dsb = mock(Disbursement.class);
        when(dsb.getDisbursementId()).thenReturn(id);
        when(dsb.getId()).thenReturn(DSB_PK_ID);
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

        // Default: admin approval threshold = 5,000,000 XAF
        lenient().when(properties.getAdminApprovalThreshold()).thenReturn(ADMIN_THRESHOLD);

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

        // Default: disbursementRepository.findByDisbursementIdForUpdate returns a mockable dsb (for PROCESSING
        // transition in Step 11 and for releaseAndFail). Has getId() = DSB_PK_ID for claim release.
        Disbursement lockedDsb = mock(Disbursement.class);
        lenient().when(lockedDsb.getId()).thenReturn(DSB_PK_ID);
        lenient().when(disbursementRepository.findByDisbursementIdForUpdate(any())).thenReturn(Optional.of(lockedDsb));

        // Default: claimTransitionService returns 1 for any transition
        lenient().when(claimTransitionService.transitionClaims(anyLong(), any(), any())).thenReturn(1);

        // Default: classifier returns RETRIABLE (most retry tests are happy-path retries)
        lenient().when(retryClassifier.classify(any())).thenReturn(DisbursementRetryClassifier.Classification.RETRIABLE);
        // Default: no transactions claimed by other disbursements
        lenient().when(refRepository.findClaimedTransactionIds(any(), any())).thenReturn(java.util.List.of());
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
    // Test 9: Recipient inactive → RECIPIENT_NOT_FOUND; releaseAndFail called
    // (CLAIM-03: claims released atomically with FAILED transition; SCHEMA-03: no wallet release)
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void initiate_recipientInactive_transitionsToFailed() {
        when(mtnPort.validateSubscriber(any())).thenReturn(new SubscriberStatus(false, MTN_MSISDN, "INACTIVE"));

        DisbursementResponse response = orchestrator.initiate(TENANT_ID, validRequest(SMALL_AMOUNT, MTN_MSISDN));

        assertThat(response.errorCode()).isEqualTo(DisbursementOrchestratorError.RECIPIENT_NOT_FOUND.getErrorCode());
        verify(disbursementRepository).findByDisbursementIdForUpdate(eq(DSB_ID));
        verify(claimTransitionService).transitionClaims(eq(DSB_PK_ID),
                eq(DisbursementRefStatus.PENDING), eq(DisbursementRefStatus.RELEASED));
        verify(mtnPort, never()).initiateDisbursement(any());
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 10: Provider throws RuntimeException → PROVIDER_ERROR; releaseAndFail called
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void initiate_providerThrows_transitionsToFailed() {
        when(mtnPort.initiateDisbursement(any())).thenThrow(new RuntimeException("provider boom"));

        DisbursementResponse response = orchestrator.initiate(TENANT_ID, validRequest(SMALL_AMOUNT, MTN_MSISDN));

        assertThat(response.errorCode()).isEqualTo(DisbursementOrchestratorError.PROVIDER_ERROR.getErrorCode());
        verify(claimTransitionService).transitionClaims(eq(DSB_PK_ID),
                eq(DisbursementRefStatus.PENDING), eq(DisbursementRefStatus.RELEASED));
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

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 15: empty transactionIds → INVALID_TRANSACTION
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void initiateRejectsEmptyTransactionIdsList() {
        doThrow(new InvalidTransactionException("transactionIds list is empty"))
                .when(transactionClaimValidationService)
                .validateAndClaim(anyLong(), anyList(), any(BigDecimal.class), anyLong());

        DisbursementResponse response = orchestrator.initiate(TENANT_ID, validRequest(SMALL_AMOUNT, MTN_MSISDN));

        assertThat(response.errorCode()).isEqualTo("INVALID_TRANSACTION");
        verify(claimTransitionService).transitionClaims(eq(DSB_PK_ID),
                eq(DisbursementRefStatus.PENDING), eq(DisbursementRefStatus.RELEASED));
        verify(mtnPort, never()).initiateDisbursement(any());
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 16: amount mismatch → AMOUNT_MISMATCH
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void initiateRejectsMismatchedAmount() {
        doThrow(new AmountMismatchException("request.amount=100 != sum=99"))
                .when(transactionClaimValidationService)
                .validateAndClaim(anyLong(), anyList(), any(BigDecimal.class), anyLong());

        DisbursementResponse response = orchestrator.initiate(TENANT_ID, validRequest(SMALL_AMOUNT, MTN_MSISDN));

        assertThat(response.errorCode()).isEqualTo("AMOUNT_MISMATCH");
        verify(claimTransitionService).transitionClaims(eq(DSB_PK_ID),
                eq(DisbursementRefStatus.PENDING), eq(DisbursementRefStatus.RELEASED));
        verify(mtnPort, never()).initiateDisbursement(any());
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 17: transaction already claimed → TRANSACTION_CLAIMED; disbursement failed
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void initiateRejectsClaimedTransaction() {
        doThrow(new TransactionClaimedException("txn-001 already claimed"))
                .when(transactionClaimValidationService)
                .validateAndClaim(anyLong(), anyList(), any(BigDecimal.class), anyLong());

        DisbursementResponse response = orchestrator.initiate(TENANT_ID, validRequest(SMALL_AMOUNT, MTN_MSISDN));

        assertThat(response.errorCode()).isEqualTo("TRANSACTION_CLAIMED");
        verify(claimTransitionService).transitionClaims(eq(DSB_PK_ID),
                eq(DisbursementRefStatus.PENDING), eq(DisbursementRefStatus.RELEASED));
        verify(mtnPort, never()).initiateDisbursement(any());
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 18: FEE-01 regression — orchestrator has NO FeeEvaluationService dependency
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void initiateNeverCallsFeeEvaluationService_FEE01_regression() {
        // FEE-01: orchestrator must not depend on FeeEvaluationService
        // Phase 54 retired the fee evaluation model; this test ensures it stays retired
        long feeFields = java.util.Arrays.stream(orchestrator.getClass().getDeclaredFields())
                .filter(f -> f.getType().getSimpleName().contains("Fee"))
                .count();
        assertThat(feeFields).as("FEE-01: no FeeEvaluationService dependency").isZero();
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 19: validation failure triggers releaseAndFail — FAILED transition + claim release
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void initiateOnValidationFailureTransitionsDisbursementToFailed() {
        doThrow(new InvalidTransactionException("txn-001 has txStatus=FAILED, expected SUCCESS"))
                .when(transactionClaimValidationService)
                .validateAndClaim(anyLong(), anyList(), any(BigDecimal.class), anyLong());

        orchestrator.initiate(TENANT_ID, validRequest(SMALL_AMOUNT, MTN_MSISDN));

        // releaseAndFail directly locks the disbursement row and applies FAILED transition
        verify(disbursementRepository).findByDisbursementIdForUpdate(eq(DSB_ID));
        verify(claimTransitionService).transitionClaims(eq(DSB_PK_ID),
                eq(DisbursementRefStatus.PENDING), eq(DisbursementRefStatus.RELEASED));
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 20: happy path calls validateAndClaim once with correct tenant/list/amount
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void initiateOnSuccessCallsValidateAndClaim() {
        DisbursementResponse response = orchestrator.initiate(TENANT_ID, validRequest(SMALL_AMOUNT, MTN_MSISDN));

        assertThat(response.errorCode()).isNull();
        verify(transactionClaimValidationService).validateAndClaim(
                eq(TENANT_ID),
                eq(java.util.List.of("txn-001")),
                eq(SMALL_AMOUNT),
                any(Long.class)
        );
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 21: amount > adminApprovalThreshold → PENDING_ADMIN_APPROVAL; no provider dispatch
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void initiate_amountAboveAdminApprovalThreshold_routesToAdminApproval() {
        Disbursement adminDsb = mockDisbursement(DSB_ID, DisbursementStatus.INITIATED, ADMIN_APPROVAL_AMOUNT);
        when(dsbService.create(any(), any(), any(), eq(DisbursementStatus.INITIATED))).thenReturn(adminDsb);

        DisbursementResponse response = orchestrator.initiate(TENANT_ID, validRequest(ADMIN_APPROVAL_AMOUNT, MTN_MSISDN));

        assertThat(response.status()).isEqualTo("PENDING_ADMIN_APPROVAL");
        assertThat(response.errorCode()).isNull();
        // transitionToPendingAdminApproval is called via disbursementService
        verify(dsbService).transitionToPendingAdminApproval(eq(DSB_ID), any(String.class));
        // AdminApprovalRequiredEvent is published
        ArgumentCaptor<DisbursementAdminApprovalRequiredEvent> eventCaptor =
                ArgumentCaptor.forClass(DisbursementAdminApprovalRequiredEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().disbursementId()).isEqualTo(DSB_ID);
        assertThat(eventCaptor.getValue().amount()).isEqualByComparingTo(ADMIN_APPROVAL_AMOUNT);
        // NO provider dispatch
        verify(mtnPort, never()).initiateDisbursement(any());
        verify(orangePort, never()).initiateDisbursement(any());
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 22: amount exactly equal to adminApprovalThreshold does NOT route to admin-approval
    //          (compareTo > 0 means STRICTLY greater; exactly 5_000_000 falls through to step-up)
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void initiate_amountAtAdminApprovalThreshold_doesNotRouteToAdminApproval() {
        BigDecimal atThreshold = ADMIN_THRESHOLD; // exactly 5_000_000
        Disbursement stepUpDsb = mockDisbursement(DSB_ID, DisbursementStatus.PENDING_CONFIRMATION, atThreshold);
        when(dsbService.create(any(), any(), any(), eq(DisbursementStatus.PENDING_CONFIRMATION)))
                .thenReturn(stepUpDsb);

        DisbursementResponse response = orchestrator.initiate(TENANT_ID, validRequest(atThreshold, MTN_MSISDN));

        // 5_000_000 > 500_000 step-up threshold → PENDING_CONFIRMATION (not admin-approval)
        assertThat(response.status()).isEqualTo("PENDING_CONFIRMATION");
        verify(dsbService, never()).transitionToPendingAdminApproval(any(), any());
        verify(eventPublisher, never()).publishEvent(any(DisbursementAdminApprovalRequiredEvent.class));
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 23: amount > STEP_UP_THRESHOLD but < adminApprovalThreshold → PENDING_CONFIRMATION
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void initiate_amountAboveStepUpButBelowAdminApproval_routesToStepUp() {
        BigDecimal midAmount = BigDecimal.valueOf(1_000_000); // > 500K and < 5M
        Disbursement stepUpDsb = mockDisbursement(DSB_ID, DisbursementStatus.PENDING_CONFIRMATION, midAmount);
        when(dsbService.create(any(), any(), any(), eq(DisbursementStatus.PENDING_CONFIRMATION)))
                .thenReturn(stepUpDsb);

        DisbursementResponse response = orchestrator.initiate(TENANT_ID, validRequest(midAmount, MTN_MSISDN));

        assertThat(response.status()).isEqualTo("PENDING_CONFIRMATION");
        verify(dsbService, never()).transitionToPendingAdminApproval(any(), any());
        verify(eventPublisher, never()).publishEvent(any(DisbursementAdminApprovalRequiredEvent.class));
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 24: amount < STEP_UP_THRESHOLD → provider dispatch (existing behavior preserved)
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void initiate_amountBelowStepUp_routesToProvider() {
        DisbursementResponse response = orchestrator.initiate(TENANT_ID, validRequest(SMALL_AMOUNT, MTN_MSISDN));

        assertThat(response.status()).isEqualTo("PROCESSING");
        verify(mtnPort).initiateDisbursement(any());
        verify(dsbService, never()).transitionToPendingAdminApproval(any(), any());
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 25: admin-approval path response does NOT contain adminNote field
    //          (ADMIN-02 invariant: adminNote is ops-only, never exposed via public API)
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void initiate_adminApprovalPath_responseDoesNotContainAdminNote() {
        Disbursement adminDsb = mockDisbursement(DSB_ID, DisbursementStatus.INITIATED, ADMIN_APPROVAL_AMOUNT);
        when(dsbService.create(any(), any(), any(), eq(DisbursementStatus.INITIATED))).thenReturn(adminDsb);

        DisbursementResponse response = orchestrator.initiate(TENANT_ID, validRequest(ADMIN_APPROVAL_AMOUNT, MTN_MSISDN));

        // DisbursementResponse record fields: disbursementId, status, providerRef, recipientMsisdn,
        // amount, fee, currency, reference, provider, errorCode, errorMessage
        // adminNote is NOT one of these fields — assert no field named "adminNote" exists
        long adminNoteFields = java.util.Arrays.stream(response.getClass().getRecordComponents())
                .filter(c -> c.getName().equalsIgnoreCase("adminNote"))
                .count();
        assertThat(adminNoteFields).as("adminNote must not appear in DisbursementResponse").isZero();
        assertThat(response.status()).isEqualTo("PENDING_ADMIN_APPROVAL");
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 26: releaseAndFail releases PENDING claims to RELEASED (CLAIM-03)
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void releaseAndFail_releasesPendingClaims() {
        // Trigger releaseAndFail via a RuntimeException on provider dispatch
        when(mtnPort.initiateDisbursement(any()))
                .thenThrow(new RuntimeException("provider error 503"));

        DisbursementResponse response = orchestrator.initiate(TENANT_ID, validRequest(SMALL_AMOUNT, MTN_MSISDN));

        assertThat(response.errorCode()).isEqualTo(DisbursementOrchestratorError.PROVIDER_ERROR.getErrorCode());
        // disbursement locked for FAILED transition
        verify(disbursementRepository).findByDisbursementIdForUpdate(eq(DSB_ID));
        // claims released atomically with FAILED transition (CLAIM-03)
        verify(claimTransitionService).transitionClaims(
                eq(DSB_PK_ID), eq(DisbursementRefStatus.PENDING), eq(DisbursementRefStatus.RELEASED));
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 27: releaseAndFail with zero claim rows completes gracefully (CLAIM-05 zero-claim invariant)
    //          transitionClaims returns 0 (no rows) — disbursement still transitions to FAILED
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void releaseAndFail_zeroClaims_completesGracefully() {
        // Configure claimTransitionService to return 0 (no claim rows exist yet)
        when(claimTransitionService.transitionClaims(anyLong(), any(), any())).thenReturn(0);

        // Trigger releaseAndFail via a RuntimeException on provider dispatch
        when(mtnPort.initiateDisbursement(any())).thenThrow(new RuntimeException("provider boom"));

        // Must NOT throw; disbursement still transitions to FAILED
        DisbursementResponse response = orchestrator.initiate(TENANT_ID, validRequest(SMALL_AMOUNT, MTN_MSISDN));

        assertThat(response.errorCode()).isEqualTo(DisbursementOrchestratorError.PROVIDER_ERROR.getErrorCode());
        // transitionClaims is called (returns 0 gracefully, no exception)
        verify(claimTransitionService).transitionClaims(
                eq(DSB_PK_ID), eq(DisbursementRefStatus.PENDING), eq(DisbursementRefStatus.RELEASED));
        // disbursementRepository was locked (FAILED transition attempted)
        verify(disbursementRepository).findByDisbursementIdForUpdate(eq(DSB_ID));
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // IDEM-01 / IDEM-02 / IDEM-03: Retry recovery tests (Phase 57)
    // ════════════════════════════════════════════════════════════════════════════════

    private static String failedCachedJson(String errorCode) {
        return "{\"disbursementId\":\"" + DSB_ID + "\",\"status\":\"FAILED\","
                + "\"providerRef\":null,\"recipientMsisdn\":null,"
                + "\"amount\":null,\"fee\":0,\"currency\":null,"
                + "\"reference\":null,\"provider\":null,"
                + "\"errorCode\":\"" + errorCode + "\","
                + "\"errorMessage\":\"prior failure\"}";
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 28: IDEM-02 — retriable code + no active claims → reactivate + dispatch
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void initiate_failedRetriableCode_reactivatesClaimsAndDispatches() {
        // Cached FAILED response with retriable code
        when(idempotencyService.checkAndReserve(any(), any()))
                .thenReturn(Optional.of(new CachedResponse(202, failedCachedJson("PROVIDER_ERROR"))));
        when(retryClassifier.classify("PROVIDER_ERROR"))
                .thenReturn(DisbursementRetryClassifier.Classification.RETRIABLE);
        // Disbursement entity exists and is FAILED
        Disbursement failedDsb = mockDisbursement(DSB_ID, DisbursementStatus.FAILED, SMALL_AMOUNT);
        when(failedDsb.getProvider()).thenReturn(MobilePaymentProvider.MTN);
        when(failedDsb.getRetryCount()).thenReturn(0);
        when(disbursementRepository.findByTenantIdAndIdempotencyKey(eq(TENANT_ID), eq("IDEM-001")))
                .thenReturn(Optional.of(failedDsb));
        when(disbursementRepository.findByDisbursementIdForUpdate(eq(DSB_ID)))
                .thenReturn(Optional.of(failedDsb));
        when(refRepository.findClaimedTransactionIds(any(), any())).thenReturn(java.util.List.of());

        DisbursementResponse response = orchestrator.initiate(TENANT_ID, validRequest(SMALL_AMOUNT, MTN_MSISDN));

        assertThat(response.status()).isEqualTo("PROCESSING");
        // RELEASED → PENDING reactivation
        verify(claimTransitionService).transitionClaims(eq(DSB_PK_ID),
                eq(DisbursementRefStatus.RELEASED), eq(DisbursementRefStatus.PENDING));
        // retry_count incremented
        verify(failedDsb).setRetryCount(1);
        // FAILED → INITIATED transition
        verify(failedDsb).applyTransition(eq(DisbursementStatus.INITIATED));
        // Provider re-dispatched
        verify(mtnPort).initiateDisbursement(any());
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 29: IDEM-01 — retriable code + active claim conflict → TRANSACTION_CLAIMED
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void initiate_failedRetriableCode_butActiveClaimExists_returnsTransactionClaimed() {
        when(idempotencyService.checkAndReserve(any(), any()))
                .thenReturn(Optional.of(new CachedResponse(202, failedCachedJson("PROVIDER_ERROR"))));
        when(retryClassifier.classify("PROVIDER_ERROR"))
                .thenReturn(DisbursementRetryClassifier.Classification.RETRIABLE);
        Disbursement failedDsb = mockDisbursement(DSB_ID, DisbursementStatus.FAILED, SMALL_AMOUNT);
        when(disbursementRepository.findByTenantIdAndIdempotencyKey(eq(TENANT_ID), eq("IDEM-001")))
                .thenReturn(Optional.of(failedDsb));
        // One transaction is claimed by another disbursement
        when(refRepository.findClaimedTransactionIds(any(), any()))
                .thenReturn(java.util.List.of("txn-001"));

        DisbursementResponse response = orchestrator.initiate(TENANT_ID, validRequest(SMALL_AMOUNT, MTN_MSISDN));

        assertThat(response.errorCode())
                .isEqualTo(DisbursementOrchestratorError.TRANSACTION_CLAIMED.getErrorCode());
        // No state change
        verify(failedDsb, never()).applyTransition(any());
        verify(claimTransitionService, never()).transitionClaims(anyLong(), eq(DisbursementRefStatus.RELEASED), any());
        verify(mtnPort, never()).initiateDisbursement(any());
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 30: IDEM-03 — terminal code → cached response returned verbatim
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void initiate_failedTerminalCode_returnsCachedResponseWithoutRetry() {
        when(idempotencyService.checkAndReserve(any(), any()))
                .thenReturn(Optional.of(new CachedResponse(202, failedCachedJson("RECIPIENT_NOT_FOUND"))));
        when(retryClassifier.classify("RECIPIENT_NOT_FOUND"))
                .thenReturn(DisbursementRetryClassifier.Classification.TERMINAL);

        DisbursementResponse response = orchestrator.initiate(TENANT_ID, validRequest(SMALL_AMOUNT, MTN_MSISDN));

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.errorCode()).isEqualTo("RECIPIENT_NOT_FOUND");
        verify(retryClassifier).classify("RECIPIENT_NOT_FOUND");
        verifyNoInteractions(refRepository);
        verify(disbursementRepository, never()).findByTenantIdAndIdempotencyKey(any(), any());
        verify(mtnPort, never()).initiateDisbursement(any());
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 31: IDEM-03 — terminal code FRAUD_BLOCK → cached response returned verbatim
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void initiate_failedTerminalCode_fraudBlock_returnsCachedResponse() {
        when(idempotencyService.checkAndReserve(any(), any()))
                .thenReturn(Optional.of(new CachedResponse(202, failedCachedJson("FRAUD_BLOCK"))));
        when(retryClassifier.classify("FRAUD_BLOCK"))
                .thenReturn(DisbursementRetryClassifier.Classification.TERMINAL);

        DisbursementResponse response = orchestrator.initiate(TENANT_ID, validRequest(SMALL_AMOUNT, MTN_MSISDN));

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.errorCode()).isEqualTo("FRAUD_BLOCK");
        verifyNoInteractions(mtnPort, orangePort);
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 32: IDEM-02 defensive — retriable but entity not found in DB → cached response
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void initiate_failedRetriable_butDisbursementNotFoundInDb_returnsCachedResponse() {
        when(idempotencyService.checkAndReserve(any(), any()))
                .thenReturn(Optional.of(new CachedResponse(202, failedCachedJson("PROVIDER_ERROR"))));
        when(retryClassifier.classify("PROVIDER_ERROR"))
                .thenReturn(DisbursementRetryClassifier.Classification.RETRIABLE);
        when(disbursementRepository.findByTenantIdAndIdempotencyKey(eq(TENANT_ID), eq("IDEM-001")))
                .thenReturn(Optional.empty());

        DisbursementResponse response = orchestrator.initiate(TENANT_ID, validRequest(SMALL_AMOUNT, MTN_MSISDN));

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.errorCode()).isEqualTo("PROVIDER_ERROR");
        verify(claimTransitionService, never()).transitionClaims(anyLong(), eq(DisbursementRefStatus.RELEASED), any());
        verify(mtnPort, never()).initiateDisbursement(any());
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 33: Race guard — retriable but status no longer FAILED inside lock → cached response
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void initiate_failedRetriable_butStatusNoLongerFailed_returnsCachedResponse() {
        when(idempotencyService.checkAndReserve(any(), any()))
                .thenReturn(Optional.of(new CachedResponse(202, failedCachedJson("PROVIDER_ERROR"))));
        when(retryClassifier.classify("PROVIDER_ERROR"))
                .thenReturn(DisbursementRetryClassifier.Classification.RETRIABLE);
        Disbursement failedDsb = mockDisbursement(DSB_ID, DisbursementStatus.FAILED, SMALL_AMOUNT);
        when(disbursementRepository.findByTenantIdAndIdempotencyKey(eq(TENANT_ID), eq("IDEM-001")))
                .thenReturn(Optional.of(failedDsb));
        // Inside the lock, the disbursement is no longer FAILED (race won by another retry)
        Disbursement lockedDsb = mock(Disbursement.class);
        when(lockedDsb.getDisbursementStatus()).thenReturn(DisbursementStatus.PROCESSING);
        when(disbursementRepository.findByDisbursementIdForUpdate(eq(DSB_ID)))
                .thenReturn(Optional.of(lockedDsb));

        DisbursementResponse response = orchestrator.initiate(TENANT_ID, validRequest(SMALL_AMOUNT, MTN_MSISDN));

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.errorCode()).isEqualTo("PROVIDER_ERROR");
        verify(lockedDsb, never()).applyTransition(any());
        verify(mtnPort, never()).initiateDisbursement(any());
    }
}
