# Architecture Patterns

**Project:** Payam v9 — Ledger Disbursement Support
**Researched:** 2026-04-21
**Confidence:** HIGH — analysis based on direct codebase inspection

---

## Recommended Architecture

The v9 change is a targeted vertical slice through the existing `transaction/contract -> service`
and `orange/service` layers. No new modules, no new tables — only a new nullable column on
`main.transaction` and a constraint replacement on `main.ledger_entry`.

Full call chain after the change:

```
WebhookTransitionService (COLLECTION)
          |
          v (after SUCCESS confirmed)
    LedgerService.postEntry(txId, tenantId, LedgerPosting.collection(amount, currency))
          |
          v
    [DEBIT CUSTOMER_WALLET, CREDIT PROVIDER_CLEARING]  — 2 rows

OrangeMoneyPort.initiateCashout (DISBURSEMENT)
          |
          v (after provider confirms SUCCESS)
    LedgerService.postEntry(txId, tenantId, LedgerPosting.disbursement(principal, fee, currency))
          |
          v
    [DEBIT MERCHANT_WALLET (gross), CREDIT CUSTOMER_WALLET (principal), CREDIT PROVIDER_FEE (fee)]  — 3 rows

          |
          v (both flows converge here)
    LedgerEntryRepository.saveAll(entries)
          |
          v
    main.ledger_entry  (append-only; balanced by V25 constraint trigger)
```

`Transaction.flow` (Flyway V25) sits on `main.transaction` so reconciliation queries can
filter by flow without inferring intent from account codes.

---

## Component Boundaries

| Component | Layer | Change | Responsibility in v9 |
|-----------|-------|--------|----------------------|
| `LedgerFlow` enum | `transaction/contract` | NEW | Two-value enum: COLLECTION, DISBURSEMENT |
| `LedgerPosting` record | `transaction/contract` | NEW | Intent DTO; callers never reference account codes |
| `LedgerService` | `transaction/service` | MODIFIED | Add `postEntry(txId, tenantId, LedgerPosting)` overload; route to flow-specific private builders |
| `WebhookTransitionService` | `webhook/service` | MODIFIED | Update collection call-site from 4-arg to `LedgerPosting.collection()` |
| `Transaction` entity | `transaction/repo` | MODIFIED | Add `flow` field with `@Enumerated(STRING)`, nullable |
| Flyway V25 | `db/migration` | NEW | Add `flow` column; drop old unique constraint; add balance-check trigger |
| `OrangeMoneyPort.initiateCashout()` | `orange/service` | MODIFIED | Replace stub; inject `LedgerService`; call `LedgerPosting.disbursement()` after provider confirms success |
| `LedgerServiceIT` | test | MODIFIED | Update existing 4-arg test calls; add disbursement-specific IT cases |

No new modules. No new packages. No changes to `PaymentOrchestrator` for the collection path.

---

## Data Flow

### Collection (existing call-site updated)

```
WebhookTransitionService.applyFinalTransition()          @Transactional(REQUIRES_NEW)
  -> ledgerService.postEntry(txId, tenantId,
         LedgerPosting.collection(amount, currency))
       -> 2 rows: DEBIT CUSTOMER_WALLET, CREDIT PROVIDER_CLEARING
       -> saveAll within the same REQUIRES_NEW transaction
```

The collection path today passes 4 raw args. After v9 the call becomes `LedgerPosting.collection(...)`.
No behavioral change — the same 2 rows result.

### Disbursement (new)

```
OrangeMoneyPort.initiateCashout(cmd)         NOT @Transactional (rule: no DB conn during HTTP)
  -> orangeMoneyClient.cashout(token, request)    // provider HTTP call
  -> on HTTP 200 / confirmed success:
       transactionTemplate.execute(() -> {
         ledgerService.postEntry(txId, tenantId,
             LedgerPosting.disbursement(cmd.amount(), resolvedFee, cmd.currency()))
       })
       -> 3 rows: DEBIT MERCHANT_WALLET (gross),
                  CREDIT CUSTOMER_WALLET (principal),
                  CREDIT PROVIDER_FEE (fee)
       -> saveAll inside TransactionTemplate boundary
```

`resolvedFee` is read from `cmd` or from `transaction.getFeeAmount()` which `FeeEvaluationService`
already computed and persisted at orchestration time.

