package com.softropic.payam.domain;

import com.softropic.payam.payment.ledger.contract.LedgerDirection;
import com.softropic.payam.payment.ledger.contract.LedgerPosting;
import com.softropic.payam.payment.ledger.repo.LedgerEntry;
import com.softropic.payam.payment.ledger.repo.LedgerEntryRepository;
import com.softropic.payam.payment.ledger.service.LedgerService;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * MUT-02: Ledger balance == debit amount check mutation kill.
 *
 * Calls LedgerService.postEntry() with a real LedgerService instance
 * (constructor-injected with a mock LedgerEntryRepository) so PITest mutations
 * in LedgerService are killed by this test.
 *
 * Captures the List&lt;LedgerEntry&gt; passed to ledgerEntryRepository.saveAll() and
 * asserts the double-entry balance invariant holds.
 */
class LedgerBalanceGuardTest {

    @Test
    @SuppressWarnings("unchecked")
    void postEntry_createsBalancedDoubleEntry() {
        LedgerEntryRepository repo = mock(LedgerEntryRepository.class);
        LedgerService service = new LedgerService(repo);

        BigDecimal amount = new BigDecimal("1000.00");
        service.postEntry("txn-ledger-001", 1L, LedgerPosting.collection(amount, "XAF"));

        ArgumentCaptor<List<LedgerEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(captor.capture());

        List<LedgerEntry> entries = captor.getValue();

        assertThat(entries)
            .as("LedgerService.postEntry() must save exactly 2 entries (DEBIT + CREDIT)")
            .hasSize(2);

        LedgerEntry debit  = entries.stream()
            .filter(e -> e.getDirection() == LedgerDirection.DEBIT).findFirst().orElseThrow();
        LedgerEntry credit = entries.stream()
            .filter(e -> e.getDirection() == LedgerDirection.CREDIT).findFirst().orElseThrow();

        assertThat(debit.getAmount())
            .as("DEBIT amount must equal CREDIT amount (double-entry balance invariant)")
            .isEqualByComparingTo(credit.getAmount());

        assertThat(debit.getEntryGroupId())
            .as("DEBIT and CREDIT entries must share the same entryGroupId")
            .isEqualTo(credit.getEntryGroupId());

        assertThat(debit.getAccountCode()).isEqualTo("CUSTOMER_WALLET");
        assertThat(credit.getAccountCode()).isEqualTo("PROVIDER_CLEARING");
    }

