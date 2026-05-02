package com.softropic.payam.disbursement.service;

import com.softropic.payam.config.TestConfig;
import com.softropic.payam.config.TestDataCleaner;
import com.softropic.payam.disbursement.contract.DisbursementStatus;
import com.softropic.payam.disbursement.repo.Disbursement;
import com.softropic.payam.disbursement.repo.DisbursementRepository;
import com.softropic.payam.disbursement.repo.MerchantWalletBalance;
import com.softropic.payam.disbursement.repo.MerchantWalletBalanceRepository;
import com.softropic.payam.tenant.contract.ApiKeyEnvironment;
import com.softropic.payam.tenant.service.TenantService;

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
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests proving SEC-04 expiry AND BAL-03 invariant (wallet balance unchanged
 * after expiry) under real Postgres.
 *
 * <p>Bypasses Quartz scheduling — invokes {@code executeInternal(null)} directly so the
 * test controls invocation timing without depending on the 60-second Quartz cadence.
 *
 * <p>spring.quartz.auto-startup=false ensures no background Quartz threads compete with
 * the direct invocation.
 */
@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true", "spring.quartz.auto-startup=false"})
@Import(TestConfig.class)
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
class DisbursementExpiryJobIT {

    @Autowired DisbursementExpiryJob expiryJob;
    @Autowired DisbursementRepository disbursementRepository;
    @Autowired MerchantWalletBalanceRepository walletRepo;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired TenantService tenantService;
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

