---
phase: 54-v31-schema-migration
plan: "02"
subsystem: disbursement-schema
tags: [flyway, ddl, v31, disbursement, wallet-retirement, schema-migration]
dependency_graph:
  requires: [54-01]
  provides: [V31-migration, reserved_amount-removed, disbursement_transaction_ref-tables, TestDataCleaner-fk-order]
  affects: [DisbursementTransactionRefIT, DisbursementRepositoryIT, DisbursementOrchestrator, DisbursementCallbackTransitionService]
tech_stack:
  added: []
  patterns: [flyway-pre-flight-DO-block, envers-aud-before-base, partial-unique-index, generate-ddl-entity-sync]
key_files:
  created:
    - src/main/resources/db/migration/V31__disbursement_transaction_ref.sql
  modified:
    - src/test/java/com/softropic/payam/config/TestDataCleaner.java
    - src/main/java/com/softropic/payam/disbursement/repo/Disbursement.java
    - src/main/java/com/softropic/payam/disbursement/service/DisbursementService.java
    - src/main/java/com/softropic/payam/disbursement/service/DisbursementOrchestrator.java
    - src/main/java/com/softropic/payam/disbursement/service/DisbursementCallbackTransitionService.java
    - src/main/java/com/softropic/payam/disbursement/api/DisbursementResource.java
    - src/test/java/com/softropic/payam/disbursement/service/DisbursementOrchestratorTest.java
    - src/test/java/com/softropic/payam/disbursement/service/DisbursementCallbackTransitionServiceTest.java
    - src/test/java/com/softropic/payam/disbursement/repo/DisbursementRepositoryIT.java
    - src/test/java/com/softropic/payam/disbursement/api/MtnDisbursementCallbackControllerIT.java
    - src/test/java/com/softropic/payam/disbursement/api/OrangeDisbursementCallbackControllerIT.java
    - src/test/java/com/softropic/payam/disbursement/webhook/DisbursementWebhookDeliveryIT.java
    - src/test/java/com/softropic/payam/disbursement/service/DisbursementExpiryJobIT.java
    - src/test/java/com/softropic/payam/mtn/service/MtnMoMoPortDisbursementCallbackTest.java
    - src/test/java/com/softropic/payam/orange/service/OrangeMoneyPortDisbursementCallbackTest.java
decisions:
  - "Pulled Plan 03 Task 1 (reserved_amount entity removal) forward — spring.jpa.generate-ddl=true re-added the dropped column after V31 ran"
  - "WalletBalanceService and FeeEvaluationService removed from DisbursementOrchestrator — fee=ZERO always (FEE-01), no wallet reservation (SCHEMA-03)"
  - "DisbursementCallbackTransitionService constructor dropped WalletBalanceService — no wallet release on FAILED callback; wallet model retired"
  - "All test SQL inserts for disbursement table updated to remove reserved_amount column reference"
metrics:
  duration: "~3 hours (including debugging generate-ddl root cause)"
  completed_date: "2026-05-02"
  tasks_completed: 2
  files_changed: 16
---

# Phase 54 Plan 02: V31 Flyway Migration — disbursement_transaction_ref Summary

V31 migration creates `disbursement_transaction_ref` + `_aud`, adds `admin_note`/`retry_count` to `disbursement`, drops `reserved_amount`, and enforces TXN-03 with a partial unique index; wallet model retired from all callers so Hibernate `generate-ddl` does not re-add the dropped column.

## Tasks Completed

| Task | Name | Commit | Key Files |
|------|------|--------|-----------|
| 1 | Author V31 Flyway migration | 75a168d | V31__disbursement_transaction_ref.sql (113 lines) |
| 2 | Fix TestDataCleaner FK ordering + retire wallet model | a6d6aa7 | TestDataCleaner.java + 14 other files |

## What Was Built

### Task 1 — V31__disbursement_transaction_ref.sql

Created `/src/main/resources/db/migration/V31__disbursement_transaction_ref.sql` (113 lines) with 5 ordered steps:

