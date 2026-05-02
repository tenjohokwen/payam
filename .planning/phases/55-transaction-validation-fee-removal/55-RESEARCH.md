# Phase 55: Transaction Validation & Fee Removal — Research

**Researched:** 2026-05-02
**Domain:** Disbursement orchestration — transaction claim locking, validation, fee removal
**Confidence:** HIGH

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| TXN-01 | Tenant supplies `transactionIds` (non-empty, max 500 UUIDs) in `DisbursementRequest`; system rejects if any transaction does not belong to the requesting tenant, returning `422 INVALID_TRANSACTION` | `DisbursementRequest` is a Java record — add `transactionIds` field with `@NotEmpty @Size(max=500)` and `@Valid`; tenant ownership check in orchestrator before lock acquisition |
| TXN-02 | System rejects if any supplied transaction has `txStatus != SUCCESS` or `flow != COLLECTION`, returning `422 INVALID_TRANSACTION`; pre-Phase-10 rows with `fee_amount IS NULL` treated as `feeAmount = 0` | `Transaction.txStatus` maps to `TransactionStatus.SUCCESS`; `Transaction.getEffectiveFlow()` returns `COLLECTION` for null flow — already handles TXN-06 |
| TXN-03 | System rejects if any transaction already has an active claim (`ref_status IN ('PENDING','CLAIMED')`), returning `422 TRANSACTION_CLAIMED` | DB partial unique index `uq_dtr_txn_active_claim` enforces at DB layer (SCHEMA-01 done); app layer checks via repository query before insert |
| TXN-04 | System rejects if `request.amount != SUM(transaction.amount - feeAmount)` across all transactions, returning `422 AMOUNT_MISMATCH` | Sum computed in-memory after loading transaction rows; `feeAmount` null-coalesced to ZERO per TXN-06 |
| TXN-05 | Claim validation and creation execute atomically via `SELECT FOR UPDATE` on `Transaction` rows ordered by `transaction_id` ascending (lexicographic) — no deadlock | JPA `@Lock(PESSIMISTIC_WRITE)` query with `ORDER BY t.transactionId ASC`; must be inside `transactionTemplate.execute()` block |
| TXN-06 | Pre-Phase-10 collection transactions with `fee_amount IS NULL` treated as `feeAmount = 0` | `Transaction.feeAmount` is nullable; null-safe coalesce in validation service: `Objects.requireNonNullElse(t.getFeeAmount(), BigDecimal.ZERO)` |
| FEE-01 | Disbursement initiation bypasses `FeeEvaluationService`; `DisbursementResponse.fee` always `BigDecimal.ZERO`; no fee rule evaluated or stored | Already implemented in Phase 54 — `DisbursementOrchestrator` has no `FeeEvaluationService` dep; `fee = BigDecimal.ZERO` hardcoded; orchestrator comment confirms FEE-01 |
| FEE-02 | Any `Transaction` row written for a disbursement payout (`flow = DISBURSEMENT`) has `feeAmount = 0` and `feeRuleId = NULL` | `Transaction` rows for payout path are written by provider ports (OrangeMoneyPort); must be wired to pass `feeAmount = 0, feeRuleId = null` |
</phase_requirements>

---

## Summary

Phase 55 wires the `transactionIds` field into the disbursement request and adds claim-based validation inside `DisbursementOrchestrator.initiate()` before the provider dispatch step. The Phase 54 schema work is fully complete — `disbursement_transaction_ref`, its partial unique index, the `DisbursementTransactionRef` entity, and the stub `DisbursementTransactionRefRepository` all exist and pass compile+IT checks. Phase 55 is purely application-layer work: no new Flyway migrations are needed.

The critical correctness invariants are: (1) tenant-ownership check fires **before** any lock is acquired; (2) status and flow checks happen after ownership but before the SELECT FOR UPDATE; (3) the SELECT FOR UPDATE on `Transaction` rows must use ascending `transaction_id` order to prevent deadlocks under concurrent overlapping request sets; (4) all validation + claim-row creation must execute atomically in a single `transactionTemplate.execute()` block; (5) `FeeEvaluationService` is already gone from the orchestrator (done in Phase 54) — FEE-01 is already satisfied and must not be regressed.

