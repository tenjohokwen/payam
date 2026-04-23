# Phase 47: Contract Types + LedgerService Rewrite - Research

**Researched:** 2026-04-21
**Domain:** Java 17 / Spring Boot — double-entry ledger contract types and service rewrite
**Confidence:** HIGH

---

## Summary

Phase 47 introduces two new types (`LedgerFlow` enum, `LedgerPosting` record) into the `transaction/contract` package and rewrites `LedgerService.postEntry()` to accept a `LedgerPosting` value object instead of four loose arguments. The schema foundation is already in place from Phase 46: V25 migration added the `flow` column to `main.transaction` and `main.transaction_aud`, dropped the V23 unique constraint, replaced it with a balance-check trigger, and relaxed the `amount >= 0` check to support zero-fee disbursements.

The existing `postEntry(String, Long, BigDecimal, String)` signature has exactly two call sites: the production `WebhookTransitionService` and the test `LedgerBalanceGuardTest` (plus `LedgerServiceIT`). All three must be migrated atomically — compiling with the old signature gone while one call site still uses it will break `mvn verify`. The migration sequence is: (1) add new types, (2) rewrite `LedgerService` to new 3-arg signature, (3) update `WebhookTransitionService` call site, (4) update test call sites, in a single phase.

The `Transaction` entity uses Lombok `@SuperBuilder` + `@Audited` (Hibernate Envers). Adding a nullable `flow` field requires a setter-less approach: the field needs no explicit setter because it will default null for existing records, and `getEffectiveFlow()` returns `COLLECTION` when null. The `@NotAudited` annotation is NOT needed — the `flow` column already exists in `transaction_aud` from V25.

**Primary recommendation:** Implement all changes in one commit wave: new contract types first, then rewrite `LedgerService`, then migrate all call sites (production + test). Do not leave any intermediate state where the 4-arg signature is gone but call sites are not yet migrated.

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| CONTRACT-01 | `LedgerFlow` enum with `COLLECTION` and `DISBURSEMENT` values in `transaction/contract` package | Package exists, pattern established by `LedgerDirection` enum in same package |
| CONTRACT-02 | `LedgerPosting` record with `flow`, `principal`, `fee`, `currency` fields; compact constructor validates `principal > 0`, `fee >= 0`, non-null `flow` and `currency` | Java 17 records with compact constructors are standard; `CachedResponse` in same package is an existing record example |
| CONTRACT-03 | `LedgerPosting.collection(principal, currency)` factory — COLLECTION posting with `fee = BigDecimal.ZERO` | Static factory method pattern on Java records |
| CONTRACT-04 | `LedgerPosting.disbursement(principal, fee, currency)` factory — DISBURSEMENT posting | Static factory method pattern on Java records |
| SERVICE-01 | `LedgerService.postEntry(txId, tenantId, LedgerPosting)` routes via `switch(posting.flow())` to flow-specific private builders; old 4-arg signature removed | Current 4-arg signature fully documented; 2 production + 2 test call sites must be migrated |
| SERVICE-02 | COLLECTION builder: 2 entries — DEBIT `CUSTOMER_WALLET` (principal) + CREDIT `PROVIDER_CLEARING` (principal) | Matches existing behavior exactly; constants already inline in current code |
| SERVICE-03 | DISBURSEMENT builder: 3 entries — DEBIT `MERCHANT_WALLET` (gross=principal+fee) + CREDIT `CUSTOMER_WALLET` (principal) + CREDIT `PROVIDER_FEE` (fee) | V25 trigger supports 3-row groups; `LedgerConstraintIT.threeEntryDisbursementGroup_succeeds()` already validates this at DB level |
| SERVICE-04 | Account code strings as private constants inside `LedgerService`; no external references | `LedgerBalanceGuardTest` and `LedgerVerifier` currently reference account code strings directly — these must be updated (tests use string literals not constants) |
| SERVICE-05 | `WebhookTransitionService` call site migrated to `LedgerPosting.collection(amount, currency)`; 4-arg method deleted | Single call site at `WebhookTransitionService.java:97-101` fully identified |
| SERVICE-06 | `Transaction` entity gains nullable `flow` field with `@Enumerated(STRING)`; `getEffectiveFlow()` returns `LedgerFlow.COLLECTION` when null | `Transaction` uses `@Audited`+`@SuperBuilder`; column exists in DB from V25; no `@NotAudited` needed |
</phase_requirements>

