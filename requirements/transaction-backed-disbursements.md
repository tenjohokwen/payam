# Transaction-Backed Disbursements

**Status:** Ready for implementation  
**Author:** Engineering — Softropic  
**Date:** 2026-05-01

---

## 1. Background

Payam (this application) is the payments API used by Softropic's merchant applications. Merchants collect money from
customers into Softropic's platform mobile money accounts (one per provider: MTN MoMo, Orange
Money). Merchants then request Payam to disburse money from those platform accounts to third-party
mobile money accounts (agency accounts, customer accounts, etc.).

The current disbursement model uses a wallet balance (`merchant_wallet_balance`) to gate
disbursements. This is a derived aggregate — it cannot prove *which* customer transactions funded
a given disbursement, making it weak against internal fraud, accidental over-disbursement, and
reconciliation disputes.

This document specifies a replacement model in which every disbursement must be explicitly
backed by a set of previously successful collection transactions. The wallet balance is removed.

---

## 2. Merchant Context

Two merchant applications are in scope.

**Mia** — a social platform, fully owned by Softropic. Collects payments from customers. Disburses
to customer mobile money accounts (e.g. cashouts, refunds). All revenue stays with Softropic.

**Gulliver** — a bus ticketing application, revenue shared between Softropic and bus agencies.
Gulliver collects ticket payments from customers and must disburse the agency share to agency
accounts. It tracks the Softropic commission internally; Payam does not need to know the
commission split. Gulliver may disburse immediately after a sale, or in batches (hourly, daily).

Both merchants use the same Payam disbursement endpoint. The requirements below apply uniformly.

---

## 3. Definitions

| Term | Meaning |
|------|---------|
| **Collection transaction** | A `Transaction` row with `flow = COLLECTION` and `txStatus = SUCCESS`, representing money paid by a customer into a Softropic platform account |
| **Disbursement** | A `Disbursement` row representing money moved from a Softropic platform account to a third-party MSISDN |
| **Transaction claim** | A `DisbursementTransactionRef` row that locks a collection transaction to a disbursement, preventing it from being used a second time |
| **Disbursable amount** | Net amount of a collection transaction available for disbursement: `transaction.amount - transaction.feeAmount` |
| **Provider** | The mobile money network: `MTN` or `ORANGE`. Determined by MSISDN routing |
| **Admin approval threshold** | Configurable XAF amount above which a disbursement requires admin sign-off before the provider is contacted |

---

## 4. Functional Requirements

### 4.1 `transactionIds` field on disbursement request

The `DisbursementRequest` gains a new required field: `transactionIds` — a non-empty list of
collection transaction UUIDs.

- Minimum 1 UUID. Maximum 500 UUIDs per request (prevent abusively large lists).
- Duplicates within the same request are rejected (HTTP 422).
- The field is part of the JSON request body (not a header).

### 4.2 Transaction validation rules

Before any disbursement row is created, Payam validates each referenced `transactionId`:

| Rule | Error if violated |
|------|------------------|
| **TXN-01** Each transaction must belong to the requesting tenant (`tenantId` match) | HTTP 422 `TRANSACTION_NOT_FOUND` |
| **TXN-02** Each transaction must have `txStatus = SUCCESS` | HTTP 422 `TRANSACTION_NOT_ELIGIBLE` |
| **TXN-03** Each transaction must have `flow = COLLECTION` | HTTP 422 `TRANSACTION_NOT_ELIGIBLE` |
| **TXN-04** None of the transactions may already have an active or successful claim (`ref_status IN ('PENDING', 'CLAIMED')`) | HTTP 422 `TRANSACTION_ALREADY_CLAIMED` |
| **TXN-05** `disbursement.amount ≤ SUM(transaction.amount - transaction.feeAmount)` across all referenced transactions | HTTP 422 `INSUFFICIENT_TRANSACTION_COVERAGE` |

There is **no provider-matching constraint** on referenced transactions. MTN collections may back
an Orange disbursement and vice versa. The disbursement provider is always determined solely by
the recipient MSISDN routing (MTN MSISDN → MTN platform account is debited and the MTN
disbursement API is used; Orange MSISDN → Orange platform account). Cross-provider treasury
management — ensuring the platform MTN account has sufficient XAF when funding an MTN
disbursement that was backed by Orange collections — is an operational concern outside Payam's
scope (see §10).

### 4.3 Claim table: `disbursement_transaction_ref`

A new table records which transactions are claimed by which disbursement.

**Schema (migration V31):**