---

## Constraint Conflict Resolution — The Core Problem

### What V23 installed

```sql
UNIQUE (entry_group_id, direction) DEFERRABLE INITIALLY DEFERRED
```

This means at most one DEBIT and one CREDIT row per `entry_group_id`. A disbursement group has
one DEBIT and **two** CREDITs (`CUSTOMER_WALLET` + `PROVIDER_FEE`). Even deferred, the uniqueness
constraint fires at commit on the two CREDIT rows sharing the same group id and direction value —
and it fails.

### Why partial unique index is wrong

Replacing the constraint with `UNIQUE (entry_group_id, direction) WHERE direction = 'DEBIT'`
protects only debits and allows unlimited CREDITs per group. Unbalanced writes would go
undetected. That is the opposite of what LEDGER-01 requires.

### Correct resolution: replace with a deferred balance-check constraint trigger

Drop the per-direction uniqueness constraint entirely. Replace it with a PostgreSQL
`CONSTRAINT TRIGGER ... DEFERRABLE INITIALLY DEFERRED` that asserts at commit:

```
SUM(DEBIT amounts) == SUM(CREDIT amounts)
```

This is the correct double-entry invariant:

| Flow | DEBIT sum | CREDIT sum | Result |
|------|-----------|------------|--------|
| COLLECTION | 500 | 500 | pass |
| DISBURSEMENT (500 + 5 fee) | 505 | 500 + 5 | pass |
| Missing credit row | 500 | 0 or 200 | fail at commit |

The trigger fires AFTER INSERT on each row but is DEFERRED — it checks at transaction commit, not
per-row, so partial groups mid-transaction are never falsely rejected.

---

## Flyway V25 Migration (concrete and safe)

```sql
-- V25: Extend ledger for disbursement flows.
--
-- 1. Replace the per-direction uniqueness constraint (V23) with a balance-check trigger.
--    Rationale: DISBURSEMENT groups have 1 DEBIT + 2 CREDITs; the unique constraint blocks
--    the second CREDIT even at commit time.
--
-- 2. Add Transaction.flow column for reconciliation queries.
--
-- This script is idempotent: DROP CONSTRAINT IF EXISTS, ADD COLUMN IF NOT EXISTS,
-- CREATE OR REPLACE FUNCTION, DROP TRIGGER IF EXISTS.

-- Step 1: Drop V23 uniqueness constraint.
ALTER TABLE main.ledger_entry
    DROP CONSTRAINT IF EXISTS uq_ledger_entry_group_direction;

-- Step 2: Add flow column to transaction (nullable — existing rows are implicitly COLLECTION).
ALTER TABLE main.transaction
    ADD COLUMN IF NOT EXISTS flow VARCHAR(20);

-- Step 3: Add flow column to transaction_aud (Envers parity — Transaction is @Audited).
--   transaction_aud was created in V20. Without this column Hibernate throws a schema
--   validation error on startup when flow is mapped as an @Audited field.
ALTER TABLE main.transaction_aud
    ADD COLUMN IF NOT EXISTS flow VARCHAR(20);

-- Step 4: Create balance-check function.
CREATE OR REPLACE FUNCTION main.check_ledger_group_balance()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    debit_sum  NUMERIC;
    credit_sum NUMERIC;
BEGIN
    SELECT COALESCE(SUM(amount), 0) INTO debit_sum
      FROM main.ledger_entry
     WHERE entry_group_id = NEW.entry_group_id
       AND direction = 'DEBIT';

    SELECT COALESCE(SUM(amount), 0) INTO credit_sum
      FROM main.ledger_entry
     WHERE entry_group_id = NEW.entry_group_id
       AND direction = 'CREDIT';

    -- Only fail when both sides are non-zero (group fully written) and do not balance.
    -- A partial group (one side still zero) is valid mid-transaction.
    IF debit_sum > 0 AND credit_sum > 0 AND debit_sum <> credit_sum THEN
        RAISE EXCEPTION 'LEDGER-01 balance violation: group % debits=% credits=%',
            NEW.entry_group_id, debit_sum, credit_sum;
    END IF;
    RETURN NEW;
END;
$$;

-- Step 5: Attach trigger — deferred so it checks at commit, not per-row insert.
DROP TRIGGER IF EXISTS trg_ledger_balance_check ON main.ledger_entry;

CREATE CONSTRAINT TRIGGER trg_ledger_balance_check
    AFTER INSERT ON main.ledger_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION main.check_ledger_group_balance();
```