---

## Architecture Patterns

### Established Pattern: `transaction/contract` Package

The `transaction/contract` package contains contract layer types used across module boundaries. Existing members:
- `LedgerDirection` enum (DEBIT, CREDIT) — simple 2-value enum, no body
- `TransactionStatus` enum — enum with abstract method and inner logic
- `TransactionEventType` enum — simple values
- `CachedResponse` record — value object with factory methods and validation

`LedgerFlow` follows the `LedgerDirection` pattern (simple enum). `LedgerPosting` follows the `CachedResponse` pattern (record with factory methods).

### Recommended Project Structure (no change)

The phase adds files to existing packages only:

```
src/main/java/com/softropic/payam/
├── transaction/
│   ├── contract/
│   │   ├── LedgerDirection.java        (existing)
│   │   ├── LedgerFlow.java             (NEW — Phase 47)
│   │   ├── LedgerPosting.java          (NEW — Phase 47)
│   │   ├── CachedResponse.java         (existing)
│   │   ├── TransactionStatus.java      (existing)
│   │   └── TransactionEventType.java   (existing)
│   ├── repo/
│   │   └── Transaction.java            (MODIFIED — add flow field + getEffectiveFlow())
│   └── service/
│       └── LedgerService.java          (MODIFIED — rewrite postEntry)
└── webhook/
    └── service/
        └── WebhookTransitionService.java  (MODIFIED — migrate call site)
```

### Pattern 1: Java 17 Record with Compact Constructor Validation

```java
// Source: Java 17 language specification — compact constructor
package com.softropic.payam.transaction.contract;

import java.math.BigDecimal;

public record LedgerPosting(
    LedgerFlow flow,
    BigDecimal principal,
    BigDecimal fee,
    String currency
) {
    // Compact constructor: all fields are already assigned before this body runs
    public LedgerPosting {
        if (flow == null)      throw new IllegalArgumentException("flow must not be null");
        if (currency == null)  throw new IllegalArgumentException("currency must not be null");
        if (principal == null || principal.compareTo(BigDecimal.ZERO) <= 0)
            throw new IllegalArgumentException("principal must be > 0");
        if (fee == null || fee.compareTo(BigDecimal.ZERO) < 0)
            throw new IllegalArgumentException("fee must be >= 0");
    }

    public static LedgerPosting collection(BigDecimal principal, String currency) {
        return new LedgerPosting(LedgerFlow.COLLECTION, principal, BigDecimal.ZERO, currency);
    }

    public static LedgerPosting disbursement(BigDecimal principal, BigDecimal fee, String currency) {
        return new LedgerPosting(LedgerFlow.DISBURSEMENT, principal, fee, currency);
    }
}
```

### Pattern 2: Switch-routed LedgerService

```java
// Source: REQUIREMENTS.md SERVICE-01, SERVICE-02, SERVICE-03, SERVICE-04
@Transactional
public void postEntry(String transactionId, Long tenantId, LedgerPosting posting) {
    List<LedgerEntry> entries = switch (posting.flow()) {
        case COLLECTION   -> buildCollectionEntries(transactionId, tenantId, posting);
        case DISBURSEMENT -> buildDisbursementEntries(transactionId, tenantId, posting);
    };
    ledgerEntryRepository.saveAll(entries);
}

private List<LedgerEntry> buildCollectionEntries(
        String transactionId, Long tenantId, LedgerPosting posting) {
    String groupId = UUID.randomUUID().toString();
    Instant now = Instant.now();
    return List.of(
        entry(transactionId, groupId, tenantId, LedgerDirection.DEBIT,
              CUSTOMER_WALLET, posting.principal(), posting.currency(), now),
        entry(transactionId, groupId, tenantId, LedgerDirection.CREDIT,
              PROVIDER_CLEARING, posting.principal(), posting.currency(), now)
    );
}

private List<LedgerEntry> buildDisbursementEntries(
        String transactionId, Long tenantId, LedgerPosting posting) {
    String groupId = UUID.randomUUID().toString();
    Instant now = Instant.now();
    BigDecimal gross = posting.principal().add(posting.fee());
    return List.of(
        entry(transactionId, groupId, tenantId, LedgerDirection.DEBIT,
              MERCHANT_WALLET, gross, posting.currency(), now),
        entry(transactionId, groupId, tenantId, LedgerDirection.CREDIT,
              CUSTOMER_WALLET, posting.principal(), posting.currency(), now),
        entry(transactionId, groupId, tenantId, LedgerDirection.CREDIT,
              PROVIDER_FEE, posting.fee(), posting.currency(), now)
    );
}
```

