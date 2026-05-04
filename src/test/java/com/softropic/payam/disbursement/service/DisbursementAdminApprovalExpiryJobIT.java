package com.softropic.payam.disbursement.service;

import com.softropic.payam.config.TestConfig;
import com.softropic.payam.config.TestDataCleaner;
import com.softropic.payam.disbursement.contract.DisbursementRefStatus;
import com.softropic.payam.disbursement.contract.DisbursementStatus;
import com.softropic.payam.disbursement.repo.Disbursement;
import com.softropic.payam.disbursement.repo.DisbursementRepository;
import com.softropic.payam.disbursement.repo.DisbursementTransactionRef;
import com.softropic.payam.disbursement.repo.DisbursementTransactionRefRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests proving CLAIM-04 (claims released on admin-approval expiry)
 * and ADMIN-03 (admin-approval auto-expires after configured hours) under real Postgres.
 *
 * <p>Pattern mirrors DisbursementExpiryJobIT:
 * - spring.quartz.auto-startup=false so Quartz does not race the direct invocation
 * - executeInternal(null) called directly to control timing
 * - JdbcTemplate used to backdate created_date so the candidate is in scope
 *
 * <p>The test overrides {@code payam.disbursement.admin-approval-timeout-hours=1} so a
 * row backdated by 120 minutes is within the expiry window without needing to wait.
 *
 * <p>No {@code MerchantWalletBalance} usage — wallet model retired in V31 (SCHEMA-03).
 */
@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true", "spring.quartz.auto-startup=false"})
@Import(TestConfig.class)
@TestPropertySource(properties = {
        "spring.cloud.compatibility-verifier.enabled=false",
        "payam.disbursement.admin-approval-timeout-hours=1"
})
class DisbursementAdminApprovalExpiryJobIT {

    @Autowired DisbursementAdminApprovalExpiryJob expiryJob;
    @Autowired DisbursementRepository disbursementRepository;
    @Autowired DisbursementTransactionRefRepository refRepository;
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