FEE-02 concerns payout-side `Transaction` rows written by provider ports. These rows are written during the cashout/disbursement callback path, not during the initiation path being built in this phase. The planner should include a FEE-02 task that audits whether `OrangeMoneyPort` and the MTN disbursement path set `feeAmount = 0` and `feeRuleId = null` on any `Transaction` rows they create.

**Primary recommendation:** Add a `TransactionClaimValidationService` (or inline into the orchestrator in a well-named private method) that runs inside `transactionTemplate.execute()` and performs: load+lock transactions, ownership check, status+flow check, active-claim check (both app-layer query AND relies on the DB index as second guard), amount-sum check, then insert `DisbursementTransactionRef` rows in PENDING state.

---

## Standard Stack

No new libraries are introduced. Phase 55 uses the same stack already in the codebase.

### Core (Already Present)
| Library | Version | Purpose | Why |
|---------|---------|---------|-----|
| Spring Data JPA | (project version) | Repository queries, `@Lock(PESSIMISTIC_WRITE)` | Existing pattern for SELECT FOR UPDATE |
| Spring `TransactionTemplate` | (project version) | Manual transaction demarcation | Project pattern: orchestrator uses `TransactionTemplate`, not `@Transactional` |
| Hibernate Envers | (project version) | Audit trail on `DisbursementTransactionRef` | Already annotated `@Audited` on entity |
| Jakarta Validation | (project version) | `@NotEmpty`, `@Size` on `DisbursementRequest` | Existing annotation-driven validation pattern |

**No new dependencies to install.**

---

## Architecture Patterns

### Pattern 1: Orchestrator Uses TransactionTemplate (NOT @Transactional)

**What:** `DisbursementOrchestrator` is NOT annotated `@Transactional`. It calls `transactionTemplate.execute(...)` for each discrete DB write. The provider HTTP call runs between transaction blocks, outside any open connection.

**Critical carry-forward:** The claim validation + lock + insert sequence is ONE `transactionTemplate.execute()` block. Do not split it into separate calls.

**From codebase (DisbursementOrchestrator lines 282–290):**
```java
transactionTemplate.execute(status -> {
    Disbursement locked = disbursementRepository.findByDisbursementIdForUpdate(disbursementId)
            .orElseThrow(() -> new IllegalStateException("Disbursement vanished: " + disbursementId));
    locked.applyTransition(DisbursementStatus.PROCESSING);
    if (result.providerRef() != null) {
        locked.setProviderRef(result.providerRef());
    }
    return null;
});
```

The Phase 55 claim-validation block follows the same pattern — one `transactionTemplate.execute()` for the entire validation + insert atomic unit.

### Pattern 2: SELECT FOR UPDATE via @Lock(PESSIMISTIC_WRITE)

**What:** JPA `@Lock(LockModeType.PESSIMISTIC_WRITE)` on a `@Query` method is the established pattern in this codebase (see `TransactionRepository.findByTransactionIdForUpdate` and `DisbursementRepository.findByDisbursementIdForUpdate`).

**For Phase 55:** `TransactionRepository` needs a new query method to load-and-lock multiple `Transaction` rows by their `transactionId` strings, ordered ascending, within a `transactionTemplate.execute()` block.

```java
// To add to TransactionRepository
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT t FROM Transaction t WHERE t.transactionId IN :transactionIds ORDER BY t.transactionId ASC")
List<Transaction> findByTransactionIdsForUpdate(@Param("transactionIds") List<String> transactionIds);
```

**Deadlock prevention rationale:** Two concurrent disbursement requests referencing an overlapping set of transaction IDs will each lock rows in the same ascending order. Since both acquire locks in the same sequence, the second request blocks behind the first for the first shared row rather than forming a cycle. This is the canonical deadlock-prevention ordering strategy documented in STATE.md.

### Pattern 3: Validation Before Lock — Ownership Check First

The validation sequence matters for both correctness and performance:

```
Pre-lock (no DB row lock held):
  1. Load Transaction rows by transactionId (non-locking read)
  2. Check tenant ownership: all rows must have tenantId == caller tenantId
  3. → Returns INVALID_TRANSACTION if mismatch

Inside transactionTemplate.execute() (lock acquired):
  4. SELECT FOR UPDATE on Transaction rows (ORDER BY transactionId ASC)
  5. Re-verify txStatus == SUCCESS AND flow == COLLECTION (TXN-02)
  6. Re-verify feeAmount null-coalesce for pre-Phase-10 rows (TXN-06)
  7. Query DisbursementTransactionRefRepository for active claims (TXN-03)
  8. Compute SUM(amount - feeAmount) and compare to request.amount (TXN-04)
  9. Insert DisbursementTransactionRef rows (one per transactionId, PENDING)
  10. Return locked Transaction rows to caller
```

Why re-verify inside the lock: a transaction can transition between the pre-lock read and the locked read. The locked read is authoritative.

Why ownership check is pre-lock: it uses a different query (by tenant), avoids acquiring locks on rows that will be rejected immediately, and prevents a timing window where one tenant could lock another tenant's transactions.

### Pattern 4: New Error Codes in DisbursementOrchestratorError

Three new error codes are needed:

| Code | HTTP | Trigger |
|------|------|---------|
| `INVALID_TRANSACTION` | 422 | TXN-01 (ownership, empty list), TXN-02 (wrong status/flow) |
| `TRANSACTION_CLAIMED` | 422 | TXN-03 (active claim exists) |
| `AMOUNT_MISMATCH` | 422 | TXN-04 (sum mismatch) |

These must be added to `DisbursementOrchestratorError` enum. `DisbursementResource.resolveHttpStatus()` currently falls through to `UNPROCESSABLE_ENTITY` (422) as default — the new codes work without additional switch cases since 422 is the default. No change to `resolveHttpStatus()` is needed unless explicit matching is desired for documentation clarity.

### Pattern 5: DisbursementRequest Record Extension

`DisbursementRequest` is a Java `record`. Records are immutable — adding a field requires adding it to the canonical constructor. The existing constructor-site in `DisbursementOrchestrator.confirm()` (line 223) constructs a `pseudoRequest` from stored disbursement fields.

**Key finding:** The `confirm()` method builds a pseudo-request from stored disbursement data. After Phase 55, `transactionIds` must be reconstructed from `DisbursementTransactionRef` rows (query by `disbursementId`) when re-confirming. Phase 55 only adds the field to the DTO and validates on the `initiate()` path — `confirm()` does NOT re-run transaction validation (the claims were already created at initiation time).

**The record extension:**
```java
public record DisbursementRequest(
    // existing fields...
    @NotEmpty
    @Size(max = 500)
    List<@NotBlank String> transactionIds,
    // existing fields continued...
    String idempotencyKey
) {}
```

The `@NotEmpty` + `@Size(max=500)` combination enforces TXN-01's list constraint at the Bean Validation layer. Cross-field validation (UUID format, tenant ownership) runs in the orchestrator.

**Construction sites that must be updated:**
- `DisbursementOrchestrator.confirm()` line 222 — pseudo-request; pass `null` or an empty list since confirm does not re-validate claims
- All test construction sites for `DisbursementRequest` (grep `new DisbursementRequest(`)

### Pattern 6: DisbursementTransactionRefRepository Query Methods

The stub repository (Phase 54) must gain query methods in Phase 55:

```java
// Check for active claims (TXN-03) — used inside the locked block
boolean existsByTransactionIdAndRefStatusIn(String transactionId, Collection<DisbursementRefStatus> statuses);

// Or bulk check for a list:
@Query("SELECT r.transactionId FROM DisbursementTransactionRef r " +
       "WHERE r.transactionId IN :transactionIds " +
       "AND r.refStatus IN :statuses")
List<String> findClaimedTransactionIds(
    @Param("transactionIds") List<String> transactionIds,
    @Param("statuses") Collection<DisbursementRefStatus> statuses);
```

### Pattern 7: Disbursement Entity — admin_note and retry_count Fields