### Why no pre-flight DO $$ guard (unlike V23)

V23 needed a guard because it was ADDING a uniqueness constraint that would fail on existing
violations. V25 is DROPPING a constraint and ADDING a trigger. The DROP is idempotent; the
trigger's balance check tolerates any pre-existing data (it only fires on new inserts). No
pre-flight scan is needed.

### Backward compatibility

The `flow` column is nullable. Existing `Transaction` rows remain null. Application code
and reporting queries treat null as COLLECTION:

```sql
-- Reconciliation query — null-safe
COALESCE(t.flow, 'COLLECTION') AS effective_flow
FROM main.transaction t
```

Hibernate entity reads null as `null`; callers use a null-safe getter:

```java
public LedgerFlow getEffectiveFlow() {
    return flow != null ? flow : LedgerFlow.COLLECTION;
}
```

---

## Integration Point: Disbursement Ledger Call

### Do not add it to PaymentOrchestrator.initiate()

`initiate()` handles collection only. It is already complex (fraud scoring, fee evaluation,
idempotency, provider dispatch, state machine). Disbursement has different semantics: no payToken,
different entry direction, fee flows out not in. Branching inside `initiate()` would make ledger
call placement ambiguous and the method harder to follow.

### Wire it inside OrangeMoneyPort.initiateCashout()

Symmetrically to how the collection ledger is called from
`WebhookTransitionService.applyFinalTransition()` — the entity that receives confirmed outcome
from the provider owns the ledger call.

Inject `LedgerService` into `OrangeMoneyPort`:

```java
// Constructor — add to the existing 8-arg constructor
private final LedgerService ledgerService;
```

Inside `initiateCashout()` after the provider confirms success:

```java
// OUTSIDE the provider HTTP call — use TransactionTemplate exactly as persistPayToken() does
transactionTemplate.execute(status -> {
    BigDecimal fee = resolveFee(cmd);    // from cmd or from Transaction.feeAmount
    ledgerService.postEntry(
        cmd.transactionId(),
        cmd.tenantId(),
        LedgerPosting.disbursement(cmd.amount(), fee, cmd.currency())
    );
    return null;
});
```

This keeps the pattern consistent: HTTP never inside `@Transactional`; DB writes via
`TransactionTemplate` after the HTTP call returns.

### resolvefee — sourcing the fee for disbursement

`FeeEvaluationService` already computes and persists `transaction.feeAmount` at
orchestration time (Phase 38 / OPS-04). The `PaymentCommand` record does not currently
carry `feeAmount` directly — it carries `amount` but not fee.

Two options:

1. Load fee from `TransactionRepository` inside `initiateCashout()` using `cmd.transactionId()` —
   one additional DB read inside the `TransactionTemplate` block. Clean but adds a query.

2. Extend `PaymentCommand` with an optional `feeAmount` field populated by the orchestrator before
   dispatch. This avoids the extra query and is the better long-term design.

**Recommendation:** extend `PaymentCommand` with `feeAmount` (nullable `BigDecimal`). The
orchestrator populates it from `FeeEvaluationService.evaluateFee()` before calling `initiateCashout()`,
exactly as it already does for `transaction.feeAmount`. This keeps `initiateCashout()` DB-interaction-free
for the fee lookup.

---

## Patterns to Follow

### Pattern 1: Intent-based ledger posting

Callers pass `LedgerPosting` (flow + amounts). `LedgerService` owns all account code constants.

```java
// contract — caller-facing API
public record LedgerPosting(LedgerFlow flow, BigDecimal principal, BigDecimal fee, String currency) {
    public static LedgerPosting collection(BigDecimal principal, String currency) {
        return new LedgerPosting(LedgerFlow.COLLECTION, principal, BigDecimal.ZERO, currency);
    }
    public static LedgerPosting disbursement(BigDecimal principal, BigDecimal fee, String currency) {
        return new LedgerPosting(LedgerFlow.DISBURSEMENT, principal, fee, currency);
    }
}

// service — account codes private constants; switch on flow
private List<LedgerEntry> entries(String txId, Long tenantId, String groupId, Instant now, LedgerPosting p) {
    return switch (p.flow()) {
        case COLLECTION   -> collectionEntries(txId, tenantId, groupId, now, p);
        case DISBURSEMENT -> disbursementEntries(txId, tenantId, groupId, now, p);
    };
}
```

