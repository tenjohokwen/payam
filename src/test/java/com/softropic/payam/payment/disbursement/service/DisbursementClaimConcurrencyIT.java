package com.softropic.payam.payment.disbursement.service;

import com.softropic.payam.payment.core.contract.MobilePaymentProvider;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.config.TestDataCleaner;
import com.softropic.payam.payment.disbursement.contract.DisbursementOrchestratorError;
import com.softropic.payam.payment.disbursement.contract.DisbursementRefStatus;
import com.softropic.payam.payment.disbursement.contract.DisbursementRequest;
import com.softropic.payam.payment.disbursement.contract.DisbursementResponse;
import com.softropic.payam.payment.disbursement.repo.DisbursementTransactionRefRepository;
import com.softropic.payam.platform.tenant.contract.ApiKeyEnvironment;
import com.softropic.payam.platform.tenant.service.TenantService;
import com.softropic.payam.payment.ledger.contract.LedgerFlow;
import com.softropic.payam.payment.ledger.contract.TransactionStatus;
import com.softropic.payam.payment.ledger.repo.Transaction;
import com.softropic.payam.payment.ledger.repo.TransactionRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TXN-05 deadlock-free concurrency proof for {@link DisbursementOrchestrator#initiate}.
 *
 * <p>Three scenarios:
 * <ol>
 *   <li>{@link #overlappingTransactionsExactlyOneSucceeds} — 2 threads claim the same 3
 *       transactions concurrently. Exactly 1 wins (PENDING_CONFIRMATION), 1 loses
 *       (TRANSACTION_CLAIMED or INVALID_TRANSACTION). No deadlock.</li>
 *   <li>{@link #nonOverlappingTransactionsBothSucceed} — 2 threads with disjoint transaction sets.
 *       Both succeed. No deadlock, no false-positive blocking.</li>
 *   <li>{@link #ascendingOrderingPreventsDeadlock} — 2 threads submit the same 3 transactions in
 *       opposite order. The repository's {@code ORDER BY transactionId ASC} serializes lock
 *       acquisition identically; no deadlock cycle can form.</li>
 * </ol>
 *
 * <p>All tests use amounts &gt; 500,000 XAF (step-up threshold) so the orchestrator returns
 * {@code PENDING_CONFIRMATION} immediately after claim validation, without invoking any provider.
 * This removes WireMock from the concurrency path and keeps the test focused on the locking
 * invariant, not the provider-dispatch path.
 *
 * <p>Exception capture: every thread runner captures its exception in an {@link AtomicReference}.
 * Post-await assertions verify the reference is null (or for the deadlock scenario, that any
 * non-null exception message does NOT contain "deadlock"). Swallowing with {@code catch (Throwable
 * ignored)} would let deadlocks pass silently — that anti-pattern is forbidden here.
 */
@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
class DisbursementClaimConcurrencyIT {

    /** Each transaction backs 1/3 of the 600,003 XAF request — well above the 500,000 step-up. */
    private static final BigDecimal EACH_TXN_AMOUNT = new BigDecimal("200001");

    private static final String MTN_MSISDN = "+237671234567";
    private static final String IDEM_KEY_A = "IDEM-CONC-A-" + UUID.randomUUID();
    private static final String IDEM_KEY_B = "IDEM-CONC-B-" + UUID.randomUUID();
    private static final String IDEM_KEY_C = "IDEM-CONC-C-" + UUID.randomUUID();
    private static final String IDEM_KEY_D = "IDEM-CONC-D-" + UUID.randomUUID();

    @Autowired DisbursementOrchestrator orchestrator;
    @Autowired TenantService tenantService;
    @Autowired TransactionRepository transactionRepository;
    @Autowired DisbursementTransactionRefRepository transactionRefRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TestDataCleaner testDataCleaner;

    private Long tenantId;

    @BeforeEach
    void setUp() {
        testDataCleaner.wipeAll();

        // Seed JWT secret required by SecurityAdviceFilter
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute(
                "INSERT INTO main.sec (id, created_by, created_date, last_modified_by, last_modified_date, " +
                "request_id, session_id, status, bus_id, value, version) " +
                "VALUES ('659287191260154475','SYSTEM_ACCOUNT','2024-12-24 06:51:55.357352'," +
                "'SYSTEM_ACCOUNT','2024-12-24 06:51:55.357352'," +
                "'bed78f34-3e09-4fa8-81db-32326a528cca', null, 'ACTIVE', 'jot'," +
                "'loiI8oT2C1tWecrNXPDjN8fveYEU8rD6nb1k1NbVy92rwdd4/KO+aHhXh3A5zjsT5eSFL/xI+9Rqyj4RI6QCiFywn5nZLIwHGPNEY0F9lnDnGGmVjv/9rO5fgGt83+cxNDyGoCePaVEpBd7xHxyDdfpAoLxQs8mhKGqcEsh09Q+26qEiEm/a9bgDSbSQ0sX00VHBLd35OLmvN+ydjEluYxBTa6KzGb2CQ6Ttg4ZaELmbZOWpEjQ1Z7BbbYiXmWyaY+2HnkyhONoGbUpvVKl1c4e9IlQzeUYkekbUbADIm2LNK9Nhfv5/L5esvFrdVOUcUpLk/y8UT9f5xOMLFJ4Ct6s0eTKvNqYkSz2DFRI8Ip4p/ns6gA4V/1MUf9GeqPUWLiOa28Vw15+R8ycUMqb8NZHOP1oj9RunhSwA7EY84bZL3+yePc3n1b8ne8xzaYVEdK1WBu3J6s2AoBaOL/JLWfu8MuxXI+ub', 'v1') " +
                "ON CONFLICT DO NOTHING");
            return null;
        });

        tenantId = tenantService.createTenant("dsb-conc-it-" + UUID.randomUUID(), ApiKeyEnvironment.PROD)
                .tenant().getId();
    }

    @AfterEach
    void tearDown() {
        testDataCleaner.wipeAll();
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────────────

    /**
     * Seeds {@code count} Transaction rows with txStatus=SUCCESS, flow=COLLECTION for this
     * tenant. Returns the list of seeded transactionIds.
     */
    private List<String> seedSuccessfulCollectionTransactions(int count, BigDecimal eachAmount) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> {
            String txId = UUID.randomUUID().toString();
            Transaction tx = Transaction.builder()
                    .transactionId(txId)
                    .traceId(txId)
                    .tenantId(tenantId)
                    .provider(MobilePaymentProvider.MTN)
                    .txStatus(TransactionStatus.SUCCESS)
                    .flow(LedgerFlow.COLLECTION)
                    .amount(eachAmount)
                    .currency("XAF")
                    .feeAmount(BigDecimal.ZERO)
                    .build();
            transactionTemplate.execute(s -> {
                transactionRepository.save(tx);
                return null;
            });
            return txId;
        }).toList();
    }

    /**
     * Builds a DisbursementRequest for the given transactionIds. Uses a step-up amount
     * (3 × EACH_TXN_AMOUNT = 600,003 XAF > 500,000 threshold) to avoid provider dispatch —
     * the orchestrator returns PENDING_CONFIRMATION at Step 8 without calling MTN/Orange.
     */
    private DisbursementRequest reqFor(List<String> txnIds, BigDecimal totalAmount, String idemKey) {
        return new DisbursementRequest(
                MTN_MSISDN, totalAmount, "XAF", "REF-" + idemKey,
                "concurrency test", null,
                txnIds, idemKey);
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 1: overlapping — exactly one thread succeeds
    // ────────────────────────────────────────────────────────────────────────────────

    /**
     * TXN-05 proof — overlapping transaction sets.
     *
     * <p>Two threads submit disbursement requests for the SAME 3 transactions. The partial
     * unique index {@code uq_dtr_txn_active_claim} (created by V31) and the app-layer probe
     * ensure exactly 1 request succeeds (PENDING_CONFIRMATION) and 1 loses with
     * TRANSACTION_CLAIMED or INVALID_TRANSACTION. No deadlock occurs because the repository
     * acquires pessimistic write locks in ascending {@code transactionId} order.
     */
    @Test
    void overlappingTransactionsExactlyOneSucceeds() throws Exception {
        // 3 shared transactions, total 600,003 XAF (step-up → PENDING_CONFIRMATION, no provider call)
        List<String> shared = seedSuccessfulCollectionTransactions(3, EACH_TXN_AMOUNT);
        BigDecimal total = EACH_TXN_AMOUNT.multiply(new BigDecimal("3"));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<DisbursementResponse> rA = new AtomicReference<>();
        AtomicReference<DisbursementResponse> rB = new AtomicReference<>();
        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        CompletableFuture<Void> fA = CompletableFuture.runAsync(() -> {
            try {
                start.await();
                rA.set(orchestrator.initiate(tenantId, reqFor(shared, total, IDEM_KEY_A)));
            } catch (Throwable t) {
                errA.set(t);
            }
        }, pool);
        CompletableFuture<Void> fB = CompletableFuture.runAsync(() -> {
            try {
                start.await();
                rB.set(orchestrator.initiate(tenantId, reqFor(shared, total, IDEM_KEY_B)));
            } catch (Throwable t) {
                errB.set(t);
            }
        }, pool);

        start.countDown();
        CompletableFuture.allOf(fA, fB).get(15, TimeUnit.SECONDS);
        pool.shutdown();

        // Neither thread threw an unexpected exception (including deadlock)
        assertThat(errA.get()).as("thread A: no unexpected exception").isNull();
        assertThat(errB.get()).as("thread B: no unexpected exception").isNull();

        // Exactly 1 winner (errorCode == null), exactly 1 loser (errorCode != null)
        long winners = java.util.stream.Stream.of(rA.get(), rB.get())
                .filter(r -> r != null && r.errorCode() == null).count();
        long losers = java.util.stream.Stream.of(rA.get(), rB.get())
                .filter(r -> r != null && r.errorCode() != null).count();
        assertThat(winners).as("exactly 1 winner").isEqualTo(1);
        assertThat(losers).as("exactly 1 loser").isEqualTo(1);

        // Loser's error code is one of the expected race-loss codes
        String loserCode = (rA.get() != null && rA.get().errorCode() != null)
                ? rA.get().errorCode() : rB.get().errorCode();
        assertThat(loserCode).as("loser error code is TRANSACTION_CLAIMED or INVALID_TRANSACTION")
                .isIn(
                        DisbursementOrchestratorError.TRANSACTION_CLAIMED.getErrorCode(),
                        DisbursementOrchestratorError.INVALID_TRANSACTION.getErrorCode());

        // Only the winner's 3 PENDING refs persisted
        long pendingRefs = transactionRefRepository.findAll().stream()
                .filter(r -> r.getRefStatus() == DisbursementRefStatus.PENDING)
                .count();
        assertThat(pendingRefs).as("3 PENDING refs from winner, none from loser").isEqualTo(3);
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 2: non-overlapping — both threads succeed independently
    // ────────────────────────────────────────────────────────────────────────────────

    /**
     * TXN-05 proof — non-overlapping transaction sets.
     *
     * <p>Two threads with disjoint transaction sets BOTH complete successfully with no
     * false-positive blocking or deadlock. Exception capture (not swallow) ensures any
     * unexpected failure — including a deadlock — fails the test loudly.
     */
    @Test
    void nonOverlappingTransactionsBothSucceed() throws Exception {
        List<String> setA = seedSuccessfulCollectionTransactions(3, EACH_TXN_AMOUNT);
        List<String> setB = seedSuccessfulCollectionTransactions(3, EACH_TXN_AMOUNT);
        BigDecimal total = EACH_TXN_AMOUNT.multiply(new BigDecimal("3"));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<DisbursementResponse> rA = new AtomicReference<>();
        AtomicReference<DisbursementResponse> rB = new AtomicReference<>();
        // CAPTURE thread exceptions — do NOT swallow. A deadlock or other unexpected failure
        // must cause this test to FAIL, not silently pass.
        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        CompletableFuture<Void> fA = CompletableFuture.runAsync(() -> {
            try {
                start.await();
                rA.set(orchestrator.initiate(tenantId, reqFor(setA, total, IDEM_KEY_C)));
            } catch (Throwable t) {
                errA.set(t);
            }
        }, pool);
        CompletableFuture<Void> fB = CompletableFuture.runAsync(() -> {
            try {
                start.await();
                rB.set(orchestrator.initiate(tenantId, reqFor(setB, total, IDEM_KEY_D)));
            } catch (Throwable t) {
                errB.set(t);
            }
        }, pool);

        start.countDown();
        CompletableFuture.allOf(fA, fB).get(15, TimeUnit.SECONDS);
        pool.shutdown();

        // Post-test assertion — no thread threw any exception (catches deadlocks and other
        // unexpected failures in the non-overlapping case)
        assertThat(errA.get()).as("thread A no exception in non-overlap path").isNull();
        assertThat(errB.get()).as("thread B no exception in non-overlap path").isNull();

        assertThat(rA.get()).as("thread A response not null").isNotNull();
        assertThat(rB.get()).as("thread B response not null").isNotNull();
        assertThat(rA.get().errorCode()).as("set A success (no error code)").isNull();
        assertThat(rB.get().errorCode()).as("set B success (no error code)").isNull();

        assertThat(transactionRefRepository.count())
                .as("6 PENDING refs total — 3 per winning disbursement").isEqualTo(6);
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Test 3: reverse-order input — ascending lock ordering prevents deadlock
    // ────────────────────────────────────────────────────────────────────────────────

    /**
     * TXN-05 proof — ascending lock ordering prevents deadlock on reverse-order input.
     *
     * <p>Thread A submits transactions in order [t1, t2, t3]; thread B submits in [t3, t2, t1].
     * Without the {@code ORDER BY transactionId ASC} inside
     * {@link com.softropic.payam.payment.ledger.repo.TransactionRepository#findByTransactionIdsForUpdate},
     * thread A would lock t1 first while thread B locks t3 first, then each waits for the
     * other's held lock — a classic deadlock cycle. The repository's ascending sort ensures
     * both threads acquire locks in the same sequence, eliminating the cycle.
     *
     * <p>NOTE on Java version: {@code List.reversed()} is a Java 21 method. This project targets
     * Java 17 (verified in {@code pom.xml}: {@code <java.version>17</java.version>}). A mutable
     * copy + {@link Collections#reverse} is used instead — works on Java 8+.
     */
    @Test
    void ascendingOrderingPreventsDeadlock() throws Exception {
        // Same 3 shared transactions claimed by both threads
        List<String> shared = seedSuccessfulCollectionTransactions(3, EACH_TXN_AMOUNT);
        BigDecimal total = EACH_TXN_AMOUNT.multiply(new BigDecimal("3"));

        // NOTE: List.reversed() is Java 21+. Project is on Java 17 — use mutable copy +
        // Collections.reverse() for backward compatibility (works on every Java >= 8).
        List<String> reversedMutable = new ArrayList<>(shared);
        Collections.reverse(reversedMutable);
        final List<String> reversed = Collections.unmodifiableList(reversedMutable);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        // Thread A: submits in natural order [t1, t2, t3]
        // Thread B: submits in reversed order [t3, t2, t1]
        // The repository re-sorts to ASC inside the SELECT FOR UPDATE, so both threads
        // acquire locks in the same order regardless of the request order.
        CompletableFuture<Void> fA = CompletableFuture.runAsync(() -> {
            try {
                start.await();
                orchestrator.initiate(tenantId, reqFor(shared, total, IDEM_KEY_A));
            } catch (Throwable t) {
                errA.set(t);
            }
        }, pool);
        CompletableFuture<Void> fB = CompletableFuture.runAsync(() -> {
            try {
                start.await();
                orchestrator.initiate(tenantId, reqFor(reversed, total, IDEM_KEY_B));
            } catch (Throwable t) {
                errB.set(t);
            }
        }, pool);

        start.countDown();
        CompletableFuture.allOf(fA, fB).get(15, TimeUnit.SECONDS);
        pool.shutdown();

        // Neither thread threw a PSQLException with "deadlock detected" in the message.
        // The assertions accept null (no exception) or any exception whose message does NOT
        // contain "deadlock" (a TRANSACTION_CLAIMED or INVALID_TRANSACTION exception is fine
        // for the loser thread).
        assertThat(errA.get()).as("thread A: no deadlock").satisfiesAnyOf(
                e -> assertThat(e).isNull(),
                e -> assertThat(e.getMessage() == null ? "" : e.getMessage().toLowerCase())
                        .doesNotContain("deadlock"));
        assertThat(errB.get()).as("thread B: no deadlock").satisfiesAnyOf(
                e -> assertThat(e).isNull(),
                e -> assertThat(e.getMessage() == null ? "" : e.getMessage().toLowerCase())
                        .doesNotContain("deadlock"));
    }
}
