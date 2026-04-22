---
phase: 47-contract-types-ledgerservice-rewrite
plan: "02"
subsystem: transaction/service, webhook/service
tags: [ledger, ledger-service, rewrite, contract-types, disbursement, collection, tdd]
dependency_graph:
  requires:
    - Phase 47 Plan 01 (LedgerFlow enum + LedgerPosting record)
    - Phase 46 Plan 01 (V25 schema migration — CHECK(amount>=0) for zero-fee entries)
  provides:
    - LedgerService.postEntry(String, Long, LedgerPosting) — 3-arg signature, switch-routed
    - COLLECTION flow: 2 entries (DEBIT CUSTOMER_WALLET + CREDIT PROVIDER_CLEARING)
    - DISBURSEMENT flow: 3 entries (DEBIT MERCHANT_WALLET gross + CREDIT CUSTOMER_WALLET + CREDIT PROVIDER_FEE)
  affects:
    - Phase 47 Plan 03 (Transaction.flow column + WebhookTransitionService flow annotation)
    - Phase 48 (disbursement tests consuming new 3-arg signature)
tech_stack:
  added: []
  patterns:
    - Java 17 switch expression (exhaustive over sealed enum — no default branch needed)
    - Private account-code constants — no external string references
    - One shared entry() helper method for all LedgerEntry construction
    - Shared entryGroupId per postEntry call — satisfies V25 balance trigger
key_files:
  created: []
  modified:
    - src/main/java/com/softropic/payam/transaction/service/LedgerService.java
    - src/main/java/com/softropic/payam/webhook/service/WebhookTransitionService.java
    - src/test/java/com/softropic/payam/domain/LedgerBalanceGuardTest.java
    - src/test/java/com/softropic/payam/transaction/LedgerServiceIT.java
decisions:
  - "Tasks 1 and 2 committed in a single atomic commit — build only compiles when all call sites migrated simultaneously"
  - "Old 4-arg postEntry deleted (not deprecated) per SERVICE-01 — no backwards-compatibility shim added"
  - "All four account codes are private static final constants inside LedgerService — no external caller references them directly"
metrics:
  duration: "~12 minutes"
  completed_date: "2026-04-22"
  tasks_completed: 2
  files_created: 0
  files_modified: 4
---

# Phase 47 Plan 02: LedgerService Rewrite + Call Site Migration Summary

**One-liner:** LedgerService.postEntry rewritten with switch-routed 3-arg signature (COLLECTION → 2 entries, DISBURSEMENT → 3 entries); all 3 existing call sites atomically migrated to LedgerPosting.collection factory.

## What Was Built

**LedgerService.java** — Fully replaced. The old 4-arg `postEntry(String, Long, BigDecimal, String)` is deleted and replaced by a 3-arg `postEntry(String, Long, LedgerPosting)`. A Java 17 exhaustive switch expression routes on `posting.flow()`:
- `COLLECTION` → `buildCollectionEntries()` produces 2 `LedgerEntry` rows: DEBIT CUSTOMER_WALLET(principal) + CREDIT PROVIDER_CLEARING(principal), sharing one `entryGroupId`.
- `DISBURSEMENT` → `buildDisbursementEntries()` produces 3 `LedgerEntry` rows: DEBIT MERCHANT_WALLET(principal+fee) + CREDIT CUSTOMER_WALLET(principal) + CREDIT PROVIDER_FEE(fee), sharing one `entryGroupId`.

Four private `static final String` constants hold the account codes. A shared `entry(...)` helper method builds each `LedgerEntry` from common parameters.

**WebhookTransitionService.java** — Migrated the `if (target == TransactionStatus.SUCCESS)` block from `ledgerService.postEntry(tx.getTransactionId(), tx.getTenantId(), tx.getAmount(), tx.getCurrency())` to `ledgerService.postEntry(tx.getTransactionId(), tx.getTenantId(), LedgerPosting.collection(tx.getAmount(), tx.getCurrency()))`. Import added for `LedgerPosting`.

**LedgerBalanceGuardTest.java** — Migrated line 37 from `service.postEntry("txn-ledger-001", 1L, amount, "XAF")` to `service.postEntry("txn-ledger-001", 1L, LedgerPosting.collection(amount, "XAF"))`. All existing CUSTOMER_WALLET / PROVIDER_CLEARING / `hasSize(2)` assertions preserved — SERVICE-02 remains locked at behavior level.

