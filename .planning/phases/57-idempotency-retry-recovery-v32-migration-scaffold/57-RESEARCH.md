# Phase 57: Idempotency Retry Recovery & V32 Migration Scaffold — Research

**Researched:** 2026-05-04
**Domain:** Disbursement idempotency retry recovery, claim reactivation, Flyway migration scaffold
**Confidence:** HIGH

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| IDEM-01 | Retry a FAILED disbursement with a retriable error code when the same Idempotency-Key is resent, provided all original transaction claims remain unclaimed | Requires new retry-detection branch in `DisbursementOrchestrator.initiate()` after idempotency cache hit; needs retriable-vs-terminal error code classification |
| IDEM-02 | On valid retry: reactivate RELEASED claim rows to PENDING (no new inserts), increment retry_count, transition disbursement FAILED→INITIATED, re-enter provider dispatch | `DisbursementTransactionRefRepository.updateRefStatusForDisbursement()` already exists; retry_count field on `Disbursement` entity already present; FAILED→INITIATED state transition must be added to the state machine |
| IDEM-03 | For terminal error codes, return cached FAILED response immediately — no retry, no claim reactivation | Requires classifying error codes into retriable vs. terminal sets and consulting the stored idempotency response |
| SCHEMA-04 | V32 migration drops merchant_wallet_balance and merchant_wallet_balance_aud; runnable with no pre-V31 data dependencies | V31 already retired the wallet at the application layer; V32 is a pure DROP with an IF EXISTS guard; no pre-flight needed |
</phase_requirements>

---

## Summary

Phase 57 adds two tightly scoped capabilities to the claim-backed disbursement system: (1) intelligent idempotency retry recovery for transiently-failed disbursements, and (2) the V32 Flyway migration that drops the now-dead wallet balance tables.

The retry recovery feature (IDEM-01, IDEM-02, IDEM-03) intercepts the existing idempotency cache-hit path in `DisbursementOrchestrator.initiate()`. Instead of blindly replaying the cached response, the orchestrator must inspect the disbursement's current status and error code: if the disbursement is FAILED with a retriable code, it re-validates claims, reactivates RELEASED `DisbursementTransactionRef` rows, increments `retry_count`, transitions the disbursement back to INITIATED, and re-enters the provider dispatch flow. Terminal error codes return the cached FAILED response directly.

The V32 scaffold (SCHEMA-04) is a safe, minimal Flyway migration that issues `DROP TABLE IF EXISTS` on `main.merchant_wallet_balance` and `main.merchant_wallet_balance_aud`. The wall between application code and these tables was erected in Phase 54 (SCHEMA-03), so no application-layer changes are required in Phase 57.

**Primary recommendation:** Implement retry recovery as a new private method `retryDisbursement(tenantId, request, disbursement)` in `DisbursementOrchestrator`. Classify retriable vs. terminal codes via a `DisbursementRetryClassifier` component. Add FAILED→INITIATED to the state machine. Write V32 as a simple idempotent DROP.

---

## Standard Stack

### Core (all already in the project — no new dependencies)

| Component | Location | Purpose | Phase 57 Role |
|-----------|----------|---------|---------------|
| `DisbursementOrchestrator` | `disbursement/service` | Orchestrates initiate/confirm flow | Extend `initiate()` cache-hit branch to detect retry |
| `DisbursementIdempotencyService` | `disbursement/service` | Redis+Postgres idempotency under `idempotency:dsb:` namespace | Existing `checkAndReserve()`/`store()` — no changes needed |
| `DisbursementTransactionRefRepository` | `disbursement/repo` | Spring Data JPA for `disbursement_transaction_ref` | `updateRefStatusForDisbursement()` already exists for RELEASED→PENDING |
| `DisbursementRepository` | `disbursement/repo` | Spring Data JPA for `disbursement` | `findByTenantIdAndIdempotencyKey()` needed to fetch the FAILED disbursement |
| `DisbursementClaimTransitionService` | `disbursement/service` | Bulk claim status transition via `@Modifying UPDATE` | Reuse for RELEASED→PENDING (IDEM-02) |
| `DisbursementStatus` | `disbursement/contract` | State machine enum | Must add FAILED→INITIATED transition |
| Flyway | `src/main/resources/db/migration` | Database schema versioning | V32 migration file |
| `TransactionTemplate` | Spring | Non-`@Transactional` orchestration pattern | Same pattern as Phase 54–56 — no change |