        // Create tenant and seed wallet
        tenantId = tenantService.createTenant("expiry-it-" + UUID.randomUUID(), ApiKeyEnvironment.PROD)
                .tenant().getId();

        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.merchant_wallet_balance " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, " +
                "request_id, status, tenant_id, balance, reserved_amount, currency, version) " +
                "VALUES (?, 'TEST', NOW(), 'TEST', NOW(), gen_random_uuid()::text, 'ACTIVE', ?, ?, 0, 'XAF', 0)",
                System.currentTimeMillis(), tenantId, new BigDecimal("100000"));
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        testDataCleaner.wipeAll();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 1: SEC-04 happy path — aged PENDING_CONFIRMATION → EXPIRED + BAL-03
    // ────────────────────────────────────────────────────────────────────────

    @Test
    void expiryJob_agedPendingConfirmation_transitionsToExpired() {
        // Insert disbursement at PENDING_CONFIRMATION with createdDate = NOW - 16 minutes
        String disbId = UUID.randomUUID().toString();
        Instant aged = Instant.now().minus(Duration.ofMinutes(16));
        insertAgedDisbursement(disbId, tenantId, DisbursementStatus.PENDING_CONFIRMATION, aged,
                               new BigDecimal("50000"), new BigDecimal("50050"));

        // Reserve in wallet to mirror real submission
        reserveInWallet(tenantId, new BigDecimal("50050"));

        // Snapshot wallet BEFORE
        MerchantWalletBalance before = walletRepo.findByTenantId(tenantId).orElseThrow();
        BigDecimal balanceBefore = before.getBalance();
        BigDecimal reservedBefore = before.getReservedAmount();

        // Run the job directly
        expiryJob.executeInternal(null);

        // Disbursement is now EXPIRED
        Disbursement after = disbursementRepository.findByDisbursementId(disbId).orElseThrow();
        assertThat(after.getDisbursementStatus()).isEqualTo(DisbursementStatus.EXPIRED);

        // BAL-03 ASSERTION: wallet balance and reservedAmount UNCHANGED
        MerchantWalletBalance walletAfter = walletRepo.findByTenantId(tenantId).orElseThrow();
        assertThat(walletAfter.getBalance()).isEqualByComparingTo(balanceBefore);
        assertThat(walletAfter.getReservedAmount()).isEqualByComparingTo(reservedBefore);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 2: Fresh PENDING_CONFIRMATION (< 15 minutes) is NOT expired
    // ────────────────────────────────────────────────────────────────────────

    @Test
    void expiryJob_freshPendingConfirmation_isNotExpired() {
        // Insert with createdDate = NOW - 5 minutes (well below 15-minute threshold)
        String disbId = UUID.randomUUID().toString();
        insertAgedDisbursement(disbId, tenantId, DisbursementStatus.PENDING_CONFIRMATION,
                               Instant.now().minus(Duration.ofMinutes(5)),
                               new BigDecimal("50000"), new BigDecimal("50050"));

        expiryJob.executeInternal(null);

        Disbursement after = disbursementRepository.findByDisbursementId(disbId).orElseThrow();
        assertThat(after.getDisbursementStatus()).isEqualTo(DisbursementStatus.PENDING_CONFIRMATION);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 3: Aged PROCESSING disbursement is ignored (wrong status)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    void expiryJob_agedProcessingDisbursement_isNotExpired() {
        // Insert PROCESSING with aged date — must be ignored by the job
        String disbId = UUID.randomUUID().toString();
        insertAgedDisbursement(disbId, tenantId, DisbursementStatus.PROCESSING,
                               Instant.now().minus(Duration.ofMinutes(20)),
                               new BigDecimal("50000"), new BigDecimal("50050"));

        expiryJob.executeInternal(null);

        Disbursement after = disbursementRepository.findByDisbursementId(disbId).orElseThrow();
        assertThat(after.getDisbursementStatus()).isEqualTo(DisbursementStatus.PROCESSING);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 4: Empty result set — job completes without error
    // ────────────────────────────────────────────────────────────────────────

    @Test
    void expiryJob_emptyResultSet_completesWithoutError() {
        // No PENDING_CONFIRMATION rows exist — job should complete normally
        expiryJob.executeInternal(null);
        // No assertion needed beyond no exception thrown
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helper methods
    // ────────────────────────────────────────────────────────────────────────

    private void insertAgedDisbursement(String disbId, Long tenantId, DisbursementStatus status,
                                        Instant createdDate, BigDecimal amount, BigDecimal reserved) {
        // Compute the age in whole minutes relative to PostgreSQL NOW() so the comparison is
        // entirely within the DB engine — avoids JVM timezone / JDBC Timestamp binding skew
        // that occurs when comparing a Java Instant against a TIMESTAMP WITHOUT TIME ZONE column.
        long ageMinutes = Duration.between(createdDate, Instant.now()).toMinutes();
        if (ageMinutes < 1) ageMinutes = 1; // safety floor
        String intervalExpr = ageMinutes + " minutes";

        final long id = System.nanoTime();
        final String interval = intervalExpr;
        transactionTemplate.execute(st -> {
            jdbcTemplate.update(
                "INSERT INTO main.disbursement " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, " +
                "request_id, status, disbursement_id, tenant_id, recipient_msisdn, " +
                "amount, currency, reference, disbursement_status, provider, idempotency_key) " +
                "VALUES (?, 'TEST', NOW() - INTERVAL '" + interval +
                "', 'TEST', NOW() - INTERVAL '" + interval + "', gen_random_uuid()::text, " +
                "'ACTIVE', ?, ?, '+237671234567', ?, 'XAF', 'REF', ?, 'MTN', ?)",
                id,                                           // id
                disbId,                                       // disbursement_id
                tenantId,                                     // tenant_id
                amount,                                       // amount
                status.name(),                                // disbursement_status
                "IDEM-" + disbId.substring(0, 8));            // idempotency_key
            return null;
        });
    }

    private void reserveInWallet(Long tenantId, BigDecimal amount) {
        transactionTemplate.execute(st -> {
            jdbcTemplate.update(
                "UPDATE main.merchant_wallet_balance SET reserved_amount = reserved_amount + ? " +
                "WHERE tenant_id = ?", amount, tenantId);
            return null;
        });
    }
}
