package com.softropic.payam.payment.disbursement.repo;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.config.TestConfig;
import com.softropic.payam.payment.disbursement.contract.DisbursementStatus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles({"dev","test"})
@SpringBootTest(properties = "enable.test.mail=true")
@Import(TestConfig.class)
class DisbursementRepositoryIT {

    @Autowired DisbursementRepository repo;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate template;

    @AfterEach
    void cleanup() {
        template.execute(s -> { jdbcTemplate.execute("DELETE FROM main.disbursement"); return null; });
    }

    private Disbursement save(String dsbId, DisbursementStatus status, MobilePaymentProvider prov,
                              String providerRef, String reference) {
        Disbursement d = Disbursement.builder()
            .disbursementId(dsbId).tenantId(1001L).recipientMsisdn("237691111111")
            .amount(new BigDecimal("100.00")).currency("XAF").reference(reference)
            .disbursementStatus(status).provider(prov).providerRef(providerRef)
            .build();
        return template.execute(s -> repo.saveAndFlush(d));
    }

    @Test
    void shouldReadPollAttempts_default_zero() {
        Disbursement saved = save(UUID.randomUUID().toString(),
            DisbursementStatus.PROCESSING, MobilePaymentProvider.MTN, "ref-1", "merch-1");
        assertThat(saved.getPollAttempts()).isEqualTo(0);
    }

    @Test
    void shouldIncrementPollAttempts() {
        String id = UUID.randomUUID().toString();
        save(id, DisbursementStatus.PROCESSING, MobilePaymentProvider.MTN, "ref-2", "merch-2");
        template.execute(s -> {
            Disbursement d = repo.findByDisbursementId(id).orElseThrow();
            d.incrementPollAttempts();
            d.incrementPollAttempts();
            repo.saveAndFlush(d);
            return null;
        });
        assertThat(repo.findByDisbursementId(id).orElseThrow().getPollAttempts()).isEqualTo(2);
    }

    @Test
    void shouldFindProcessingForPolling_skipLocked_returns_only_processing_mtn_rows() {
        save(UUID.randomUUID().toString(), DisbursementStatus.PROCESSING, MobilePaymentProvider.MTN, "r1", "m1");
        save(UUID.randomUUID().toString(), DisbursementStatus.SUCCESS,    MobilePaymentProvider.MTN, "r2", "m2");
        save(UUID.randomUUID().toString(), DisbursementStatus.PROCESSING, MobilePaymentProvider.ORANGE, "r3", "m3");
        // Run in a transaction so SKIP LOCKED has an active tx
        List<Disbursement> result = template.execute(s ->
            repo.findProcessingDisbursementsForPolling(
                DisbursementStatus.PROCESSING.name(),
                MobilePaymentProvider.MTN.name(),
                Instant.now().plus(1, ChronoUnit.HOURS),
                10));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getProvider()).isEqualTo(MobilePaymentProvider.MTN);
        assertThat(result.get(0).getDisbursementStatus()).isEqualTo(DisbursementStatus.PROCESSING);
    }

    @Test
    void shouldFindByProviderRef() {
        String id = UUID.randomUUID().toString();
        save(id, DisbursementStatus.PROCESSING, MobilePaymentProvider.MTN, "ref-abc-123", "merch-x");
        assertThat(repo.findByProviderRef("ref-abc-123")).isPresent();
        assertThat(repo.findByProviderRef("does-not-exist")).isEmpty();
    }

    @Test
    void shouldFindByReference() {
        String id = UUID.randomUUID().toString();
        save(id, DisbursementStatus.PROCESSING, MobilePaymentProvider.ORANGE, "ref-2", "merch-ref-001");
        assertThat(repo.findByReference("merch-ref-001")).isPresent();
        assertThat(repo.findByReference("nope")).isEmpty();
    }
}