Note: extract a private `entry(...)` helper to avoid repetition across the two builders.

### Pattern 3: Transaction Entity Flow Field

```java
// Source: Transaction.java existing pattern (txStatus field for reference)
import com.softropic.payam.transaction.contract.LedgerFlow;

@Enumerated(EnumType.STRING)
@Column(name = "flow")   // nullable — no nullable=false; column exists from V25
private LedgerFlow flow;

/**
 * Returns the effective ledger flow for this transaction.
 * Pre-v9 rows have null flow — treated as COLLECTION per project decision.
 */
public LedgerFlow getEffectiveFlow() {
    return flow != null ? flow : LedgerFlow.COLLECTION;
}
```

No setter needed for now — Phase 47 only adds the field; population is Phase 49's responsibility.

### Anti-Patterns to Avoid

- **Keeping the 4-arg overload as deprecated:** SERVICE-01 explicitly requires deletion. A deprecated alias would continue to compile but violates the requirement and risks CASHOUT-02 calling the wrong signature in Phase 49.
- **Making `flow` `@NotAudited`:** The `transaction_aud` table already has the `flow` column from V25 migration. `@NotAudited` would prevent Hibernate Envers from writing the flow value to the audit table.
- **Placing `LedgerPosting` in the service package:** The contract layer (`transaction/contract`) is the correct location for types shared across module boundaries, following the established pattern for `LedgerDirection`, `TransactionStatus`, etc.
- **Adding `@Column(nullable = false)` to `Transaction.flow`:** The column is nullable by design — pre-v9 rows have `NULL` flow, interpreted as COLLECTION by `getEffectiveFlow()`.
- **Using `@Builder.Default` for `flow`:** Unlike `txStatus`, `flow` should stay null for pre-v9 rows; `@Builder.Default = LedgerFlow.COLLECTION` would write `COLLECTION` for all newly created transactions including those created before the cashout path is wired (Phase 49). Leave it null.

---

## Current State Inventory

### Files to Modify

| File | Current State | Change Required |
|------|---------------|-----------------|
| `LedgerService.java` | `postEntry(String, Long, BigDecimal, String)` — 59 lines | Rewrite: new 3-arg signature, switch routing, private builders, 4 account-code constants |
| `Transaction.java` | No `flow` field | Add `LedgerFlow flow` field + `getEffectiveFlow()` method |
| `WebhookTransitionService.java` | Calls `ledgerService.postEntry(tx.getTransactionId(), tx.getTenantId(), tx.getAmount(), tx.getCurrency())` at line 97-101 | Change to `ledgerService.postEntry(tx.getTransactionId(), tx.getTenantId(), LedgerPosting.collection(tx.getAmount(), tx.getCurrency()))` |

### Files to Create

| File | Location |
|------|----------|
| `LedgerFlow.java` | `transaction/contract/` |
| `LedgerPosting.java` | `transaction/contract/` |

### Test Files That Must Be Updated

The 4-arg `postEntry` call appears in tests — these must be updated or they will fail to compile:

| Test File | Line | Current Call | Required Change |
|-----------|------|--------------|-----------------|
| `LedgerBalanceGuardTest.java` | 37 | `service.postEntry("txn-ledger-001", 1L, amount, "XAF")` | `service.postEntry("txn-ledger-001", 1L, LedgerPosting.collection(amount, "XAF"))` |
| `LedgerServiceIT.java` | 113 | `ledgerService.postEntry(transactionId, tenantId, new BigDecimal("500.00"), "XAF")` | `ledgerService.postEntry(transactionId, tenantId, LedgerPosting.collection(new BigDecimal("500.00"), "XAF"))` |
| `LedgerServiceIT.java` | 139 | `ledgerService.postEntry(transactionId, tenantId, new BigDecimal("500.00"), "XAF")` | Same migration as above |