```sql
CREATE TABLE main.disbursement_transaction_ref (
    id              BIGSERIAL PRIMARY KEY,
    disbursement_id UUID        NOT NULL REFERENCES main.disbursement(disbursement_id),
    transaction_id  UUID        NOT NULL REFERENCES main.transaction(transaction_id),
    ref_status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_date    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- One active claim per transaction at a time.
-- RELEASED rows fall outside this index, allowing re-use of the transaction.
CREATE UNIQUE INDEX uidx_dsb_txn_ref_active
    ON main.disbursement_transaction_ref(transaction_id)
    WHERE ref_status IN ('PENDING', 'CLAIMED');
```

**`ref_status` lifecycle:**

| Value | Meaning |
|-------|---------|
| `PENDING` | Disbursement initiated or awaiting admin approval; transaction is locked |
| `CLAIMED` | Disbursement reached `SUCCESS`, or reached `EXPIRED` from the `PROCESSING` state (money may have moved; ops must review before releasing) |
| `RELEASED` | Disbursement reached `FAILED`, or reached `EXPIRED` from the `PENDING_ADMIN_APPROVAL` state (provider was never contacted; transaction is safe to reuse) |

### 4.4 Concurrency safety

The validation and claim creation in §4.2 / §4.3 must be atomic. The implementation uses the
following sequence inside a single `TransactionTemplate`:

1. `SELECT ... FOR UPDATE` on each referenced `Transaction` row, **ordered by `transaction_id`
   ascending** to prevent deadlock when concurrent requests reference overlapping sets.
2. Perform all TXN-01 through TXN-05 checks on the locked rows.
3. Insert `DisbursementTransactionRef` rows (all with `ref_status = PENDING`).
4. Create the `Disbursement` row.

Because step 3 inserts into the partial unique index, a concurrent request referencing the same
transaction IDs will receive a unique-constraint violation at the DB level even if the application
check in step 2 raced ahead. The application must catch this and return HTTP 409 / error code
`TRANSACTION_ALREADY_CLAIMED`.

### 4.5 Removal of wallet balance

The following are removed entirely:

- `main.merchant_wallet_balance` table (drop in migration V31)
- `MerchantWalletBalance` entity
- `MerchantWalletBalanceRepository`
- `WalletBalanceService` (`checkAndReserve`, `release`)
- `Disbursement.reservedAmount` column (drop in migration V31)
- `InsufficientBalanceException`
- All wallet balance references in `DisbursementOrchestrator` (steps 6 and `releaseAndFail`)
- `INSUFFICIENT_BALANCE` error code

The transaction-claim check (§4.2 TXN-06) replaces the wallet balance as the sole funds
availability gate.

### 4.6 Removal of merchant step-up confirmation (SEC-04)

The following are removed entirely:

- `DisbursementStatus.PENDING_CONFIRMATION`
- `POST /v1/disbursements/{disbursementId}/confirm` endpoint
- `DisbursementOrchestrator.confirm()` method
- `PENDING_CONFIRMATION` logic in `DisbursementOrchestrator.initiate()`
- `DisbursementExpiryJob` handling for `PENDING_CONFIRMATION` aging

### 4.7 Admin approval flow

Disbursements whose `amount` strictly exceeds a configurable threshold require admin approval
before Payam contacts the mobile money provider.

**Configuration property (new):**

```yaml
payam:
  disbursement:
    admin-approval-threshold: 500000   # XAF; integer
    admin-approval-timeout-hours: 48   # hours before auto-expiry; integer
```

Both properties are required. There are no defaults in code — operators must set them explicitly,
or the application fails to start.

**New state: `PENDING_ADMIN_APPROVAL`**

Added to `DisbursementStatus`. Allowed transitions:

```
INITIATED → PENDING_ADMIN_APPROVAL → PROCESSING → SUCCESS
                                  ↓ (admin reject)
                                FAILED
                                  ↓ (timeout)
                                EXPIRED
```

Full updated state machine:

```
INITIATED ──────────────────────────────────────────────────────────────┐
    │                                                                    │
    ├──(amount > threshold)──→ PENDING_ADMIN_APPROVAL                   │
    │                              │ (admin approve)                     │
    │                              │                    (any)            │
    │                              ├──→ PROCESSING ──────────→ SUCCESS   │
    │                              │        │                    (terminal)
    │                              │        └──→ FAILED          │
    │                              │        └──→ EXPIRED         │
    │                              ├──(admin reject)──→ FAILED   │
    │                              └──(timeout)──→ EXPIRED       │
    │                                                             │
    └──(amount ≤ threshold)──→ PROCESSING ───────────────────────┘
                                   │
                              SUCCESS / FAILED / EXPIRED
```