`Disbursement.java` is missing `adminNote` and `retryCount` fields that were added to the DB by V31. These fields are NOT needed by Phase 55 logic but need to be added to the entity to prevent `spring.jpa.generate-ddl=true` from reversing the V31 column additions (same pitfall that hit Phase 54 with `reservedAmount`). The planner should include a Wave 0 task to verify these fields are present on the entity; if absent, add them.

**Check:** `grep -n "admin_note\|adminNote\|retry_count\|retryCount" src/main/java/.../disbursement/repo/Disbursement.java`

### Recommended Decomposition into Plans

**Plan 01 (Wave 0 + Core Validation):** DisbursementRequest extension + DisbursementTransactionRefRepository query methods + TransactionRepository.findByTransactionIdsForUpdate + new error codes + validation service (or orchestrator method) + unit tests

**Plan 02 (Integration Tests):** `DisbursementOrchestratorIT` extended with claim-validation scenarios + concurrency SELECT FOR UPDATE test + FEE-02 audit of provider ports

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Pessimistic lock on multiple rows | Custom `LOCK TABLE` SQL | `@Lock(PESSIMISTIC_WRITE)` with JPQL `ORDER BY` | JPA handles lock mode, session lifecycle, release on commit |
| Deadlock-safe multi-row lock ordering | Application-layer retry loop | `ORDER BY t.transactionId ASC` in the query | Consistent ordering prevents deadlocks at query level, no retry needed |
| Partial unique constraint enforcement | Application-layer dedup check as sole guard | Query check + rely on DB index as second guard | Race condition between check-then-insert can be lost — DB index is the authoritative guard for TXN-03 |
| Amount sum comparison | Custom decimal comparison | `BigDecimal.compareTo()` (NOT `.equals()`) | `BigDecimal.equals()` considers scale — 100.00 != 100.0; `compareTo` is scale-insensitive |

**Key insight:** `BigDecimal.equals()` is wrong for financial amount comparison. Always use `request.amount().compareTo(sumDisbursable) == 0` for TXN-04.

---

## Common Pitfalls

### Pitfall 1: BigDecimal.equals() Scale Sensitivity
**What goes wrong:** `new BigDecimal("100.00").equals(new BigDecimal("100.0"))` returns `false`. TXN-04 fails for valid requests if `.equals()` is used.
**Why it happens:** `BigDecimal.equals()` compares both value and scale.
**How to avoid:** Use `compareTo() == 0` for all amount equality checks.

### Pitfall 2: Ownership Check Inside the Lock (Too Late)
**What goes wrong:** Acquiring `SELECT FOR UPDATE` on rows before checking tenant ownership means you briefly lock another tenant's transactions — potential for contention and a security smell.
**Why it happens:** Combining all validation inside one locked block seems clean.
**How to avoid:** Run a non-locking tenant check first (before `transactionTemplate.execute()`). Only proceed to the locking block if ownership passes.

### Pitfall 3: DisbursementRequest Constructor Break in confirm()
**What goes wrong:** `confirm()` constructs a `pseudoRequest` using the all-args `DisbursementRequest` constructor. Adding `transactionIds` to the record breaks this call.
**Why it happens:** Java records require all fields in the canonical constructor.
**How to avoid:** Pass `null` (or `Collections.emptyList()`) for `transactionIds` in the pseudo-request inside `confirm()`. Document in a comment that `confirm()` does not re-run transaction validation.

### Pitfall 4: Claiming Without Re-Checking Inside Lock
**What goes wrong:** Race condition — two requests both pass the pre-lock active-claim check (app query returns empty), then both proceed to insert PENDING claims. The second insert hits the DB partial unique index and throws `DataIntegrityViolationException`.
**Why it happens:** The app-layer check is a read, not atomic with the insert.
**How to avoid:** Re-check active claims inside the `transactionTemplate.execute()` block after the SELECT FOR UPDATE. The DB index is the authoritative final guard and will fire if the app check somehow passes. Catch `DataIntegrityViolationException` and translate to `TRANSACTION_CLAIMED`.

