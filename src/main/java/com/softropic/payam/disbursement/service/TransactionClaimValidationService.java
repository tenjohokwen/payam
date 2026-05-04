package com.softropic.payam.disbursement.service;

import com.softropic.payam.disbursement.contract.DisbursementRefStatus;
import com.softropic.payam.disbursement.contract.exception.AmountMismatchException;
import com.softropic.payam.disbursement.contract.exception.InvalidTransactionException;
import com.softropic.payam.disbursement.contract.exception.TransactionClaimedException;
import com.softropic.payam.disbursement.repo.DisbursementTransactionRef;
import com.softropic.payam.disbursement.repo.DisbursementTransactionRefRepository;
import com.softropic.payam.transaction.contract.LedgerFlow;
import com.softropic.payam.transaction.contract.TransactionStatus;
import com.softropic.payam.transaction.repo.Transaction;
import com.softropic.payam.transaction.repo.TransactionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates and creates per-collection-transaction claims that back a disbursement
 * (v11 TXN-01..TXN-04, TXN-06).
 *
 * <p><strong>Ordering contract:</strong> {@link #validateAndClaim} performs a
 * non-locking ownership pre-check FIRST, then expects the caller to invoke it
 * inside a {@code transactionTemplate.execute(...)} block. The locked phase uses
 * {@code SELECT FOR UPDATE} on Transaction rows ordered by transactionId ASC to
 * prevent deadlocks under concurrent overlapping request sets (TXN-05).
 *
 * <p><strong>Validation order:</strong>
 * <ol>
 *   <li>Empty list → InvalidTransactionException</li>
 *   <li>Pre-lock load → ownership check → InvalidTransactionException if any tx
 *       not owned by tenant (BEFORE acquiring locks)</li>
 *   <li>SELECT FOR UPDATE on transaction rows (caller's transactionTemplate)</li>
 *   <li>Re-verify each row: txStatus == SUCCESS AND effectiveFlow == COLLECTION
 *       → InvalidTransactionException naming the offending id</li>
 *   <li>Active-claim probe via DisbursementTransactionRefRepository
 *       → TransactionClaimedException naming the conflicting ids</li>
 *   <li>Sum disbursable = SUM(amount - coalesce(feeAmount, 0)) and compare to
 *       requestedAmount via {@code BigDecimal.compareTo} → AmountMismatchException
 *       if not equal (scale-insensitive, per TXN-06)</li>
 *   <li>Insert one PENDING DisbursementTransactionRef per transactionId; catch
 *       DataIntegrityViolationException (V31 partial unique index) and surface as
 *       TransactionClaimedException — the DB is the authoritative final guard</li>
 * </ol>
 */
@Service
public class TransactionClaimValidationService {

    private static final Logger log = LoggerFactory.getLogger(TransactionClaimValidationService.class);

    /** TXN-03 active-claim set — PENDING and CLAIMED block new claims; RELEASED does not. */
    private static final Set<DisbursementRefStatus> ACTIVE_CLAIM_STATUSES =
            EnumSet.of(DisbursementRefStatus.PENDING, DisbursementRefStatus.CLAIMED);

    private final TransactionRepository transactionRepository;
    private final DisbursementTransactionRefRepository transactionRefRepository;

    public TransactionClaimValidationService(TransactionRepository transactionRepository,
                                              DisbursementTransactionRefRepository transactionRefRepository) {
        this.transactionRepository = transactionRepository;
        this.transactionRefRepository = transactionRefRepository;
    }

    /**
     * Validate the supplied transactionIds and create PENDING claim rows.
     * MUST be called inside a transactionTemplate.execute block — relies on caller's
     * transaction for SELECT FOR UPDATE semantics.
     *
     * @param tenantId         the tenant initiating the disbursement
     * @param transactionIds   non-empty list (already enforced by Bean Validation, re-checked here)
     * @param requestedAmount  the disbursement request amount; compared via compareTo
     * @param disbursementDbId Disbursement.getId() — the BIGINT FK target of DisbursementTransactionRef.disbursementId
     * @throws InvalidTransactionException if TXN-01 (empty/wrong tenant/missing) or TXN-02 (status/flow) fails
     * @throws TransactionClaimedException if TXN-03 (active claim) or DB partial unique index fires
     * @throws AmountMismatchException     if TXN-04 (sum mismatch) fails
     */
    public void validateAndClaim(Long tenantId,
                                 List<String> transactionIds,
                                 BigDecimal requestedAmount,
                                 Long disbursementDbId) {

        // ── TXN-01: empty list guard ────────────────────────────────────────────
        if (transactionIds == null || transactionIds.isEmpty()) {
            throw new InvalidTransactionException("transactionIds list is empty");
        }

        // ── TXN-01: pre-lock ownership check ────────────────────────────────────
        // Non-locking read; fast-fail before acquiring any row locks. Ownership is
        // immutable on Transaction rows so this read does not need to be inside the
        // lock block.
        List<Transaction> preCheck = loadTransactions(transactionIds);
        if (preCheck.size() != transactionIds.size()) {
            Set<String> found = preCheck.stream()
                    .map(Transaction::getTransactionId).collect(Collectors.toSet());
            List<String> missing = transactionIds.stream()
                    .filter(id -> !found.contains(id)).toList();
            throw new InvalidTransactionException(
                    "transactionIds not found: " + missing);
        }
        for (Transaction t : preCheck) {
            if (!Objects.equals(t.getTenantId(), tenantId)) {
                throw new InvalidTransactionException(
                        "transactionId " + t.getTransactionId()
                                + " does not belong to tenant " + tenantId);
            }
        }

        // ── TXN-05: SELECT FOR UPDATE (ORDER BY transactionId ASC) ─────────────
        // Caller is inside transactionTemplate.execute — this acquires PESSIMISTIC_WRITE
        // locks for the duration of the outer transaction. Ascending ordering prevents
        // deadlocks under concurrent overlapping request sets.
        List<Transaction> locked =
                transactionRepository.findByTransactionIdsForUpdate(transactionIds);

        // ── TXN-02: re-verify status + flow under the lock ──────────────────────
        // Locked read is authoritative — between the pre-check read and now, status
        // could have changed (e.g. concurrent webhook).
        for (Transaction t : locked) {
            if (t.getTxStatus() != TransactionStatus.SUCCESS) {
                throw new InvalidTransactionException(
                        "transactionId " + t.getTransactionId()
                                + " has txStatus=" + t.getTxStatus()
                                + ", expected SUCCESS");
            }
            if (t.getEffectiveFlow() != LedgerFlow.COLLECTION) {
                throw new InvalidTransactionException(
                        "transactionId " + t.getTransactionId()
                                + " has flow=" + t.getEffectiveFlow()
                                + ", expected COLLECTION");
            }
        }

        // ── TXN-03: app-layer active-claim probe ────────────────────────────────
        // The DB partial unique index uq_dtr_txn_active_claim is the authoritative
        // final guard (caught below at INSERT time). This probe lets us return a
        // clean error code instead of catching DataIntegrityViolationException for
        // the common case.
        List<String> claimed = transactionRefRepository.findClaimedTransactionIds(
                transactionIds, ACTIVE_CLAIM_STATUSES);
        if (!claimed.isEmpty()) {
            throw new TransactionClaimedException(
                    "transactionIds already have active claims: " + claimed);
        }

        // ── TXN-04: amount equality check (compareTo, scale-insensitive) ────────
        // TXN-06: feeAmount=null (pre-Phase-10 rows) coalesced to BigDecimal.ZERO.
        BigDecimal sumDisbursable = locked.stream()
                .map(t -> t.getAmount().subtract(
                        Objects.requireNonNullElse(t.getFeeAmount(), BigDecimal.ZERO)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (requestedAmount.compareTo(sumDisbursable) != 0) {
            throw new AmountMismatchException(
                    "request.amount=" + requestedAmount
                            + " != sum(transaction.amount - feeAmount)=" + sumDisbursable);
        }

        // ── CLAIM-01: insert one PENDING DisbursementTransactionRef per txn ─────
        // Catch DataIntegrityViolationException — the partial unique index is the
        // authoritative race-loser arbiter. If two concurrent requests both passed
        // the app probe but only one can win the insert, surface the loser as
        // TRANSACTION_CLAIMED.
        for (String txnId : transactionIds) {
            try {
                DisbursementTransactionRef ref = DisbursementTransactionRef.builder()
                        .disbursementId(disbursementDbId)
                        .transactionId(txnId)
                        .refStatus(DisbursementRefStatus.PENDING)
                        .build();
                transactionRefRepository.save(ref);
            } catch (DataIntegrityViolationException e) {
                log.warn("Claim insert race lost — partial unique index fired",
                        kv("operation", "dsb_claim_race_lost"),
                        kv("transactionId", txnId),
                        kv("disbursementDbId", disbursementDbId));
                throw new TransactionClaimedException(
                        "transactionId " + txnId + " was claimed concurrently");
            }
        }

        log.info("Transaction claims created",
                kv("operation", "dsb_claims_created"),
                kv("disbursementDbId", disbursementDbId),
                kv("count", transactionIds.size()),
                kv("sumDisbursable", sumDisbursable));
    }

    /**
     * Load transactions by id (non-locking). Uses existing findByTransactionId per element.
     * The list is bounded to 500 ids by Bean Validation; the locked re-read via
     * findByTransactionIdsForUpdate is the authoritative read. Keep private so callers
     * cannot bypass the tenant ownership check.
     */
    private List<Transaction> loadTransactions(List<String> transactionIds) {
        return transactionIds.stream()
                .map(id -> transactionRepository.findByTransactionId(id).orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }
}
