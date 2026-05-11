package com.softropic.payam.disbursement.service;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.disbursement.contract.DisbursementRefStatus;
import com.softropic.payam.disbursement.contract.exception.AmountMismatchException;
import com.softropic.payam.disbursement.contract.exception.InvalidTransactionException;
import com.softropic.payam.disbursement.contract.exception.TransactionClaimedException;
import com.softropic.payam.disbursement.repo.DisbursementTransactionRef;
import com.softropic.payam.disbursement.repo.DisbursementTransactionRefRepository;
import com.softropic.payam.transaction.contract.LedgerFlow;
import com.softropic.payam.transaction.contract.TransactionStatus;
import com.softropic.payam.transaction.repo.Transaction;
import com.softropic.payam.transaction.repo.TransactionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionClaimValidationServiceTest {

    @Mock
    TransactionRepository transactionRepository;
    @Mock
    DisbursementTransactionRefRepository transactionRefRepository;
    @InjectMocks
    TransactionClaimValidationService service;

    private static final Long TENANT_ID = 42L;
    private static final Long DSB_DB_ID = 999L;

    private Transaction tx(String txnId, Long tenantId, TransactionStatus status,
                            LedgerFlow flow, BigDecimal amount, BigDecimal feeAmount) {
        return Transaction.builder()
                .transactionId(txnId).traceId(txnId).tenantId(tenantId)
                .provider(MobilePaymentProvider.MTN)
                .txStatus(status).flow(flow)
                .amount(amount).feeAmount(feeAmount)
                .currency("XAF").build();
    }

    @BeforeEach
    void setUpDefaultClaimedIdsEmpty() {
        // By default, no active claims exist
        lenient().when(transactionRefRepository.findClaimedTransactionIds(anyList(), any(Collection.class)))
                .thenReturn(List.of());
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // TXN-01: Empty list → InvalidTransactionException
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void emptyTransactionIdsThrowsInvalidTransaction() {
        assertThatThrownBy(() ->
                service.validateAndClaim(TENANT_ID, List.of(), BigDecimal.valueOf(100), DSB_DB_ID))
                .isInstanceOf(InvalidTransactionException.class)
                .hasMessageContaining("empty");
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // TXN-01: Wrong tenant ownership → InvalidTransactionException BEFORE SELECT FOR UPDATE
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void wrongTenantOwnershipThrowsInvalidTransactionBeforeLock() {
        Transaction wrongTenantTx = tx("txn-001", 99L /*different tenant*/,
                TransactionStatus.SUCCESS, LedgerFlow.COLLECTION, BigDecimal.valueOf(100), null);
        when(transactionRepository.findByTransactionId("txn-001")).thenReturn(Optional.of(wrongTenantTx));

        assertThatThrownBy(() ->
                service.validateAndClaim(TENANT_ID, List.of("txn-001"), BigDecimal.valueOf(100), DSB_DB_ID))
                .isInstanceOf(InvalidTransactionException.class)
                .hasMessageContaining("does not belong to tenant");

        // The lock query must NOT have been called — ownership check happens FIRST
        verify(transactionRepository, never()).findByTransactionIdsForUpdate(anyList());
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // TXN-01: Missing transaction (request 3 ids, findAll returns only 2)
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void missingTransactionThrowsInvalidTransaction() {
        Transaction tx1 = tx("txn-001", TENANT_ID, TransactionStatus.SUCCESS,
                LedgerFlow.COLLECTION, BigDecimal.valueOf(50), null);
        Transaction tx2 = tx("txn-002", TENANT_ID, TransactionStatus.SUCCESS,
                LedgerFlow.COLLECTION, BigDecimal.valueOf(50), null);

        when(transactionRepository.findByTransactionId("txn-001")).thenReturn(Optional.of(tx1));
        when(transactionRepository.findByTransactionId("txn-002")).thenReturn(Optional.of(tx2));
        when(transactionRepository.findByTransactionId("txn-003")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.validateAndClaim(TENANT_ID, List.of("txn-001", "txn-002", "txn-003"),
                        BigDecimal.valueOf(100), DSB_DB_ID))
                .isInstanceOf(InvalidTransactionException.class)
                .hasMessageContaining("txn-003");
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // TXN-02: Non-SUCCESS status → InvalidTransactionException
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void nonSuccessStatusThrowsInvalidTransaction() {
        Transaction failedTx = tx("txn-001", TENANT_ID, TransactionStatus.FAILED,
                LedgerFlow.COLLECTION, BigDecimal.valueOf(100), null);

        when(transactionRepository.findByTransactionId("txn-001")).thenReturn(Optional.of(failedTx));
        when(transactionRepository.findByTransactionIdsForUpdate(anyList())).thenReturn(List.of(failedTx));

        assertThatThrownBy(() ->
                service.validateAndClaim(TENANT_ID, List.of("txn-001"), BigDecimal.valueOf(100), DSB_DB_ID))
                .isInstanceOf(InvalidTransactionException.class)
                .hasMessageContaining("txStatus");
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // TXN-02: Non-COLLECTION flow → InvalidTransactionException
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void nonCollectionFlowThrowsInvalidTransaction() {
        Transaction disbTx = tx("txn-001", TENANT_ID, TransactionStatus.SUCCESS,
                LedgerFlow.DISBURSEMENT, BigDecimal.valueOf(100), null);

        when(transactionRepository.findByTransactionId("txn-001")).thenReturn(Optional.of(disbTx));
        when(transactionRepository.findByTransactionIdsForUpdate(anyList())).thenReturn(List.of(disbTx));

        assertThatThrownBy(() ->
                service.validateAndClaim(TENANT_ID, List.of("txn-001"), BigDecimal.valueOf(100), DSB_DB_ID))
                .isInstanceOf(InvalidTransactionException.class)
                .hasMessageContaining("flow");
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // TXN-06: null flow treated as COLLECTION (getEffectiveFlow → COLLECTION)
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void nullFlowTreatedAsCollectionPasses() {
        // flow=null, txStatus=SUCCESS → should pass TXN-02 (getEffectiveFlow() == COLLECTION)
        Transaction nullFlowTx = tx("txn-001", TENANT_ID, TransactionStatus.SUCCESS,
                null /*null flow*/, BigDecimal.valueOf(100), null);

        when(transactionRepository.findByTransactionId("txn-001")).thenReturn(Optional.of(nullFlowTx));
        when(transactionRepository.findByTransactionIdsForUpdate(anyList())).thenReturn(List.of(nullFlowTx));

        // Should not throw — null flow is treated as COLLECTION
        service.validateAndClaim(TENANT_ID, List.of("txn-001"), BigDecimal.valueOf(100), DSB_DB_ID);

        verify(transactionRefRepository).save(any(DisbursementTransactionRef.class));
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // TXN-06: feeAmount=null treated as ZERO — full amount is disbursable
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void nullFeeAmountTreatedAsZero() {
        // amount=100, feeAmount=null → disbursable=100, request.amount=100 → passes
        Transaction nullFeeTx = tx("txn-001", TENANT_ID, TransactionStatus.SUCCESS,
                LedgerFlow.COLLECTION, BigDecimal.valueOf(100), null /*null feeAmount*/);

        when(transactionRepository.findByTransactionId("txn-001")).thenReturn(Optional.of(nullFeeTx));
        when(transactionRepository.findByTransactionIdsForUpdate(anyList())).thenReturn(List.of(nullFeeTx));

        // Should not throw — null feeAmount coalesces to ZERO
        service.validateAndClaim(TENANT_ID, List.of("txn-001"), BigDecimal.valueOf(100), DSB_DB_ID);

        verify(transactionRefRepository).save(any(DisbursementTransactionRef.class));
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // TXN-04: Amount mismatch → AmountMismatchException with both numbers in message
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void amountMismatchThrowsAmountMismatch() {
        // sum(amount - feeAmount) = 100 - 1 = 99, but request.amount = 100 → mismatch
        Transaction tx1 = tx("txn-001", TENANT_ID, TransactionStatus.SUCCESS,
                LedgerFlow.COLLECTION, BigDecimal.valueOf(100), BigDecimal.valueOf(1));

        when(transactionRepository.findByTransactionId("txn-001")).thenReturn(Optional.of(tx1));
        when(transactionRepository.findByTransactionIdsForUpdate(anyList())).thenReturn(List.of(tx1));

        assertThatThrownBy(() ->
                service.validateAndClaim(TENANT_ID, List.of("txn-001"), BigDecimal.valueOf(100), DSB_DB_ID))
                .isInstanceOf(AmountMismatchException.class)
                .hasMessageContaining("100")
                .hasMessageContaining("99");
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // TXN-04: Scale difference — compareTo passes (100.00 == 100.0)
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void scaleDifferenceCompareToPasses() {
        // amount=100.00 (scale 2), request.amount=100.0 (scale 1) — compareTo should treat as equal
        Transaction tx1 = tx("txn-001", TENANT_ID, TransactionStatus.SUCCESS,
                LedgerFlow.COLLECTION, new BigDecimal("100.00"), null);

        when(transactionRepository.findByTransactionId("txn-001")).thenReturn(Optional.of(tx1));
        when(transactionRepository.findByTransactionIdsForUpdate(anyList())).thenReturn(List.of(tx1));

        // request.amount has different scale — compareTo must be used (not equals)
        service.validateAndClaim(TENANT_ID, List.of("txn-001"), new BigDecimal("100.0"), DSB_DB_ID);

        verify(transactionRefRepository).save(any(DisbursementTransactionRef.class));
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // TXN-03: Active claim exists → TransactionClaimedException naming conflicting ids
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void activeClaimThrowsTransactionClaimed() {
        Transaction tx1 = tx("txn-001", TENANT_ID, TransactionStatus.SUCCESS,
                LedgerFlow.COLLECTION, BigDecimal.valueOf(50), null);
        Transaction tx2 = tx("txn-002", TENANT_ID, TransactionStatus.SUCCESS,
                LedgerFlow.COLLECTION, BigDecimal.valueOf(50), null);

        when(transactionRepository.findByTransactionId("txn-001")).thenReturn(Optional.of(tx1));
        when(transactionRepository.findByTransactionId("txn-002")).thenReturn(Optional.of(tx2));
        when(transactionRepository.findByTransactionIdsForUpdate(anyList())).thenReturn(List.of(tx1, tx2));

        // Simulate "txn-002" already has an active claim
        when(transactionRefRepository.findClaimedTransactionIds(anyList(), any(Collection.class)))
                .thenReturn(List.of("txn-002"));

        assertThatThrownBy(() ->
                service.validateAndClaim(TENANT_ID, List.of("txn-001", "txn-002"),
                        BigDecimal.valueOf(100), DSB_DB_ID))
                .isInstanceOf(TransactionClaimedException.class)
                .hasMessageContaining("txn-002");
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Happy path: successful validation inserts PENDING ref rows for each transactionId
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void successfulValidationInsertsPendingRefRows() {
        Transaction tx1 = tx("txn-001", TENANT_ID, TransactionStatus.SUCCESS,
                LedgerFlow.COLLECTION, BigDecimal.valueOf(60), BigDecimal.valueOf(10));
        Transaction tx2 = tx("txn-002", TENANT_ID, TransactionStatus.SUCCESS,
                LedgerFlow.COLLECTION, BigDecimal.valueOf(50), null);
        Transaction tx3 = tx("txn-003", TENANT_ID, TransactionStatus.SUCCESS,
                LedgerFlow.COLLECTION, BigDecimal.valueOf(10), null);

        when(transactionRepository.findByTransactionId("txn-001")).thenReturn(Optional.of(tx1));
        when(transactionRepository.findByTransactionId("txn-002")).thenReturn(Optional.of(tx2));
        when(transactionRepository.findByTransactionId("txn-003")).thenReturn(Optional.of(tx3));
        when(transactionRepository.findByTransactionIdsForUpdate(anyList()))
                .thenReturn(List.of(tx1, tx2, tx3));

        // sum = (60-10) + 50 + 10 = 110
        service.validateAndClaim(TENANT_ID, List.of("txn-001", "txn-002", "txn-003"),
                BigDecimal.valueOf(110), DSB_DB_ID);

        ArgumentCaptor<DisbursementTransactionRef> captor =
                ArgumentCaptor.forClass(DisbursementTransactionRef.class);
        verify(transactionRefRepository, times(3)).save(captor.capture());

        List<DisbursementTransactionRef> saved = captor.getAllValues();
        // All 3 must be PENDING
        assertThat(saved).allMatch(r -> r.getRefStatus() == DisbursementRefStatus.PENDING);
        // All 3 must have the correct disbursementDbId
        assertThat(saved).allMatch(r -> DSB_DB_ID.equals(r.getDisbursementId()));
        // Each must have a unique transactionId matching the input
        assertThat(saved).extracting(DisbursementTransactionRef::getTransactionId)
                .containsExactlyInAnyOrder("txn-001", "txn-002", "txn-003");
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // TXN-03: DataIntegrityViolationException on save → TransactionClaimedException (DB-layer guard)
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void dataIntegrityViolationOnSaveSurfacesAsTransactionClaimed() {
        Transaction tx1 = tx("txn-001", TENANT_ID, TransactionStatus.SUCCESS,
                LedgerFlow.COLLECTION, BigDecimal.valueOf(100), null);

        when(transactionRepository.findByTransactionId("txn-001")).thenReturn(Optional.of(tx1));
        when(transactionRepository.findByTransactionIdsForUpdate(anyList())).thenReturn(List.of(tx1));
        when(transactionRefRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("uq_dtr_txn_active_claim violation"));

        assertThatThrownBy(() ->
                service.validateAndClaim(TENANT_ID, List.of("txn-001"), BigDecimal.valueOf(100), DSB_DB_ID))
                .isInstanceOf(TransactionClaimedException.class)
                .hasMessageContaining("txn-001");
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // BigDecimal compareTo trap: ensure correct scale-insensitive comparison is used
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void bigDecimalEqualsTrapAvoided() {
        // new BigDecimal("100.00").equals(new BigDecimal("100")) is false
        // new BigDecimal("100.00").compareTo(new BigDecimal("100")) is 0
        // This test verifies the behavioral outcome: matching on compareTo passes, not fails
        Transaction tx1 = tx("txn-001", TENANT_ID, TransactionStatus.SUCCESS,
                LedgerFlow.COLLECTION, new BigDecimal("100.00"), new BigDecimal("0.00"));

        when(transactionRepository.findByTransactionId("txn-001")).thenReturn(Optional.of(tx1));
        when(transactionRepository.findByTransactionIdsForUpdate(anyList())).thenReturn(List.of(tx1));

        // sum disbursable = 100.00 - 0.00 = 100.00, request.amount = 100 (scale 0)
        // equals() would fail (scale difference); compareTo() must pass
        service.validateAndClaim(TENANT_ID, List.of("txn-001"), new BigDecimal("100"), DSB_DB_ID);

        verify(transactionRefRepository).save(any(DisbursementTransactionRef.class));
    }
}