### Pattern 2: Nullable flow column with COLLECTION default

Add nullable, treat null as COLLECTION in all application and SQL code. No backfill needed.

```java
@Enumerated(EnumType.STRING)
@Column(name = "flow", nullable = true)
private LedgerFlow flow;
```

### Pattern 3: Envers audit table parity

`Transaction` is `@Audited`. Adding `flow` without `@NotAudited` means Envers will try to
write it to `transaction_aud`. V25 must add the column to `transaction_aud` to avoid a
Hibernate schema validation failure. This is the same pattern used in v8 (`platform_config_aud`
corrected in V24 with `ADD COLUMN IF NOT EXISTS`).

### Pattern 4: TransactionTemplate for post-HTTP DB writes

Established rule (PROJECT.md Key Decisions): no `@Transactional` on methods that make provider
HTTP calls. The ledger write goes inside `TransactionTemplate.execute()` after the HTTP call
returns — not wrapping it.

### Pattern 5: Backward-compatible LedgerService method signature

Keep the old 4-arg `postEntry(String, Long, BigDecimal, String)` as a deprecated delegate for
one release, then remove after all callers migrated. Avoids a big-bang refactor if other callers
are added by concurrent work.

```java
/** @deprecated Use {@link #postEntry(String, Long, LedgerPosting)} */
@Deprecated
@Transactional
public void postEntry(String transactionId, Long tenantId, BigDecimal amount, String currency) {
    postEntry(transactionId, tenantId, LedgerPosting.collection(amount, currency));
}
```

In v9, the only production call-site (`WebhookTransitionService`) is migrated in the same
milestone, so the deprecated delegate can be removed at the end of v9.

---

## Anti-Patterns to Avoid

### Anti-Pattern 1: flow column NOT NULL without backfill

Adding `flow VARCHAR(20) NOT NULL` fails on any table with existing rows. Always `NULLABLE` first.
The V25 script uses `ADD COLUMN IF NOT EXISTS flow VARCHAR(20)` with no NOT NULL constraint.

### Anti-Pattern 2: Leaking account codes to callers

Any class that references `"MERCHANT_WALLET"`, `"PROVIDER_FEE"`, or `"CUSTOMER_WALLET"` outside
`LedgerService` creates coupling that will require shotgun surgery on future account structure
changes. All account code logic stays inside `LedgerService` as private constants.

### Anti-Pattern 3: Partial unique index instead of balance trigger

Replacing `UNIQUE(entry_group_id, direction)` with a direction-conditional partial index
protects only one side of the ledger and allows unbounded unbalanced writes on the other.
Use the balance-check trigger (V25) instead.

### Anti-Pattern 4: @Transactional on initiateCashout()

The project rule is explicit: do not hold a DB connection during provider HTTP. The ledger
write must be in a `TransactionTemplate` block that executes after the HTTP call returns,
not wrapping it.

### Anti-Pattern 5: Adding disbursement branching inside PaymentOrchestrator.initiate()

The orchestrator is not `@Transactional` and manages complex control flow (fraud, fees,
idempotency, state machine). Ledger calls belong at the service that confirms the outcome,
not in the top-level orchestrator.

---

## Build Order (Dependency-Respecting)

| Step | Artifact | Depends On | Notes |
|------|----------|------------|-------|
| 1 | `LedgerFlow` enum | Nothing | Pure enum; compiles standalone |
| 2 | `LedgerPosting` record | `LedgerFlow` | Contract record; no Spring deps |
| 3 | Flyway V25 | Running DB | DDL only; safe before Java changes (backward-compatible schema) |
| 4 | `Transaction.flow` field + `getEffectiveFlow()` | V25 (schema) + `LedgerFlow` (type) | Entity change |
| 5 | `LedgerService` new overload + private builders | `LedgerPosting`, `LedgerFlow` | Old 4-arg deprecated here |
| 6 | `WebhookTransitionService` collection call-site migration | Step 5 | One call-site; remove 4-arg usage |
| 7 | `PaymentCommand` gain optional `feeAmount` field | Nothing | Extends existing record |
| 8 | `OrangeMoneyPort.initiateCashout()` implementation | Steps 5, 7 | Replaces UnsupportedOperationException stub |
| 9 | Unit tests: `LedgerServiceTest` | Steps 1-5 | COLLECTION + DISBURSEMENT builder coverage |
| 10 | Updated `LedgerServiceIT` | Steps 5-6 | Remove 4-arg calls; add flow-specific IT assertions |
| 11 | `OrangeMoneyPortIT` for disbursement path | Steps 5-8 | WireMock stub for cashout; assert 3 ledger rows |