`LedgerBalanceGuardTest` also asserts `debit.getAccountCode()).isEqualTo("CUSTOMER_WALLET")` and `credit.getAccountCode()).isEqualTo("PROVIDER_CLEARING")` — these assertions test behavior, not the constants, so they remain correct after the SERVICE-04 constant extraction (the strings themselves don't change).

`LedgerVerifier` defines its own constants `DEBIT_ACCOUNT = "CUSTOMER_WALLET"` and `CREDIT_ACCOUNT = "PROVIDER_CLEARING"`. SERVICE-04 says callers must not reference account codes directly — but `LedgerVerifier` is a test helper, not a production caller. No change required to satisfy SERVICE-04. The requirement is about production callers.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Validation in record | Custom validator class | Compact constructor (`public LedgerPosting { ... }`) | Java 17 native; called on every construction including factory methods |
| Enum switch | if-else chain | `switch (posting.flow())` with exhaustive cases | Compiler enforces exhaustiveness; adding a third flow later causes compile error |
| Entry group ID | Timestamp-based ID | `UUID.randomUUID().toString()` | Already established pattern in `LedgerService` |
| Ledger timestamp | `LocalDateTime` | `Instant.now()` | Already established pattern; `createdDate` column is `TIMESTAMP` stored as UTC |

---

## Common Pitfalls

### Pitfall 1: Partial Signature Migration Breaks Compile
**What goes wrong:** Deleting the 4-arg `postEntry` before all call sites are updated causes compilation failure, blocking `mvn verify`.
**Why it happens:** `LedgerBalanceGuardTest` and `LedgerServiceIT` both call the old 4-arg signature directly. They are not caught by IDE warnings if only production files are checked.
**How to avoid:** Update all three call sites (production + 2 test files) in the same commit that deletes the old signature.
**Warning signs:** `error: no suitable method found for postEntry(String,Long,BigDecimal,String)` in test compilation output.

### Pitfall 2: `@NotAudited` on `Transaction.flow` Would Break Envers
**What goes wrong:** If `flow` is annotated `@NotAudited`, Hibernate Envers will not attempt to write it to `transaction_aud`. However, since V25 added the `flow` column to `transaction_aud`, omitting `@NotAudited` is correct — Envers will write the value naturally.
**Why it happens:** Previous `feeAmount` and `feeRuleId` fields used `@NotAudited` because V14 only added columns to `main.transaction`, not `transaction_aud`. Phase 47 is different — V25 added `flow` to BOTH tables.
**How to avoid:** Do not add `@NotAudited` to `flow`. The column exists in `transaction_aud` from V25.
**Warning signs:** Envers test failures or missing `flow` values in audit rows.

### Pitfall 3: DISBURSEMENT Balance Violation at DB Level
**What goes wrong:** The V25 `trg_ledger_balance_check` trigger fires `AFTER INSERT` (DEFERRABLE INITIALLY DEFERRED) and asserts `SUM(DEBIT) == SUM(CREDIT)` per `entry_group_id` at commit. If the 3 DISBURSEMENT entries don't use the same `groupId`, each entry is evaluated individually as an unbalanced singleton group.
**Why it happens:** Each entry inserted with a different `groupId` forms its own group and fails the balance check immediately.
**How to avoid:** The `buildDisbursementEntries` builder must generate a single `groupId` shared across all 3 entries — consistent with the existing collection builder pattern.
**Warning signs:** `JpaSystemException` wrapping `PSQLException: Ledger balance violation` during IT.

### Pitfall 4: `BigDecimal.ZERO` vs `new BigDecimal("0")` in Record Validation
**What goes wrong:** `BigDecimal.ZERO.compareTo(value) < 0` is the correct fee check (`fee >= 0`), but `fee.equals(BigDecimal.ZERO)` fails for `new BigDecimal("0.00")` due to scale differences.
**Why it happens:** `BigDecimal.equals()` is scale-sensitive; `compareTo()` is not.
**How to avoid:** Use `fee.compareTo(BigDecimal.ZERO) < 0` in the compact constructor validation.
**Warning signs:** `IllegalArgumentException` thrown for valid `fee = 0.00` in disbursement factory.

### Pitfall 5: `Transaction.flow` Field and `@SuperBuilder`
**What goes wrong:** `Transaction` uses Lombok `@SuperBuilder`. Adding a new field without `@Builder.Default` means the builder will set it to `null` by default — which is the intended behavior. However, if someone adds `@Builder.Default` with a non-null value, all newly created transactions will have a non-null flow, which is only correct after Phase 49 wires disbursement creation.
**Why it happens:** Intent confusion between "default for builder" and "default for display/logic".
**How to avoid:** No `@Builder.Default` on `flow`. The null-interpretation logic belongs in `getEffectiveFlow()`, not in the builder default.

---

## Code Examples

### `LedgerFlow.java` (full file)

```java
// Source: mirrors existing LedgerDirection enum in same package
package com.softropic.payam.transaction.contract;

public enum LedgerFlow {
    COLLECTION,
    DISBURSEMENT
}
```

### `WebhookTransitionService` call site migration

```java
// Before (line 97-101):
ledgerService.postEntry(
    tx.getTransactionId(),
    tx.getTenantId(),
    tx.getAmount(),
    tx.getCurrency()
);

// After:
ledgerService.postEntry(
    tx.getTransactionId(),
    tx.getTenantId(),
    LedgerPosting.collection(tx.getAmount(), tx.getCurrency())
);
// Import: com.softropic.payam.transaction.contract.LedgerPosting
```

### `Transaction.java` additions

```java
// New import (already has EnumType import from txStatus and provider fields):
import com.softropic.payam.transaction.contract.LedgerFlow;

// New field (after feeRuleId field, before applyTransition method):
@Enumerated(EnumType.STRING)
@Column(name = "flow")
private LedgerFlow flow;

// New method:
public LedgerFlow getEffectiveFlow() {
    return flow != null ? flow : LedgerFlow.COLLECTION;
}
```

---

## Environment Availability

Step 2.6: SKIPPED — Phase 47 is a pure Java source code change. No external tools, services, or CLIs required beyond the project's standard Maven build.

---

## Validation Architecture

`workflow.nyquist_validation` is absent from `.planning/config.json` — treated as enabled.

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (Jupiter) + AssertJ + Mockito |
| Config file | `pom.xml` (Maven Surefire + Failsafe) |
| Quick run command | `mvn test -pl . -Dtest=LedgerBalanceGuardTest` |
| Full suite command | `mvn verify` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| CONTRACT-01 | `LedgerFlow` enum compiles, values correct | unit (compile gate) | `mvn test-compile` | ❌ Wave 0 (new file) |
| CONTRACT-02 | `LedgerPosting` compact constructor rejects invalid args | unit | `mvn test -Dtest=LedgerBalanceGuardTest` | ❌ Wave 0 (tests for this are Phase 48 TEST-04, but compile must pass) |
| CONTRACT-03 | `collection()` factory sets fee=ZERO and flow=COLLECTION | unit | `mvn test -Dtest=LedgerBalanceGuardTest` | ❌ Wave 0 (new factory used by migrated test) |
| CONTRACT-04 | `disbursement()` factory sets flow=DISBURSEMENT | unit | `mvn test -Dtest=LedgerBalanceGuardTest` | ❌ Wave 0 (new factory) |
| SERVICE-01 | New 3-arg signature compiles, old 4-arg removed | unit (compile gate) | `mvn test-compile` | ✅ (migrated LedgerBalanceGuardTest) |
| SERVICE-02 | COLLECTION → 2 balanced entries, correct account codes | unit | `mvn test -Dtest=LedgerBalanceGuardTest` | ✅ (must migrate call site) |
| SERVICE-03 | DISBURSEMENT → 3 entries, gross = principal + fee | integration | `mvn verify -Dtest=LedgerConstraintIT` | ✅ (threeEntryDisbursementGroup_succeeds already exists at DB level) |
| SERVICE-04 | No external account code string references | code review / compile | `mvn compile` | ✅ (LedgerVerifier uses own string literals — acceptable) |
| SERVICE-05 | WebhookTransitionService uses LedgerPosting.collection | compile gate | `mvn compile` | ✅ (migrate call site) |
| SERVICE-06 | Transaction.flow nullable, getEffectiveFlow() returns COLLECTION for null | unit | `mvn test -Dtest=LedgerConstraintIT#flowColumn_existsAndIsNullable` | ✅ (schema test already exists; method tested via compile) |

### Sampling Rate

- **Per task commit:** `mvn test -Dtest=LedgerBalanceGuardTest,LedgerServiceIT`
- **Per wave merge:** `mvn verify`
- **Phase gate:** `mvn verify` green before marking phase complete

### Wave 0 Gaps

- [ ] `LedgerFlow.java` must be created before any other file in this phase references it
- [ ] `LedgerPosting.java` must be created before `LedgerService` or `WebhookTransitionService` are modified
- No test framework installation needed — existing infrastructure is sufficient

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `postEntry(txId, tenantId, amount, currency)` — 4 loose args | `postEntry(txId, tenantId, LedgerPosting)` — typed value object | Phase 47 | All callers must migrate; type safety enforced by compiler |
| Collection-only ledger (2 entries always) | Flow-routed ledger (2 entries COLLECTION, 3 entries DISBURSEMENT) | Phase 47 | DB trigger already supports both; `LedgerConstraintIT` already validates 3-entry groups |
| `uq_ledger_entry_group_direction` unique constraint | `trg_ledger_balance_check` deferrable trigger | Phase 46 (V25) | 3-entry groups now valid; trigger fires at commit not per-row |

---

## Open Questions

1. **Should `Transaction.flow` have a setter for Phase 47?**
   - What we know: Phase 47 only adds the field; Phase 49 (CASHOUT-01/CASHOUT-02) is where disbursement transactions are created with `flow = DISBURSEMENT`.
   - What's unclear: Whether Phase 49 will set the field via entity builder or via a setter.
   - Recommendation: Add no setter in Phase 47. Phase 49 will use the `@SuperBuilder` with `.flow(LedgerFlow.DISBURSEMENT)` in the builder chain. This avoids accidental mutation of the field before the cashout path is ready.

2. **Does `LedgerBalanceGuardTest` need a disbursement case in Phase 47?**
   - What we know: TEST-05 (`LedgerBalanceGuardTest` updated with disbursement case) is assigned to Phase 48, not Phase 47.
   - What's unclear: Whether the compilation migration of the existing test is enough for Phase 47's `mvn verify` gate.
   - Recommendation: Only migrate the existing call site to `LedgerPosting.collection()`. Do not add disbursement test cases — that is Phase 48 scope.

---

## Sources

### Primary (HIGH confidence)
- Direct source code reading — `LedgerService.java`, `WebhookTransitionService.java`, `Transaction.java`, `LedgerEntry.java`, `LedgerDirection.java`, `CachedResponse.java`, `TransactionStatus.java`
- `V25__ledger_disbursement_schema.sql` — confirmed schema state post-Phase 46
- `LedgerBalanceGuardTest.java`, `LedgerServiceIT.java`, `LedgerConstraintIT.java`, `LedgerVerifier.java`, `LedgerDoubleEntryTest.java` — confirmed test baseline and exact call sites
- `REQUIREMENTS.md` v9 — CONTRACT-01..04, SERVICE-01..06 exact specs
- `STATE.md` — confirmed decisions carried forward from v8

### Secondary (MEDIUM confidence)
- Java 17 language specification — compact constructor behavior on records (training knowledge, cross-verified with code pattern in `CachedResponse.java`)

### Tertiary (LOW confidence)
- None.

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — Java 17 records, Spring Boot JPA, Lombok `@SuperBuilder` all confirmed from existing codebase
- Architecture: HIGH — all patterns derived from direct source reading
- Pitfalls: HIGH — derived from actual code state and V25 SQL trigger semantics

**Research date:** 2026-04-21
**Valid until:** 2026-05-21 (stable domain)

---

## RESEARCH COMPLETE
