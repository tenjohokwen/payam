package com.softropic.payam.disbursement.service;

import com.softropic.payam.common.payment.MobilePaymentProvider;
import com.softropic.payam.disbursement.contract.DisbursementRequest;
import com.softropic.payam.disbursement.contract.DisbursementStatus;
import com.softropic.payam.disbursement.repo.Disbursement;
import com.softropic.payam.disbursement.repo.DisbursementRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * DB-write helper for Disbursement entities. Each public method runs in its own
 * {@code @Transactional} boundary so the orchestrator can compose them without holding a
 * connection open across HTTP calls.
 *
 * <p>This service is intentionally thin — it does NOT contain orchestration logic. The
 * {@link DisbursementOrchestrator} owns the full initiation/confirmation sequence and calls
 * these methods at the appropriate points in the flow.
 */
@Service
public class DisbursementService {

    private static final Logger log = LoggerFactory.getLogger(DisbursementService.class);

    private final DisbursementRepository disbursementRepository;

    public DisbursementService(DisbursementRepository disbursementRepository) {
        this.disbursementRepository = disbursementRepository;
    }

    /**
     * Create the initial Disbursement row in the supplied state (INITIATED or PENDING_CONFIRMATION).
     * The disbursementId is generated as a UUID. The reservedAmount field is the principal+fee total
     * already debited from the wallet.
     *
     * @param tenantId       owning tenant
     * @param provider       resolved mobile payment provider (MTN or ORANGE)
     * @param request        disbursement request DTO
     * @param reservedAmount principal + fee total that has been reserved from the wallet
     * @param initialStatus  INITIATED for small amounts; PENDING_CONFIRMATION for step-up
     * @return saved Disbursement entity with disbursementId populated
     */
    @Transactional
    public Disbursement create(Long tenantId,
                               MobilePaymentProvider provider,
                               DisbursementRequest request,
                               BigDecimal reservedAmount,
                               DisbursementStatus initialStatus) {
        String disbursementId = UUID.randomUUID().toString();
        Disbursement dsb = Disbursement.builder()
                .disbursementId(disbursementId)
                .tenantId(tenantId)
                .recipientMsisdn(request.recipientMsisdn())
                .amount(request.amount())
                .currency(request.currency())
                .reference(request.reference())
                .description(request.description())
                .disbursementStatus(initialStatus)
                .provider(provider)
                .idempotencyKey(request.idempotencyKey())
                .reservedAmount(reservedAmount)
                .metadata(request.metadata())
                .build();
        Disbursement saved = disbursementRepository.save(dsb);
        log.info("Disbursement created",
                kv("operation", "dsb_create"),
                kv("disbursementId", disbursementId),
                kv("tenantId", tenantId),
                kv("status", initialStatus.name()),
                kv("provider", provider.name()));
        return saved;
    }

    /**
     * Transition a disbursement to FAILED (under pessimistic lock). Used by the orchestrator
     * when a post-reservation failure occurs (subscriber inactive, provider error).
     *
     * <p>IMPORTANT: The caller MUST also release the wallet balance via
     * {@link WalletBalanceService#release} before or after calling this method (BAL-02).
     * This method only handles the DB state transition, not the wallet release.
     *
     * @param disbursementId the disbursementId UUID string
     * @throws IllegalStateException if no matching disbursement found (programmer bug)
     */
    @Transactional
    public void transitionToFailed(String disbursementId) {
        Disbursement locked = disbursementRepository.findByDisbursementIdForUpdate(disbursementId)
                .orElseThrow(() -> new IllegalStateException("Disbursement not found: " + disbursementId));
        locked.applyTransition(DisbursementStatus.FAILED);
        log.info("Disbursement transitioned to FAILED",
                kv("operation", "dsb_transition"),
                kv("disbursementId", disbursementId),
                kv("toStatus", "FAILED"));
    }
}
