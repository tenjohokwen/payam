# Milestone v11 Requirements — Transaction-Backed Disbursements

**Milestone:** v11
**Status:** Active
**Defined:** 2026-05-02
**Source:** `requirements/transaction-backed-disbursements.md`
**Core Value:** Reliable, fraud-resistant payment processing with full traceability — no double charges, no blind trust of webhooks, no silent failures.

---

## v11 Requirements

### TXN — Transaction Validation & Claim Locking

- [ ] **TXN-01**: Tenant supplies `transactionIds` (non-empty, max 500 UUIDs) in `DisbursementRequest`; system rejects if any transaction does not belong to the requesting tenant, returning `422 INVALID_TRANSACTION`
- [ ] **TXN-02**: System rejects a disbursement where any supplied transaction has `txStatus != SUCCESS` or `flow != COLLECTION`, returning `422 INVALID_TRANSACTION`
- [ ] **TXN-03**: System rejects a disbursement where any supplied transaction has an active claim (`ref_status IN ('PENDING', 'CLAIMED')` in `disbursement_transaction_ref`), returning `422 TRANSACTION_CLAIMED`
- [ ] **TXN-04**: System rejects a disbursement where `disbursement.amount != SUM(disbursableAmount)` across all supplied transactions (`disbursableAmount = transaction.amount - feeAmount`), returning `422 AMOUNT_MISMATCH`
- [ ] **TXN-05**: System performs claim validation and creation atomically via `SELECT FOR UPDATE` on `Transaction` rows ordered by `transaction_id` ascending (lexicographic) within a single database transaction to prevent deadlocks
- [ ] **TXN-06**: For pre-Phase-10 collection transactions with `fee_amount IS NULL`, system treats `feeAmount = 0` so the full `transaction.amount` is disbursable

### CLAIM — Claim Lifecycle

- [ ] **CLAIM-01**: System creates a `DisbursementTransactionRef` row in `PENDING` state for each supplied transaction when a disbursement is accepted (atomically with disbursement creation, enforced by the partial unique index)
- [ ] **CLAIM-02**: System transitions all claims from `PENDING` to `CLAIMED` when the disbursement reaches `SUCCESS`
- [ ] **CLAIM-03**: System transitions all claims to `RELEASED` when the disbursement reaches `FAILED` for any reason, including Insufficient Funds — released transactions are available for future disbursements
- [ ] **CLAIM-04**: System transitions all claims to `RELEASED` when a `PENDING_ADMIN_APPROVAL` disbursement auto-expires — released transactions are available for future disbursements
- [ ] **CLAIM-05**: System retains claims in `CLAIMED` state when a `PROCESSING` disbursement transitions to `EXPIRED` due to an internal error — claims remain held pending ops reconciliation with the provider

### ADMIN — Admin Approval Flow

- [ ] **ADMIN-01**: Disbursements where `amount > payam.disbursement.admin-approval-threshold` (default: 500,000 XAF, configurable) transition to `PENDING_ADMIN_APPROVAL` instead of dispatching to the provider; the existing `PENDING_CONFIRMATION` merchant step-up flow is unchanged and co-exists
- [ ] **ADMIN-02**: System stores `admin_note` (TEXT, nullable) on the disbursement row on transition to `PENDING_ADMIN_APPROVAL`; the field is never returned in the public merchant API response; system sends best-effort email/Slack notification to Platform Ops
- [ ] **ADMIN-03**: System auto-expires `PENDING_ADMIN_APPROVAL` disbursements after `payam.disbursement.admin-approval-timeout-hours` (default: 24 h), transitioning to `EXPIRED` and releasing all associated claims

### FEE — Fee Exemption

- [ ] **FEE-01**: Disbursement initiation bypasses `FeeEvaluationService`; `DisbursementResponse.fee` is always `BigDecimal.ZERO`; no fee rule is evaluated or stored
- [ ] **FEE-02**: Any `Transaction` row written for a disbursement payout (`flow = DISBURSEMENT`) has `feeAmount = 0` and `feeRuleId = NULL`

### IDEM — Idempotency Retry Recovery

- [ ] **IDEM-01**: System retries a `FAILED` disbursement with a retriable error code (`TIMEOUT`, `SYSTEM_ERROR`, `HTTP_5xx`) when the same `Idempotency-Key` is resent, provided all original transaction claims remain unclaimed (no active PENDING/CLAIMED ref for any of the original `transactionIds`)
- [ ] **IDEM-02**: On successful retry validation, system reactivates the existing RELEASED `DisbursementTransactionRef` rows for this disbursement to `PENDING` (does not insert new rows — preserving audit trail), increments `retry_count`, and transitions the disbursement from `FAILED` to `INITIATED`
- [ ] **IDEM-03**: System returns the cached `FAILED` response for terminal error codes (`ADMIN_REJECTED`, `INVALID_RECIPIENT`, `INSUFFICIENT_PROVIDER_FUNDS`) when the same `Idempotency-Key` is resent — no retry permitted

