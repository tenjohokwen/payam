package com.softropic.payam.transaction;

import com.softropic.payam.config.TestConfig;
import com.softropic.payam.tenant.contract.ApiKeyEnvironment;
import com.softropic.payam.tenant.service.TenantService;
import com.softropic.payam.transaction.contract.TransactionEventType;
import com.softropic.payam.transaction.contract.TransactionStatus;
import com.softropic.payam.transaction.repo.PaymentEventLog;
import com.softropic.payam.transaction.repo.PaymentEventLogRepository;
import com.softropic.payam.transaction.service.EventLogService;

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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;


@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"ledger.database.spy=true", "enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
class PaymentEventLogIT {

    @Autowired
    private EventLogService eventLogService;

    @Autowired
    private PaymentEventLogRepository paymentEventLogRepository;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        // JWT secret required by SecurityAdviceFilter.addSecretToThread() on every request.
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

        // Create a real tenant to satisfy FK on transaction.tenant_id (used implicitly via
        // TransactionService for realistic data; not required directly by PaymentEventLog
        // which only stores transactionId as a String, not a FK — but we seed it anyway
        // for consistency and to allow future tests that join through Transaction).
        tenantService.createTenant("Event Log IT Corp", ApiKeyEnvironment.PROD);
    }

    @AfterEach
    void tearDown() {
        // Delete in FK-safe order: payment_event_log -> transaction -> tenant -> sec
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM main.payment_event_log");
            jdbcTemplate.execute("DELETE FROM main.transaction");
            jdbcTemplate.execute("DELETE FROM main.tenant_api_key");
            jdbcTemplate.execute("DELETE FROM main.tenant");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    // -------------------------------------------------------------------------
    // Test 1: First event for a transaction uses "GENESIS" as previousHash
    // -------------------------------------------------------------------------
    @Test
    void append_firstEvent_usesGenesisHash() {
        String txId = UUID.randomUUID().toString();

        PaymentEventLog entry = eventLogService.append(
                txId,
                "trace-genesis-001",
                null,
                TransactionEventType.PAYMENT_INITIATED,
                null,
                TransactionStatus.INITIATED,
                "system",
                null);

        assertThat(entry).isNotNull();
        assertThat(entry.getPreviousHash()).isEqualTo("GENESIS");
        assertThat(entry.getEventHash()).isNotNull();
        assertThat(entry.getEventHash()).hasSize(64);
        assertThat(entry.getStatusFrom()).isNull();
        assertThat(entry.getStatusTo()).isEqualTo(TransactionStatus.INITIATED);
        assertThat(entry.getTransactionId()).isEqualTo(txId);
    }

    // -------------------------------------------------------------------------
    // Test 2: Chained events — event2.previousHash == event1.eventHash
    // -------------------------------------------------------------------------
    @Test
    void append_chainedEvents_hashLinkCorrect() {
        String txId = UUID.randomUUID().toString();

        PaymentEventLog event1 = eventLogService.append(
                txId,
                "trace-chain-001",
                "EXT-001",
                TransactionEventType.PAYMENT_INITIATED,
                null,
                TransactionStatus.INITIATED,
                "system",
                null);

        PaymentEventLog event2 = eventLogService.append(
                txId,
                "trace-chain-001",
                "EXT-001",
                TransactionEventType.PROVIDER_AUTH_REQUESTED,
                TransactionStatus.INITIATED,
                TransactionStatus.AUTH_PENDING,
                "system",
                null);

        assertThat(event2.getPreviousHash()).isEqualTo(event1.getEventHash());
        assertThat(event2.getEventHash()).isNotEqualTo(event1.getEventHash());
    }

    // -------------------------------------------------------------------------
    // Test 3: verifyChain returns true for an intact 3-event chain
    // -------------------------------------------------------------------------
    @Test
    void verifyChain_intactChain_returnsTrue() {
        String txId = UUID.randomUUID().toString();

        eventLogService.append(txId, "trace-verify-001", null,
                TransactionEventType.PAYMENT_INITIATED,
                null, TransactionStatus.INITIATED, "system", null);

        eventLogService.append(txId, "trace-verify-001", null,
                TransactionEventType.PROVIDER_AUTH_REQUESTED,
                TransactionStatus.INITIATED, TransactionStatus.AUTH_PENDING, "system", null);

        eventLogService.append(txId, "trace-verify-001", null,
                TransactionEventType.PROVIDER_AUTHORIZED,
                TransactionStatus.AUTH_PENDING, TransactionStatus.AUTHORIZED, "provider", null);

        boolean chainValid = eventLogService.verifyChain(txId);
        assertThat(chainValid).isTrue();

        // Also verify the repository query returns events in order
        List<PaymentEventLog> events =
                paymentEventLogRepository.findByTransactionIdOrderByCreatedDateAsc(txId);
        assertThat(events).hasSize(3);
        assertThat(events.get(0).getPreviousHash()).isEqualTo("GENESIS");
        assertThat(events.get(1).getPreviousHash()).isEqualTo(events.get(0).getEventHash());
        assertThat(events.get(2).getPreviousHash()).isEqualTo(events.get(1).getEventHash());
    }
}