**On transition to `PENDING_ADMIN_APPROVAL`:**

1. The disbursement row is created with status `PENDING_ADMIN_APPROVAL`. Transaction claims are
   inserted with `ref_status = PENDING`.
2. An email is sent to `payam.platform.notification-email` using the existing `MailManager`.
   - Subject: `[Payam] Disbursement approval required — {amount} XAF to {recipientMsisdn}`
   - Body must include: `disbursementId`, `tenantId`, `amount`, `currency`, `recipientMsisdn`,
     `reference`, list of referenced `transactionIds`, timestamp.
   - Mail failure is logged as ERROR but does not fail the request.
3. The API response returns HTTP 202 with status `PENDING_ADMIN_APPROVAL`.

**Admin API endpoints (new):**

```
GET  /v1/admin/disbursements
     Query params: status (optional, e.g. PENDING_ADMIN_APPROVAL), tenantId (optional),
                   page, size (max 100)
     Returns: paginated DisbursementListItem

POST /v1/admin/disbursements/{disbursementId}/approve
     Body: { "adminNote": "string (optional, max 500 chars)" }
     Requires: disbursement in PENDING_ADMIN_APPROVAL state
     Effect: transition → PROCESSING, proceed with provider dispatch (subscriber validation +
             provider call + transition to PROCESSING), update claims to CLAIMED on SUCCESS
     Returns: DisbursementResponse

POST /v1/admin/disbursements/{disbursementId}/reject
     Body: { "adminNote": "string (optional, max 500 chars)" }
     Requires: disbursement in PENDING_ADMIN_APPROVAL state
     Effect: transition → FAILED, set all claims to RELEASED
     Returns: DisbursementResponse
```

Admin endpoints are protected by the existing admin security context (same as
`AdminTransactionResource`). The `adminNote` is stored on the disbursement row (new column
`admin_note VARCHAR(500)`).

**Admin approval timeout:**

The existing `DisbursementExpiryJob` (Quartz) is updated to also age `PENDING_ADMIN_APPROVAL`
rows that have exceeded `payam.disbursement.admin-approval-timeout-hours`. On timeout:

- Transition disbursement to `EXPIRED`.
- Set all associated claims to `RELEASED` (provider was never contacted; transactions are safe
  to reuse).

This differs from `EXPIRED` reached from `PROCESSING` (where claims stay `CLAIMED`). The expiry
job determines the correct `ref_status` based on the disbursement's previous status before the
EXPIRED transition.

### 4.8 Claim release semantics by terminal state

| Terminal state | Reached from | Claim `ref_status` |
|---------------|-------------|-------------------|
| `SUCCESS` | `PROCESSING` | `CLAIMED` |
| `FAILED` | Any state | `RELEASED` |
| `EXPIRED` | `PENDING_ADMIN_APPROVAL` (timeout) | `RELEASED` |
| `EXPIRED` | `PROCESSING` (internal error / ops) | `CLAIMED` |

### 4.9 Idempotency

No change to existing idempotency behaviour. The `Idempotency-Key` header remains required.
The `DisbursementIdempotencyService` deduplicates within a 24-hour window using the `dsb:`
namespace. A replayed request returns the cached response without re-running validation or
creating new claim rows.

### 4.10 Disbursement initiation sequence (updated)

```
POST /v1/disbursements
    │
    1. Idempotency check — replay cached response if present
    2. Resolve disbursement provider from recipientMsisdn (MSISDN routing)
    3. Velocity checks (tenant/minute, tenant/hour, MSISDN/day) — unchanged
    4. Fraud evaluation — unchanged
    5. Fee evaluation — unchanged (fee is stored on disbursement row for audit)
    6. ┌ TransactionTemplate ──────────────────────────────────────┐
       │ a. SELECT FOR UPDATE on each Transaction (ordered by id)   │
       │ b. Validate TXN-01..TXN-05                                 │
       │ c. INSERT DisbursementTransactionRef rows (PENDING)         │
       │ d. INSERT Disbursement row (INITIATED or                    │
       │       PENDING_ADMIN_APPROVAL)                              │
       └────────────────────────────────────────────────────────────┘
    7. If PENDING_ADMIN_APPROVAL:
       │  a. Send admin notification email (async, best-effort)
       │  b. Store idempotency response (202)
       └  c. Return 202 PENDING_ADMIN_APPROVAL
    8. Validate recipient subscriber (provider call)
       └─ If inactive: set claims RELEASED, transition FAILED, return error
    9. Call provider initiateDisbursement (outside any DB transaction)
       └─ On error: set claims RELEASED, transition FAILED, return error
   10. ┌ TransactionTemplate ──────────────────────────────────────┐
       │ Transition disbursement → PROCESSING; store providerRef    │
       └────────────────────────────────────────────────────────────┘
   11. Store idempotency response (202 PROCESSING)
   12. Return 202 PROCESSING
```

