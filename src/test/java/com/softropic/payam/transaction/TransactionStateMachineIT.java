package com.softropic.payam.transaction;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.tenant.contract.ApiKeyEnvironment;
import com.softropic.payam.tenant.service.TenantService;
import com.softropic.payam.transaction.contract.TransactionStatus;
import com.softropic.payam.transaction.contract.exception.IllegalStateTransitionException;
import com.softropic.payam.transaction.repo.Transaction;
import com.softropic.payam.transaction.repo.TransactionRepository;
import com.softropic.payam.transaction.service.TransactionService;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"ledger.database.spy=true", "enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
class TransactionStateMachineIT {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long tenantId;

    @BeforeEach
    void setUp() {
        // Load JWT secret required by SecurityAdviceFilter.addSecretToThread().
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

        // Create a tenant to satisfy the FK constraint on transaction.tenant_id
        TenantService.TenantCreationResult tenantResult =
            tenantService.createTenant("TX State Machine Corp", ApiKeyEnvironment.PROD);
        tenantId = tenantResult.tenant().getId();
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("delete from main.transaction");
            jdbcTemplate.execute("delete from main.tenant_api_key");
            jdbcTemplate.execute("delete from main.tenant");
            jdbcTemplate.execute("delete from main.sec");
            return null;
        });
    }

    // -------------------------------------------------------------------------
    // Test 1: initiate creates a persisted INITIATED transaction
    // -------------------------------------------------------------------------
    @Test
    void initiate_createsInitiatedTransaction() {
        Transaction tx = transactionService.initiate(
            tenantId, MobilePaymentProvider.MTN,
            new BigDecimal("100.00"), "XAF", "REF-001");

        assertThat(tx).isNotNull();
        assertThat(tx.getTxStatus()).isEqualTo(TransactionStatus.INITIATED);
        assertThat(tx.getTransactionId()).isNotNull();
        assertThat(tx.getTransactionId()).matches(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        assertThat(tx.getTraceId()).isNotNull();
        assertThat(tx.getTenantId()).isEqualTo(tenantId);
        assertThat(tx.getId()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Test 2: valid transition INITIATED -> AUTH_PENDING succeeds
    // -------------------------------------------------------------------------
    @Test
    void applyTransition_validTransition_succeeds() {
        // Create transaction directly to isolate state machine behavior
        Transaction persisted = transactionTemplate.execute(status -> {
            Transaction tx = Transaction.builder()
                .transactionId(java.util.UUID.randomUUID().toString())
                .traceId("test-trace-id")
                .tenantId(tenantId)
                .provider(MobilePaymentProvider.MTN)
                .amount(new BigDecimal("50.00"))
                .currency("XAF")
                .txStatus(TransactionStatus.INITIATED)
                .build();
            return transactionRepository.save(tx);
        });

        transactionTemplate.execute(status -> {
            Transaction tx = transactionRepository.findById(persisted.getId()).orElseThrow();
            tx.applyTransition(TransactionStatus.AUTH_PENDING);
            transactionRepository.save(tx);
            return null;
        });

        Transaction updated = transactionRepository.findById(persisted.getId()).orElseThrow();
        assertThat(updated.getTxStatus()).isEqualTo(TransactionStatus.AUTH_PENDING);
    }

    // -------------------------------------------------------------------------
    // Test 3: invalid transition INITIATED -> SUCCESS throws
    // -------------------------------------------------------------------------
    @Test
    void applyTransition_invalidTransition_throwsException() {
        Transaction persisted = transactionTemplate.execute(status -> {
            Transaction tx = Transaction.builder()
                .transactionId(java.util.UUID.randomUUID().toString())
                .traceId("test-trace-id-2")
                .tenantId(tenantId)
                .provider(MobilePaymentProvider.ORANGE)
                .amount(new BigDecimal("75.00"))
                .currency("XAF")
                .txStatus(TransactionStatus.INITIATED)
                .build();
            return transactionRepository.save(tx);
        });

        Transaction tx = transactionRepository.findById(persisted.getId()).orElseThrow();
        assertThatThrownBy(() -> tx.applyTransition(TransactionStatus.SUCCESS))
            .isInstanceOf(IllegalStateTransitionException.class);
    }

    // -------------------------------------------------------------------------
    // Test 4: terminal state SUCCESS cannot transition to FAILED
    // -------------------------------------------------------------------------
    @Test
    void terminalState_cannotTransition() {
        // Advance through full valid chain to SUCCESS
        Transaction persisted = transactionTemplate.execute(status -> {
            Transaction tx = Transaction.builder()
                .transactionId(java.util.UUID.randomUUID().toString())
                .traceId("test-trace-id-3")
                .tenantId(tenantId)
                .provider(MobilePaymentProvider.MTN)
                .amount(new BigDecimal("200.00"))
                .currency("XAF")
                .txStatus(TransactionStatus.INITIATED)
                .build();
            return transactionRepository.save(tx);
        });

        // Apply valid chain: INITIATED -> AUTH_PENDING -> AUTHORIZED -> PROCESSING -> SUCCESS
        transactionTemplate.execute(status -> {
            Transaction tx = transactionRepository.findById(persisted.getId()).orElseThrow();
            tx.applyTransition(TransactionStatus.AUTH_PENDING);
            tx.applyTransition(TransactionStatus.AUTHORIZED);
            tx.applyTransition(TransactionStatus.PROCESSING);
            tx.applyTransition(TransactionStatus.SUCCESS);
            transactionRepository.save(tx);
            return null;
        });

        Transaction terminal = transactionRepository.findById(persisted.getId()).orElseThrow();
        assertThat(terminal.getTxStatus()).isEqualTo(TransactionStatus.SUCCESS);

        // SUCCESS is terminal — cannot transition to anything
        assertThatThrownBy(() -> terminal.applyTransition(TransactionStatus.FAILED))
            .isInstanceOf(IllegalStateTransitionException.class);
    }
}
