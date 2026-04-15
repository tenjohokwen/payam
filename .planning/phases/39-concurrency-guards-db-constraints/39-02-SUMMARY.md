---
phase: 39-concurrency-guards-db-constraints
plan: 02
subsystem: database
tags: [postgres, flyway, ledger, constraints, testcontainers, spring-data-jpa]

# Dependency graph
requires:
  - phase: 02-transaction-core
    provides: ledger_entry table (V4 schema), LedgerService.postEntry(), LedgerEntry entity

provides:
  - V23 Flyway migration: DEFERRABLE INITIALLY DEFERRED unique constraint uq_ledger_entry_group_direction on (entry_group_id, direction)
  - LedgerConstraintIT: two integration tests proving constraint rejects unbalanced inserts and accepts balanced paired inserts

affects:
  - Any future phase writing ledger_entry rows directly via JDBC — constraint fires at commit

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "DEFERRABLE INITIALLY DEFERRED unique constraint for paired-insert patterns — check at commit, not row-by-row"
    - "Pre-flight DO block in Flyway migration — fail fast with diagnostic count before DDL"
    - "TransactionTemplate wrapping for deferred constraint tests — required for DEFERRED to fire"

key-files:
  created:
    - src/main/resources/db/migration/V23__ledger_group_constraint.sql
    - src/test/java/com/softropic/payam/transaction/LedgerConstraintIT.java
  modified: []

key-decisions:
  - "DEFERRABLE INITIALLY DEFERRED chosen over constraint trigger — simpler DDL, satisfies LEDGER-01 as stated (at-most-one per direction, not exactly-two); trigger approach deferred to if requirement tightens"
  - "Pre-flight DO block in V23 — prevents opaque ADD CONSTRAINT error on populated DB; gives operator countable violation number"
  - "Two DEBITs used in unbalancedInsert test (not one lone DEBIT) — lone DEBIT satisfies the unique constraint (1 is not a duplicate); two DEBITs is what the constraint actually blocks"

patterns-established:
  - "Pattern: Deferred constraint test must wrap JdbcTemplate inserts in TransactionTemplate — autocommit bypasses deferred constraint"
  - "Pattern: Constraint chain assertion — walk getCause() chain to find constraint name (DataIntegrityViolationException wraps PSQLException)"

requirements-completed: [LEDGER-01]

# Metrics
duration: 41min
completed: 2026-04-15
---

# Phase 39 Plan 02: Ledger DB Constraint (LEDGER-01) Summary

**PostgreSQL DEFERRABLE INITIALLY DEFERRED unique constraint on ledger_entry(entry_group_id, direction) enforces LEDGER-01 at the DB layer — unbalanced writes rejected at commit time, balanced DEBIT+CREDIT pairs commit cleanly**

## Performance

- **Duration:** 41 min
- **Started:** 2026-04-15T05:02:03Z
- **Completed:** 2026-04-15T05:43:27Z
- **Tasks:** 3 (Task 0: stub, Task 1: migration, Task 2: full test body)
- **Files modified:** 2

## Accomplishments

- V23 Flyway migration adds `uq_ledger_entry_group_direction UNIQUE (entry_group_id, direction) DEFERRABLE INITIALLY DEFERRED` on `main.ledger_entry`
- Pre-flight DO block in V23 detects and reports any existing duplicate (entry_group_id, direction) pairs before DDL executes — fails fast with a countable diagnostic
- LedgerConstraintIT proves: two-DEBIT insert raises DataIntegrityViolationException referencing `uq_ledger_entry_group_direction`; DEBIT+CREDIT paired insert commits with 2 visible rows
- Existing LedgerServiceIT (2 tests) stays green — deferred constraint does not break LedgerService.postEntry()'s saveAll() pattern

## Task Commits

1. **Task 0: LedgerConstraintIT stub** - `435b393` (test)
2. **Task 1: V23 Flyway migration** - `151efec` (feat)
3. **Task 2: LedgerConstraintIT body** - `1b117d4` (test)

## Files Created/Modified

- `src/main/resources/db/migration/V23__ledger_group_constraint.sql` — Flyway migration with pre-flight check + `uq_ledger_entry_group_direction` DEFERRABLE INITIALLY DEFERRED constraint
- `src/test/java/com/softropic/payam/transaction/LedgerConstraintIT.java` — Integration test proving DB rejects unbalanced inserts and accepts balanced ones

## Decisions Made

- Used DEFERRABLE INITIALLY DEFERRED (not a constraint trigger): simpler DDL, no PL/pgSQL function needed, satisfies LEDGER-01 requirement. Trigger would be needed only if "exactly two rows" (not "at most one per direction") becomes required.
- Used two-DEBIT pattern in `unbalancedInsert_isRejectedByConstraint`: a lone DEBIT does not violate the unique constraint (no duplicate). Two DEBITs is what LEDGER-01 actually blocks — a future bug writing duplicate direction rows.
- Pre-flight DO block is non-optional: without it, a populated DB with violations gets an opaque "could not create unique index" error. The diagnostic count enables targeted investigation.
- LedgerConstraintIT @BeforeEach seeds a tenant (FK reference for tenant_id) but skips transaction row (transaction_id is VARCHAR without FK — plan note confirmed this).

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None. V23 applied cleanly on Testcontainers DB, LedgerServiceIT stayed green, LedgerConstraintIT both tests passed first time.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- LEDGER-01 proven: DB rejects duplicate-direction inserts at commit time; balanced inserts still commit
- Constraint is DEFERRABLE INITIALLY DEFERRED — safe for any future code that needs to insert both rows in one transaction
- V23 migration is the last migration in this phase (V22 from plan 39-01 adds @Version column for TenantApiKey)

---
*Phase: 39-concurrency-guards-db-constraints*
*Completed: 2026-04-15*