Steps 1-2 compile without touching any infrastructure. Step 3 is safe to run before the
Java changes — the nullable column and removed constraint are backward-compatible with
existing application code already deployed. Steps 9-11 run only after all prior steps compile.

---

## Scalability Considerations

| Concern | Collection (v1-v8) | After v9 (disbursement) |
|---------|-------------------|------------------------|
| Rows per transaction in ledger_entry | 2 | 2 (collection) or 3 (disbursement) |
| Constraint check cost | Deferred unique lookup O(1) | Deferred balance trigger: 2 aggregate queries per INSERT — negligible at Cameroon volumes |
| Reconciliation query | Must infer flow from account_code | Filter on `transaction.flow` directly |
| Index coverage | `idx_ledger_transaction_id`, `idx_ledger_entry_group_id` | Unchanged; both cover all query patterns |
| Envers audit volume | 1 revision per tx state change | +1 column in transaction_aud; no volume change |

No new indexes needed for v9. If `flow` column becomes a high-frequency filter in reporting
queries, add `CREATE INDEX idx_transaction_flow ON main.transaction(flow)` in a later migration.

---

## New vs Modified — Complete Map

| File | Status | Build Step |
|------|--------|------------|
| `transaction/contract/LedgerFlow.java` | NEW | 1 |
| `transaction/contract/LedgerPosting.java` | NEW | 2 |
| `db/migration/V25__ledger_disbursement.sql` | NEW | 3 |
| `transaction/repo/Transaction.java` | MODIFIED — add `flow` field + `getEffectiveFlow()` | 4 |
| `transaction/service/LedgerService.java` | MODIFIED — new overload, private builders, deprecate 4-arg | 5 |
| `webhook/service/WebhookTransitionService.java` | MODIFIED — update collection call-site | 6 |
| `common/payment/PaymentCommand.java` | MODIFIED — add optional `feeAmount` field | 7 |
| `orange/service/OrangeMoneyPort.java` | MODIFIED — implement `initiateCashout()`, inject `LedgerService` | 8 |
| `transaction/LedgerServiceIT.java` | MODIFIED — update 4-arg calls; add disbursement cases | 10 |
| `orange/OrangeMoneyPortIT.java` (or new) | NEW/MODIFIED — disbursement ledger integration test | 11 |

---

## Sources

- Direct inspection: `transaction/service/LedgerService.java` (current 4-arg postEntry signature)
- Direct inspection: `transaction/repo/LedgerEntry.java` (@Immutable, @Tsid, column layout)
- Direct inspection: `transaction/repo/Transaction.java` (@Audited, @NotAudited precedents, existing fields)
- Direct inspection: `db/migration/V23__ledger_group_constraint.sql` (constraint being replaced + rationale)
- Direct inspection: `db/migration/V3__transaction_schema.sql`, `V4__ledger_schema.sql` (base schema)
- Direct inspection: `webhook/service/WebhookTransitionService.java` (sole production ledger call-site)
- Direct inspection: `orange/service/OrangeMoneyPort.java` (disbursement stub, TransactionTemplate usage pattern)
- Direct inspection: `payment/service/PaymentOrchestrator.java` (no-@Transactional rule, TransactionTemplate usage)
- Direct inspection: `transaction/LedgerServiceIT.java` (existing test structure; 4-arg calls to migrate)
- Direct inspection: `orange/infrastructure/OrangeMoneyClient.java` (cashout HTTP method exists)
- PROJECT.md Key Decisions: no @Transactional on orchestrator; TransactionTemplate pattern; Flyway migration patterns
- requirements/payam-ledger.md: LedgerFlow/LedgerPosting spec; open questions on zero-fee and account code separation