### No New Dependencies

No new libraries are needed. All primitives are present:
- Claim bulk-update: `updateRefStatusForDisbursement()` already handles FROM/TO parameterized updates.
- FAILED disbursement lookup by idempotency key: `DisbursementRepository.findByTenantIdAndIdempotencyKey()` already exists.
- retry_count field: `Disbursement.retryCount` already mapped; V31 added the DB column.
- Flyway DROP TABLE: native SQL, no library.

---

## Architecture Patterns

### Pattern 1: Retry Detection in the Idempotency Cache-Hit Branch

The existing `DisbursementOrchestrator.initiate()` handles cache hits as:

```java
Optional<CachedResponse> cached = idempotencyService.checkAndReserve(tenantId, request.idempotencyKey());
if (cached.isPresent()) {
    CachedResponse cr = cached.get();
    if ("RESERVED".equals(cr.responseBody())) {
        return DisbursementResponse.failed(null,
                DisbursementOrchestratorError.DISBURSEMENT_ALREADY_PROCESSING.getErrorCode(), ...);
    }
    return JsonUtil.toObject(cr.responseBody(), DisbursementResponse.class);
}
```

Phase 57 extends the second branch. Instead of immediately returning the deserialized cached response, the orchestrator must:

1. Deserialize the cached `DisbursementResponse` from the stored JSON.
2. If `status != "FAILED"` — return the cached response as-is (SUCCESS, PROCESSING, PENDING_CONFIRMATION are all non-retriable replays).
3. If `status == "FAILED"` — look up the `Disbursement` row by `(tenantId, idempotencyKey)` to inspect `errorCode`.
4. Classify the `errorCode` as RETRIABLE or TERMINAL.
5. If TERMINAL — return the cached FAILED response immediately (IDEM-03).
6. If RETRIABLE — run claim re-validation and reactivation, then re-enter `dispatchToProvider()` (IDEM-01, IDEM-02).

```java
// Pseudocode for the extended cache-hit branch
if (cached.isPresent()) {
    CachedResponse cr = cached.get();
    if ("RESERVED".equals(cr.responseBody())) {
        return DisbursementResponse.failed(null, DISBURSEMENT_ALREADY_PROCESSING, ...);
    }
    DisbursementResponse cachedResp = JsonUtil.toObject(cr.responseBody(), DisbursementResponse.class);
    if (!"FAILED".equals(cachedResp.status())) {
        return cachedResp;   // PROCESSING / SUCCESS / PENDING_* — replay as-is
    }
    // FAILED — check if retriable
    return handleRetry(tenantId, request, cachedResp);
}
```

### Pattern 2: Retry Validation and Claim Reactivation (IDEM-01, IDEM-02)

The `handleRetry` method must:

1. Load the `Disbursement` entity via `DisbursementRepository.findByTenantIdAndIdempotencyKey()`.
2. Classify the error code — if TERMINAL, return the cached response.
3. Re-validate all original transaction claims are NOT currently PENDING or CLAIMED (IDEM-01's guard condition). Use `DisbursementTransactionRefRepository.findClaimedTransactionIds()` with `ACTIVE_CLAIM_STATUSES = {PENDING, CLAIMED}`.
4. Inside a single `transactionTemplate.execute()` block:
   a. `SELECT FOR UPDATE` on the `Disbursement` row.
   b. Verify the disbursement is still in FAILED state (race guard — another thread may have processed it).
   c. Transition disbursement: FAILED → INITIATED via `applyTransition()`.
   d. Increment `retry_count`: `disbursement.setRetryCount(disbursement.getRetryCount() + 1)`.
   e. Bulk-reactivate all RELEASED claim rows for this disbursement to PENDING: `updateRefStatusForDisbursement(disbursementDbId, RELEASED, PENDING)`.
5. Re-update the idempotency entry RESERVED (so in-flight guard works during re-dispatch).
6. Call `dispatchToProvider()` with the reconstituted request.

**Critical invariant:** Step 4 must be a single `transactionTemplate.execute()` block so the status transition and claim reactivation commit atomically. This matches the existing pattern in `releaseAndFail()`.

**Idempotency store re-use:** After successful re-dispatch, `idempotencyService.store()` overwrites the old FAILED response with the new PROCESSING response (same key, same tenant). The `store()` method uses UPSERT (`ON CONFLICT DO UPDATE`) so overwriting is safe.

### Pattern 3: Error Code Classification

A new `DisbursementRetryClassifier` component (or static utility class) classifies error codes:

```java
// Retriable error codes — transient failures
static final Set<String> RETRIABLE_CODES = Set.of(
    "TIMEOUT",
    "SYSTEM_ERROR",
    "HTTP_5XX",        // used if the orchestrator stores a 5xx-based code
    "PROVIDER_ERROR",  // catch-all for HttpClientException 5xx paths
    "PROVIDER_UNAVAILABLE"   // circuit breaker — provider may recover
);

// Terminal error codes — no retry permitted (IDEM-03)
static final Set<String> TERMINAL_CODES = Set.of(
    "ADMIN_REJECTED",
    "INVALID_RECIPIENT",      // maps to RECIPIENT_NOT_FOUND
    "INSUFFICIENT_PROVIDER_FUNDS"   // maps to InsufficientFundsDetector + IF alert
);
```

**Important mapping note:** The `DisbursementOrchestratorError` enum currently uses `PROVIDER_ERROR` and `PROVIDER_UNAVAILABLE` as the error codes stored in failed responses, NOT `TIMEOUT`/`SYSTEM_ERROR`/`HTTP_5xx`. The REQUIREMENTS.md refers to conceptual names. The planner must map REQUIREMENTS.md conceptual codes to actual `DisbursementOrchestratorError` enum values used in the stored responses:

| REQUIREMENTS.md Code | Actual Stored `DisbursementOrchestratorError` | Classification |
|----------------------|-----------------------------------------------|----------------|
| TIMEOUT | `PROVIDER_ERROR` (HttpClientException) | RETRIABLE |
| SYSTEM_ERROR | `PROVIDER_ERROR` (Exception) | RETRIABLE |
| HTTP_5xx | `PROVIDER_ERROR` (HttpClientException) | RETRIABLE |
| ADMIN_REJECTED | Does not exist yet — new enum value needed | TERMINAL |
| INVALID_RECIPIENT | `RECIPIENT_NOT_FOUND` | TERMINAL |
| INSUFFICIENT_PROVIDER_FUNDS | `PROVIDER_ERROR` (with IF signal — NOT yet a distinct code) | TERMINAL |

**Gap discovered:** `ADMIN_REJECTED` and `INSUFFICIENT_PROVIDER_FUNDS` are not currently distinct error codes in `DisbursementOrchestratorError`. The planner has two options:
- Option A (simpler): Add `ADMIN_REJECTED` and `INSUFFICIENT_PROVIDER_FUNDS` as new enum values to `DisbursementOrchestratorError`; update the InsufficientFunds path to use the new code.
- Option B (minimal): Classify ALL codes NOT in the RETRIABLE set as TERMINAL (treat unknown codes as terminal by default — conservative, safe).

**Recommendation:** Option B for Phase 57. The existing `PROVIDER_ERROR` code is used for BOTH retriable 5xx errors AND non-retriable provider errors (e.g. 4xx bad request for invalid recipient). This means pure code-based classification is ambiguous for `PROVIDER_ERROR`. The safest classification is:
- RETRIABLE: `PROVIDER_ERROR`, `PROVIDER_UNAVAILABLE` (both represent transient provider-side failures that may clear on retry)
- TERMINAL: `RECIPIENT_NOT_FOUND` (subscriber inactive — won't change), everything else that could indicate a non-transient condition

Phase 58 E2E tests will validate this classification. If the planner wants strict separation, Option A is cleaner but requires additional enum values and changes to the FAILED-state handler in `DisbursementCallbackTransitionService`.

### Pattern 4: State Machine Change — FAILED → INITIATED

The `DisbursementStatus` enum currently has FAILED as a terminal state (empty `allowedTransitions()`). IDEM-02 requires FAILED → INITIATED for retry recovery. This change must be made:

```java
FAILED {
    @Override
    public Set<DisbursementStatus> allowedTransitions() {
        // IDEM-02: retry recovery transitions FAILED back to INITIATED
        return EnumSet.of(INITIATED);
    }
},
```

**Test impact:** `DisbursementStatusTest` currently asserts FAILED is terminal. The test must be updated to reflect the new FAILED→INITIATED transition while keeping all other terminal behavior.

### Pattern 5: V32 Migration — DROP wallet tables

V32 is minimal — a pure DROP with IF EXISTS guards:

```sql
-- V32: Drop merchant wallet balance tables
-- These tables were retired at the application layer in Phase 54 (SCHEMA-03).
-- V32 drops them after ops confirm all pre-V31 disbursements have reached terminal state.
-- IF EXISTS guards make this migration idempotent and safe on environments
-- where the tables may have already been manually dropped.

DROP TABLE IF EXISTS main.merchant_wallet_balance_aud;
DROP TABLE IF EXISTS main.merchant_wallet_balance;
```

**Drop order:** `_aud` first, then base table. The `_aud` table references `revinfo(rev)` via FK — but `merchant_wallet_balance_aud` actually only has: `id, rev, revtype, ...` columns. In Envers, the `rev` column references `main.revinfo(rev)`. Dropping `_aud` first avoids any FK violation from `revinfo` trying to cascade. The `merchant_wallet_balance` table has no FKs that other tables reference (confirmed in V28 DDL: no FK from other tables to `merchant_wallet_balance`), so order is safe.

**No pre-flight assertion needed:** Unlike V31 (which had an in-flight disbursement guard), V32 carries no such risk. The tables are dead-code since Phase 54. They have no active writers. The IF EXISTS guard handles environments where they've already been dropped.

**Testability:** V32 must be runnable in the Testcontainers-backed IT environment without requiring pre-V31 data. Since the tables exist in V28 and V31 does not drop them, V32 will see them and drop successfully. Flyway runs all migrations in sequence in the test environment.

### Anti-Patterns to Avoid

- **Do not insert new `DisbursementTransactionRef` rows on retry:** IDEM-02 explicitly requires reactivating RELEASED rows, not creating new ones. The audit trail of RELEASED→PENDING transitions is preserved by Envers `@Audited` on `DisbursementTransactionRef`. New inserts would violate the partial unique index (`uq_dtr_txn_active_claim`) if the previous RELEASED rows still exist.
- **Do not re-run `validateAndClaim()` on retry:** `validateAndClaim()` is designed for first-time claim creation. It inserts new rows. Retry uses `updateRefStatusForDisbursement(RELEASED → PENDING)` — existing rows, no inserts. IDEM-01's "re-validate that claims are not currently PENDING/CLAIMED" must be done with the `findClaimedTransactionIds()` probe, not full `validateAndClaim()`.
- **Do not open a DB transaction across the provider HTTP call:** The existing `dispatchToProvider()` already upholds this invariant. The retry path calls the same `dispatchToProvider()` method — no new transaction boundary is opened there.
- **Do not call `idempotencyService.store()` before the provider dispatch succeeds:** The PROCESSING response is stored only after `port.initiateDisbursement(cmd)` returns. On retry, the same flow applies — the idempotency record is overwritten with the new PROCESSING response only on success.
- **Do not transition CLAIMED claims on retry:** On retry, only RELEASED rows are reactivated. CLAIMED rows (from a prior SUCCESS on another disbursement using same transactions — impossible by TXN-03 design, but defensive) must not be touched.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Claim status bulk-update | Custom UPDATE loop | `DisbursementTransactionRefRepository.updateRefStatusForDisbursement()` | Already written; handles RELEASED→PENDING atomically |
| Idempotency response overwrite | Custom SQL UPSERT | `DisbursementIdempotencyService.store()` | Already does Postgres-first UPSERT; Redis update included |
| FAILED disbursement lookup by tenant+key | Custom query | `DisbursementRepository.findByTenantIdAndIdempotencyKey()` | Already exists |
| DROP TABLE IF EXISTS | Custom migration helper | Standard Flyway SQL migration | Native SQL is sufficient |
| Retry count increment | Custom @Modifying query | `disbursement.setRetryCount(disbursement.getRetryCount() + 1)` + `disbursementRepository.save()` within the transaction | Simple field set inside the locked `transactionTemplate.execute()` block |

---

## Common Pitfalls

### Pitfall 1: Confusing IDEM-01 "re-validation" with full `validateAndClaim()` re-run

**What goes wrong:** Developer calls `transactionClaimValidationService.validateAndClaim()` on retry, which attempts to INSERT new claim rows. This fails with a `DataIntegrityViolationException` (partial unique index) because the RELEASED rows for the same `transaction_id` don't block insertion of PENDING, but the RELEASED rows' `disbursement_id` != the retry disbursement's ID... actually this succeeds as INSERT (RELEASED rows are excluded from the partial index). But it inserts NEW rows instead of reactivating the existing ones, violating the "no new rows" invariant of IDEM-02 and breaking the audit trail.

**Prevention:** Use `findClaimedTransactionIds(transactionIds, {PENDING, CLAIMED})` for the re-validation probe, and `updateRefStatusForDisbursement(disbursementDbId, RELEASED, PENDING)` for reactivation.

**Warning signs:** Audit query on `disbursement_transaction_ref_aud` shows multiple `ADD` entries for the same `transaction_id`.

### Pitfall 2: State machine terminal-state test regression

**What goes wrong:** `DisbursementStatusTest` has assertions verifying that FAILED has empty `allowedTransitions()`. Adding FAILED→INITIATED breaks the test.

**Prevention:** Update the FAILED terminal-state test to verify FAILED allows INITIATED only. Add a new test: `failedToInitiatedIsAllowed()`. Keep the existing `failedToSuccessThrows()` etc.

**Warning signs:** `DisbursementStatusTest` failing with `allowedTransitions` size assertion.

### Pitfall 3: Race between retry in-flight detection and concurrent retry attempt

**What goes wrong:** Two concurrent requests with the same idempotency key, both hitting FAILED. First request reserves the Redis key (sets back to RESERVED), performs reactivation, dispatches. Second request sees RESERVED sentinel and returns DISBURSEMENT_ALREADY_PROCESSING. This is correct. However: if the reactivation `transactionTemplate.execute()` block in the first thread fails (e.g. claims were claimed by a concurrent different disbursement), the Redis RESERVED sentinel is left dangling until TTL. Subsequent requests can't retry.

**Prevention:** On retry reactivation failure (TransactionClaimedException), clear the Redis idempotency reservation so the caller can retry again. This mirrors the original RESERVED sentinel behavior — the caller is told TRANSACTION_CLAIMED and can retry with different transaction IDs.

**Warning signs:** Idempotency key stuck in RESERVED after a failed retry attempt.

### Pitfall 4: V32 DROP order — aud before base

**What goes wrong:** Dropping `merchant_wallet_balance` before `merchant_wallet_balance_aud` may cause a FK violation if the _aud table's `rev` FK check cascades. (In practice Envers `_aud` tables are NOT referenced by other base tables, so dropping base first is usually safe, but the _aud → revinfo FK direction means _aud has a FK TO revinfo, not FROM anything else to _aud.)

**Prevention:** Drop `_aud` first. This is the pattern used across all other Flyway migrations in the project (e.g. V28 creates _aud before base; reversal drops _aud first is the natural inverse).

### Pitfall 5: `findByTenantIdAndIdempotencyKey` returning stale data

**What goes wrong:** The cached idempotency response stores a FAILED `DisbursementResponse` with the `errorCode` field. But `DisbursementResponse` is a serialized JSON record. The `errorCode` in the response object is the `DisbursementOrchestratorError` enum name (e.g. `"PROVIDER_ERROR"`). The retry classifier reads `errorCode` from the `DisbursementResponse`, not from the `Disbursement` entity. Make sure the classifier operates on the deserialized `DisbursementResponse.errorCode()` from the cached JSON, not by loading the Disbursement entity separately.

**Alternative:** Load the Disbursement entity for the retry classification, as it preserves the disbursement_status and any future errorCode field. Currently `Disbursement` entity does NOT have an `errorCode` field — only the serialized idempotency response carries it. The classifier must read from the deserialized response.

**Warning signs:** Classifier always returning TERMINAL or always RETRIABLE.

### Pitfall 6: `retry_count` incrementing without the lock

**What goes wrong:** `disbursement.setRetryCount()` is called outside the `transactionTemplate.execute()` block on a stale entity, then saved without the pessimistic lock. A concurrent retry increments the same value, causing a lost update.

**Prevention:** All retry_count changes must happen INSIDE `transactionTemplate.execute()` after `findByDisbursementIdForUpdate()` acquires the row lock.

---

## Code Examples

### Finding FAILED disbursement by idempotency key

```java
// Source: DisbursementRepository (existing query — no change needed)
Optional<Disbursement> findByTenantIdAndIdempotencyKey(Long tenantId, String idempotencyKey);
```

### Claim reactivation (RELEASED → PENDING)

```java
// Source: DisbursementTransactionRefRepository (existing query — no change needed)
// updateRefStatusForDisbursement(disbursementId, RELEASED, PENDING) reactivates all
// RELEASED claims for this disbursement. Returns the count of rows updated.
int reactivated = refRepository.updateRefStatusForDisbursement(
    disbursement.getId(), DisbursementRefStatus.RELEASED, DisbursementRefStatus.PENDING);
```

### Atomic retry transition block

```java
// Inside transactionTemplate.execute() — acquires PESSIMISTIC_WRITE lock
Disbursement locked = disbursementRepository.findByDisbursementIdForUpdate(disbursementId)
    .orElseThrow(() -> new IllegalStateException("Disbursement not found: " + disbursementId));

// Guard: must still be FAILED (another thread may have processed it)
if (locked.getDisbursementStatus() != DisbursementStatus.FAILED) {
    // Return cached response — race condition, another retry already progressed it
    return cachedResponse;
}

// IDEM-02: FAILED → INITIATED
locked.applyTransition(DisbursementStatus.INITIATED);

// IDEM-02: increment retry_count
locked.setRetryCount(locked.getRetryCount() + 1);

// IDEM-02: reactivate RELEASED claims to PENDING
int reactivated = claimTransitionService.transitionClaims(
    locked.getId(), DisbursementRefStatus.RELEASED, DisbursementRefStatus.PENDING);

log.info("Disbursement retry reactivation",
    kv("operation", "dsb_retry_reactivation"),
    kv("disbursementId", disbursementId),
    kv("reactivatedClaims", reactivated),
    kv("retryCount", locked.getRetryCount()));
```

### V32 migration file

```sql
-- V32: Drop merchant wallet balance tables (SCHEMA-04)
-- Application-layer retirement performed in Phase 54 (SCHEMA-03).
-- Ops sign-off required before applying V32 in production.
-- IF EXISTS guards make this idempotent.

DROP TABLE IF EXISTS main.merchant_wallet_balance_aud;
DROP TABLE IF EXISTS main.merchant_wallet_balance;
```

### DisbursementStatus state machine change

```java
// Before (Phase 54 state):
FAILED {
    @Override
    public Set<DisbursementStatus> allowedTransitions() {
        return EnumSet.noneOf(DisbursementStatus.class);  // terminal
    }
},

// After (Phase 57 — IDEM-02):
FAILED {
    @Override
    public Set<DisbursementStatus> allowedTransitions() {
        // IDEM-02: retry recovery transitions FAILED back to INITIATED.
        // FAILED remains terminal for all other purposes — SUCCESS/PROCESSING are NOT reachable.
        return EnumSet.of(INITIATED);
    }
},
```

---

## State of the Art

| Aspect | Current State | Phase 57 Change |
|--------|--------------|-----------------|
| FAILED state machine | Terminal (empty allowedTransitions) | FAILED → INITIATED added for retry recovery |
| Idempotency cache hit | Replay cached response verbatim | Extended to classify FAILED responses as retriable or terminal |
| `merchant_wallet_balance` table | Dead code since Phase 54; table still exists in DB | V32 drops it |
| `retry_count` field | Mapped on Disbursement entity; V31 added DB column; never written | First write happens in IDEM-02 retry path |

---

## Environment Availability

Step 2.6: SKIPPED (no new external dependencies — this is a pure application-layer + Flyway migration phase using the same Testcontainers PostgreSQL + Redis that all other disbursement phases use).

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test + Testcontainers |
| Config file | none — Spring Boot auto-configuration via `@SpringBootTest` |
| Quick run command | `mvn test -pl . -Dtest=DisbursementIdempotencyRetryIT -q` |
| Full suite command | `mvn verify -q` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| IDEM-01 | FAILED disbursement with retriable code + same key → re-validates claims; returns TRANSACTION_CLAIMED if any claim is PENDING/CLAIMED | Unit + Integration | `mvn test -Dtest=DisbursementOrchestratorTest,DisbursementIdempotencyRetryIT` | Unit: partial (DisbursementOrchestratorTest needs new cases); IT: NO — Wave 0 |
| IDEM-02 | Valid retry → RELEASED claims → PENDING, retry_count incremented, FAILED→INITIATED, re-dispatch | Unit + Integration | `mvn test -Dtest=DisbursementOrchestratorTest,DisbursementIdempotencyRetryIT` | NO — Wave 0 |
| IDEM-03 | Terminal error code + same key → cached FAILED returned immediately | Unit | `mvn test -Dtest=DisbursementOrchestratorTest` | NO — new test cases |
| SCHEMA-04 | V32 drops merchant_wallet_balance and _aud; runnable in test env | Integration (schema verification) | `mvn test -Dtest=V32MigrationIT` | NO — Wave 0 |

### Sampling Rate

- **Per task commit:** `mvn test -Dtest=DisbursementOrchestratorTest,DisbursementStatusTest -q`
- **Per wave merge:** `mvn verify -q`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps

- [ ] `src/test/java/com/softropic/payam/disbursement/service/DisbursementIdempotencyRetryIT.java` — integration test: covers IDEM-01 (claim already PENDING blocks retry), IDEM-02 (valid retry reactivates claims + increments retry_count + transitions FAILED→INITIATED), IDEM-03 (terminal code returns cached FAILED)
- [ ] `src/test/java/com/softropic/payam/disbursement/repo/V32MigrationIT.java` — schema test: verifies merchant_wallet_balance and _aud do not exist after V32 runs; IF EXISTS guard works; migration produces no error on a clean database

Existing tests needing update (not Wave 0 — they need code changes, not file creation):
- `DisbursementStatusTest`: update FAILED state to expect `EnumSet.of(INITIATED)` instead of `EnumSet.noneOf()`
- `DisbursementOrchestratorTest`: add cases for IDEM-01, IDEM-02, IDEM-03 retry paths

---

## Sources

### Primary (HIGH confidence)

- Source code audit: `DisbursementOrchestrator.java` — complete initiate() and dispatchToProvider() flow
- Source code audit: `DisbursementIdempotencyService.java` — idempotency cache-hit path, RESERVED sentinel, store() UPSERT
- Source code audit: `DisbursementTransactionRefRepository.java` — `updateRefStatusForDisbursement()` method signature and semantics
- Source code audit: `DisbursementRepository.java` — `findByTenantIdAndIdempotencyKey()` presence confirmed
- Source code audit: `Disbursement.java` — `retryCount` field and `incrementPollAttempts()` pattern for field mutation
- Source code audit: `DisbursementStatus.java` — current state machine, FAILED is terminal (empty allowedTransitions)
- Source code audit: `V28__disbursement_schema.sql` — merchant_wallet_balance and _aud table DDL
- Source code audit: `V31__disbursement_transaction_ref.sql` — idx_dtr_disbursement_id exists (needed by retry RELEASED lookup)
- Source code audit: `DisbursementRefStatus.java` — RELEASED docstring explicitly mentions "Reactivated to PENDING by IDEM-02 retry recovery"
- Source code audit: `DisbursementOrchestratorError.java` — current error codes (no ADMIN_REJECTED, no INSUFFICIENT_PROVIDER_FUNDS distinct codes)
- Source code audit: `STATE.md` — "Retry reactivates existing RELEASED DisbursementTransactionRef rows (no new inserts)"
- Source code audit: `REQUIREMENTS.md` — IDEM-01, IDEM-02, IDEM-03, SCHEMA-04 definitions verbatim

---

## Metadata

**Confidence breakdown:**

- Standard stack: HIGH — entire implementation uses existing project infrastructure; no new libraries
- Architecture: HIGH — retry pattern fully derivable from existing orchestrator structure + Requirements
- State machine change: HIGH — FAILED→INITIATED is unambiguous; confirmed by DisbursementRefStatus Javadoc
- Error code classification: MEDIUM — conceptual codes (TIMEOUT, SYSTEM_ERROR, HTTP_5xx) in REQUIREMENTS.md do not map 1:1 to existing `DisbursementOrchestratorError` enum values; planner must choose mapping approach
- V32 migration: HIGH — trivially `DROP TABLE IF EXISTS`; no risk; IF EXISTS guard handles all environments
- Pitfalls: HIGH — validated by reading all existing service code and test patterns

**Research date:** 2026-05-04
**Valid until:** 2026-05-11 (7 days — fast-moving milestone)

---

## Open Questions

1. **Error code classification: new enum values vs. code-set approach**
   - What we know: The REQUIREMENTS mention `ADMIN_REJECTED`, `INVALID_RECIPIENT`, `INSUFFICIENT_PROVIDER_FUNDS` as terminal codes. `ADMIN_REJECTED` does not exist as a `DisbursementOrchestratorError` value.
   - What's unclear: Whether Phase 57 should add `ADMIN_REJECTED` and `INSUFFICIENT_PROVIDER_FUNDS` as distinct error code values, or rely on the conservative approach (anything not in RETRIABLE_SET is TERMINAL).
   - Recommendation: Use the conservative approach for Phase 57 (retriable = `{PROVIDER_ERROR, PROVIDER_UNAVAILABLE}`; everything else is terminal). The Phase 58 E2E tests will surface any misclassification.

2. **`request.transactionIds()` on retry: where to get them**
   - What we know: On retry, the orchestrator has the original `DisbursementRequest` (from the incoming HTTP request). The same `transactionIds` list is present. The retry re-validation probe uses them. But `dispatchToProvider()` takes a `DisbursementRequest` and passes it to the provider — it uses `request.transactionIds()` nowhere in the provider path.
   - What's unclear: The planner must confirm `dispatchToProvider()` does not inspect `transactionIds` (confirmed: it does not — the pseudoRequest in `confirm()` explicitly passes null and it works).
   - Recommendation: Pass the original `request` (with its `transactionIds`) to `dispatchToProvider()` on retry. No special handling needed.

3. **V32 in production: ops sign-off protocol**
   - What we know: SCHEMA-04 says "after ops confirm all pre-V31 disbursements have reached a terminal state". V32 is scaffolded in Phase 57 but not run until ops confirm.
   - What's unclear: Should Phase 57 include a comment or Flyway placeholder that prevents accidental auto-migration?
   - Recommendation: Add a clearly visible comment block at the top of V32 stating "Requires ops sign-off before applying in production. Run `flyway repair` and then `flyway migrate` after confirmation." In test environments it runs automatically (Testcontainers). This is sufficient — Flyway doesn't support conditional execution natively.