        tenantId = tenantService.createTenant("admin-expiry-it-" + UUID.randomUUID(), ApiKeyEnvironment.PROD)
                .tenant().getId();
    }

    @AfterEach
    void tearDown() {
        testDataCleaner.wipeAll();
    }

    // ── Test 1: CLAIM-04 + ADMIN-03 happy path ───────────────────────────────────

    @Test
    void expiresAgedAdminApproval_andReleasesAllClaims() {
        // 1. Seed disbursement with status PENDING_ADMIN_APPROVAL, backdate by 120 minutes
        //    (well beyond the 1-hour test threshold)
        String dsbId = UUID.randomUUID().toString();
        long dsbPk = seedDisbursement(dsbId, DisbursementStatus.PENDING_ADMIN_APPROVAL,
                BigDecimal.valueOf(6_000_000));
        backdateDisbursement(dsbPk, 120);

        // 2. Seed 3 PENDING claim rows (UUID is 36 chars — matches VARCHAR(36) column)
        seedClaim(dsbPk, UUID.randomUUID().toString(), DisbursementRefStatus.PENDING);
        seedClaim(dsbPk, UUID.randomUUID().toString(), DisbursementRefStatus.PENDING);
        seedClaim(dsbPk, UUID.randomUUID().toString(), DisbursementRefStatus.PENDING);

        // 3. Run job directly — no Quartz scheduling needed
        expiryJob.executeInternal(null);

        // 4. Disbursement must be EXPIRED
        Disbursement after = disbursementRepository.findByDisbursementId(dsbId).orElseThrow();
        assertThat(after.getDisbursementStatus()).isEqualTo(DisbursementStatus.EXPIRED);

        // 5. All 3 claims must be RELEASED
        List<DisbursementTransactionRef> refs = refRepository.findAll().stream()
                .filter(r -> dsbPk == r.getDisbursementId())
                .toList();
        assertThat(refs).hasSize(3);
        assertThat(refs).allMatch(r -> r.getRefStatus() == DisbursementRefStatus.RELEASED);
    }

    // ── Test 2: Row younger than threshold is left untouched ─────────────────────

    @Test
    void doesNotExpireYoungerThanThreshold_leavesClaimsPending() {
        // Seed disbursement at NOW() — within the 1-hour window, NOT expired
        String dsbId = UUID.randomUUID().toString();
        long dsbPk = seedDisbursement(dsbId, DisbursementStatus.PENDING_ADMIN_APPROVAL,
                BigDecimal.valueOf(6_000_000));
        // No backdating — created_date is NOW()

        seedClaim(dsbPk, UUID.randomUUID().toString(), DisbursementRefStatus.PENDING);
        seedClaim(dsbPk, UUID.randomUUID().toString(), DisbursementRefStatus.PENDING);

        expiryJob.executeInternal(null);

        // Status must remain PENDING_ADMIN_APPROVAL
        Disbursement after = disbursementRepository.findByDisbursementId(dsbId).orElseThrow();
        assertThat(after.getDisbursementStatus()).isEqualTo(DisbursementStatus.PENDING_ADMIN_APPROVAL);

        // Claims must remain PENDING
        List<DisbursementTransactionRef> refs = refRepository.findAll().stream()
                .filter(r -> dsbPk == r.getDisbursementId())
                .toList();
        assertThat(refs).allMatch(r -> r.getRefStatus() == DisbursementRefStatus.PENDING);
    }

    // ── Test 3: @Modifying scoping — already-RELEASED claims are not re-touched ───

    @Test
    void onExpiry_doesNotTouchAlreadyReleasedClaims() {
        // Seed disbursement aged beyond threshold
        String dsbId = UUID.randomUUID().toString();
        long dsbPk = seedDisbursement(dsbId, DisbursementStatus.PENDING_ADMIN_APPROVAL,
                BigDecimal.valueOf(6_000_000));
        backdateDisbursement(dsbPk, 120);

        // Mixed claim states — 3 PENDING (will be transitioned) + 2 already RELEASED
        // (e.g. audit-trail leftover from an earlier retry)
        seedClaim(dsbPk, UUID.randomUUID().toString(), DisbursementRefStatus.PENDING);
        seedClaim(dsbPk, UUID.randomUUID().toString(), DisbursementRefStatus.PENDING);
        seedClaim(dsbPk, UUID.randomUUID().toString(), DisbursementRefStatus.PENDING);
        seedClaim(dsbPk, UUID.randomUUID().toString(), DisbursementRefStatus.RELEASED);
        seedClaim(dsbPk, UUID.randomUUID().toString(), DisbursementRefStatus.RELEASED);

        expiryJob.executeInternal(null);

        // Disbursement is EXPIRED
        Disbursement after = disbursementRepository.findByDisbursementId(dsbId).orElseThrow();
        assertThat(after.getDisbursementStatus()).isEqualTo(DisbursementStatus.EXPIRED);

        // All 5 rows are now RELEASED (3 transitioned + 2 were already RELEASED)
        List<DisbursementTransactionRef> refs = refRepository.findAll().stream()
                .filter(r -> dsbPk == r.getDisbursementId())
                .toList();
        assertThat(refs).hasSize(5);
        assertThat(refs).allMatch(r -> r.getRefStatus() == DisbursementRefStatus.RELEASED);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /**
     * Insert a disbursement row with the given status and return its BIGINT PK.
     *
     * <p>Uses {@code RETURNING id} to retrieve the actual PK inserted — eliminates any
     * nanoTime capture drift between the INSERT and the returned value.
     */
    private long seedDisbursement(String dsbId, DisbursementStatus status, BigDecimal amount) {
        final long id = Math.abs(System.nanoTime());
        final long[] inserted = {0L};
        transactionTemplate.execute(st -> {
            Long pk = jdbcTemplate.queryForObject(
                "INSERT INTO main.disbursement " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, " +
                "request_id, status, disbursement_id, tenant_id, recipient_msisdn, " +
                "amount, currency, reference, disbursement_status, provider, idempotency_key) " +
                "VALUES (?, 'TEST', NOW(), 'TEST', NOW(), gen_random_uuid()::text, " +
                "'ACTIVE', ?, ?, '+237671234567', ?, 'XAF', 'REF', ?, 'MTN', ?) " +
                "RETURNING id",
                Long.class,
                id,
                dsbId,
                tenantId,
                amount,
                status.name(),
                "IDEM-" + dsbId.substring(0, 8));
            inserted[0] = pk != null ? pk : id;
            return null;
        });
        return inserted[0];
    }

    /**
     * Backdate the created_date of a disbursement row by {@code minutes} minutes using DB NOW().
     * Using PostgreSQL interval arithmetic keeps the comparison entirely within the DB engine,
     * avoiding JVM timezone / JDBC Timestamp binding skew.
     *
     * <p>Wrapped in transactionTemplate.execute so the UPDATE is committed before the
     * job's findExpiredCandidates SELECT runs on a different connection.
     */
    private void backdateDisbursement(long dsbPk, long minutes) {
        transactionTemplate.execute(st -> {
            jdbcTemplate.update(
                "UPDATE main.disbursement SET created_date = NOW() - CAST(? || ' minutes' AS INTERVAL) WHERE id = ?",
                minutes, dsbPk);
            return null;
        });
    }

    /**
     * Insert a DisbursementTransactionRef row linked to the given disbursement BIGINT PK.
     * The unique constraint {@code uq_dtr_txn_active_claim} applies only to PENDING/CLAIMED
     * rows — RELEASED rows can co-exist for the same transaction_id.
     */
    private void seedClaim(long dsbPk, String txId, DisbursementRefStatus status) {
        transactionTemplate.execute(st -> {
            jdbcTemplate.update(
                "INSERT INTO main.disbursement_transaction_ref " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, " +
                "request_id, status, disbursement_id, transaction_id, ref_status) " +
                "VALUES (?, 'TEST', NOW(), 'TEST', NOW(), gen_random_uuid()::text, " +
                "'ACTIVE', ?, ?, ?)",
                Math.abs(System.nanoTime()),
                dsbPk,
                txId,
                status.name());
            return null;
        });
    }
}
