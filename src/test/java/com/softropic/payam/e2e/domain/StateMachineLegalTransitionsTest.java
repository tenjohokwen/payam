package com.softropic.payam.e2e.domain;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.e2e.AbstractPayamE2ETest;
import com.softropic.payam.e2e.builder.TenantBuilder;
import com.softropic.payam.platform.tenant.service.TenantService;
import com.softropic.payam.transaction.contract.TransactionStatus;
import com.softropic.payam.transaction.contract.exception.IllegalStateTransitionException;
import com.softropic.payam.transaction.repo.Transaction;
import com.softropic.payam.transaction.repo.TransactionRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import com.softropic.payam.platform.tenant.repo.TenantRepository;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * INV-05-TEST: State machine legal and illegal transitions invariant.
 *
 * Proves that:
 * 1. All illegal transitions throw IllegalStateTransitionException and leave the DB row unchanged.
 * 2. All legal transitions succeed in the documented order.
 *
 * Legal chains:
 *   Long:  INITIATED -> AUTH_PENDING -> AUTHORIZED -> PROCESSING -> SUCCESS
 *   Short: INITIATED -> PROCESSING -> SUCCESS  (used by synchronous provider path)
 * PROCESSING can also reach FAILED and REVERSED.
 * Any state can reach FAILED except terminals.
 */
public class StateMachineLegalTransitionsTest extends AbstractPayamE2ETest {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private com.softropic.payam.platform.tenant.repo.TenantRepository tenantRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    // -------------------------------------------------------------------------
    // Illegal transitions — all must throw and leave DB unchanged
    // -------------------------------------------------------------------------

    static Stream<Arguments> illegalTransitions() {
        return Stream.of(
            // From INITIATED: not allowed to SUCCESS, REVERSED, AUTHORIZED
            Arguments.of(TransactionStatus.INITIATED,    TransactionStatus.SUCCESS),
            Arguments.of(TransactionStatus.INITIATED,    TransactionStatus.REVERSED),
            Arguments.of(TransactionStatus.INITIATED,    TransactionStatus.AUTHORIZED),

            // From AUTH_PENDING: only AUTHORIZED and FAILED are legal
            Arguments.of(TransactionStatus.AUTH_PENDING, TransactionStatus.SUCCESS),
            Arguments.of(TransactionStatus.AUTH_PENDING, TransactionStatus.REVERSED),
            Arguments.of(TransactionStatus.AUTH_PENDING, TransactionStatus.INITIATED),
            Arguments.of(TransactionStatus.AUTH_PENDING, TransactionStatus.PROCESSING),

            // From AUTHORIZED: only PROCESSING and FAILED are legal
            Arguments.of(TransactionStatus.AUTHORIZED,   TransactionStatus.SUCCESS),
            Arguments.of(TransactionStatus.AUTHORIZED,   TransactionStatus.INITIATED),
            Arguments.of(TransactionStatus.AUTHORIZED,   TransactionStatus.AUTH_PENDING),
            Arguments.of(TransactionStatus.AUTHORIZED,   TransactionStatus.REVERSED),

            // From PROCESSING: only SUCCESS, FAILED, REVERSED are legal
            Arguments.of(TransactionStatus.PROCESSING,   TransactionStatus.INITIATED),
            Arguments.of(TransactionStatus.PROCESSING,   TransactionStatus.AUTH_PENDING),
            Arguments.of(TransactionStatus.PROCESSING,   TransactionStatus.AUTHORIZED),

            // From SUCCESS (terminal): no transitions
            Arguments.of(TransactionStatus.SUCCESS,      TransactionStatus.INITIATED),
            Arguments.of(TransactionStatus.SUCCESS,      TransactionStatus.AUTH_PENDING),
            Arguments.of(TransactionStatus.SUCCESS,      TransactionStatus.AUTHORIZED),
            Arguments.of(TransactionStatus.SUCCESS,      TransactionStatus.PROCESSING),
            Arguments.of(TransactionStatus.SUCCESS,      TransactionStatus.FAILED),
            Arguments.of(TransactionStatus.SUCCESS,      TransactionStatus.REVERSED),

            // From FAILED (terminal): no transitions
            Arguments.of(TransactionStatus.FAILED,       TransactionStatus.INITIATED),
            Arguments.of(TransactionStatus.FAILED,       TransactionStatus.AUTH_PENDING),
            Arguments.of(TransactionStatus.FAILED,       TransactionStatus.AUTHORIZED),
            Arguments.of(TransactionStatus.FAILED,       TransactionStatus.PROCESSING),
            Arguments.of(TransactionStatus.FAILED,       TransactionStatus.SUCCESS),
            Arguments.of(TransactionStatus.FAILED,       TransactionStatus.REVERSED),

            // From REVERSED (terminal): no transitions
            Arguments.of(TransactionStatus.REVERSED,     TransactionStatus.INITIATED),
            Arguments.of(TransactionStatus.REVERSED,     TransactionStatus.AUTH_PENDING),
            Arguments.of(TransactionStatus.REVERSED,     TransactionStatus.AUTHORIZED),
            Arguments.of(TransactionStatus.REVERSED,     TransactionStatus.PROCESSING),
            Arguments.of(TransactionStatus.REVERSED,     TransactionStatus.SUCCESS),
            Arguments.of(TransactionStatus.REVERSED,     TransactionStatus.FAILED)
        );
    }

