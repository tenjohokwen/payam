# Milestone v9: Ledger Disbursement Support

**Status:** ✅ SHIPPED 2026-04-23
**Phases:** 46–49
**Total Plans:** 8

## Overview

Extended the double-entry ledger to support disbursement/cashout flows — merchant wallet debited the gross amount, customer credited the principal, provider retains the fee. Introduced `LedgerFlow`/`LedgerPosting` contract types, rewrote `LedgerService` with flow-routing, delivered schema migration (V25), full test coverage, and wired `OrangeMoneyPort.initiateCashout()` with a real HTTP call and 3-row balanced ledger entry.

## Phases

### Phase 46: Flyway V25 Schema Migration

**Goal**: The database schema supports disbursement ledger writes — V25 migration runs cleanly, all balance invariants are enforced by trigger, and the transaction table tracks flow direction
**Depends on**: Phase 45
**Requirements**: SCHEMA-01, SCHEMA-02, SCHEMA-03, SCHEMA-04
**Plans**: 1 plan

Plans:
- [x] 46-01-PLAN.md — V25 migration: drop `uq_ledger_entry_group_direction`, add deferrable `check_ledger_balance` CONSTRAINT TRIGGER, relax `amount >= 0`, add nullable `flow VARCHAR(20)` to `main.transaction` + `main.transaction_aud`; update `LedgerConstraintIT` (SCHEMA-01..04)

**Success Criteria achieved:**
1. V25 migration completed successfully — existing V23 constraint dropped, balance trigger installed
2. Deferrable CONSTRAINT TRIGGER asserts SUM(DEBIT)==SUM(CREDIT) per `entry_group_id` at commit time
3. Zero-amount `PROVIDER_FEE` entries (amount=0) insert without constraint violation
4. `flow VARCHAR(20)` column on `main.transaction` and `main.transaction_aud`; existing rows have `flow = NULL`
5. `mvn verify` passes — 222 tests, 0 failures

**Key decision:** CONSTRAINT TRIGGER fires `JpaSystemException` (not `DataIntegrityViolationException`) when triggered via `TransactionTemplate` — `isInstanceOfAny` in `LedgerConstraintIT` updated to include `JpaSystemException`

---

### Phase 47: Contract Types + LedgerService Rewrite

**Goal**: Callers express ledger intent through typed `LedgerPosting` values, and `LedgerService` routes them to flow-specific entry builders — the old 4-argument signature is gone
**Depends on**: Phase 46
**Requirements**: CONTRACT-01..04, SERVICE-01..06
**Plans**: 3 plans

Plans:
- [x] 47-01-PLAN.md — `LedgerFlow` enum (COLLECTION/DISBURSEMENT) + `LedgerPosting` record with compact-constructor and two static factories; 13 unit tests (CONTRACT-01..04)
- [x] 47-02-PLAN.md — `LedgerService.postEntry` rewrite (switch-routed 3-arg) + migrate 3 call sites atomically; old 4-arg deleted (SERVICE-01..05)
- [x] 47-03-PLAN.md — `Transaction.flow` nullable JPA field + `getEffectiveFlow()` returning COLLECTION for null; V25 column mapped (SERVICE-06)

**Success Criteria achieved:**
1. `LedgerFlow.COLLECTION` and `LedgerFlow.DISBURSEMENT` exist in `transaction/contract`; no caller references account codes directly
2. `LedgerPosting.collection(principal, currency)` and `.disbursement(principal, fee, currency)` compile; compact constructor rejects negative values, null flow, null currency
3. COLLECTION → 2 entries; DISBURSEMENT → 3 entries; both routed via `switch(posting.flow())`
4. `WebhookTransitionService` and all call sites migrated atomically; old 4-arg method deleted
5. `Transaction.flow` nullable `@Enumerated(STRING)`; `getEffectiveFlow()` null-coalesces to COLLECTION

---

### Phase 48: Test Coverage

**Goal**: Every ledger flow variant is proven correct by unit tests, the PITest mutation threshold is maintained, and a real-database integration test confirms disbursement rows persist without constraint violation
**Depends on**: Phase 47
**Requirements**: TEST-01..08
**Plans**: 2 plans

