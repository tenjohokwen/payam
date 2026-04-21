# Requirements — v9 Ledger Disbursement Support

**Milestone:** v9
**Goal:** Extend the double-entry ledger to support disbursement/cashout flows — merchant wallet debited the gross amount, customer credited the principal, provider retains the fee.
**Defined:** 2026-04-21

---

## Milestone Requirements

### SCHEMA — Database Migration

- [ ] **SCHEMA-01**: Flyway V25 drops `uq_ledger_entry_group_direction` constraint and replaces it with a deferrable balance-check trigger asserting `SUM(DEBIT) == SUM(CREDIT)` per entry group at commit
- [ ] **SCHEMA-02**: Flyway V25 includes a pre-flight DO block that verifies no unbalanced entry groups exist before dropping the V23 constraint
- [ ] **SCHEMA-03**: Flyway V25 relaxes `CHECK (amount > 0)` on `ledger_entry.amount` to `CHECK (amount >= 0)` to allow zero-amount `PROVIDER_FEE` entries in zero-fee disbursements
- [ ] **SCHEMA-04**: Flyway V25 adds nullable `flow VARCHAR(20)` column to `main.transaction` and `main.transaction_aud` (Envers AUD parity)

### CONTRACT — New Types in `transaction/contract`

- [ ] **CONTRACT-01**: `LedgerFlow` enum added with values `COLLECTION` and `DISBURSEMENT`
- [ ] **CONTRACT-02**: `LedgerPosting` record added with fields `flow`, `principal`, `fee`, `currency`; compact constructor validates `principal > 0`, `fee >= 0`, non-null `flow` and `currency`
- [ ] **CONTRACT-03**: `LedgerPosting.collection(principal, currency)` factory creates a COLLECTION posting with `fee = BigDecimal.ZERO`
- [ ] **CONTRACT-04**: `LedgerPosting.disbursement(principal, fee, currency)` factory creates a DISBURSEMENT posting

### SERVICE — `LedgerService` Rewrite

- [ ] **SERVICE-01**: `LedgerService.postEntry(txId, tenantId, LedgerPosting)` routes via `switch (posting.flow())` to flow-specific private entry builders; old 4-arg signature removed
- [ ] **SERVICE-02**: COLLECTION entry builder produces exactly 2 entries: DEBIT `CUSTOMER_WALLET` (principal) + CREDIT `PROVIDER_CLEARING` (principal)
- [ ] **SERVICE-03**: DISBURSEMENT entry builder produces exactly 3 entries: DEBIT `MERCHANT_WALLET` (gross = principal + fee) + CREDIT `CUSTOMER_WALLET` (principal) + CREDIT `PROVIDER_FEE` (fee)
- [ ] **SERVICE-04**: All account code strings (`CUSTOMER_WALLET`, `PROVIDER_CLEARING`, `MERCHANT_WALLET`, `PROVIDER_FEE`) are private constants inside `LedgerService`; no caller references them directly
- [ ] **SERVICE-05**: `WebhookTransitionService` collection call-site migrated from 4-arg `postEntry` to `LedgerPosting.collection(amount, currency)`; 4-arg method deleted
- [ ] **SERVICE-06**: `Transaction` entity gains nullable `flow` field with `@Enumerated(STRING)`; `getEffectiveFlow()` returns `LedgerFlow.COLLECTION` when `flow` is null

### CASHOUT — Orange Disbursement Wiring

- [ ] **CASHOUT-01**: `PaymentCommand` record gains optional `feeAmount` field (nullable `BigDecimal`); orchestrator populates it from `FeeEvaluationService` before dispatching to `OrangeMoneyPort`
- [ ] **CASHOUT-02**: `OrangeMoneyPort.initiateCashout()` calls `LedgerService.postEntry()` with `LedgerPosting.disbursement(principal, fee, currency)` after provider confirms success, inside a `TransactionTemplate` block (no `@Transactional` on the method)

### TEST — Test Coverage

- [ ] **TEST-01**: Unit test: COLLECTION flow → exactly 2 entries, balanced, correct account codes (`CUSTOMER_WALLET` debit, `PROVIDER_CLEARING` credit)
- [ ] **TEST-02**: Unit test: DISBURSEMENT flow with fee > 0 → exactly 3 entries, gross debit equals principal + fee, balanced
- [ ] **TEST-03**: Unit test: DISBURSEMENT flow with fee = 0 → exactly 3 entries including a zero-amount `PROVIDER_FEE` credit, balanced
- [ ] **TEST-04**: Unit test: `LedgerPosting` compact constructor rejects negative principal, negative fee, null currency, null flow
- [ ] **TEST-05**: `LedgerBalanceGuardTest` updated with a disbursement case to maintain PITest MUT-02 mutation kill rate (≥ 90%)
- [ ] **TEST-06**: `LedgerServiceIT` integration test: disbursement group of 3 rows persisted in real PostgreSQL via Testcontainers; no constraint violation; amounts balanced
- [ ] **TEST-07**: `LedgerVerifier.assertDisbursementLedgerBalanced(txId, principal, fee)` added as a reusable E2E helper; existing `assertLedgerBalanced` collection method unchanged
- [ ] **TEST-08**: `mvn verify` (unit + integration tests) passes after every phase commit

---

## Future Requirements (Deferred)

- Full Orange Money cashout HTTP adapter implementation — `OrangeMoneyPort.initiateCashout()` currently stubs `UnsupportedOperationException`; full integration deferred per PROJECT.md validated requirements
- Running-balance queries and admin ledger views — per-account balance endpoint for reporting; deferred until flow column is proven in production
- Partial index on `transaction.flow` for high-volume reconciliation queries — deferred until query latency data is available

---

## Out of Scope

- MTN MoMo disbursement path — MTN disbursements go through the disbursement API which has different mechanics; scoped to Orange cashout for v9
- Ledger reversal / compensating entries — neither provider supports native refunds in the current API versions; out of scope per PROJECT.md
- `LedgerEntry` schema changes — entity is immutable and append-only; no modifications to existing columns

---

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| SCHEMA-01 | Phase 46 | Pending |
| SCHEMA-02 | Phase 46 | Pending |
| SCHEMA-03 | Phase 46 | Pending |
| SCHEMA-04 | Phase 46 | Pending |
| CONTRACT-01 | Phase 47 | Pending |
| CONTRACT-02 | Phase 47 | Pending |
| CONTRACT-03 | Phase 47 | Pending |
| CONTRACT-04 | Phase 47 | Pending |
| SERVICE-01 | Phase 47 | Pending |
| SERVICE-02 | Phase 47 | Pending |
| SERVICE-03 | Phase 47 | Pending |
| SERVICE-04 | Phase 47 | Pending |
| SERVICE-05 | Phase 47 | Pending |
| SERVICE-06 | Phase 47 | Pending |
| TEST-01 | Phase 48 | Pending |
| TEST-02 | Phase 48 | Pending |
| TEST-03 | Phase 48 | Pending |
| TEST-04 | Phase 48 | Pending |
| TEST-05 | Phase 48 | Pending |
| TEST-06 | Phase 48 | Pending |
| TEST-07 | Phase 48 | Pending |
| TEST-08 | Cross-cutting gate (all phases) | Pending |
| CASHOUT-01 | Phase 49 | Pending |
| CASHOUT-02 | Phase 49 | Pending |