### Pitfall 5: Test Setup — DisbursementRequest Construction Sites
**What goes wrong:** Adding `transactionIds` to the record breaks ~15+ test construction sites.
**Why it happens:** Java records have only the canonical constructor — no partial constructors.
**How to avoid:** Systematically update all `new DisbursementRequest(...)` call sites. Grep: `new DisbursementRequest(` — also check `DisbursementOrchestratorIT`, `DisbursementOrchestratorTest`, E2E test files.

### Pitfall 6: spring.jpa.generate-ddl Reversing V31 Columns
**What goes wrong:** `admin_note` and `retry_count` are in the DB (V31) but not yet on `Disbursement.java`. `spring.jpa.generate-ddl=true` in `application-dev.yaml` causes Hibernate to emit `ALTER TABLE ... DROP COLUMN` on test context startup, removing the columns V31 added.
**Why it happens:** Hibernate's DDL generation syncs the schema to match the entity — missing fields get dropped.
**How to avoid:** Add `adminNote` and `retryCount` fields to `Disbursement.java` as part of Wave 0. Check if already present before adding.

### Pitfall 7: Transaction Rows Written by Provider Ports (FEE-02)
**What goes wrong:** `Transaction` rows written for disbursement payouts (via OrangeMoneyPort or the MTN path) may carry non-zero `feeAmount` or non-null `feeRuleId` if those code paths weren't updated.
**Why it happens:** FEE-02 requires `flow=DISBURSEMENT` rows to have `feeAmount=0, feeRuleId=null`. The initiation path (FEE-01) is already clean, but the payout-side writes are in provider ports.
**How to avoid:** Audit `OrangeMoneyPort.initiateCashout()` (Phase 49 work) and any MTN disbursement payout path that creates `Transaction` rows. Ensure `LedgerPosting.disbursement(principal, BigDecimal.ZERO, currency)` is the pattern used and that the resulting Transaction row has the correct fee fields.

---

## FEE-01 Status (Already Complete in Phase 54)

FEE-01 is **already satisfied** by Phase 54 Plan 02. The evidence:

1. `DisbursementOrchestrator` has no `FeeEvaluationService` field — removed in commit a6d6aa7
2. Line 165 in `DisbursementOrchestrator.java`: `BigDecimal fee = BigDecimal.ZERO;` (comment says "FEE-01")
3. `DisbursementResponse.accepted(...)` receives `fee` parameter as `BigDecimal.ZERO` throughout
4. `DisbursementOrchestratorTest` mocks confirm FeeEvaluationService is gone from constructor

The planner should include a verification task to confirm FEE-01 remains satisfied (no regression) but should NOT include an implementation task for it.

---

## Code Examples

### SELECT FOR UPDATE — Multiple Rows, Ascending Order (TXN-05)

```java
// Source: TransactionRepository (project pattern), adapted for multi-row
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT t FROM Transaction t WHERE t.transactionId IN :transactionIds ORDER BY t.transactionId ASC")
List<Transaction> findByTransactionIdsForUpdate(
    @Param("transactionIds") List<String> transactionIds);
```

### Null-Safe feeAmount Coalesce (TXN-06)

```java
// Source: TXN-06 requirement; Transaction.feeAmount is nullable (pre-Phase-10)
BigDecimal effectiveFee = Objects.requireNonNullElse(transaction.getFeeAmount(), BigDecimal.ZERO);
BigDecimal disbursable = transaction.getAmount().subtract(effectiveFee);
```

### Amount Sum Comparison (TXN-04)

```java
// Source: TXN-04 requirement; BigDecimal.compareTo avoids scale sensitivity
BigDecimal sumDisbursable = lockedTxns.stream()
    .map(t -> t.getAmount().subtract(Objects.requireNonNullElse(t.getFeeAmount(), BigDecimal.ZERO)))
    .reduce(BigDecimal.ZERO, BigDecimal::add);
if (request.amount().compareTo(sumDisbursable) != 0) {
    return DisbursementResponse.failed(null,
        DisbursementOrchestratorError.AMOUNT_MISMATCH.getErrorCode(),
        "Request amount " + request.amount() + " != disbursable sum " + sumDisbursable);
}
```

