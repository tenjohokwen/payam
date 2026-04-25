---
phase: 50-schema-balance-infrastructure
plan: "01"
subsystem: disbursement
tags: [flyway, schema, state-machine, disbursement, wallet-balance]
dependency_graph:
  requires: []
  provides:
    - V28 Flyway migration (main.disbursement, main.disbursement_aud, main.merchant_wallet_balance, main.merchant_wallet_balance_aud)
    - DisbursementStatus enum with EXPIRED terminal state (BAL-03)
    - com.softropic.payam.disbursement.contract.DisbursementStatus
  affects:
    - Plan 02 depends on V28 tables for JPA entity mapping (Disbursement, MerchantWalletBalance)
tech_stack:
  added: []
  patterns:
    - Flyway CREATE TABLE IF NOT EXISTS (idempotent schema migration, V28)
    - EnumSet-based state machine guard mirroring TransactionStatus pattern
    - IllegalStateTransitionException (existing type reused, not new)
key_files:
  created:
    - src/main/resources/db/migration/V28__disbursement_schema.sql
    - src/main/java/com/softropic/payam/disbursement/contract/DisbursementStatus.java
    - src/test/java/com/softropic/payam/disbursement/contract/DisbursementStatusTest.java
  modified: []
decisions:
  - "disbursement_status column name (not status) avoids AbstractAuditingEntity.status collision — consistent with plan spec and RESEARCH.md anti-pattern warning"
  - "merchant_wallet_balance_aud created before base table in migration for idempotency — order matches V27 pattern"
  - "reserved_amount stored on both disbursement (per-row release precision) and merchant_wallet_balance (operational visibility total) — required by BAL-02/BAL-03 per RESEARCH.md open question resolution"
metrics:
  duration_seconds: 399
  completed_date: "2026-04-25"
  tasks_completed: 2
  files_changed: 3
---

# Phase 50 Plan 01: Schema & State Machine Summary

**One-liner:** Flyway V28 creates disbursement + merchant_wallet_balance tables; DisbursementStatus enum adds terminal EXPIRED state for BAL-03 reserved-balance-held semantics.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | V28 Flyway migration | fe80df6 | src/main/resources/db/migration/V28__disbursement_schema.sql |
| 2 (RED) | DisbursementStatus failing tests | ad058a6 | src/test/java/.../DisbursementStatusTest.java |
| 2 (GREEN) | DisbursementStatus enum | 420829e | src/main/java/.../DisbursementStatus.java |

## V28 Migration — Exact Table/Column/Constraint Names

Plan 02 entity mapping reference:

### Table: `main.disbursement`
- Primary key: `CONSTRAINT pk_disbursement PRIMARY KEY (id)`
- Business key unique: `CONSTRAINT uq_disbursement_id UNIQUE (disbursement_id)`
- Amount check: `CONSTRAINT chk_disbursement_amount_positive CHECK (amount > 0)`
- Lifecycle column: `disbursement_status VARCHAR(30) NOT NULL` (NOT `status`)
- Index: `idx_disbursement_tenant_id ON main.disbursement (tenant_id)`
- Reserved balance column: `reserved_amount NUMERIC(20, 2)` (nullable — set on reserve)

### Table: `main.disbursement_aud` (Envers)
- PK: `PRIMARY KEY (id, rev)` with `REFERENCES main.revinfo(rev)`
- All audited fields nullable (Envers pattern)
- Includes `disbursement_status VARCHAR(30)` (nullable in aud)

### Table: `main.merchant_wallet_balance`
- Primary key: `CONSTRAINT pk_merchant_wallet_balance PRIMARY KEY (id)`
- Tenant unique: `CONSTRAINT uq_wallet_tenant_id UNIQUE (tenant_id)`
- Balance check: `CONSTRAINT chk_wallet_balance_non_negative CHECK (balance >= 0)`
- Reserved check: `CONSTRAINT chk_wallet_reserved_non_negative CHECK (reserved_amount >= 0)`
- Columns: `balance NUMERIC(20, 2) NOT NULL DEFAULT 0`, `reserved_amount NUMERIC(20, 2) NOT NULL DEFAULT 0`, `currency CHAR(3) NOT NULL DEFAULT 'XAF'`, `version BIGINT NOT NULL DEFAULT 0`