1. **Pre-flight DO $$ block** — raises `EXCEPTION 'V31 pre-flight: N disbursement(s) in PROCESSING or PENDING_CONFIRMATION...'` if any in-flight rows exist. First executable SQL in file.
2. **ADD COLUMN admin_note + retry_count** — `disbursement` (admin_note TEXT nullable, retry_count INT NOT NULL DEFAULT 0) and `disbursement_aud` (both nullable per Envers convention).
3. **DROP COLUMN reserved_amount** — from `disbursement` and `disbursement_aud`.
4. **CREATE TABLE disbursement_transaction_ref_aud** (Envers audit, before base table) and **disbursement_transaction_ref** (BIGINT TSID PK, disbursement_id BIGINT NOT NULL, transaction_id VARCHAR(36) NOT NULL, ref_status VARCHAR(30) NOT NULL, standard audit columns, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE').
5. **Partial unique index** `uq_dtr_txn_active_claim` ON `disbursement_transaction_ref (transaction_id) WHERE ref_status IN ('PENDING', 'CLAIMED')` — enforces TXN-03 at DB layer.

### Task 2 — FK ordering + wallet model retirement

`TestDataCleaner.wipeAll()` updated to delete in FK-safe order:
```
...transaction → disbursement_transaction_ref_aud → disbursement_transaction_ref → disbursement_aud → disbursement → merchant_wallet_balance_aud → merchant_wallet_balance...
```

Wallet model retired from production Java (pulled forward from Plan 03, see Deviations):
- `Disbursement.java`: `reservedAmount` field and `@Column(name = "reserved_amount")` removed
- `DisbursementService.create()`: removed `BigDecimal reservedAmount` parameter (now 4-arg)
- `DisbursementOrchestrator`: removed `FeeEvaluationService` and `WalletBalanceService` dependencies; `fee = BigDecimal.ZERO` (FEE-01); no `checkAndReserve`/`release` calls
- `DisbursementCallbackTransitionService`: removed `WalletBalanceService` from constructor; no wallet release on FAILED callback
- `DisbursementResource.toListItem()`: `fee = BigDecimal.ZERO` (FEE-01)

Test suite updated throughout: `reserved_amount` removed from all `disbursement` table INSERT SQL; builder call `.reservedAmount()` removed; mock expectations for `walletBalanceService` removed; `dsbService.create()` stub updated to 4-arg.

## Test Results

- **DisbursementTransactionRefIT**: 5/5 GREEN
- **DisbursementOrchestratorTest**: 14/14 GREEN  
- **DisbursementCallbackTransitionServiceTest**: 5/5 GREEN
- **Full unit test suite** (`mvn test`): BUILD SUCCESS

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] spring.jpa.generate-ddl=true caused Hibernate to re-add reserved_amount after V31 dropped it**

- **Found during:** Task 2 — IT test `disbursement_hasAdminNoteAndRetryCount_andReservedAmountIsGone` failed
- **Issue:** `spring.jpa.generate-ddl: true` in `application-dev.yaml` (line 45) causes Hibernate to emit `ALTER TABLE main.disbursement add column reserved_amount numeric(20,2)` during test context startup, even with `hibernate.ddl-auto: none`. Because `Disbursement.java` still mapped the field, Hibernate re-added the column that V31 just dropped. The plan stated "does NOT touch Disbursement.java field — Plan 03 owns that" but V31's `DROP COLUMN` was immediately reversed by Hibernate's DDL generation.
- **Root cause identified by:** Enabling Flyway DEBUG logging and capturing full test output to `/tmp/test_full.txt`, finding the `ALTER TABLE main.disbursement add column reserved_amount` line in the Hibernate DDL log.
- **Fix:** Pulled forward Plan 03 Task 1 — removed `reservedAmount` from `Disbursement.java` and updated all callers. This was the minimum required to make V31's column drop survive context startup.
- **Files modified:** Disbursement.java, DisbursementService.java, DisbursementOrchestrator.java, DisbursementCallbackTransitionService.java, DisbursementResource.java
- **Additional test files updated:** DisbursementOrchestratorTest.java, DisbursementCallbackTransitionServiceTest.java, DisbursementRepositoryIT.java, MtnDisbursementCallbackControllerIT.java, OrangeDisbursementCallbackControllerIT.java, DisbursementWebhookDeliveryIT.java, DisbursementExpiryJobIT.java, MtnMoMoPortDisbursementCallbackTest.java, OrangeMoneyPortDisbursementCallbackTest.java
- **Commits:** a6d6aa7
- **Impact on Plan 03:** Plan 03 Task 1 (entity field removal) is now complete. Plan 03 should skip that task and proceed from Task 2 (DisbursementStatus enum extension).

## Known Stubs

None — all schema changes are fully wired and tested.

## Self-Check