### 4.11 Claim updates on callback / poller transitions

When a disbursement transitions to a terminal state via provider callback or status poller:

- `SUCCESS` → update all claims for that disbursement to `CLAIMED`
- `FAILED` → update all claims for that disbursement to `RELEASED`
- `EXPIRED` from `PROCESSING` → update all claims to `CLAIMED`

These updates run in the same `TransactionTemplate` as the disbursement state transition to
ensure atomicity.

---

## 5. Data Model Changes

### 5.1 New table (migration V31)

```sql
CREATE TABLE main.disbursement_transaction_ref (
    id              BIGSERIAL PRIMARY KEY,
    disbursement_id UUID        NOT NULL REFERENCES main.disbursement(disbursement_id),
    transaction_id  UUID        NOT NULL REFERENCES main.transaction(transaction_id),
    ref_status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_date    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uidx_dsb_txn_ref_active
    ON main.disbursement_transaction_ref(transaction_id)
    WHERE ref_status IN ('PENDING', 'CLAIMED');

CREATE INDEX idx_dsb_txn_ref_disbursement
    ON main.disbursement_transaction_ref(disbursement_id);
```

### 5.2 Modified: `main.disbursement` (migration V31)

Add columns:
```sql
ALTER TABLE main.disbursement
    ADD COLUMN admin_note VARCHAR(500);
```

Drop columns:
```sql
ALTER TABLE main.disbursement
    DROP COLUMN reserved_amount;
```

### 5.3 Add `PENDING_ADMIN_APPROVAL` to status check constraint (migration V31)

```sql
-- If there is an existing CHECK constraint on disbursement_status, add the new value.
-- Exact SQL depends on whether the constraint uses an enum type or a VARCHAR CHECK.
```

### 5.4 Drop wallet balance table (migration V31)

```sql
DROP TABLE main.merchant_wallet_balance;
DROP TABLE main.merchant_wallet_balance_aud; -- Envers audit table
```

### 5.5 New config properties

```yaml
payam:
  disbursement:
    admin-approval-threshold: 500000
    admin-approval-timeout-hours: 48
```

Both are bound to a new `DisbursementProperties` `@ConfigurationProperties` class. The
application fails to start if either is absent.

---

## 6. API Contract Changes

### 6.1 `POST /v1/disbursements` — updated request body

```json
{
  "recipientMsisdn": "+237692954629",
  "amount": 15000,
  "currency": "XAF",
  "reference": "GULLIVER-TXN-2026-001",
  "transactionIds": [
    "550e8400-e29b-41d4-a716-446655440000",
    "6ba7b810-9dad-11d1-80b4-00c04fd430c8"
  ],
  "description": "Agency payout March 2026",
  "metadata": "{\"agencyCode\": \"CMR-001\"}"
}
```

`transactionIds` is **required** (HTTP 400 if absent or empty).

### 6.2 New HTTP error codes

| Error code | HTTP status | Trigger |
|------------|------------|---------|
| `TRANSACTION_NOT_FOUND` | 422 | Referenced transaction does not exist or belongs to another tenant |
| `TRANSACTION_NOT_ELIGIBLE` | 422 | Transaction is not SUCCESS+COLLECTION |
| `TRANSACTION_ALREADY_CLAIMED` | 422 | Transaction already has PENDING or CLAIMED ref |
| `INSUFFICIENT_TRANSACTION_COVERAGE` | 422 | `amount > SUM(transaction.amount - feeAmount)` |
| `DUPLICATE_TRANSACTION_ID` | 422 | Same UUID appears more than once in the request list |

### 6.3 Removed endpoints

`POST /v1/disbursements/{disbursementId}/confirm` — removed. Clients referencing this endpoint
must be updated before deployment.

### 6.4 New admin endpoints

```
GET  /v1/admin/disbursements
POST /v1/admin/disbursements/{disbursementId}/approve
POST /v1/admin/disbursements/{disbursementId}/reject
```

