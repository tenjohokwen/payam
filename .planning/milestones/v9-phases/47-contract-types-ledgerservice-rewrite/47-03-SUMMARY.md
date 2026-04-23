---
phase: 47-contract-types-ledgerservice-rewrite
plan: "03"
subsystem: transaction/repo
tags: [ledger, entity-mapping, jpa, flow-column, tdd, service-06]
dependency_graph:
  requires:
    - 47-01 (LedgerFlow enum in transaction/contract)
    - 46-01 (V25 Flyway migration — flow VARCHAR(20) column on main.transaction + main.transaction_aud)
  provides:
    - Transaction.flow nullable JPA field mapped to main.transaction.flow
    - Transaction.getEffectiveFlow() null-coalescing accessor returning COLLECTION for null
  affects:
    - Phase 49 (disbursement orchestration uses .flow(LedgerFlow.DISBURSEMENT) via @SuperBuilder)
tech_stack:
  added: []
  patterns:
    - Nullable @Enumerated(EnumType.STRING) JPA field without @Builder.Default
    - Null-coalescing accessor for backward-compatible column with pre-v9 NULL rows
    - TDD red-green cycle for entity field addition
key_files:
  created:
    - src/test/java/com/softropic/payam/transaction/repo/TransactionFlowTest.java
  modified:
    - src/main/java/com/softropic/payam/transaction/repo/Transaction.java
decisions:
  - "No @Builder.Default on flow field — null preserved for pre-v9 rows; fallback belongs in accessor"
  - "No @NotAudited — V25 added flow to transaction_aud for Envers parity (natural audit)"
  - "No setFlow() setter — Phase 49 uses @SuperBuilder to set flow at construction time"
  - "getEffectiveFlow() returns LedgerFlow.COLLECTION for null — correct interpretation of pre-v9 collection-only behavior"
metrics:
  duration: "~10 minutes"
  completed_date: "2026-04-22"
  tasks_completed: 2
  files_created: 1
  files_modified: 1
---

# Phase 47 Plan 03: Transaction.flow Entity Field + getEffectiveFlow() Summary

**One-liner:** Nullable `flow` field of type `LedgerFlow` added to Transaction JPA entity with `@Enumerated(EnumType.STRING)` mapping V25 column, plus `getEffectiveFlow()` returning `COLLECTION` for null pre-v9 rows.

## What Was Built

**Transaction.java — 3 additions:**

1. **Import** — `import com.softropic.payam.transaction.contract.LedgerFlow;` inserted alphabetically after `TransactionStatus` import.

2. **Field** — `@Enumerated(EnumType.STRING) @Column(name = "flow") private LedgerFlow flow;` added after `feeRuleId` field. Nullable by design — no `@Builder.Default` (pre-v9 rows remain null), no `@NotAudited` (V25 added `flow` to `transaction_aud` for Envers parity).

3. **Accessor** — `public LedgerFlow getEffectiveFlow()` returns `flow != null ? flow : LedgerFlow.COLLECTION`. Legacy null rows interpret as COLLECTION (correct — pre-v9 behavior was collection-only).

**TransactionFlowTest.java — 3 unit tests:**
- `getEffectiveFlow_returnsCollectionWhenFlowNull` — builder omitting `.flow(...)` yields `getFlow()==null` and `getEffectiveFlow()==COLLECTION`
- `getEffectiveFlow_returnsStoredValueWhenDisbursement` — explicit `.flow(DISBURSEMENT)` echoed by accessor
- `getEffectiveFlow_returnsStoredValueWhenCollection` — explicit `.flow(COLLECTION)` echoed (not the default path)

No setter was added. Phase 49 will set flow via `Transaction.builder().flow(LedgerFlow.DISBURSEMENT)...` through `@SuperBuilder`.

## Test Counts

- `TransactionFlowTest.java` — 3 tests, all passing (pure in-memory, no Docker required)
- Total Phase 47 unit tests (without E2E): LedgerFlowTest (3) + LedgerPostingTest (10) + TransactionFlowTest (3) = 16 unit tests

## Commits

| Task | Commit | Files |
|------|--------|-------|
| Task 1: Add flow field + getEffectiveFlow() to Transaction entity | 42fca90 | Transaction.java |
| Task 2: Add TransactionFlowTest + mvn verify gate | 96b0fd9 | TransactionFlowTest.java |

## Verification Results

- `mvn test-compile` — clean, no warnings or errors
- `mvn test -Dtest="TransactionFlowTest"` — 3/3 passing
- All Phase 47 final verification checks pass:
  1. `grep -c "private LedgerFlow flow" Transaction.java` = 1
  2. `grep -c "public LedgerFlow getEffectiveFlow" Transaction.java` = 1
  3. No old 4-arg `postEntry` signatures in `src/` (0 matches)
  4. No `CUSTOMER_WALLET|PROVIDER_CLEARING|MERCHANT_WALLET|PROVIDER_FEE` outside `LedgerService.java` (0 matches)
- Full `mvn verify`: 305 tests run; 220 passing unit/security/domain tests; 85 E2E context-load failures are pre-existing Docker-unavailable Testcontainers infrastructure issues — not caused by this plan

## Phase 47 Completion

All 10 Phase 47 requirements are now complete:
- CONTRACT-01: LedgerFlow enum — Plan 01
- CONTRACT-02: LedgerFlow.COLLECTION value — Plan 01
- CONTRACT-03: LedgerFlow.DISBURSEMENT value — Plan 01
- CONTRACT-04: LedgerPosting record — Plan 01
- SERVICE-01: LedgerService.postEntry 3-arg signature — Plan 02
- SERVICE-02: COLLECTION routing in LedgerService — Plan 02
- SERVICE-03: DISBURSEMENT routing in LedgerService — Plan 02
- SERVICE-04: Account code strings private to LedgerService — Plan 02
- SERVICE-05: WebhookTransitionService migrated to new signature — Plan 02
- SERVICE-06: Transaction.flow nullable + getEffectiveFlow() — **This plan**

## Deviations from Plan

**1. [Rule 3 - Blocking] Worktree required merge from main before executing**

- **Found during:** Task 1 setup — `LedgerFlow` not found in worktree's branch
- **Issue:** Worktree `worktree-agent-a6664a2b` was behind `main` by 5 commits (Plans 47-01 completed by parallel agent), so `LedgerFlow.java` did not exist in the worktree
- **Fix:** `git merge main --no-edit` fast-forward merge to pull 47-01 changes into worktree
- **Files modified:** None beyond what the merge brought in
- **Impact:** No code changes needed; worktree now has LedgerFlow + LedgerPosting as expected

## Known Stubs

None — all three additions are complete implementations.

## Self-Check: PASSED

- [x] `src/main/java/com/softropic/payam/transaction/repo/Transaction.java` modified (verified)
- [x] `src/test/java/com/softropic/payam/transaction/repo/TransactionFlowTest.java` exists
- [x] Commit 42fca90 exists
- [x] Commit 96b0fd9 exists
- [x] `grep -c "private LedgerFlow flow" Transaction.java` = 1
- [x] `grep -c "public LedgerFlow getEffectiveFlow" Transaction.java` = 1
- [x] TransactionFlowTest: 3/3 passing
