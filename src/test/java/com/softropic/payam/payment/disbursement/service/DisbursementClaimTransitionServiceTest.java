package com.softropic.payam.payment.disbursement.service;

import com.softropic.payam.payment.disbursement.contract.DisbursementRefStatus;
import com.softropic.payam.payment.disbursement.repo.DisbursementTransactionRefRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DisbursementClaimTransitionServiceTest {

    private DisbursementTransactionRefRepository repo;
    private DisbursementClaimTransitionService sut;

    @BeforeEach
    void setUp() {
        repo = mock(DisbursementTransactionRefRepository.class);
        sut = new DisbursementClaimTransitionService(repo);
    }

    @Test
    void transitionClaims_pendingToClaimed_returnsRowCount() {
        when(repo.updateRefStatusForDisbursement(99L, DisbursementRefStatus.PENDING, DisbursementRefStatus.CLAIMED))
                .thenReturn(3);

        int result = sut.transitionClaims(99L, DisbursementRefStatus.PENDING, DisbursementRefStatus.CLAIMED);

        assertThat(result).isEqualTo(3);
        verify(repo).updateRefStatusForDisbursement(99L, DisbursementRefStatus.PENDING, DisbursementRefStatus.CLAIMED);
    }

    @Test
    void transitionClaims_pendingToReleased_returnsRowCount() {
        when(repo.updateRefStatusForDisbursement(99L, DisbursementRefStatus.PENDING, DisbursementRefStatus.RELEASED))
                .thenReturn(5);

        int result = sut.transitionClaims(99L, DisbursementRefStatus.PENDING, DisbursementRefStatus.RELEASED);

        assertThat(result).isEqualTo(5);
    }

    @Test
    void transitionClaims_zeroRowsAffected_returnsZero() {
        when(repo.updateRefStatusForDisbursement(99L, DisbursementRefStatus.PENDING, DisbursementRefStatus.RELEASED))
                .thenReturn(0);

        int result = sut.transitionClaims(99L, DisbursementRefStatus.PENDING, DisbursementRefStatus.RELEASED);

        assertThat(result).isZero();
    }

    @Test
    void transitionClaims_logsStructuredOperationKey() {
        // Logback ListAppender pattern — captures log events for assertion.
        // The structured kv("operation","dsb_claim_transition") is rendered into the
        // ILoggingEvent's argument array (logstash-logback-encoder StructuredArgument).
        ch.qos.logback.classic.Logger logbackLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                        DisbursementClaimTransitionService.class);
        ch.qos.logback.classic.Level originalLevel = logbackLogger.getLevel();
        logbackLogger.setLevel(ch.qos.logback.classic.Level.INFO);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        try {
            when(repo.updateRefStatusForDisbursement(99L, DisbursementRefStatus.PENDING, DisbursementRefStatus.CLAIMED))
                    .thenReturn(2);

            sut.transitionClaims(99L, DisbursementRefStatus.PENDING, DisbursementRefStatus.CLAIMED);

            assertThat(appender.list)
                    .anyMatch(evt ->
                            evt.getLevel() == ch.qos.logback.classic.Level.INFO
                            && java.util.Arrays.stream(
                                    evt.getArgumentArray() == null
                                            ? new Object[0]
                                            : evt.getArgumentArray())
                                    .map(Object::toString)
                                    .anyMatch(s -> s.contains("operation=dsb_claim_transition")));
        } finally {
            logbackLogger.detachAppender(appender);
            logbackLogger.setLevel(originalLevel);
        }
    }
}
