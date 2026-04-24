# Ledger Service — Disbursement Support

## Context

The current `LedgerService.postEntry()` hard-codes the double-entry pattern for a single flow: a customer paying into the system. It always posts:

| Direction | Account Code        | Amount    |
|-----------|---------------------|-----------|
| DEBIT     | `CUSTOMER_WALLET`   | principal |
| CREDIT    | `PROVIDER_CLEARING` | principal |

This models money moving **customer → system** (collection). It does not cover the inverse: **system → customer** (disbursement/cashout), where the merchant wallet is debited the gross amount (principal + provider fee) and the customer receives only the principal.

**Example — Orange Money cashout of 500 XAF:**
- Orange charges 1% (5 XAF) on the disbursement.
- Our merchant wallet is debited **505** (principal + fee).
- The customer receives **500**.
- Orange retains **5** as their service fee.

The ledger must represent this correctly without leaking account-code knowledge to callers.

---

## Goals

1. Support disbursement flows where the merchant wallet is debited the gross amount and the provider retains a fee.
2. Keep account codes **private** to `LedgerService` — callers express *intent*, not entry shape.
3. Maintain the existing double-entry invariant: sum of debits == sum of credits within every entry group.
4. Zero changes to the existing collection path — no caller regressions.

---

## Proposed Design

### 1. Introduce `LedgerFlow` enum

```java
// package: com.softropic.payam.transaction.contract

public enum LedgerFlow {
    /** Customer pays into the system (mobile money payment). */
    COLLECTION,

    /** System disburses to a customer (cashout, payout). */
    DISBURSEMENT
}
```

### 2. Introduce `LedgerPosting` record

```java
// package: com.softropic.payam.transaction.contract

/**
 * Describes a money-moving event without referencing account codes.
 * Callers state what happened; LedgerService decides the entries.
 */
public record LedgerPosting(
        LedgerFlow flow,
        BigDecimal principal,   // amount the customer sends or receives
        BigDecimal fee,         // provider fee deducted from merchant wallet (zero for COLLECTION)
        String currency
) {
    public LedgerPosting {
        Objects.requireNonNull(flow);
        Objects.requireNonNull(currency);
        if (principal.compareTo(BigDecimal.ZERO) <= 0)
            throw new IllegalArgumentException("principal must be positive");
        if (fee == null || fee.compareTo(BigDecimal.ZERO) < 0)
            throw new IllegalArgumentException("fee must be non-negative");
    }

    /** Convenience factory for the existing collection path. */
    public static LedgerPosting collection(BigDecimal principal, String currency) {
        return new LedgerPosting(LedgerFlow.COLLECTION, principal, BigDecimal.ZERO, currency);
    }

    /** Factory for a disbursement with a provider fee. */
    public static LedgerPosting disbursement(BigDecimal principal, BigDecimal fee, String currency) {
        return new LedgerPosting(LedgerFlow.DISBURSEMENT, principal, fee, currency);
    }
}
```

### 3. Rewrite `LedgerService.postEntry`

Replace the single four-arg method with one that accepts `LedgerPosting`. The old signature becomes a thin wrapper for backward compatibility during migration, then removed once all callers are updated.

```java
@Service
public class LedgerService {

    // --- account codes (private: callers never touch these) ---
    private static final String CUSTOMER_WALLET   = "CUSTOMER_WALLET";
    private static final String PROVIDER_CLEARING = "PROVIDER_CLEARING";
    private static final String MERCHANT_WALLET   = "MERCHANT_WALLET";
    private static final String PROVIDER_FEE      = "PROVIDER_FEE";

    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerService(LedgerEntryRepository ledgerEntryRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional
    public void postEntry(String transactionId, Long tenantId, LedgerPosting posting) {
        String groupId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        List<LedgerEntry> entries = switch (posting.flow()) {
            case COLLECTION  -> collectionEntries(transactionId, tenantId, groupId, now, posting);
            case DISBURSEMENT -> disbursementEntries(transactionId, tenantId, groupId, now, posting);
        };

        ledgerEntryRepository.saveAll(entries);
    }

    // --- private builders ---

    private List<LedgerEntry> collectionEntries(
            String txId, Long tenantId, String groupId, Instant now, LedgerPosting p) {

        // DEBIT  CUSTOMER_WALLET   principal   (customer's wallet decreases)
        // CREDIT PROVIDER_CLEARING principal   (our clearing account increases)
        return List.of(
                entry(txId, tenantId, groupId, now, LedgerDirection.DEBIT,  CUSTOMER_WALLET,   p.principal(), p.currency()),
                entry(txId, tenantId, groupId, now, LedgerDirection.CREDIT, PROVIDER_CLEARING, p.principal(), p.currency())
        );
    }

    private List<LedgerEntry> disbursementEntries(
            String txId, Long tenantId, String groupId, Instant now, LedgerPosting p) {

        BigDecimal gross = p.principal().add(p.fee());

        // DEBIT  MERCHANT_WALLET   gross       (our wallet decreases by principal + fee)
        // CREDIT CUSTOMER_WALLET   principal   (customer's wallet increases by principal)
        // CREDIT PROVIDER_FEE      fee         (Orange/provider retains fee)
        //
        // Invariant: gross == principal + fee → debits == credits ✓
        return List.of(
                entry(txId, tenantId, groupId, now, LedgerDirection.DEBIT,  MERCHANT_WALLET,   gross,       p.currency()),
                entry(txId, tenantId, groupId, now, LedgerDirection.CREDIT, CUSTOMER_WALLET,   p.principal(), p.currency()),
                entry(txId, tenantId, groupId, now, LedgerDirection.CREDIT, PROVIDER_FEE,      p.fee(),     p.currency())
        );
    }

    private LedgerEntry entry(String txId, Long tenantId, String groupId, Instant now,
                              LedgerDirection direction, String accountCode,
                              BigDecimal amount, String currency) {
        return LedgerEntry.builder()
                .transactionId(txId)
                .entryGroupId(groupId)
                .tenantId(tenantId)
                .direction(direction)
                .accountCode(accountCode)
                .amount(amount)
                .currency(currency)
                .createdDate(now)
                .build();
    }
}
```

