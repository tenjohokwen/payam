package com.softropic.payam.disbursement.service;

import com.softropic.payam.config.TestConfig;
import com.softropic.payam.config.TestDataCleaner;
import com.softropic.payam.disbursement.contract.exception.InsufficientBalanceException;
import com.softropic.payam.disbursement.repo.MerchantWalletBalance;
import com.softropic.payam.disbursement.repo.MerchantWalletBalanceRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BAL-01 concurrency proof: 20 threads attempt to reserve more than the wallet holds;
 * exactly 1 succeeds, 19 receive InsufficientBalanceException. No overdraft (balance &gt;= 0).
 *
 * BAL-02 round-trip: reserve then release restores the exact starting balance.
 */
@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
class WalletBalanceConcurrencyIT {

    private static final Long TENANT_ID = 88881L;
    private static final BigDecimal PER_REQUEST_AMOUNT = new BigDecimal("1000.00");

    @Autowired private WalletBalanceService walletBalanceService;
    @Autowired private MerchantWalletBalanceRepository walletBalanceRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private TestDataCleaner testDataCleaner;

    @BeforeEach
    void seedWallet() {
        testDataCleaner.wipeAll();
        // Seed one wallet with balance = exactly one PER_REQUEST_AMOUNT (so only 1 reserve can succeed).
        transactionTemplate.execute(status -> {
            MerchantWalletBalance wallet = MerchantWalletBalance.builder()
                .tenantId(TENANT_ID)
                .balance(PER_REQUEST_AMOUNT)
                .reservedAmount(BigDecimal.ZERO)
                .currency("XAF")
                .version(0L)
                .build();
            walletBalanceRepository.save(wallet);
            return null;
        });
    }

    @AfterEach
    void cleanup() {
        testDataCleaner.wipeAll();
    }

    @Test
    void concurrentReserve_exactlyOneSucceeds() throws Exception {
        final int THREADS = 20;
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger insufficientFailures = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                try { barrier.await(10, TimeUnit.SECONDS); } catch (Exception ignored) {}
                walletBalanceService.checkAndReserve(TENANT_ID, PER_REQUEST_AMOUNT);
                return null;
            }));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS))
            .as("All 20 threads must complete within 60s")
            .isTrue();

        for (Future<?> f : futures) {
            try {
                f.get();
                successes.incrementAndGet();
            } catch (ExecutionException ex) {
                Throwable root = ex.getCause();
                while (root != null && !(root instanceof InsufficientBalanceException)) {
                    root = root.getCause();
                }
                if (root instanceof InsufficientBalanceException) {
                    insufficientFailures.incrementAndGet();
                } else {
                    throw ex; // unexpected — fail loudly
                }
            }
        }

        assertThat(successes.get())
            .as("Exactly 1 of %d concurrent reserve attempts must succeed", THREADS)
            .isEqualTo(1);
        assertThat(insufficientFailures.get())
            .as("Exactly %d of %d concurrent reserve attempts must throw InsufficientBalanceException", THREADS - 1, THREADS)
            .isEqualTo(THREADS - 1);

        // DB state: wallet balance is 0 (no overdraft), reserved = PER_REQUEST_AMOUNT (exactly 1 reservation landed)
        MerchantWalletBalance finalWallet = walletBalanceRepository.findByTenantId(TENANT_ID).orElseThrow();
        assertThat(finalWallet.getBalance())
            .as("Wallet balance must not be negative (no overdraft)")
            .isEqualByComparingTo("0");
        assertThat(finalWallet.getReservedAmount())
            .as("Exactly one reservation landed")
            .isEqualByComparingTo(PER_REQUEST_AMOUNT);
    }

    @Test
    void reserveThenRelease_restoresWalletBalance() {
        // BAL-02: reserve then release conserves the exact balance
        walletBalanceService.checkAndReserve(TENANT_ID, new BigDecimal("400.00"));
        MerchantWalletBalance afterReserve = walletBalanceRepository.findByTenantId(TENANT_ID).orElseThrow();
        assertThat(afterReserve.getBalance()).isEqualByComparingTo("600.00");
        assertThat(afterReserve.getReservedAmount()).isEqualByComparingTo("400.00");

        walletBalanceService.release(TENANT_ID, new BigDecimal("400.00"));
        MerchantWalletBalance afterRelease = walletBalanceRepository.findByTenantId(TENANT_ID).orElseThrow();
        assertThat(afterRelease.getBalance())
            .as("BAL-02: balance must equal starting value after reserve+release round-trip")
            .isEqualByComparingTo(PER_REQUEST_AMOUNT);
        assertThat(afterRelease.getReservedAmount()).isEqualByComparingTo("0");
    }

    @Test
    void insufficientBalance_throwsAndNoDatabaseMutation() {
        assertThat(walletBalanceRepository.findByTenantId(TENANT_ID).orElseThrow().getBalance())
            .isEqualByComparingTo("1000.00");

        try {
            walletBalanceService.checkAndReserve(TENANT_ID, new BigDecimal("5000.00"));
            throw new AssertionError("Expected InsufficientBalanceException");
        } catch (InsufficientBalanceException expected) {
            // good
        }

        MerchantWalletBalance wallet = walletBalanceRepository.findByTenantId(TENANT_ID).orElseThrow();
        assertThat(wallet.getBalance())
            .as("On insufficient balance, DB balance must be unchanged")
            .isEqualByComparingTo("1000.00");
        assertThat(wallet.getReservedAmount()).isEqualByComparingTo("0");
    }
}
