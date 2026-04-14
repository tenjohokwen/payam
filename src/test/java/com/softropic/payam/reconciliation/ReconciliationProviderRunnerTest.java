package com.softropic.payam.reconciliation;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.reconciliation.port.ProviderReportPort;
import com.softropic.payam.reconciliation.port.ProviderTransactionRecord;
import com.softropic.payam.reconciliation.repo.ReconciliationDiscrepancyRepository;
import com.softropic.payam.reconciliation.repo.ReconciliationReport;
import com.softropic.payam.reconciliation.repo.ReconciliationReportRepository;
import com.softropic.payam.reconciliation.service.ReconciliationProviderRunner;
import com.softropic.payam.transaction.repo.Transaction;
import com.softropic.payam.transaction.repo.TransactionRepository;
import com.softropic.payam.transaction.contract.TransactionStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconciliationProviderRunnerTest {
    @Mock TransactionRepository transactionRepository;
    @Mock ReconciliationReportRepository reportRepository;
    @Mock ReconciliationDiscrepancyRepository discrepancyRepository;
    @Mock ProviderReportPort port;
    @InjectMocks ReconciliationProviderRunner runner;

    private ReconciliationReport report;
    private LocalDate reportDate;
    private Instant from;
    private Instant to;

    @BeforeEach
    void setUp() {
        reportDate = LocalDate.of(2026, 4, 13);
        from = Instant.parse("2026-04-13T00:00:00Z");
        to = Instant.parse("2026-04-14T00:00:00Z");
        report = ReconciliationReport.builder()
            .reportDate(reportDate)
            .provider(MobilePaymentProvider.MTN)
            .totalChecked(0).totalMatched(0).totalDiscrepancies(0)
            .status("IN_PROGRESS")
            .build();
        // NOTE: ReconciliationReport.id is inherited from BaseEntity and has no public setter.
        // For tests that need a non-null id, use reflection via org.springframework.test.util.ReflectionTestUtils
        // or spy the report. For Test C which calls markFailed(123L), mock reportRepository.findById(123L).
    }

    @Test
    void runForProvider_pagesTransactionsWithPageSize1000() {
        when(transactionRepository.findForReconciliationPaged(
                eq(MobilePaymentProvider.MTN), any(), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        runner.runForProvider(report, MobilePaymentProvider.MTN, reportDate, port, from, to);

        verify(transactionRepository).findForReconciliationPaged(
            eq(MobilePaymentProvider.MTN), eq(from), eq(to),
            argThat(p -> p.getPageSize() == 1000 && p.getPageNumber() == 0));
    }

    @Test
    void runForProvider_iteratesMultiplePages_andPersistsDiscrepanciesPerPage() {
        Transaction tx = Transaction.builder()
            .transactionId("tx-1")
            .traceId("trace-1")
            .tenantId(1L)
            .providerRef("ref-1")
            .amount(new BigDecimal("500.00"))
            .currency("XAF")
            .provider(MobilePaymentProvider.MTN)
            .build();

        // Page 0: one tx, not last. Page 1: empty, last.
        Page<Transaction> page0 = new PageImpl<>(List.of(tx), PageRequest.of(0, 1000), 1001L);
        Page<Transaction> page1 = new PageImpl<>(List.of(), PageRequest.of(1, 1000), 1001L);
        when(transactionRepository.findForReconciliationPaged(any(), any(), any(),
                argThat(p -> p != null && p.getPageNumber() == 0))).thenReturn(page0);
        when(transactionRepository.findForReconciliationPaged(any(), any(), any(),
                argThat(p -> p != null && p.getPageNumber() == 1))).thenReturn(page1);

        // Provider returns null status -> MISSING_IN_PROVIDER discrepancy
        when(port.fetchProviderRecord(any(), any()))
            .thenReturn(new ProviderTransactionRecord(null, null, null, false));

        runner.runForProvider(report, MobilePaymentProvider.MTN, reportDate, port, from, to);

        verify(discrepancyRepository, atLeastOnce()).saveAll(anyIterable());
        verify(transactionRepository).findForReconciliationPaged(any(), any(), any(),
            argThat(p -> p != null && p.getPageNumber() == 0));
        verify(transactionRepository).findForReconciliationPaged(any(), any(), any(),
            argThat(p -> p != null && p.getPageNumber() == 1));
        assertThat(report.getStatus()).isEqualTo("COMPLETE");
    }

    @Test
    void markFailed_setsReportStatusToFailed() {
        ReconciliationReport r = ReconciliationReport.builder()
            .reportDate(reportDate).provider(MobilePaymentProvider.ORANGE)
            .totalChecked(0).totalMatched(0)
            .totalDiscrepancies(0).status("IN_PROGRESS").build();
        when(reportRepository.findById(123L)).thenReturn(Optional.of(r));

        runner.markFailed(123L);

        verify(reportRepository).save(argThat(saved -> "FAILED".equals(saved.getStatus())));
    }

    @Test
    void runForProvider_propagatesException_whenDiscrepancyPersistenceThrows() {
        Transaction tx = Transaction.builder()
            .transactionId("tx-1")
            .traceId("trace-1")
            .tenantId(1L)
            .providerRef("ref-1")
            .amount(new BigDecimal("500.00"))
            .currency("XAF")
            .provider(MobilePaymentProvider.MTN)
            .build();

        Page<Transaction> page0 = new PageImpl<>(List.of(tx), PageRequest.of(0, 1000), 1L);
        when(transactionRepository.findForReconciliationPaged(any(), any(), any(), any(Pageable.class)))
            .thenReturn(page0);
        when(port.fetchProviderRecord(any(), any()))
            .thenReturn(new ProviderTransactionRecord(null, null, null, false));
        when(discrepancyRepository.saveAll(anyIterable()))
            .thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() ->
            runner.runForProvider(report, MobilePaymentProvider.MTN, reportDate, port, from, to))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("db down");
    }
}
