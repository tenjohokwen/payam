package com.softropic.payam.transaction;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.platform.tenant.contract.ApiKeyEnvironment;
import com.softropic.payam.platform.tenant.service.TenantService;
import com.softropic.payam.transaction.contract.LedgerDirection;
import com.softropic.payam.transaction.contract.LedgerPosting;
import com.softropic.payam.transaction.repo.LedgerEntry;
import com.softropic.payam.transaction.repo.LedgerEntryRepository;
import com.softropic.payam.transaction.repo.Transaction;
import com.softropic.payam.transaction.repo.TransactionRepository;
import com.softropic.payam.transaction.service.LedgerService;

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


@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"ledger.database.spy=true", "enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
class LedgerServiceIT {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long tenantId;
    private String transactionId;

    @BeforeEach
    void setUp() {
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

        // Create tenant
        TenantService.TenantCreationResult tenantResult =
            tenantService.createTenant("Ledger Service Corp", ApiKeyEnvironment.PROD);
        tenantId = tenantResult.tenant().getId();

        // Create a transaction row for FK reference
        transactionId = UUID.randomUUID().toString();
        transactionTemplate.execute(status -> {
            Transaction tx = Transaction.builder()
                .transactionId(transactionId)
                .traceId("ledger-trace-001")
                .tenantId(tenantId)
                .provider(MobilePaymentProvider.MTN)
                .amount(new BigDecimal("500.00"))
                .currency("XAF")
                .build();
            transactionRepository.save(tx);
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM main.ledger_entry");
            jdbcTemplate.execute("DELETE FROM main.transaction");
            jdbcTemplate.execute("DELETE FROM main.tenant_api_key");
            jdbcTemplate.execute("DELETE FROM main.tenant");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    // -------------------------------------------------------------------------
    // Test 1: postEntry inserts exactly 2 rows — one DEBIT and one CREDIT
    // -------------------------------------------------------------------------
    @Test
    void postEntry_insertsTwoRows_debitAndCredit() {
        ledgerService.postEntry(transactionId, tenantId, LedgerPosting.collection(new BigDecimal("500.00"), "XAF"));

        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(transactionId);

        assertThat(entries).hasSize(2);

        LedgerEntry debit = entries.stream()
            .filter(e -> e.getDirection() == LedgerDirection.DEBIT)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No DEBIT entry found"));

        LedgerEntry credit = entries.stream()
            .filter(e -> e.getDirection() == LedgerDirection.CREDIT)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No CREDIT entry found"));

        assertThat(debit.getEntryGroupId()).isEqualTo(credit.getEntryGroupId());
        assertThat(debit.getAmount()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(credit.getAmount()).isEqualByComparingTo(new BigDecimal("500.00"));
    }

    // -------------------------------------------------------------------------
    // Test 2: DEBIT and CREDIT amounts are equal — net balance is zero
    // -------------------------------------------------------------------------
    @Test
    void postEntry_balancedCheck() {
        ledgerService.postEntry(transactionId, tenantId, LedgerPosting.collection(new BigDecimal("500.00"), "XAF"));

        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(transactionId);

        BigDecimal debitSum = entries.stream()
            .filter(e -> e.getDirection() == LedgerDirection.DEBIT)
            .map(LedgerEntry::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal creditSum = entries.stream()
            .filter(e -> e.getDirection() == LedgerDirection.CREDIT)
            .map(LedgerEntry::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // credit - debit == 0 for a balanced entry
        assertThat(BigDecimal.ZERO.compareTo(creditSum.subtract(debitSum))).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // Test 3 (TEST-06): DISBURSEMENT persists 3 balanced rows in real Postgres.
    //   DEBIT  MERCHANT_WALLET  1050.00
    //   CREDIT CUSTOMER_WALLET  1000.00
    //   CREDIT PROVIDER_FEE       50.00
    // V25 balance-check trigger accepts the balanced group at commit.
    // -------------------------------------------------------------------------
    @Test
    void postEntry_disbursement_persistsThreeBalancedRows() {
        BigDecimal principal = new BigDecimal("1000.00");
        BigDecimal fee       = new BigDecimal("50.00");
        BigDecimal gross     = principal.add(fee); // 1050.00

        ledgerService.postEntry(transactionId, tenantId,
            LedgerPosting.disbursement(principal, fee, "XAF"));

        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(transactionId);

        assertThat(entries)
            .as("DISBURSEMENT must persist exactly 3 rows (V25 trigger accepted balanced group)")
            .hasSize(3);

        LedgerEntry debit = entries.stream()
            .filter(e -> e.getDirection() == LedgerDirection.DEBIT)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No DEBIT entry found"));

        List<LedgerEntry> credits = entries.stream()
            .filter(e -> e.getDirection() == LedgerDirection.CREDIT)
            .toList();

        assertThat(credits)
            .as("DISBURSEMENT must have exactly 2 CREDIT rows")
            .hasSize(2);

        // DEBIT side — MERCHANT_WALLET, gross = principal + fee
        assertThat(debit.getAccountCode())
            .as("DEBIT account code must be MERCHANT_WALLET")
            .isEqualTo("MERCHANT_WALLET");
        assertThat(debit.getAmount())
            .as("DEBIT amount must equal principal + fee")
            .isEqualByComparingTo(gross);

        // All 3 rows share one entry_group_id — V25 trigger groups by this.
        String groupId = debit.getEntryGroupId();
        assertThat(groupId).isNotNull();
        assertThat(credits)
            .as("All DISBURSEMENT rows must share one entry_group_id")
            .allMatch(e -> groupId.equals(e.getEntryGroupId()));

        // CREDIT side — one CUSTOMER_WALLET = principal, one PROVIDER_FEE = fee
        assertThat(credits)
            .as("one CREDIT must be CUSTOMER_WALLET with amount = principal")
            .anyMatch(e -> "CUSTOMER_WALLET".equals(e.getAccountCode())
                && e.getAmount().compareTo(principal) == 0);
        assertThat(credits)
            .as("one CREDIT must be PROVIDER_FEE with amount = fee")
            .anyMatch(e -> "PROVIDER_FEE".equals(e.getAccountCode())
                && e.getAmount().compareTo(fee) == 0);

        // Balance invariant — sum(CREDIT) == sum(DEBIT)
        BigDecimal debitSum = entries.stream()
            .filter(e -> e.getDirection() == LedgerDirection.DEBIT)
            .map(LedgerEntry::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal creditSum = credits.stream()
            .map(LedgerEntry::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(creditSum)
            .as("sum(CREDIT) must equal sum(DEBIT) — balanced by V25 trigger")
            .isEqualByComparingTo(debitSum);

        // Currency preserved on all 3 rows
        assertThat(entries)
            .as("all rows must have currency XAF")
            .allMatch(e -> "XAF".equals(e.getCurrency()));
    }
}
