package com.softropic.payam.disbursement.service;

import com.softropic.payam.disbursement.contract.exception.InsufficientBalanceException;
import com.softropic.payam.disbursement.repo.MerchantWalletBalance;
import com.softropic.payam.disbursement.repo.MerchantWalletBalanceRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletBalanceServiceTest {

    @Mock
    private MerchantWalletBalanceRepository walletBalanceRepository;

    @InjectMocks
    private WalletBalanceService walletBalanceService;

    private MerchantWalletBalance wallet;
    private final Long TENANT_ID = 1L;

    @BeforeEach
    void setUp() {
        wallet = new MerchantWalletBalance();
        wallet.setTenantId(TENANT_ID);
        wallet.setBalance(new BigDecimal("1000.00"));
        wallet.setReservedAmount(BigDecimal.ZERO);
        wallet.setCurrency("XAF");
    }

    @Test
    void checkAndReserve_sufficientBalance_decrementsBalanceIncrementsReserved() {
        when(walletBalanceRepository.findByTenantIdForUpdate(TENANT_ID))
            .thenReturn(Optional.of(wallet));

        walletBalanceService.checkAndReserve(TENANT_ID, new BigDecimal("300.00"));

        assertThat(wallet.getBalance()).isEqualByComparingTo("700.00");
        assertThat(wallet.getReservedAmount()).isEqualByComparingTo("300.00");
        verify(walletBalanceRepository, times(1)).findByTenantIdForUpdate(TENANT_ID);
    }

    @Test
    void checkAndReserve_exactMatch_leavesZeroBalance() {
        wallet.setBalance(new BigDecimal("500.00"));
        when(walletBalanceRepository.findByTenantIdForUpdate(TENANT_ID))
            .thenReturn(Optional.of(wallet));

        walletBalanceService.checkAndReserve(TENANT_ID, new BigDecimal("500.00"));

        assertThat(wallet.getBalance()).isEqualByComparingTo("0");
        assertThat(wallet.getReservedAmount()).isEqualByComparingTo("500.00");
    }

    @Test
    void checkAndReserve_insufficientBalance_throwsAndDoesNotMutate() {
        wallet.setBalance(new BigDecimal("100.00"));
        when(walletBalanceRepository.findByTenantIdForUpdate(TENANT_ID))
            .thenReturn(Optional.of(wallet));

        assertThatThrownBy(() ->
            walletBalanceService.checkAndReserve(TENANT_ID, new BigDecimal("500.00")))
            .isInstanceOf(InsufficientBalanceException.class)
            .hasMessageContaining("100")
            .hasMessageContaining("500");

        // Critical: the wallet state MUST NOT have been mutated on the failure path.
        assertThat(wallet.getBalance()).isEqualByComparingTo("100.00");
        assertThat(wallet.getReservedAmount()).isEqualByComparingTo("0");
    }

    @Test
    void checkAndReserve_walletMissing_throwsInsufficientBalance() {
        when(walletBalanceRepository.findByTenantIdForUpdate(99L))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            walletBalanceService.checkAndReserve(99L, new BigDecimal("100.00")))
            .isInstanceOf(InsufficientBalanceException.class)
            .hasMessageContaining("99");
    }

    @Test
    void release_restoresBalanceAndDecrementsReserved() {
        wallet.setBalance(new BigDecimal("700.00"));
        wallet.setReservedAmount(new BigDecimal("300.00"));
        when(walletBalanceRepository.findByTenantIdForUpdate(TENANT_ID))
            .thenReturn(Optional.of(wallet));

        walletBalanceService.release(TENANT_ID, new BigDecimal("300.00"));

        assertThat(wallet.getBalance()).isEqualByComparingTo("1000.00");
        assertThat(wallet.getReservedAmount()).isEqualByComparingTo("0");
    }

    @Test
    void release_walletMissing_throwsIllegalState() {
        when(walletBalanceRepository.findByTenantIdForUpdate(99L))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            walletBalanceService.release(99L, new BigDecimal("100.00")))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reserveThenRelease_restoresInitialBalance() {
        // BAL-02 round-trip conservation
        when(walletBalanceRepository.findByTenantIdForUpdate(TENANT_ID))
            .thenReturn(Optional.of(wallet));

        walletBalanceService.checkAndReserve(TENANT_ID, new BigDecimal("250.00"));
        assertThat(wallet.getBalance()).isEqualByComparingTo("750.00");
        assertThat(wallet.getReservedAmount()).isEqualByComparingTo("250.00");

        walletBalanceService.release(TENANT_ID, new BigDecimal("250.00"));
        assertThat(wallet.getBalance()).isEqualByComparingTo("1000.00");
        assertThat(wallet.getReservedAmount()).isEqualByComparingTo("0");
    }
}