    @Test
    @SuppressWarnings("unchecked")
    void postEntry_disbursement_createsThreeBalancedEntries() {
        LedgerEntryRepository repo = mock(LedgerEntryRepository.class);
        LedgerService service = new LedgerService(repo);

        BigDecimal principal = new BigDecimal("1000.00");
        BigDecimal fee       = new BigDecimal("50.00");
        BigDecimal gross     = principal.add(fee); // 1050.00

        service.postEntry("txn-disb-001", 1L,
            LedgerPosting.disbursement(principal, fee, "XAF"));

        ArgumentCaptor<List<LedgerEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(captor.capture());

        List<LedgerEntry> entries = captor.getValue();

        assertThat(entries)
            .as("DISBURSEMENT must save exactly 3 entries (DEBIT MERCHANT_WALLET + CREDIT CUSTOMER_WALLET + CREDIT PROVIDER_FEE)")
            .hasSize(3);

        LedgerEntry debit = entries.stream()
            .filter(e -> e.getDirection() == LedgerDirection.DEBIT)
            .findFirst().orElseThrow();

        List<LedgerEntry> credits = entries.stream()
            .filter(e -> e.getDirection() == LedgerDirection.CREDIT)
            .toList();

        assertThat(credits)
            .as("DISBURSEMENT must have exactly 2 CREDIT entries")
            .hasSize(2);

        // Gross DEBIT = principal + fee
        assertThat(debit.getAmount())
            .as("DEBIT MERCHANT_WALLET amount = principal + fee")
            .isEqualByComparingTo(gross);
        assertThat(debit.getAccountCode())
            .as("DEBIT account code")
            .isEqualTo("MERCHANT_WALLET");

        // All 3 rows share one groupId
        String groupId = debit.getEntryGroupId();
        assertThat(credits)
            .as("All DISBURSEMENT entries must share one entryGroupId (V25 balance trigger grouping)")
            .allMatch(e -> groupId.equals(e.getEntryGroupId()));

        // Balance invariant: sum(credits) == debit
        BigDecimal creditSum = credits.stream()
            .map(LedgerEntry::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(creditSum)
            .as("sum(CREDIT amounts) must equal DEBIT amount (balanced)")
            .isEqualByComparingTo(gross);

        // Account codes + amounts on the two credits
        assertThat(credits)
            .as("one CREDIT must be CUSTOMER_WALLET = principal")
            .anyMatch(e -> "CUSTOMER_WALLET".equals(e.getAccountCode())
                && e.getAmount().compareTo(principal) == 0);
        assertThat(credits)
            .as("one CREDIT must be PROVIDER_FEE = fee")
            .anyMatch(e -> "PROVIDER_FEE".equals(e.getAccountCode())
                && e.getAmount().compareTo(fee) == 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void postEntry_disbursementZeroFee_zeroProviderFeeEntry() {
        LedgerEntryRepository repo = mock(LedgerEntryRepository.class);
        LedgerService service = new LedgerService(repo);

        BigDecimal principal = new BigDecimal("1000.00");
        BigDecimal fee       = BigDecimal.ZERO;
        BigDecimal gross     = principal; // gross == principal when fee == 0

        service.postEntry("txn-disb-zero-001", 1L,
            LedgerPosting.disbursement(principal, fee, "XAF"));

        ArgumentCaptor<List<LedgerEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(captor.capture());

        List<LedgerEntry> entries = captor.getValue();

        assertThat(entries)
            .as("DISBURSEMENT with fee=0 must still save 3 entries (PROVIDER_FEE credit amount = 0.00)")
            .hasSize(3);

        LedgerEntry debit = entries.stream()
            .filter(e -> e.getDirection() == LedgerDirection.DEBIT)
            .findFirst().orElseThrow();

        assertThat(debit.getAmount())
            .as("DEBIT amount = principal when fee = 0")
            .isEqualByComparingTo(gross);
        assertThat(debit.getAccountCode()).isEqualTo("MERCHANT_WALLET");

        List<LedgerEntry> credits = entries.stream()
            .filter(e -> e.getDirection() == LedgerDirection.CREDIT)
            .toList();

        assertThat(credits).hasSize(2);

        // Zero-amount PROVIDER_FEE credit — relies on V25 CHECK (amount >= 0) relaxation (SCHEMA-03).
        // Compare with compareTo (not equals) because new BigDecimal("0.00") != BigDecimal.ZERO via equals.
        assertThat(credits)
            .as("PROVIDER_FEE credit amount must compare equal to BigDecimal.ZERO (scale-insensitive)")
            .anyMatch(e -> "PROVIDER_FEE".equals(e.getAccountCode())
                && e.getAmount().compareTo(BigDecimal.ZERO) == 0);

        // CUSTOMER_WALLET credit = principal
        assertThat(credits)
            .as("CUSTOMER_WALLET credit amount must equal principal when fee = 0")
            .anyMatch(e -> "CUSTOMER_WALLET".equals(e.getAccountCode())
                && e.getAmount().compareTo(principal) == 0);

        // Balance invariant: sum(credits) == debit (= principal)
        BigDecimal creditSum = credits.stream()
            .map(LedgerEntry::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(creditSum)
            .as("sum(CREDIT amounts) must equal DEBIT amount even when fee = 0")
            .isEqualByComparingTo(principal);

        // Shared groupId
        String groupId = debit.getEntryGroupId();
        assertThat(credits).allMatch(e -> groupId.equals(e.getEntryGroupId()));
    }
}