### Claim Row Creation (CLAIM-01 — Phase 56, but scaffolded here)

```java
// Source: DisbursementTransactionRef entity (Phase 54 SCHEMA-01)
// Create one PENDING row per supplied transactionId
for (String txnId : request.transactionIds()) {
    DisbursementTransactionRef ref = DisbursementTransactionRef.builder()
        .disbursementId(dsb.getId())
        .transactionId(txnId)
        .refStatus(DisbursementRefStatus.PENDING)
        .build();
    transactionRefRepository.save(ref);
}
```

### DisbursementRequest Extension

```java
// Source: existing DisbursementRequest record pattern
public record DisbursementRequest(
    @NotBlank @Size(max = 20) String recipientMsisdn,
    @NotNull @Positive BigDecimal amount,
    @NotBlank @Size(min = 3, max = 3) String currency,
    @NotBlank @Size(max = 50) String reference,
    @Size(max = 140) String description,
    @Size(max = 2048) String metadata,
    @NotEmpty @Size(max = 500) List<@NotBlank String> transactionIds,  // NEW TXN-01
    String idempotencyKey
) {}
```

---

## Architecture: Where Validation Lives

The validation logic should live in **either**:

Option A: A dedicated `TransactionClaimValidationService` (`@Service`, injected into `DisbursementOrchestrator`). This keeps the orchestrator clean and makes the validation testable in isolation with mocks.

Option B: A private method `validateAndClaimTransactions(...)` directly in `DisbursementOrchestrator`, called inside the `transactionTemplate.execute()` block.

**Recommendation: Option A** (`TransactionClaimValidationService`) — consistent with how `DisbursementVelocityService` and `DisbursementFraudEvaluationService` are extracted from the orchestrator. The planner should create this service with a method signature like:

```java
/**
 * Validates transaction claims and creates PENDING DisbursementTransactionRef rows.
 * Must be called inside a transactionTemplate.execute() block — relies on outer
 * transaction for SELECT FOR UPDATE semantics.
 *
 * @throws InvalidTransactionException  if TXN-01 or TXN-02 fails
 * @throws TransactionClaimedException  if TXN-03 fails
 * @throws AmountMismatchException      if TXN-04 fails
 */
public void validateAndClaim(Long tenantId,
                              List<String> transactionIds,
                              BigDecimal requestedAmount,
                              Long disbursementId);
```

Or — since the orchestrator returns `DisbursementResponse` on error (not exceptions) — the service can return a `ValidationResult` discriminated union (or `Optional<DisbursementResponse>` for failures).

**Project convention alignment:** Other validation services (`DisbursementVelocityService`, `DisbursementFraudEvaluationService`) throw domain exceptions, and the orchestrator catches them and maps to `DisbursementResponse.failed(...)`. Use the same pattern: throw `InvalidTransactionException`, `TransactionClaimedException`, `AmountMismatchException` from the service; catch in the orchestrator.

---

## Orchestrator Modification Map

The existing `DisbursementOrchestrator.initiate()` sequence (7 steps):

```
1. Idempotency check
2. MSISDN routing
3. Velocity check
4. Fraud check
5. Fee evaluation → already BigDecimal.ZERO (FEE-01 done)
6. Step-up gate
7. Create Disbursement row
```

**Phase 55 adds Step 6.5 (after disbursement row created, before step-up gate return or provider dispatch):**

```
7. Create Disbursement row (INITIATED or PENDING_CONFIRMATION)
→ NEW 7.5: Inside transactionTemplate.execute():
   a. Load + lock Transaction rows (SELECT FOR UPDATE ORDER BY transactionId ASC)
   b. Tenant ownership check
   c. Status/flow check (TXN-02)
   d. Active-claim check (TXN-03)
   e. Amount sum check (TXN-04)
   f. Insert DisbursementTransactionRef rows (PENDING)
8. If PENDING_CONFIRMATION → return
9–11. Provider dispatch
```

