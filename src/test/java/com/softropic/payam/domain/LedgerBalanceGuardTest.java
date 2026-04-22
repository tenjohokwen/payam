package com.softropic.payam.domain;

import com.softropic.payam.transaction.contract.LedgerDirection;
import com.softropic.payam.transaction.contract.LedgerPosting;
import com.softropic.payam.transaction.repo.LedgerEntry;
import com.softropic.payam.transaction.repo.LedgerEntryRepository;
import com.softropic.payam.transaction.service.LedgerService;

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
}