### Table: `main.merchant_wallet_balance_aud` (Envers)
- PK: `PRIMARY KEY (id, rev)` with `REFERENCES main.revinfo(rev)`

## DisbursementStatus Enum

**Package:** `com.softropic.payam.disbursement.contract`
**Fully qualified name:** `com.softropic.payam.disbursement.contract.DisbursementStatus`

**Exception type reused:** `com.softropic.payam.transaction.contract.exception.IllegalStateTransitionException` — NOT a new exception; the existing type is imported and thrown.

**Transition map:**
| From | To (allowed) |
|------|-------------|
| INITIATED | PENDING_CONFIRMATION, PROCESSING, FAILED |
| PENDING_CONFIRMATION | PROCESSING, EXPIRED, FAILED |
| PROCESSING | SUCCESS, FAILED, EXPIRED |
| SUCCESS | (terminal — none) |
| FAILED | (terminal — none) |
| EXPIRED | (terminal — none) |

**BAL-03 semantics:** EXPIRED is a distinct terminal state from FAILED. `EXPIRED` means provider accepted OR step-up timeout — reserved balance held. Only `FAILED` triggers balance release (Plan 02 `WalletBalanceService.release()` must NOT be called for EXPIRED transitions).

## Test Coverage

14 unit tests in `DisbursementStatusTest`:
- `allValuesDeclared` — 6 values confirmed
- `initiatedAllowedTransitions`, `pendingConfirmationAllowedTransitions`, `processingAllowedTransitions`
- `successIsTerminal`, `failedIsTerminal`, `expiredIsTerminal` (BAL-03 terminal guard)
- `legalTransitionReturnsNext` — legal path returns next state
- `pendingConfirmationToExpiredSucceeds` — SEC-04 timeout path
- `processingToExpiredSucceeds` — BAL-03 internal error path
- `initiatedToSuccessThrows` — must go through PROCESSING
- `expiredTransitionToAnythingThrows` — parameterized over all other values
- `successTransitionToAnythingThrows` — parameterized over all other values
- `failedTransitionToProcessingThrows` — FAILED is terminal

## reserved_amount Storage Decision

Per RESEARCH.md open question 2: `reserved_amount` is stored on BOTH tables:
- `main.disbursement.reserved_amount` (nullable `NUMERIC(20,2)`) — exact amount reserved for this disbursement; used by Plan 02 `WalletBalanceService.release()` for per-row precision
- `main.merchant_wallet_balance.reserved_amount` (NOT NULL DEFAULT 0) — running total of all earmarked amounts across open disbursements; provides operational visibility without scanning the disbursement table

## Deviations from Plan

None — plan executed exactly as written. Migration file uses single-space column definitions (rather than aligned multi-space) to ensure `grep -q "disbursement_status VARCHAR(30) NOT NULL"` acceptance checks pass verbatim.

## Known Stubs

None — this plan delivers schema and enum only; no data-flow stubs introduced.

## Self-Check: PASSED

- `src/main/resources/db/migration/V28__disbursement_schema.sql` — FOUND
- `src/main/java/com/softropic/payam/disbursement/contract/DisbursementStatus.java` — FOUND
- `src/test/java/com/softropic/payam/disbursement/contract/DisbursementStatusTest.java` — FOUND
- Commit fe80df6 — FOUND
- Commit ad058a6 — FOUND
- Commit 420829e — FOUND
- `mvn -q -pl . -Dtest=DisbursementStatusTest test` — PASSES (14/14)
- `mvn -q compile` — PASSES