Wait: actually, careful reading of TXN-01: "before any lock is acquired" means the tenant ownership check fires BEFORE the SELECT FOR UPDATE. So:

```
7. Create Disbursement row
→ NEW 7a: Non-locking read — load transactions by IDs
→ NEW 7b: Tenant ownership check (BEFORE lock)
→ NEW 7c: Inside transactionTemplate.execute():
   - SELECT FOR UPDATE (ORDER BY transactionId ASC)
   - TXN-02: status/flow check
   - TXN-03: active-claim check
   - TXN-04: amount sum check
   - Insert PENDING ref rows
8. If PENDING_CONFIRMATION → return
9–11. Provider dispatch
```

The non-locking read in 7a is acceptable because the ownership check is a read of immutable data (tenant_id is set at transaction creation and never changes). The locked read in 7c is the authoritative validation — it also re-confirms status/flow since those can change between 7a and 7c.

**Where to insert relative to step-up gate:** Claims are created regardless of whether the disbursement is PENDING_CONFIRMATION or going straight to provider. The success criteria say claims must be created when a disbursement is accepted. The step-up path returns early after creating claims.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Mockito (unit), Spring Boot Test + Testcontainers (IT) |
| Config file | `src/test/resources/application-test.yaml` / `@ActiveProfiles({"dev","test"})` |
| Quick run command | `mvn test -pl . -Dtest=DisbursementOrchestratorTest,TransactionClaimValidationServiceTest` |
| Full suite command | `mvn verify` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| TXN-01 | Empty transactionIds → 422 INVALID_TRANSACTION before lock | Unit | `mvn test -Dtest=TransactionClaimValidationServiceTest` | No — Wave 0 |
| TXN-01 | Wrong-tenant transactionId → 422 INVALID_TRANSACTION before lock | Unit + IT | same | No — Wave 0 |
| TXN-02 | Non-SUCCESS status → 422 INVALID_TRANSACTION | Unit | same | No — Wave 0 |
| TXN-02 | Non-COLLECTION flow → 422 INVALID_TRANSACTION | Unit | same | No — Wave 0 |
| TXN-03 | Active claim exists → 422 TRANSACTION_CLAIMED | Unit + IT | same | No — Wave 0 |
| TXN-04 | Amount mismatch → 422 AMOUNT_MISMATCH | Unit | same | No — Wave 0 |
| TXN-05 | Concurrent requests on overlapping sets → no deadlock | IT (concurrent) | `mvn verify -Dtest=DisbursementClaimConcurrencyIT` | No — Wave 0 |
| TXN-06 | NULL feeAmount treated as 0 | Unit | same | No — Wave 0 |
| FEE-01 | No FeeEvaluationService call in initiate() | Unit (verify no mock interaction) | `mvn test -Dtest=DisbursementOrchestratorTest` | Yes (existing) |
| FEE-02 | Payout Transaction rows have feeAmount=0, feeRuleId=null | IT (audit check) | `mvn verify -Dtest=DisbursementOrchestratorIT` | Partial (needs assertion) |

### Sampling Rate
- Per task commit: `mvn test -Dtest=DisbursementOrchestratorTest,TransactionClaimValidationServiceTest`
- Per wave merge: `mvn verify`
- Phase gate: Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `src/test/java/.../disbursement/service/TransactionClaimValidationServiceTest.java` — covers TXN-01..06
- [ ] `src/test/java/.../disbursement/service/DisbursementClaimConcurrencyIT.java` — covers TXN-05
- [ ] Verify `Disbursement.java` has `adminNote` and `retryCount` fields to prevent generate-ddl regression
- [ ] New error codes in `DisbursementOrchestratorError` before tests can reference them

---

## Environment Availability

Phase 55 is code/config-only — no new external dependencies.

