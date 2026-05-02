# Transaction-Backed Disbursements

**Status:** Ready for implementation  
**Author:** Engineering — Softropic  
**Date:** 2026-05-01

---

## 1. Background

Payam is the payments API used by Softropic's merchant applications. Merchants collect money from customers into Softropic's platform mobile money accounts. This document specifies a model in which every disbursement must be explicitly backed by a set of previously successful collection transactions. The legacy wallet balance model is removed.

---

## 2. Definitions

| Term | Meaning |
|------|---------|
| **Collection transaction** | A `Transaction` row with `flow = COLLECTION` and `txStatus = SUCCESS`. |
| **Transaction claim** | A `DisbursementTransactionRef` row locking a collection transaction to a disbursement. |
| **Disbursable amount** | `transaction.amount - transaction.feeAmount`. For legacy rows (pre-Phase 10) where `feeAmount IS NULL`, treat `feeAmount` as `0`, making the full `transaction.amount` disbursable. |
| **Retriable failure** | A technical failure (Timeout, Provider 5xx, System Error) that permits an idempotency retry. |
| **Terminal failure** | A business or permanent failure (Invalid recipient, Admin rejection, Insufficient Provider Funds) that blocks idempotency retries. |

---

## 3. Functional Requirements

### 3.1 `transactionIds` field on disbursement request

`DisbursementRequest` gains a required `transactionIds` field: a non-empty list of UUIDs, max 500 entries.

### 3.2 Transaction validation rules (TXN)

| Rule | Logic |
|------|-------|
| **TXN-01** | Must belong to the requesting tenant. |
| **TXN-02** | `txStatus` must be `SUCCESS`. |
| **TXN-03** | `flow` must be `COLLECTION`. |
| **TXN-04** | No active claim (no `DisbursementTransactionRef` row with `ref_status IN ('PENDING', 'CLAIMED')` for this `transaction_id`). |
| **TXN-05** | `disbursement.amount == SUM(disbursableAmount)` across all supplied transactions (exact equality). |

**Rationale for TXN-05 exact equality:** Allowing `disbursement.amount < SUM(disbursableAmount)` would silently forfeit the remainder to the platform with no merchant-visible confirmation. Requiring exact equality eliminates the class of accidental loss. If a merchant's transaction set does not sum exactly to the desired disbursement amount, they must adjust the transaction selection.

### 3.3 Concurrency & Atomicity

Validation and claim creation must execute in a single transaction using `SELECT FOR UPDATE` on `Transaction` rows ordered by `transaction_id` (lexicographic ascending). Consistent lock ordering prevents deadlocks when concurrent requests share overlapping transaction sets.

### 3.4 Admin Approval Flow

Disbursements where `amount > payam.disbursement.admin-approval-threshold` (default: 500,000 XAF, configurable) transition to `PENDING_ADMIN_APPROVAL`.

This state replaces and supersedes the existing merchant step-up `PENDING_CONFIRMATION` state for the admin-threshold path. The existing `POST /v1/disbursements/{id}/confirm` endpoint is **not** used for admin approval; the admin acts via a separate internal interface.

- **Admin note:** Stored on the disbursement row for internal audit; **not** returned in the public merchant API response.
- **Notification:** Best-effort email/Slack to Platform Ops on transition to `PENDING_ADMIN_APPROVAL`.
- **Timeout:** Configurable via `payam.disbursement.admin-approval-timeout-hours` (default: 24 h). On expiry, the disbursement transitions to `EXPIRED` and claims are released (`RELEASED`), freeing the transactions for future disbursements.

> **Note on `PENDING_CONFIRMATION`:** The existing merchant-facing step-up state remains valid for sub-threshold disbursements that require 2FA confirmation. It is not removed by this feature. Only the admin-approval path is new.

### 3.5 No Platform Fees on Disbursements

Platform fees **must not** be applied to disbursements.

- `DisbursementOrchestrator` must **not** call `FeeEvaluationService.evaluateFee()`. The fee calculation step is removed from the disbursement initiation flow.
- The `fee` field in `DisbursementResponse` must always be `BigDecimal.ZERO`.
- If a `Transaction` row is recorded for a disbursement payout (`flow = DISBURSEMENT`), its `feeAmount` must be `0` and `feeRuleId` must be `NULL`.
- This does **not** affect collection-side fees already deducted when computing `disbursableAmount` (§2). Those fees were charged at collection time and are separate.

### 3.6 Idempotency & Retriable Failure Recovery

When a request arrives with a duplicate `Idempotency-Key`:

