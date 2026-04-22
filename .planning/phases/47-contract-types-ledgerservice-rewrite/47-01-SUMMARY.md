---
phase: 47-contract-types-ledgerservice-rewrite
plan: "01"
subsystem: transaction/contract
tags: [ledger, contract-types, enum, record, java17, tdd]
dependency_graph:
  requires: []
  provides:
    - LedgerFlow enum (transaction/contract)
    - LedgerPosting record (transaction/contract)
  affects:
    - Phase 47 Plan 02 (LedgerService rewrite consumes LedgerPosting)
    - Phase 47 Plan 03 (WebhookTransitionService migration)
tech_stack:
  added: []
  patterns:
    - Java 17 record with compact constructor validation
    - Static factory method pattern on value record
    - TDD red-green cycle for new contract types
key_files:
  created:
    - src/main/java/com/softropic/payam/transaction/contract/LedgerFlow.java
    - src/main/java/com/softropic/payam/transaction/contract/LedgerPosting.java
    - src/test/java/com/softropic/payam/transaction/contract/LedgerFlowTest.java
    - src/test/java/com/softropic/payam/transaction/contract/LedgerPostingTest.java
  modified: []
decisions:
  - "BigDecimal.compareTo(ZERO) used (not .equals) to handle scale-insensitive zero check per Pitfall 4"
  - "No existing production call sites modified — intentional interface-first ordering for Phase 47"
metrics:
  duration: "~7 minutes"
  completed_date: "2026-04-22"
  tasks_completed: 2
  files_created: 4
  files_modified: 0
---

# Phase 47 Plan 01: Contract Types (LedgerFlow + LedgerPosting) Summary

**One-liner:** LedgerFlow enum (COLLECTION/DISBURSEMENT) and LedgerPosting record with compact-constructor validation and two static factories added to transaction/contract package.

## What Was Built

Two new contract-layer types in `com.softropic.payam.transaction.contract`:

**LedgerFlow.java** — A simple 2-value enum following the existing `LedgerDirection` pattern. Values: `COLLECTION` and `DISBURSEMENT`. Used by `LedgerPosting` and will be used by `Transaction.flow` field (Phase 47 Plan 03) and `LedgerService` routing switch (Plan 02).

**LedgerPosting.java** — A Java 17 record with 4 fields (`flow`, `principal`, `fee`, `currency`) and a compact constructor that enforces invariants. Two static factories:
- `collection(principal, currency)` — sets `flow=COLLECTION`, `fee=BigDecimal.ZERO`
- `disbursement(principal, fee, currency)` — sets `flow=DISBURSEMENT`

Key validation logic uses `compareTo(BigDecimal.ZERO)` (not `.equals()`) to avoid scale-sensitivity pitfall with `new BigDecimal("0.00")`.

## Test Counts

- `LedgerFlowTest.java` — 3 tests: values() containsExactly, valueOf COLLECTION, valueOf DISBURSEMENT
- `LedgerPostingTest.java` — 10 tests: 2 factory happy-paths, 1 zero-fee allowed, 7 constructor rejection cases
- **Total: 13 tests — all passing**

## Commits

| Task | Commit | Files |
|------|--------|-------|
| Task 1: LedgerFlow enum + LedgerFlowTest | a721ec1 | LedgerFlow.java, LedgerFlowTest.java |
| Task 2: LedgerPosting record + LedgerPostingTest | c6ccc0e | LedgerPosting.java, LedgerPostingTest.java |

## Verification Results

- `mvn compile` — clean, no warnings or errors
- `mvn test -Dtest="LedgerFlowTest,LedgerPostingTest"` — 13/13 passing
- No existing production code references LedgerPosting or LedgerFlow (confirmed by grep)
- Full `mvn verify`: unit tests green; E2E test failures are pre-existing Docker-unavailable infrastructure issues unrelated to these changes (ApplicationContext failures due to Testcontainers requiring Docker)

## No Existing Call Sites Modified

This plan is intentionally additive-only. No existing production code was touched. Plan 02 will rewrite `LedgerService.postEntry()` to accept `LedgerPosting`, and Plan 03 will migrate `WebhookTransitionService` and test call sites.

## Deviations from Plan

None — plan executed exactly as written.

## Known Stubs

None — both files are complete implementations with no placeholder values.

## Self-Check: PASSED

- [x] `src/main/java/com/softropic/payam/transaction/contract/LedgerFlow.java` exists
- [x] `src/main/java/com/softropic/payam/transaction/contract/LedgerPosting.java` exists
- [x] `src/test/java/com/softropic/payam/transaction/contract/LedgerFlowTest.java` exists
- [x] `src/test/java/com/softropic/payam/transaction/contract/LedgerPostingTest.java` exists
- [x] Commit a721ec1 exists
- [x] Commit c6ccc0e exists