Step 2.6: SKIPPED — all required infrastructure (PostgreSQL, Redis, WireMock) was verified green by Phase 54. No new external tools needed.

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Wallet-balance gate (WalletBalanceService) | Claim-based locking (DisbursementTransactionRef) | Phase 54 (SCHEMA-03) | No wallet code in orchestrator; claims are the gate |
| FeeEvaluationService for disbursements | fee = BigDecimal.ZERO always | Phase 54 Plan 02 (FEE-01) | Already done; must not be regressed |
| DisbursementTransactionRefRepository stub | Needs query methods for Phase 55 | Phase 54 deferred intentionally | Add: findByTransactionIdsForUpdate, findClaimedTransactionIds |
| DisbursementRequest (no transactionIds) | Needs transactionIds field | Phase 55 (TXN-01) | Record extension breaks all existing test construction sites |

---

## Open Questions

1. **Does `Disbursement.java` already have `adminNote` / `retryCount` fields?**
   - What we know: V31 migration added these columns; Phase 54 SUMMARY says entity field removal was pulled forward but does not explicitly say the ADD fields were done
   - What's unclear: The fields may or may not be on the entity
   - Recommendation: Wave 0 task — grep for `admin_note` on `Disbursement.java`; add if missing (prevents generate-ddl regression)

2. **Does `OrangeMoneyPort.initiateCashout()` write Transaction rows with feeAmount?**
   - What we know: Phase 49 wired `LedgerPosting.disbursement(principal, fee, currency)` where fee came from `FeeEvaluationService`. Phase 54 retired FeeEvaluationService from the DISBURSEMENT orchestrator but Phase 49's cashout path may still evaluate a fee.
   - What's unclear: Whether Phase 49's `OrangeMoneyPort.initiateCashout()` still sets `feeAmount` on the payout `Transaction` row
   - Recommendation: FEE-02 task in Phase 55 should audit `OrangeMoneyPort.initiateCashout()` and ensure payout Transaction rows use `feeAmount = 0, feeRuleId = null`

3. **How does `confirm()` handle transactionIds after Phase 55?**
   - What we know: `confirm()` builds a `pseudoRequest` from stored disbursement data; it does not re-validate claims
   - What's unclear: Whether `transactionIds = null` in the pseudo-request is safe or triggers Bean Validation on the confirm path
   - Recommendation: `confirm()` does not go through `@Valid` — it calls `orchestrator.confirm()` directly, which internally builds the pseudo-request. The pseudo-request is not validated by Bean Validation. Passing `null` for `transactionIds` in the pseudo-request is safe as long as the orchestrator's confirm() path does not call `validateAndClaim()`.

---

## Sources

### Primary (HIGH confidence)
- Codebase inspection: `DisbursementOrchestrator.java` — confirmed FEE-01 done, TransactionTemplate pattern
- Codebase inspection: `DisbursementTransactionRef.java` + `DisbursementTransactionRefRepository.java` — confirmed stub state and what needs to be added
- Codebase inspection: `Transaction.java` — confirmed field names (`transactionId`, `txStatus`, `flow`, `feeAmount`, `feeRuleId`, `tenantId`)
- Codebase inspection: `TransactionRepository.java` — confirmed `@Lock(PESSIMISTIC_WRITE)` pattern
- Codebase inspection: `DisbursementRequest.java` — confirmed record structure, all constructor sites
- Codebase inspection: `DisbursementStatus.java` — confirmed PENDING_ADMIN_APPROVAL added in Phase 54
- Codebase inspection: `V31__disbursement_transaction_ref.sql` — confirmed partial unique index and table structure
- Codebase inspection: `54-02-SUMMARY.md` — confirmed FEE-01 done, wallet retired, what was pulled forward
- STATE.md decisions — confirmed transactionId ordering strategy, TransactionTemplate pattern, no FK on non-PK columns

### Secondary (MEDIUM confidence)
- REQUIREMENTS.md TXN-01..TXN-06, FEE-01..FEE-02 — requirements as specified (no alternative source needed; project-internal)

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — no new dependencies; all patterns confirmed from existing codebase
- Architecture: HIGH — patterns directly verified from Phase 54 artifacts and existing orchestrator code
- Pitfalls: HIGH — most pitfalls are confirmed from Phase 54 SUMMARY (generate-ddl, record constructors) or from direct codebase analysis

**Research date:** 2026-05-02
**Valid until:** 2026-06-01 (stable codebase, no fast-moving external deps)