Secured with existing admin authentication. Not accessible by tenant API keys.

---

## 7. Non-Functional Requirements

### 7.1 Concurrency

- Concurrent disbursement requests referencing overlapping transaction ID sets must not both
  succeed. The partial unique index is the last-resort guard; `SELECT FOR UPDATE` is the primary.
- Locking order: always lock `Transaction` rows in ascending `transaction_id` order to prevent
  deadlock.
- Claim inserts and disbursement row creation occur in the same `TransactionTemplate`.

### 7.2 Idempotency

- A replayed request (same `Idempotency-Key`) within 24 hours returns the cached response without
  any DB writes. No duplicate claim rows are created.

### 7.3 Observability

- Log a structured event (`operation = dsb_txn_validation`) at INFO level when validation passes,
  including `tenantId`, `disbursementId`, `transactionCount`, `sumDisbursable`, `requestedAmount`.
- Log a WARN event (`operation = dsb_txn_validation_failed`) when any TXN-0x rule fails, with the
  specific rule code.
- Log a WARN event (`operation = dsb_admin_approval_required`) when a disbursement is routed to
  `PENDING_ADMIN_APPROVAL`, including `disbursementId`, `amount`, `tenantId`.
- Log a WARN event (`operation = dsb_admin_approval_timeout`) when the expiry job ages a
  `PENDING_ADMIN_APPROVAL` row to `EXPIRED`.
- Existing Micrometer metrics for disbursement states are extended to include
  `PENDING_ADMIN_APPROVAL` and `RELEASED` claim state.

### 7.4 Atomicity

- Claim rows and disbursement row are created in one `TransactionTemplate`. If the disbursement
  row creation fails, the claim rows are rolled back.
- Claim `ref_status` updates (to `CLAIMED` or `RELEASED`) on terminal transitions run in the
  same `TransactionTemplate` as the disbursement status transition.

---

## 8. Test Requirements

### 8.1 Tests to remove or rewrite

| Existing test | Action |
|---------------|--------|
| `DisbursementOrchestratorIT` — SEC-04 step-up (PENDING_CONFIRMATION path) | Remove step-up test cases; replace with PENDING_ADMIN_APPROVAL cases (§8.2) |
| `DisbursementOrchestratorIT` — insufficient balance | Remove; no wallet balance |
| `DisbursementOrchestratorIT` — wallet reservation / release | Remove |
| `DisbursementResourceIT` — `POST /confirm` endpoint | Remove |
| `DisbursementExpiryJobIT` — PENDING_CONFIRMATION aging | Rewrite for PENDING_ADMIN_APPROVAL timeout |
| `DisbursementIdempotencyIT` — any wallet balance assertions | Remove wallet balance assertions |

### 8.2 New integration tests required

**Transaction validation (`DisbursementTxnValidationIT`)**

- Happy path: valid request with 2 MTN SUCCESS COLLECTION transactions, disbursement to MTN MSISDN; disbursement created.
- Happy path (cross-provider): MTN and Orange SUCCESS COLLECTION transactions mixed; disbursement to Orange MSISDN; accepted — no provider constraint on referenced transactions.
- TXN-01: transaction belongs to a different tenant → 422 `TRANSACTION_NOT_FOUND`.
- TXN-02: transaction has `txStatus = FAILED` → 422 `TRANSACTION_NOT_ELIGIBLE`.
- TXN-03: transaction has `flow = DISBURSEMENT` → 422 `TRANSACTION_NOT_ELIGIBLE`.
- TXN-04: transaction already has a PENDING claim → 422 `TRANSACTION_ALREADY_CLAIMED`.
- TXN-04: transaction already has a CLAIMED ref → 422 `TRANSACTION_ALREADY_CLAIMED`.
- TXN-04: transaction has a RELEASED ref → disbursement allowed (ref is reusable).
- TXN-05: `amount > SUM(net)` → 422 `INSUFFICIENT_TRANSACTION_COVERAGE`.
- TXN-05: `amount == SUM(net)` → accepted (boundary case).
- Duplicate UUID in request list → 422 `DUPLICATE_TRANSACTION_ID`.
- Empty `transactionIds` list → 400.
- Transaction list with 500 UUIDs (maximum) → accepted.
- Transaction list with 501 UUIDs → 422.

**Claim lifecycle (`DisbursementClaimLifecycleIT`)**