1. **If status is `SUCCESS`, `PROCESSING`, or `PENDING_ADMIN_APPROVAL`:** Return the cached response immediately.
2. **If status is `FAILED`:**
   - Check whether the previous error is **Retriable** (`TIMEOUT`, `SYSTEM_ERROR`, `HTTP_5xx`).
   - **If retriable:**
     1. Lock and validate the original `transactionIds` (§3.2, §3.3) to confirm they are still unclaimed (no active `PENDING`/`CLAIMED` ref exists; the prior `RELEASED` refs for this disbursement do not block this check).
     2. Reactivate the existing `DisbursementTransactionRef` rows for this disbursement from `RELEASED` → `PENDING` (do **not** insert new rows; preserving row identity maintains the audit trail).
     3. Transition the disbursement record from `FAILED` → `INITIATED`.
     4. Increment `retry_count`.
     5. Continue with provider dispatch.
   - **If terminal** (`ADMIN_REJECTED`, `INVALID_RECIPIENT`, `INSUFFICIENT_PROVIDER_FUNDS`): Return the cached `FAILED` response.

### 3.7 Monitoring & "Insufficient Funds" Alert

Since platform balances are not tracked internally, the system must react to provider responses.

If a provider returns an error code mapping to **Insufficient Funds**, Payam must:
- Transition the disbursement to `FAILED`.
- Release all claims (`ref_status → RELEASED`).
- **Trigger a high-priority alert** (Slack/PagerDuty/Email) to Platform Ops, naming the provider account that requires liquidity.

---

## 4. Claim Lifecycle

Claims are created in `PENDING` state when the disbursement is validated and accepted. They advance to `CLAIMED` on provider `SUCCESS`.

| Disbursement terminal state | Reached from | Claim `ref_status` | Notes |
|-----------------------------|-------------|--------------------|-------|
| `SUCCESS` | `PROCESSING` | `CLAIMED` | Funds confirmed transferred. |
| `FAILED` | Any state | `RELEASED` | Transactions available for future disbursements. |
| `EXPIRED` | `PENDING_ADMIN_APPROVAL` (timeout) | `RELEASED` | Transactions available for future disbursements. |
| `EXPIRED` | `PROCESSING` (internal error) | `CLAIMED` | **Ambiguous state — ops must reconcile with provider.** Claims remain `CLAIMED` because the provider may have already transferred funds. Do not release without provider confirmation. |

---

## 5. Data Model Changes

### 5.1 New table: `disbursement_transaction_ref`

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `disbursement_id` | UUID FK → `disbursement` | |
| `transaction_id` | UUID FK → `transaction` | |
| `ref_status` | ENUM(`PENDING`, `CLAIMED`, `RELEASED`) | |
| `created_date` | TIMESTAMP | |

**Index:** Unique partial index on `(transaction_id)` WHERE `ref_status IN ('PENDING', 'CLAIMED')`. This enforces TXN-04 at the database level.

### 5.2 `disbursement` table updates

- **Add** `admin_note` (TEXT, nullable) — internal only, never serialised in API response.
- **Add** `retry_count` (INT, default 0) — internal audit counter.
- **Remove** `reserved_amount` — superseded by claim-based locking.

### 5.3 `merchant_wallet_balance` deprecation

The wallet balance table is replaced by claim-based locking and **must not** be dropped in V31. It is deprecated in V31 (reads and writes removed from application code) and dropped in V32 after confirming no in-flight pre-V31 PROCESSING disbursements remain in the database. The V31 migration must add a migration-time assertion: no `disbursement` row in `PROCESSING` or `PENDING_CONFIRMATION` with `created_date < migration_timestamp`.

---

## 6. Migration Strategy

1. **V31 Migration:**
   - Create `disbursement_transaction_ref` table with partial unique index.
   - Add `admin_note`, `retry_count` columns to `disbursement`.
   - Drop `reserved_amount` from `disbursement`.
   - Soft-deprecate `merchant_wallet_balance` (remove application-layer reads/writes).
   - Assert no PROCESSING/PENDING_CONFIRMATION disbursements exist from before the migration (fail migration if found).

2. **V32 Migration:**
   - Drop `merchant_wallet_balance` after ops confirm no legacy disbursements remain open.

3. **Legacy fees (`feeAmount IS NULL`):** Pre-Phase-10 collection transactions with `fee_amount IS NULL` use `fee_amount = 0` for `disbursableAmount` computation — the full `transaction.amount` is disbursable.

4. **Legacy failed disbursements:** Pre-migration `FAILED` disbursements have no `DisbursementTransactionRef` rows and are therefore not retriable under the new idempotency logic. This is acceptable; they can be resubmitted with a fresh `Idempotency-Key`.