    @ParameterizedTest(name = "illegal: {0} -> {1}")
    @MethodSource("illegalTransitions")
    void allIllegalTransitions_throwAndLeaveDbUnchanged(TransactionStatus fromStatus,
                                                         TransactionStatus toStatus) {
        Long tenantId = createTenantId();
        String txId = persistTransactionInStatus(tenantId, fromStatus);

        // The applyTransition call throws — no transaction template wrapping needed
        // because applyTransition is a pure in-memory call on the entity, not @Transactional.
        // We load the entity fresh and call applyTransition on the in-memory object.
        Transaction tx = transactionTemplate.execute(status -> {
            return transactionRepository.findByTransactionId(txId).orElseThrow();
        });
        assertThat(tx).isNotNull();

        final Transaction txForLambda = tx;
        assertThatThrownBy(() -> txForLambda.applyTransition(toStatus))
            .isInstanceOf(IllegalStateTransitionException.class)
            .hasMessageContaining(fromStatus.name())
            .hasMessageContaining(toStatus.name());

        // DB must still have the original fromStatus
        String actualStatus = jdbcTemplate.queryForObject(
            "SELECT tx_status FROM main.transaction WHERE transaction_id = ?",
            String.class, txId);
        assertThat(actualStatus)
            .as("DB must be unchanged after illegal transition attempt from %s to %s",
                fromStatus, toStatus)
            .isEqualTo(fromStatus.name());
    }

    // -------------------------------------------------------------------------
    // Legal transitions — drive through full chain
    // -------------------------------------------------------------------------

    @Test
    void allLegalTransitions_longChain_succeedInOrder() {
        Long tenantId = createTenantId();
        String txId = persistTransactionInStatus(tenantId, TransactionStatus.INITIATED);

        // INITIATED -> AUTH_PENDING
        applyAndSave(txId, TransactionStatus.AUTH_PENDING);
        assertDbStatus(txId, "AUTH_PENDING");

        // AUTH_PENDING -> AUTHORIZED
        applyAndSave(txId, TransactionStatus.AUTHORIZED);
        assertDbStatus(txId, "AUTHORIZED");

        // AUTHORIZED -> PROCESSING
        applyAndSave(txId, TransactionStatus.PROCESSING);
        assertDbStatus(txId, "PROCESSING");

        // PROCESSING -> SUCCESS
        applyAndSave(txId, TransactionStatus.SUCCESS);
        assertDbStatus(txId, "SUCCESS");
    }

