package com.softropic.payam.disbursement.service;

import com.softropic.payam.disbursement.contract.exception.InsufficientBalanceException;
import com.softropic.payam.disbursement.repo.MerchantWalletBalance;
import com.softropic.payam.disbursement.repo.MerchantWalletBalanceRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Atomic balance gate for the MERCHANT_WALLET.
 *
 * BAL-01: checkAndReserve uses PESSIMISTIC_WRITE on MerchantWalletBalanceRepository.findByTenantIdForUpdate.
 *         Under N concurrent callers with balance covering only 1, exactly 1 succeeds; the other N-1
 *         throw InsufficientBalanceException. Verified by WalletBalanceConcurrencyIT.
 *
 * BAL-02: release restores the exact amount to balance and decrements reserved_amount. Called ONLY
 *         when a disbursement reaches the FAILED terminal state.
 *
 * BAL-03 boundary: release MUST NOT be called when a disbursement reaches EXPIRED. EXPIRED means the
 *         provider may still execute the payout; releasing balance would allow overdraft. Ops must
 *         resolve EXPIRED disbursements manually.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletBalanceService {

    private final MerchantWalletBalanceRepository walletBalanceRepository;

    /**
     * Atomically check that the wallet has at least {@code amount} available, and if so decrement the
     * available balance and increment the reserved amount. The underlying query holds a
     * PESSIMISTIC_WRITE row lock for the duration of this transaction; other callers for the same
     * tenant block until this method commits.
     *
     * @throws InsufficientBalanceException if the wallet does not exist or balance &lt; amount.
     *         The wallet row state is NOT mutated when the check fails (the setBalance/setReservedAmount
     *         calls happen only after the guard passes).
     */
    @Transactional
    public void checkAndReserve(Long tenantId, BigDecimal amount) {
        MerchantWalletBalance wallet = walletBalanceRepository
            .findByTenantIdForUpdate(tenantId)
            .orElseThrow(() -> new InsufficientBalanceException(
                "No wallet found for tenant: " + tenantId));

        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new InsufficientBalanceException(
                "Insufficient balance for tenant " + tenantId
                + ": available=" + wallet.getBalance().toPlainString()
                + " required=" + amount.toPlainString());
        }

        wallet.setBalance(wallet.getBalance().subtract(amount));
        wallet.setReservedAmount(wallet.getReservedAmount().add(amount));
        log.debug("Reserved {} on tenant {} wallet: new balance={}, reserved={}",
            amount, tenantId, wallet.getBalance(), wallet.getReservedAmount());
    }

    /**
     * Restore {@code amount} to the wallet available balance and decrement reserved_amount by the same.
     *
     * <p>IMPORTANT: call this method ONLY when a disbursement transitions to FAILED (BAL-02). Do NOT
     * call for EXPIRED — per BAL-03, EXPIRED means the provider may have accepted the transfer and
     * the reserved balance must be held pending manual ops resolution.
     *
     * @throws IllegalStateException if no wallet exists for tenantId — this is a programmer bug because
     *         a wallet must have existed for checkAndReserve to succeed earlier in the lifecycle.
     */
    @Transactional
    public void release(Long tenantId, BigDecimal amount) {
        MerchantWalletBalance wallet = walletBalanceRepository
            .findByTenantIdForUpdate(tenantId)
            .orElseThrow(() -> new IllegalStateException(
                "Cannot release: no wallet found for tenant " + tenantId));

        wallet.setBalance(wallet.getBalance().add(amount));
        wallet.setReservedAmount(wallet.getReservedAmount().subtract(amount));
        log.debug("Released {} on tenant {} wallet: new balance={}, reserved={}",
            amount, tenantId, wallet.getBalance(), wallet.getReservedAmount());
    }
}