### ALERT — Monitoring

- [ ] **ALERT-01**: When a provider returns an error code mapping to Insufficient Funds, system transitions the disbursement to `FAILED`, releases all claims to `RELEASED`, and triggers a high-priority alert (Slack/PagerDuty/Email) to Platform Ops identifying the affected provider account and its need for liquidity

### SCHEMA — Database Migrations

- [ ] **SCHEMA-01**: V31 migration creates `disbursement_transaction_ref` table with columns (`id` UUID PK, `disbursement_id` UUID FK, `transaction_id` UUID FK, `ref_status` ENUM, `created_date` TIMESTAMP) and a partial unique index on `(transaction_id)` WHERE `ref_status IN ('PENDING', 'CLAIMED')` — enforces TXN-03 at the database level
- [ ] **SCHEMA-02**: V31 migration adds `admin_note` (TEXT, nullable) and `retry_count` (INT NOT NULL DEFAULT 0) to the `disbursement` table, and removes the `reserved_amount` column
- [ ] **SCHEMA-03**: V31 migration includes a pre-flight assertion that no `disbursement` row with `disbursement_status IN ('PROCESSING', 'PENDING_CONFIRMATION')` exists — migration fails fast if found; `merchant_wallet_balance` table is retired at the application layer (all reads/writes removed from code) but the table is not dropped in V31
- [ ] **SCHEMA-04**: V32 migration drops `merchant_wallet_balance` (and its audit counterpart) after ops confirm all pre-V31 disbursements have reached a terminal state

---

## Future Requirements (Deferred to v12+)

- Admin wallet top-up endpoint — production funding mechanism (no longer needed for wallet, may evolve to liquidity management)
- Batch disbursements (`POST /v1/disbursements/batch`) — significant new flow, single-disbursement API first
- Disbursement reversal — blocked until MTN/Orange expose reversal in their APIs
- Disbursement reconciliation report — extend existing daily reconciliation to cover disbursement flows
- Admin disbursement investigation UI — extend transaction search to cover disbursements
- Partial-amount disbursement with explicit `overagePolicy: BURN` flag — deferred; exact equality required in v11

---

## Out of Scope (Explicit Exclusions)

| Feature | Reason |
|---------|--------|
| `disbursement.amount < SUM(disbursableAmount)` ("transaction burning") | Silent merchant loss; exact equality required; partial disbursement requires explicit future design |
| Drop `merchant_wallet_balance` in V31 | Unsafe during migration window; deferred to V32 with ops sign-off |
| New fee rules for disbursements | Disbursements are permanently fee-exempt per FEE-01 |
| ML fraud signals for transaction-backed disbursements | Rule-based scoring sufficient; claim validation provides new layer of protection |
| Multi-currency disbursements | XAF only; consistent with existing constraint |

---

## Traceability

| REQ-ID | Phase | Plan | Status |
|--------|-------|------|--------|
| TXN-01 | Phase 55 | — | Pending |
| TXN-02 | Phase 55 | — | Pending |
| TXN-03 | Phase 55 | — | Pending |
| TXN-04 | Phase 55 | — | Pending |
| TXN-05 | Phase 55 | — | Pending |
| TXN-06 | Phase 55 | — | Pending |
| CLAIM-01 | Phase 56 | — | Pending |
| CLAIM-02 | Phase 56 | — | Pending |
| CLAIM-03 | Phase 56 | — | Pending |
| CLAIM-04 | Phase 56 | — | Pending |
| CLAIM-05 | Phase 56 | — | Pending |
| ADMIN-01 | Phase 56 | — | Pending |
| ADMIN-02 | Phase 56 | — | Pending |
| ADMIN-03 | Phase 56 | — | Pending |
| FEE-01 | Phase 55 | — | Pending |
| FEE-02 | Phase 55 | — | Pending |
| IDEM-01 | Phase 57 | — | Pending |
| IDEM-02 | Phase 57 | — | Pending |
| IDEM-03 | Phase 57 | — | Pending |
| ALERT-01 | Phase 56 | — | Pending |
| SCHEMA-01 | Phase 54 | — | Pending |
| SCHEMA-02 | Phase 54 | — | Pending |
| SCHEMA-03 | Phase 54 | — | Pending |
| SCHEMA-04 | Phase 57 | — | Pending |

**Coverage:**
- v11 requirements: 24 total
- Mapped to phases: 24 ✓
- Unmapped: 0 ✓

---

*Requirements defined: 2026-05-02*
*Last updated: 2026-05-01 — traceability table populated after roadmap creation*