    @Test
    void allLegalTransitions_shortChain_initiatedDirectlyToProcessing() {
        Long tenantId = createTenantId();
        String txId = persistTransactionInStatus(tenantId, TransactionStatus.INITIATED);

        // INITIATED -> PROCESSING (synchronous provider path — skips AUTH_PENDING/AUTHORIZED)
        applyAndSave(txId, TransactionStatus.PROCESSING);
        assertDbStatus(txId, "PROCESSING");

        // PROCESSING -> SUCCESS
        applyAndSave(txId, TransactionStatus.SUCCESS);
        assertDbStatus(txId, "SUCCESS");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Long createTenantId() {
        TenantBuilder.CreatedTenant tenant = new TenantBuilder()
            .withName("SM-Test-" + UUID.randomUUID().toString().substring(0, 8))
            .create(tenantService, tenantRepository);
        return tenant.tenantId();
    }

    /**
     * Persists a transaction in the given status by building from INITIATED
     * and applying transitions as needed.
     */
    private String persistTransactionInStatus(Long tenantId, TransactionStatus targetStatus) {
        String txId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();

        return transactionTemplate.execute(status -> {
            Transaction tx = Transaction.builder()
                .transactionId(txId)
                .traceId(traceId)
                .tenantId(tenantId)
                .provider(MobilePaymentProvider.MTN)
                .amount(new BigDecimal("100.00"))
                .currency("XAF")
                .txStatus(TransactionStatus.INITIATED)
                .build();

            // Apply transitions to reach target status
            if (targetStatus == TransactionStatus.INITIATED) {
                // already there
            } else if (targetStatus == TransactionStatus.AUTH_PENDING) {
                tx.applyTransition(TransactionStatus.AUTH_PENDING);
            } else if (targetStatus == TransactionStatus.AUTHORIZED) {
                tx.applyTransition(TransactionStatus.AUTH_PENDING);
                tx.applyTransition(TransactionStatus.AUTHORIZED);
            } else if (targetStatus == TransactionStatus.PROCESSING) {
                tx.applyTransition(TransactionStatus.AUTH_PENDING);
                tx.applyTransition(TransactionStatus.AUTHORIZED);
                tx.applyTransition(TransactionStatus.PROCESSING);
            } else if (targetStatus == TransactionStatus.SUCCESS) {
                tx.applyTransition(TransactionStatus.AUTH_PENDING);
                tx.applyTransition(TransactionStatus.AUTHORIZED);
                tx.applyTransition(TransactionStatus.PROCESSING);
                tx.applyTransition(TransactionStatus.SUCCESS);
            } else if (targetStatus == TransactionStatus.FAILED) {
                tx.applyTransition(TransactionStatus.FAILED);
            } else if (targetStatus == TransactionStatus.REVERSED) {
                tx.applyTransition(TransactionStatus.AUTH_PENDING);
                tx.applyTransition(TransactionStatus.AUTHORIZED);
                tx.applyTransition(TransactionStatus.PROCESSING);
                tx.applyTransition(TransactionStatus.REVERSED);
            }

            transactionRepository.save(tx);
            return txId;
        });
    }

    private void applyAndSave(String txId, TransactionStatus next) {
        transactionTemplate.execute(status -> {
            Transaction tx = transactionRepository.findByTransactionId(txId).orElseThrow();
            tx.applyTransition(next);
            transactionRepository.save(tx);
            return null;
        });
    }

    private void assertDbStatus(String txId, String expectedStatus) {
        String actualStatus = jdbcTemplate.queryForObject(
            "SELECT tx_status FROM main.transaction WHERE transaction_id = ?",
            String.class, txId);
        assertThat(actualStatus)
            .as("tx_status for transactionId=%s", txId)
            .isEqualTo(expectedStatus);
    }
}
