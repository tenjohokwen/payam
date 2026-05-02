---
phase: 54-v31-schema-migration
plan: "01"
subsystem: disbursement
tags: [schema-migration, jpa-entity, enum, wave-0, red-green]
dependency_graph:
  requires: []
  provides:
    - DisbursementRefStatus enum (PENDING, CLAIMED, RELEASED)
    - DisbursementTransactionRef JPA entity
    - DisbursementTransactionRefRepository stub
    - DisbursementTransactionRefIT test scaffold (Wave 0 RED)
  affects:
    - Plan 02 (V31 Flyway migration — GREEN gate for this test scaffold)
    - Phase 55 (adds query methods to DisbursementTransactionRefRepository)
tech_stack:
  added: []
  patterns:
    - AbstractAuditingEntity extension with @SuperBuilder/@Audited (mirrors Disbursement.java)
    - @Enumerated(EnumType.STRING) for VARCHAR-backed enum (project standard, no PostgreSQL ENUM types)
    - Wave 0 RED scaffold — test compiles but fails until DDL lands in next plan
key_files:
  created:
    - src/main/java/com/softropic/payam/disbursement/contract/DisbursementRefStatus.java
    - src/main/java/com/softropic/payam/disbursement/repo/DisbursementTransactionRef.java
    - src/main/java/com/softropic/payam/disbursement/repo/DisbursementTransactionRefRepository.java
    - src/test/java/com/softropic/payam/disbursement/repo/DisbursementTransactionRefIT.java
  modified: []
decisions:
  - "DisbursementRefStatus placed in disbursement/contract (not repo) — mirrors DisbursementStatus placement; it is a business-domain state, not a persistence artifact"
  - "DisbursementTransactionRef.transactionId typed as String/VARCHAR(36) — matches ledger_entry.transaction_id convention; no cross-table FK on non-PK column per project anti-pattern rules"
  - "Repository left as minimal JpaRepository stub — query methods (findByTransactionIdAndRefStatusIn, findByDisbursementId) deferred to Phase 55 to keep this wave strictly compile-only"
  - "DisbursementTransactionRefIT tests will intentionally FAIL before Plan 02 applies V31 — this is the Wave 0 RED state; Plan 02 is the GREEN gate"
metrics:
  duration_seconds: 833
  completed_date: "2026-05-02"
  tasks_completed: 3
  files_changed: 4
---

# Phase 54 Plan 01: Wave 0 Entity + Repository + Test Scaffold Summary

**One-liner:** DisbursementRefStatus enum + DisbursementTransactionRef JPA entity + repository stub + integration test scaffold (Wave 0 RED) — compile-time prerequisites for the V31 Flyway migration in Plan 02.

## What Was Built

Plan 01 is the Wave 0 prerequisite for the V31 schema migration. It creates all Java compile-time artifacts that Plan 02's DDL verification depends on:

1. **`DisbursementRefStatus` enum** — three-value lifecycle enum (PENDING/CLAIMED/RELEASED) in `disbursement/contract` package, consistent with `DisbursementStatus` placement. Plain value type — no state-machine guard logic (deferred to Phase 56).

2. **`DisbursementTransactionRef` JPA entity** — extends `AbstractAuditingEntity` (inherits TSID id, audit columns, Envers @Audited). Maps three fields: `disbursementId` (Long FK to disbursement.id), `transactionId` (VARCHAR(36) logical UUID matching ledger_entry pattern), `refStatus` (DisbursementRefStatus via @Enumerated(STRING)).

3. **`DisbursementTransactionRefRepository`** — minimal `JpaRepository<DisbursementTransactionRef, Long>` stub. No query methods — Phase 55 will add `findByTransactionIdAndRefStatusIn()` and `findByDisbursementId()` for TXN-03 claim checks and IDEM-02 retry recovery.

4. **`DisbursementTransactionRefIT`** — integration test scaffold with 5 @Test methods covering SCHEMA-01 (table columns + partial unique index), SCHEMA-02 (admin_note/retry_count presence, reserved_amount absence), and SCHEMA-03 (pre-flight RAISE EXCEPTION on PROCESSING row). Tests FAIL before Plan 02 (V31 DDL not yet applied) — intentional Wave 0 RED.

## Why IT Tests Fail at End of Plan 01

The `DisbursementTransactionRefIT` test class was designed to fail before V31 is applied. Specifically:

- `disbursementTransactionRefTable_existsWithExpectedColumns` — `main.disbursement_transaction_ref` table does not yet exist (V31 creates it in Plan 02)
- `partialUniqueIndex_rejectsDuplicateActiveClaim` — same reason; the partial unique index does not exist
- `disbursement_hasAdminNoteAndRetryCount_andReservedAmountIsGone` — `admin_note` and `retry_count` columns not yet added; `reserved_amount` not yet dropped
- `preflight_raisesException_whenProcessingDisbursementExists` — this test inserts a PROCESSING row and calls the same DO $$ block V31 will run; it will pass even before Plan 02 since the pre-flight SQL is inline in the test
- `preflight_passes_whenAllDisbursementsAreTerminal` — passes trivially if disbursement table exists

Plan 02 will run `mvn verify -Dtest=DisbursementTransactionRefIT` as its GREEN gate.

## Downstream Consumers

- **Plan 02** (this phase): Writes `V31__disbursement_transaction_ref.sql`, runs `DisbursementTransactionRefIT` as the GREEN gate for SCHEMA-01/02/03
- **Phase 55**: Adds `findByTransactionIdAndRefStatusIn(String, List<DisbursementRefStatus>)` and `findByDisbursementId(Long)` to `DisbursementTransactionRefRepository` for TXN-03 claim validation and IDEM-02 retry recovery

## Deviations from Plan

None — plan executed exactly as written.

## Known Stubs

- `DisbursementTransactionRefRepository` — intentional stub per plan design; query methods added in Phase 55
- `DisbursementTransactionRefIT` — Wave 0 RED state; tests will fail until Plan 02 applies V31 DDL (this is by design, not a defect)

## Self-Check: PASSED

Files created:
- FOUND: src/main/java/com/softropic/payam/disbursement/contract/DisbursementRefStatus.java
- FOUND: src/main/java/com/softropic/payam/disbursement/repo/DisbursementTransactionRef.java
- FOUND: src/main/java/com/softropic/payam/disbursement/repo/DisbursementTransactionRefRepository.java
- FOUND: src/test/java/com/softropic/payam/disbursement/repo/DisbursementTransactionRefIT.java

Commits:
- FOUND: 93a0d8e (DisbursementRefStatus enum)
- FOUND: 2686046 (entity + repository)
- FOUND: 8213777 (IT scaffold)

Verification: `mvn compile` and `mvn test-compile` both exit 0. No production code consumes the new types (Phase 55 is the first consumer).