**Double-entry balance check per flow:**

| Flow          | DEBIT                    | CREDIT                                  | Balance |
|---------------|--------------------------|-----------------------------------------|---------|
| COLLECTION    | CUSTOMER_WALLET (500)    | PROVIDER_CLEARING (500)                 | ✓       |
| DISBURSEMENT  | MERCHANT_WALLET (505)    | CUSTOMER_WALLET (500) + PROVIDER_FEE (5)| ✓       |

### 4. Call-site changes

**Existing collection call** (e.g., in `PaymentOrchestrator` or wherever `postEntry` is invoked after a successful collection):

```java
// Before
ledgerService.postEntry(transactionId, tenantId, amount, currency);

// After — semantically identical, no behaviour change
ledgerService.postEntry(transactionId, tenantId, LedgerPosting.collection(amount, currency));
```

**New disbursement call** (when the orchestrator completes a cashout):

```java
// fee comes from FeeEvaluationService, already stored on Transaction.feeAmount
BigDecimal fee = transaction.getFeeAmount() != null ? transaction.getFeeAmount() : BigDecimal.ZERO;
ledgerService.postEntry(transactionId, tenantId, LedgerPosting.disbursement(principal, fee, currency));
```

The caller does **not** need to know about `MERCHANT_WALLET`, `CUSTOMER_WALLET`, or `PROVIDER_FEE`. All account-code decisions live inside `LedgerService`.

---

## What Does Not Change

- `LedgerEntry` entity — immutable, append-only, no schema changes needed beyond handling a third entry per disbursement group.
- `LedgerEntryRepository` — existing queries (`findByTransactionId`, `findByEntryGroupId`) continue to work; a disbursement group simply returns 3 rows instead of 2.
- `FeeEvaluationService` — fee computation is unchanged; the computed fee flows into `LedgerPosting.disbursement()` the same way it flows into `Transaction.feeAmount` today.
- `TransactionStatus` state machine — unaffected.

---

## Migration Steps

1. Add `LedgerFlow` enum to `transaction/contract`.
2. Add `LedgerPosting` record to `transaction/contract`.
3. Rewrite `LedgerService` as above (the old four-arg `postEntry` can be left as a deprecated delegate for one release if other callers exist outside this codebase, then removed).
4. Update existing collection call-sites to use `LedgerPosting.collection(...)`.
5. Wire `LedgerPosting.disbursement(...)` into the disbursement orchestration path when it is built.
6. Add unit tests covering:
   - `COLLECTION` → exactly 2 entries, balanced, correct account codes.
   - `DISBURSEMENT` with fee > 0 → exactly 3 entries, balanced, gross debit = principal + fee.
   - `DISBURSEMENT` with fee = 0 → 3 entries still created (PROVIDER_FEE entry with amount zero), balance holds.
   - `LedgerPosting` constructor rejects negative principal, negative fee, or null currency.

---

## Open Questions

| # | Question | Recommendation |
|---|----------|----------------|
| 1 | Should a zero-fee disbursement still emit a `PROVIDER_FEE` entry? | Yes — keeps the entry shape uniform and avoids conditional branching in reporting queries. A zero-amount entry is harmless. |
| 2 | Should `MERCHANT_WALLET` and `PROVIDER_CLEARING` share the same account code concept? | No — keep them separate. `PROVIDER_CLEARING` represents inbound settlement; `MERCHANT_WALLET` represents our pre-funded outbound balance. They serve different reconciliation purposes. |
| 3 | Does the `Transaction` entity need a `flow` column? | Recommended. Storing `LedgerFlow` (or a broader `TransactionType`) on the `Transaction` row avoids having to infer intent from account codes during reconciliation and reporting. |