**LedgerServiceIT.java** — Migrated 2 call sites (lines 113 and 139) from `ledgerService.postEntry(transactionId, tenantId, new BigDecimal("500.00"), "XAF")` to `ledgerService.postEntry(transactionId, tenantId, LedgerPosting.collection(new BigDecimal("500.00"), "XAF"))`. Import added. Existing test logic and assertions unchanged.

## Atomic Commit Strategy

Tasks 1 and 2 were committed in a single commit because the build only compiles when both the new signature and all call site migrations are in place. A partial commit (Task 1 only) would have broken compilation.

## Test Results

- `LedgerFlowTest` — 3 tests: PASSED
- `LedgerPostingTest` — 10 tests: PASSED
- `LedgerBalanceGuardTest` — 1 test (migrated + passing): PASSED
- **Unit test subtotal: 14 tests, 0 failures, 0 errors**
- `LedgerServiceIT` and E2E tests require Docker/Testcontainers — unavailable in this environment (pre-existing constraint noted in Phase 47-01 SUMMARY and confirmed in Plan 46-01 SUMMARY)
- `mvn compile -q` exits 0 — full production code compiles cleanly

## Phase-Level Verification

| Check | Result |
|-------|--------|
| `mvn compile -q` | EXIT 0 — clean |
| `grep -rE "postEntry\\(.*,.*BigDecimal.*,.*\\)"`  | 0 matches — old 4-arg signature eliminated |
| `grep -rc "LedgerPosting\\." src/` | 5 files (WebhookTransitionService, LedgerService, LedgerServiceIT, LedgerBalanceGuardTest, LedgerPostingTest) |
| `grep -c "switch (posting.flow())" LedgerService.java` | 1 — switch routing confirmed |
| 4 private account-code constants in LedgerService | Confirmed |
| Old 4-arg `postEntry(String, Long, BigDecimal, String)` | Zero occurrences in src/ |

## Commits

| Task | Commit | Files |
|------|--------|-------|
| Task 1+2: LedgerService rewrite + all call sites migrated (atomic) | 7f766f3 | LedgerService.java, WebhookTransitionService.java, LedgerBalanceGuardTest.java, LedgerServiceIT.java |

## Pending (Future Plans)

- `Transaction.flow` column (`VARCHAR(20)`) — still pending (Plan 03 scope per RESEARCH)
- `getEffectiveFlow()` helper method — still pending (Plan 03 scope)
- Disbursement-specific unit and integration tests (TEST-02, TEST-03) — Plan 48 scope per plan instructions; this plan only migrates COLLECTION call sites

## Deviations from Plan

**Worktree merge required before execution:** The Plan 01 contract types (`LedgerFlow.java`, `LedgerPosting.java`) existed in the main repo but the worktree was behind. A `git merge 6dbcd4d` (fast-forward) was applied before starting execution. This is normal parallel-agent infrastructure behavior, not a code deviation.

**Tasks committed atomically (not separately):** The plan implies committing after each task, but Task 1 alone breaks compilation at call sites still using the old signature. Both tasks committed as one logical unit `7f766f3`. This correctly follows the plan's own caveat: "Executor note: do NOT commit until Task 2 is green."

## Known Stubs

None — all implementations are complete. No placeholder values or TODO markers in modified files.

## Self-Check: PASSED

- [x] `src/main/java/com/softropic/payam/transaction/service/LedgerService.java` — rewritten, contains `switch (posting.flow())`
- [x] `src/main/java/com/softropic/payam/webhook/service/WebhookTransitionService.java` — contains `LedgerPosting.collection`
- [x] `src/test/java/com/softropic/payam/domain/LedgerBalanceGuardTest.java` — contains `LedgerPosting.collection`
- [x] `src/test/java/com/softropic/payam/transaction/LedgerServiceIT.java` — contains 2x `LedgerPosting.collection`
- [x] Commit 7f766f3 exists
- [x] 0 old 4-arg postEntry calls survive in src/
- [x] 14 unit tests passing (LedgerFlowTest + LedgerPostingTest + LedgerBalanceGuardTest)