- Successful disbursement: after SUCCESS callback, claims transition to `CLAIMED`.
- Failed disbursement (provider error): claims transition to `RELEASED`; same transaction IDs
  can be used in a subsequent disbursement.
- Failed disbursement (subscriber inactive): claims transition to `RELEASED`.
- Expired disbursement from PENDING_ADMIN_APPROVAL (timeout): claims transition to `RELEASED`;
  same transaction IDs can be used again.
- Expired disbursement from PROCESSING: claims stay `CLAIMED`.

**Concurrency (`DisbursementConcurrencyClaimIT`)**

- Two concurrent requests referencing the same transaction ID: exactly one succeeds, one returns
  `TRANSACTION_ALREADY_CLAIMED`. No duplicate claim rows.
- Verify via thread barrier: both threads reach the lock acquisition point simultaneously.
- Verify no deadlock when requests reference overlapping sets in reverse ID order.

**Admin approval flow (`DisbursementAdminApprovalIT`)**

- Request with `amount > admin-approval-threshold`: disbursement created with
  `PENDING_ADMIN_APPROVAL`; email sent.
- Request with `amount == admin-approval-threshold`: disbursement proceeds directly to provider.
- Request with `amount > threshold` but below threshold after fee: disbursement proceeds directly
  (threshold is compared against request `amount`, not `amount + fee`).
- Admin approves: disbursement proceeds to `PROCESSING`; on SUCCESS callback, claims become
  `CLAIMED`.
- Admin rejects: disbursement becomes `FAILED`; claims become `RELEASED`.
- Admin approves an already-FAILED disbursement: 422 `INVALID_STATE`.
- Approve endpoint called by a non-admin tenant key: 403.
- Timeout: after `admin-approval-timeout-hours`, expiry job transitions to `EXPIRED`; claims
  become `RELEASED`.
- Admin notification email contains correct `disbursementId`, `amount`, `transactionIds`.

**Admin list endpoint (`DisbursementAdminListIT`)**

- `GET /v1/admin/disbursements?status=PENDING_ADMIN_APPROVAL` returns only matching rows.
- Pagination (page/size) works correctly.
- Filtering by `tenantId` works correctly.
- Non-admin caller receives 403.

**Idempotency regression (`DisbursementIdempotencyIT` — update existing)**

- Replayed request with same `Idempotency-Key` returns cached response; no new claim rows created
  (assert `disbursement_transaction_ref` row count unchanged after replay).

### 8.3 Test data helpers

Integration tests must be able to seed:
- `Transaction` rows with `txStatus = SUCCESS`, `flow = COLLECTION`, specific `provider` and
  `feeAmount`, for a given `tenantId`.
- `DisbursementTransactionRef` rows with a given `ref_status`, to pre-populate claim state.

These can be JPA saves within `@Transactional` test setup methods, or a dedicated test fixture
helper class.

---

## 9. Migration Strategy

### 9.1 Backward compatibility

The `/confirm` endpoint is removed. Neither Mia nor Gulliver currently calls it, so no
merchant-side coordination is required before deployment. The endpoint must not exist
post-deployment; any future client that discovers it via documentation should be directed to
the admin approval flow.

### 9.2 Flyway migration V31

A single migration covers all schema changes:

1. Create `disbursement_transaction_ref` + indexes.
2. Add `admin_note` column to `disbursement`.
3. Drop `reserved_amount` column from `disbursement`.
4. Drop `merchant_wallet_balance` and `merchant_wallet_balance_aud` tables.
5. Add `PENDING_ADMIN_APPROVAL` to any disbursement status constraint.
6. `PENDING_CONFIRMATION` rows that exist at the time of migration: transition to `EXPIRED`
   (ops to review). This is a one-time data migration within V31.

### 9.3 `PENDING_CONFIRMATION` data in production

If any `PENDING_CONFIRMATION` rows exist at migration time, the V31 migration script transitions
them to `EXPIRED`. Since they have no transaction claims (old model), no claim rows need updating.
Ops should review these rows post-migration.

---

## 10. Out of Scope

- Cross-provider treasury management (ensuring the platform MTN account has sufficient funds for
  MTN disbursements is an ops concern outside Payam).
- Softropic commission calculation — Gulliver tracks this internally; Payam does not.
- Partial claiming (a transaction's net amount is either fully available or fully claimed; no
  partial allocation per disbursement).
- Self-service admin notification preferences — the notification email is a single platform
  property.
- Webhook delivery to merchants on `PENDING_ADMIN_APPROVAL` status — the API response is
  sufficient; no outbound webhook for this status.