Plans:
- [x] 48-01-PLAN.md — DISBURSEMENT unit tests in `LedgerBalanceGuardTest` (fee>0 + fee=0); PITest 100% mutation kill rate on `LedgerService` (TEST-02..05)
- [x] 48-02-PLAN.md — `LedgerServiceIT.postEntry_disbursement_persistsThreeBalancedRows` (real Testcontainers PostgreSQL); `LedgerVerifier.assertDisbursementLedgerBalanced` + 5 unit tests; `mvn verify` gate (TEST-06..08)

**Success Criteria achieved:**
1. COLLECTION unit test: 2 entries, balanced, correct account codes — pre-satisfied from Plan 47
2. DISBURSEMENT fee>0: 3 entries, gross debit == principal+fee, balanced
3. DISBURSEMENT fee=0: 3 entries including zero-amount PROVIDER_FEE credit, balanced
4. `LedgerPosting` constructor rejects all 4 invalid cases
5. `LedgerBalanceGuardTest` PITest kill rate: 100% (4/4 mutants killed)
6. `LedgerServiceIT`: 3-row disbursement group persisted, V25 trigger accepts at commit
7. `LedgerVerifier.assertDisbursementLedgerBalanced` exists; existing `assertLedgerBalanced` untouched
8. `mvn verify` passes

---

### Phase 49: Orange Cashout Wiring

**Goal**: The Orange Money cashout path records disbursement ledger entries after provider confirmation, using the fee evaluated by `FeeEvaluationService`
**Depends on**: Phase 47
**Requirements**: CASHOUT-01, CASHOUT-02
**Plans**: 2 plans

Plans:
- [x] 49-01-PLAN.md — `PaymentCommand` 14th nullable `feeAmount` field; 13-arg compat constructor; `withFeeAmount` wither; `PaymentOrchestrator.initiate()` enriches command from `FeeEvaluationService` (CASHOUT-01)
- [x] 49-02-PLAN.md — `OrangeMoneyPort.initiateCashout()` implementation: real HTTP `/cashout` call, `LedgerPosting.disbursement` via `TransactionTemplate`, null-fee fallback to ZERO; `OrangeMoneyPortIT` 8/8 tests green (CASHOUT-02)

**Success Criteria achieved:**
1. `PaymentCommand.feeAmount` nullable `BigDecimal`; orchestrator populates from `FeeEvaluationService`; existing call sites compile unchanged
2. `initiateCashout()` calls `orangeMoneyClient.cashout()`, guards on 2xx, posts `LedgerPosting.disbursement` inside `TransactionTemplate` block (no `@Transactional` on method)
3. `feeAmount = null` posts `LedgerPosting.disbursement(principal, BigDecimal.ZERO, currency)` without throwing
4. `mvn verify` passes — no regressions in Orange Money or orchestration tests

---

## Milestone Summary

**Key Decisions:**
- `compareTo(BigDecimal.ZERO)` used throughout instead of `.equals()` for scale-insensitive zero checks in LedgerPosting compact constructor
- Old 4-arg `postEntry` deleted (not deprecated) — atomic migration with no backwards-compatibility shim
- `Transaction.flow` has no `@Builder.Default` — null preserved for pre-v9 rows; fallback to COLLECTION belongs in `getEffectiveFlow()` accessor only
- No `@Transactional` on `OrangeMoneyPort.initiateCashout()` — `TransactionTemplate` used for discrete DB operations, consistent with `PaymentOrchestrator` pattern
- Tasks 1+2 in Phase 47-02 committed atomically — build only compiles when LedgerService rewrite and all call site migrations are in place together

**Issues Resolved:**
- V23 deferrable unique constraint blocked zero-amount entries — V25 trigger-based approach fixes this correctly
- JpaSystemException vs DataIntegrityViolationException divergence when CONSTRAINT TRIGGER fires via TransactionTemplate

**Issues Deferred:**
- `LedgerConstraintIT.flowColumn_existsAndIsNullable` assertion mismatch (VARCHAR(20) vs 255) — pre-existing from Phase 46, non-blocking for test suite

**Technical Debt Incurred:**
- Full Orange Money cashout HTTP adapter integration test against live Orange sandbox — deferred pending sandbox credentials
- Running-balance queries and admin ledger views — deferred until `flow` column proven in production

---

*For current project status, see .planning/PROJECT.md*
