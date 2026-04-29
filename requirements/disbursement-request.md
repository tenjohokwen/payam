# Disbursement Request API

### Client-Initiated Payouts via MTN MoMo and Orange Money (Cameroon)

---

# 1. Context

The existing payment API handles **collections**: a customer authorises a debit on their mobile wallet and the merchant receives funds. This document specifies the inverse flow — **disbursements** — where a pre-funded merchant wallet sends money out to a recipient's mobile wallet. Typical use cases: salary payouts, customer refunds, agent cashouts, loan disbursements.

The implementation builds directly on the infrastructure already in place:

- `MtnMoMoClient.transfer()` / `getTransferStatus()` — partially wired, needs an orchestrator
- `OrangeMoneyClient.ic2cTransfer()` — internal channel-to-customer transfer (merchant payout)
- `FeeEvaluationService` — fee computation, unchanged
- `LedgerService` (after the DISBURSEMENT flow from `payam-ledger.md` is implemented)
- `ApiKeyAuthenticationFilter` / `TenantPrincipal` — tenant isolation, unchanged
- `IdempotencyKeyRepository` — Redis-backed, 24-hour TTL, unchanged

---

# 2. Objectives

1. Expose a single `POST /v1/disbursements` endpoint usable by all tenants.
2. Route each disbursement to the correct provider based on recipient MSISDN prefix.
3. Enforce the same idempotency, fraud, and audit standards as collections — with stricter controls where disbursements carry higher risk.
4. Post correct double-entry ledger entries (DISBURSEMENT flow: merchant wallet debit, customer credit, provider fee credit).
5. Deliver asynchronous result to the tenant via outbound webhook (same pipeline as payments).

---

# 3. Core Design Principles

### 3.1 Disbursement-Specific Risk Profile

Disbursements are **higher risk than collections**:

- Money leaves the platform; errors are harder to reverse than declined debit requests.
- A fraudulent or misconfigured disbursement drains the merchant's pre-funded balance immediately.
- Social-engineering attacks commonly target cashout flows.

The fraud controls and approval gates described in §7 reflect this elevated risk profile.

### 3.2 Pre-Funded Balance Gating

A disbursement **must not reach the provider** unless the tenant's `MERCHANT_WALLET` ledger balance covers `principal + fee`. The balance check is performed inside the orchestrator, within the same database transaction that reserves the funds (optimistic lock on the wallet balance row). A failed balance check returns `HTTP 422` with code `INSUFFICIENT_BALANCE` — it is not retried.

### 3.3 Idempotency by Default

All write operations require an `Idempotency-Key` header. Behaviour is identical to the collections path: Redis NX atomic set, 24-hour TTL, duplicate requests within TTL return the cached response.

### 3.4 Provider Agnosticism

Callers supply a recipient MSISDN. The orchestrator resolves the provider (MTN or Orange) via `MsisdnPrefixRoute` — the same routing table already used for collections. No provider-specific field is ever exposed in the public API.

---

# 4. API Contract

## 4.1 Initiate Disbursement

```
POST /v1/disbursements
Authorization: Bearer <api_key>
Idempotency-Key: <client_uuid>
Content-Type: application/json
```

### Request Body

```json
{
  "recipientMsisdn": "237671234567",
  "amount": 5000,
  "currency": "XAF",
  "reference": "PAY-2024-001",
  "description": "July salary",
  "metadata": {
    "employeeId": "EMP-099"
  }
}
```

| Field            | Type   | Required | Rules                                                    |
|------------------|--------|----------|----------------------------------------------------------|
| `recipientMsisdn`| string | yes      | E.164 format, Cameroon (+237) only, validated active     |
| `amount`         | number | yes      | Positive integer, XAF minimum unit (no decimals)         |
| `currency`       | string | yes      | `"XAF"` — sole supported currency at launch              |
| `reference`      | string | yes      | Max 50 chars, unique per tenant (used as external ref)   |
| `description`    | string | no       | Max 140 chars, passed as payee note to provider          |
| `metadata`       | object | no       | Arbitrary key-value pairs, stored on transaction, max 2 KB|

### Success Response — `202 Accepted`

```json
{
  "disbursementId": "dsb_01j2x3y4z5",
  "status": "PROCESSING",
  "recipientMsisdn": "237671234567",
  "amount": 5000,
  "fee": 50,
  "currency": "XAF",
  "reference": "PAY-2024-001",
  "provider": "MTN",
  "createdAt": "2024-07-15T10:23:45Z"
}
```

`202 Accepted` signals that the provider has accepted the transfer request. The final outcome arrives via webhook (§10).

### Error Responses

| HTTP | Code                    | Meaning                                              |
|------|-------------------------|------------------------------------------------------|
| 400  | `VALIDATION_ERROR`      | Missing or invalid field                             |
| 401  | `UNAUTHORIZED`          | Missing or invalid API key                           |
| 409  | `DUPLICATE_REQUEST`     | Idempotency-Key already used for a different payload |
| 422  | `INSUFFICIENT_BALANCE`  | Merchant wallet balance < principal + fee            |
| 422  | `RECIPIENT_NOT_FOUND`   | MSISDN not active on the provider's network          |
| 422  | `AMOUNT_BELOW_MINIMUM`  | Amount below provider minimum transfer threshold     |
| 422  | `DAILY_LIMIT_EXCEEDED`  | Tenant or recipient daily disbursement cap reached   |
| 429  | `RATE_LIMIT_EXCEEDED`   | Velocity threshold hit (see §7)                      |
| 503  | `PROVIDER_UNAVAILABLE`  | Provider API unreachable after retries               |

---

## 4.2 Query Disbursement Status

```
GET /v1/disbursements/{disbursementId}
Authorization: Bearer <api_key>
```

### Response — `200 OK`

```json
{
  "disbursementId": "dsb_01j2x3y4z5",
  "status": "SUCCESS",
  "recipientMsisdn": "237671234567",
  "amount": 5000,
  "fee": 50,
  "currency": "XAF",
  "reference": "PAY-2024-001",
  "provider": "MTN",
  "providerTransactionId": "mtn_fin_abc123",
  "createdAt": "2024-07-15T10:23:45Z",
  "completedAt": "2024-07-15T10:24:12Z"
}
```

Tenant-scoped: a tenant can only retrieve their own disbursements. Attempting to fetch another tenant's `disbursementId` returns `404 Not Found`.

---

# 5. Disbursement State Machine

```
INITIATED
  ├─[balance check fails]       → FAILED (no provider call; funds never reserved)
  ├─[recipient invalid]         → FAILED (no provider call)
  ├─[fraud blocked]             → FAILED (no provider call)
  └─[provider accepted: 202]    → PROCESSING

PROCESSING
  ├─[webhook SUCCESS + verified] → SUCCESS  (ledger posted, outbound webhook delivered)
  ├─[webhook FAILED + verified]  → FAILED   (balance reserved entry reversed)
  └─[polling timeout ~10 min]    → EXPIRED  (human review required; treated as FAILED for tenant)

SUCCESS  (terminal)
FAILED   (terminal — balance reservation reversed)
EXPIRED  (terminal — ops investigation required)
```

All state transitions append an event to `PaymentEventLog` (hash-chained, immutable). No state is ever overwritten.

---

# 6. Orchestration Flow

```
Client → POST /v1/disbursements
           │
           ▼
  ApiKeyAuthenticationFilter
  (resolves TenantPrincipal)
           │
           ▼
  DisbursementOrchestrator.initiate(tenantId, request)
     │
     ├─ 1. Idempotency check (Redis NX)
     │       └─ HIT → return cached DisbursementResponse (no-op)
     │
     ├─ 2. Input validation
     │       └─ FAIL → 400 ValidationError
     │
     ├─ 3. Fraud scoring (FraudScoringService)
     │       └─ score > 80 → 422 / FAILED event logged
     │
     ├─ 4. Route MSISDN → provider (MsisdnPrefixRoute)
     │
     ├─ 5. Validate recipient account holder (MobileMoneyPort.validateAccountHolder)
     │       └─ INACTIVE → 422 RECIPIENT_NOT_FOUND
     │
     ├─ 6. Compute fee (FeeEvaluationService)
     │
     ├─ 7. Balance gate — atomic check-and-reserve on MERCHANT_WALLET
     │       └─ INSUFFICIENT → 422 INSUFFICIENT_BALANCE
     │
     ├─ 8. Create Transaction (status=INITIATED, flow=DISBURSEMENT)
     │
     ├─ 9. Call provider
     │       ├─ MTN  → MtnMoMoPort.transfer(referenceId, DisbursementRequest)
     │       └─ Orange → OrangeMoneyPort.ic2cTransfer(request)
     │         └─ FAIL → Transaction status=FAILED; balance reservation reversed
     │
     ├─ 10. Transition → PROCESSING
     │
     ├─ 11. Post ledger entries (LedgerPosting.disbursement(principal, fee, currency))
     │
     └─ 12. Return DisbursementResponse (status=PROCESSING) → 202 Accepted


Async — provider callback:

  MtnDisbursementCallbackController  (or OrangeDisbursementCallbackController)
     │
     ├─ Validate IP whitelist + signature
     ├─ Replay protection (deduplicate by provider reference)
     ├─ Double-check: call GET /disbursement/v1_0/transfer/{id} (or Orange status API)
     │     └─ Verify amount, MSISDN, status match
     ├─ Transition → SUCCESS or FAILED
     │     └─ FAILED → reverse balance reservation entry
     ├─ Append event (PaymentEventLog)
     └─ Deliver outbound webhook to tenant (async, with retry)
```

---

# 7. Security Controls

## 7.1 Authentication & Tenant Isolation

Identical to the collections path:

- `Authorization: Bearer <api_key>` required on every request.
- `ApiKeyAuthenticationFilter` resolves `TenantPrincipal`; all queries are scoped to `tenantId`.
- A disbursement record is invisible to any tenant other than the one that created it.

## 7.2 Velocity & Threshold Rules (Disbursement-Specific)

Disbursements apply **stricter velocity controls** than collections because funds leave the platform immediately:

| Dimension                     | Threshold (default)   | Action              |
|-------------------------------|-----------------------|---------------------|
| Disbursements / minute / tenant | > 20               | `429 RATE_LIMIT_EXCEEDED` |
| Disbursements / hour / tenant   | > 200              | `429 RATE_LIMIT_EXCEEDED` |
| Single disbursement amount      | > 500,000 XAF      | Requires elevated fraud score review |
| Total disbursed / day / tenant  | Configurable per tenant (default: 10,000,000 XAF) | `422 DAILY_LIMIT_EXCEEDED` |
| Disbursements to same MSISDN / hour | > 3           | Flag for review; continue if score ≤ 50 |
| Disbursements to same MSISDN / day  | > 10          | Block; `422 DAILY_LIMIT_EXCEEDED` |

Thresholds are stored per-tenant in the platform config and can be adjusted by an admin without a deployment. Defaults above are conservative starting points.

## 7.3 Fraud Scoring (Layered)

The same `FraudScoringService` used for collections runs on every disbursement, with adjusted signal weights:

| Signal                                  | Score adjustment |
|-----------------------------------------|------------------|
| New tenant API key (< 7 days old)       | +25              |
| Recipient MSISDN never seen before      | +15              |
| Amount > 3× tenant's median payout      | +30              |
| Same recipient, amount, reference in < 60 s | +40         |
| Recipient MSISDN on known-fraud list    | +80              |
| Provider reports subscriber inactive   | +60              |

Decision thresholds:

- **0–50** → Allow, proceed
- **51–80** → Allow but flag; alert sent to tenant ops dashboard
- **>80** → Block; transaction status set to FAILED with reason `FRAUD_BLOCK`

## 7.4 Idempotency

- Header: `Idempotency-Key: <client_uuid>` — mandatory; request rejected (`400`) if absent.
- Backend: Redis NX atomic set `idempotency:<tenantId>:<key>` → `disbursementId`, TTL 24 hours.
- On hit: return the original `DisbursementResponse` unchanged — the provider is never called again.
- Key collision (same key, different payload): `409 DUPLICATE_REQUEST`.

## 7.5 Webhook Callback Security

Incoming provider callbacks:

1. **IP whitelist** — accept only from MTN/Orange published IP ranges. Requests from unlisted IPs are silently dropped (no response that could aid reconnaissance).
2. **Signature verification** — MTN: `X-Callback-Signature` HMAC; Orange: `X-AUTH-TOKEN` header validation.
3. **Double-check pattern** — never trust the callback status at face value. After passing signature checks, call the provider's status API and verify `transactionId`, `amount`, `MSISDN`, and `status` match. Only then advance the transaction state.
4. **Replay protection** — deduplicate on `providerReferenceId`; a provider reference already in a terminal state is acknowledged (`200 OK`) but causes no state change.

## 7.6 Audit Trail

All events must be appended to `PaymentEventLog` (append-only, hash-chained):

```
DisbursementInitiated
FraudCheckPassed / FraudCheckBlocked
BalanceReserved / BalanceSufficiencyFailed
ProviderTransferSent
CallbackReceived
StatusVerified
DisbursementSucceeded / DisbursementFailed
OutboundWebhookDelivered
```

Each event carries: `transactionId`, `tenantId`, `actor`, `timestamp`, `metadata`, `previous_hash`, `hash`.

---

# 8. MTN MoMo — Disbursement Flow

MTN's Disbursement API is a separate product from Collections and requires a separate OAuth2 token.

## 8.1 Authentication

```
POST /disbursement/token
Authorization: Basic <base64(apiUser:apiKey)>
Ocp-Apim-Subscription-Key: <disbursement_subscription_key>
```

`MtnMoMoClient.fetchDisbursementToken()` already implements this. The token is short-lived; the client must refresh it before expiry.

## 8.2 Transfer Request

```
POST /disbursement/v1_0/transfer
X-Reference-Id: <UUID>
X-Target-Environment: production
Ocp-Apim-Subscription-Key: <disbursement_subscription_key>
Authorization: Bearer <disbursement_token>
Content-Type: application/json

{
  "amount": "5000",
  "currency": "XAF",
  "externalId": "<tenant_reference>",
  "payee": {
    "partyIdType": "MSISDN",
    "partyId": "237671234567"
  },
  "payerMessage": "Salary payment",
  "payeeNote": "July salary"
}
```

Response: `202 Accepted` (asynchronous). The `X-Reference-Id` UUID is the `providerReferenceId` stored on the transaction.

## 8.3 Status Polling (Fallback)

If the callback is not received within 5 minutes, the orchestrator polls:

```
GET /disbursement/v1_0/transfer/{X-Reference-Id}
Authorization: Bearer <disbursement_token>
```

Possible `status` values: `PENDING`, `SUCCESSFUL`, `FAILED`.

## 8.4 Sequence Diagram

```
DisbursementOrchestrator → MTN: POST /disbursement/v1_0/transfer
MTN → DisbursementOrchestrator: 202 Accepted
MTN → Recipient phone: USSD push (no action needed from recipient for merchant payout)
MTN (async) → DisbursementCallbackController: PUT /v1/callbacks/mtn/disbursement/{referenceId}
DisbursementCallbackController → MTN: GET /disbursement/v1_0/transfer/{referenceId}  (double-check)
DisbursementCallbackController → LedgerService: finalise entries
DisbursementCallbackController → WebhookDeliveryService: notify tenant
```

## 8.5 MTN-Specific Constraints

- Minimum transfer: **100 XAF**
- Maximum single transfer: **1,000,000 XAF** (confirm with MTN agreement)
- Recipient must be an active MTN MoMo subscriber
- The `externalId` field must be unique per API user; reusing the same `externalId` with the same subscription key will be rejected by MTN

---

# 9. Orange Money — Disbursement Flow

Orange uses the **Internal Channel-to-Customer (IC2C)** transfer endpoint for merchant-to-subscriber payouts. This is distinct from the Merchant Payment (MP) collection flow.

## 9.1 Authentication

```
POST /token
Authorization: Basic <base64(apiUsername:apiPassword)>
```

`OrangeMoneyClient.fetchToken()` already implements this. The bearer token is reused across calls until expiry.

The `X-AUTH-TOKEN` header (base64 of `apiUsername:apiPassword`) must also be included in disbursement requests.

## 9.2 IC2C Transfer

```
POST /ic2c/pay
Authorization: Bearer <access_token>
X-AUTH-TOKEN: <base64(apiUsername:apiPassword)>
Content-Type: application/json

{
  "subscriberMsisdn": "237691234567",
  "channelUserMsisdn": "<merchant_msisdn>",
  "amount": "5000",
  "orderId": "<tenant_reference>",
  "description": "July salary",
  "notifUrl": "https://payam.example.com/v1/callbacks/orange/disbursement"
}
```

| Field               | Notes                                                  |
|---------------------|--------------------------------------------------------|
| `subscriberMsisdn`  | Recipient — Orange subscriber to credit                |
| `channelUserMsisdn` | Merchant's Orange channel MSISDN (from platform config)|
| `amount`            | String representation of integer XAF amount            |
| `orderId`           | Unique per tenant; maps to `reference` field           |
| `notifUrl`          | Payam's callback URL for this disbursement             |

## 9.3 Status Check (Fallback)

Orange does not guarantee a callback delivery. Poll after 5 minutes if no callback:

```
GET /ic2c/paystatus/{payToken}
Authorization: Bearer <access_token>
X-AUTH-TOKEN: <base64(apiUsername:apiPassword)>
```

## 9.4 Sequence Diagram

```
DisbursementOrchestrator → Orange: POST /ic2c/pay
Orange → DisbursementOrchestrator: 200 OK (payToken, txnid)
Orange → Recipient phone: credit notification (SMS)
Orange (async) → OrangeDisbursementCallbackController: POST /v1/callbacks/orange/disbursement
OrangeDisbursementCallbackController → Orange: GET /ic2c/paystatus/{payToken}  (double-check)
OrangeDisbursementCallbackController → LedgerService: finalise entries
OrangeDisbursementCallbackController → WebhookDeliveryService: notify tenant
```

## 9.5 Orange-Specific Constraints

- Orange IC2C is a synchronous initiation (returns `200 OK` immediately) but final settlement is still asynchronous — do not mark SUCCESS until the callback confirms.
- The `channelUserMsisdn` must be the merchant MSISDN configured on the platform, not the recipient.
- Minimum transfer: **100 XAF**
- Orange charges a fee on outbound transfers; the fee percentage is stored in platform config per tenant agreement.

---

# 10. Outbound Webhook to Tenant

After a disbursement reaches a terminal state, Payam delivers a webhook to the tenant's configured URL:

```
POST <tenant_webhook_url>
Content-Type: application/json
X-Payam-Signature: <HMAC-SHA256(payload, webhook_secret)>

{
  "event": "disbursement.completed",
  "disbursementId": "dsb_01j2x3y4z5",
  "status": "SUCCESS",
  "recipientMsisdn": "237671234567",
  "amount": 5000,
  "fee": 50,
  "currency": "XAF",
  "reference": "PAY-2024-001",
  "provider": "MTN",
  "providerTransactionId": "mtn_fin_abc123",
  "completedAt": "2024-07-15T10:24:12Z"
}
```

For failures:

```json
{
  "event": "disbursement.failed",
  "disbursementId": "dsb_01j2x3y4z5",
  "status": "FAILED",
  "failureReason": "RECIPIENT_ACCOUNT_BLOCKED",
  ...
}
```

Delivery: same async retry pipeline used for collection webhooks. Tenant must return `2xx`; non-2xx triggers exponential backoff (max 5 retries over 24 hours).

---

# 11. Ledger Entries

On disbursement SUCCESS, `LedgerService.postEntry` is called with a `LedgerPosting.disbursement(principal, fee, currency)`. This produces three entries per the design in `payam-ledger.md`:

| Direction | Account Code      | Amount              |
|-----------|-------------------|---------------------|
| DEBIT     | `MERCHANT_WALLET` | principal + fee     |
| CREDIT    | `CUSTOMER_WALLET` | principal           |
| CREDIT    | `PROVIDER_FEE`    | fee                 |

On disbursement FAILED (after a transfer was initiated), the balance reservation is reversed:

| Direction | Account Code      | Amount              |
|-----------|-------------------|---------------------|
| DEBIT     | `PROVIDER_FEE`    | fee (reversal)      |
| DEBIT     | `CUSTOMER_WALLET` | principal (reversal)|
| CREDIT    | `MERCHANT_WALLET` | principal + fee     |

The `LedgerFlow.DISBURSEMENT` path in `LedgerService` must be implemented as specified in `payam-ledger.md` before the disbursement orchestrator can go live.

---

# 12. Monitoring & Alerts

The following metrics must be instrumented and visible on the ops dashboard:

| Metric                              | Alert threshold              |
|-------------------------------------|------------------------------|
| Disbursement success rate / 5 min   | < 90% → warning              |
| Disbursement success rate / 5 min   | < 70% → critical (PagerDuty) |
| Callback latency (initiation → terminal) | > 3 min → warning      |
| Balance reservation failures / hour | > 10 → warning               |
| Fraud blocks / hour                 | > 5% of volume → alert       |
| Provider API error rate             | > 5% → warning               |

Structured log event for every disbursement:

```json
{
  "timestamp": "...",
  "trace_id": "...",
  "disbursement_id": "...",
  "tenant_id": "...",
  "actor": "api",
  "action": "DISBURSEMENT_INITIATED",
  "status": "PROCESSING",
  "amount": 5000,
  "fee": 50,
  "currency": "XAF",
  "provider": "MTN",
  "recipient_msisdn_hash": "<sha256 of msisdn>",
  "fraud_score": 12,
  "ip": "..."
}
```

> Note: MSISDN is stored hashed in logs (SHA-256) for PII compliance. The raw MSISDN is retained only in the transaction record and ledger, accessible via the investigation dashboard.

---

# 13. Implementation Checklist

| # | Task | Depends on |
|---|------|------------|
| 1 | Implement `LedgerFlow.DISBURSEMENT` in `LedgerService` | `payam-ledger.md` |
| 2 | Create `DisbursementRequest` / `DisbursementResponse` DTOs in `disbursement/contract` | — |
| 3 | Create `DisbursementOrchestrator` service | 1, 4, 5 |
| 4 | Create `DisbursementResource` controller (`POST /v1/disbursements`, `GET /v1/disbursements/{id}`) | 2 |
| 5 | Create `DisbursementRepository` (mirrors `TransactionRepository`) | — |
| 6 | Wire `MtnMoMoPort.transfer()` into orchestrator (MTN client already exists) | 3 |
| 7 | Implement `OrangeMoneyPort.ic2cDisbursement()` wrapping `OrangeMoneyClient.ic2cTransfer()` | 3 |
| 8 | Create `MtnDisbursementCallbackController` | 6 |
| 9 | Create `OrangeDisbursementCallbackController` | 7 |
| 10 | Add balance-reservation logic to merchant wallet | 3 |
| 11 | Add disbursement-specific velocity rules to `FraudScoringService` | 3 |
| 12 | Add daily disbursement limit config to platform config | 3 |
| 13 | Add disbursement events to `PaymentEventLog` enum | 3 |
| 14 | Extend outbound webhook pipeline for `disbursement.completed` / `disbursement.failed` events | 3 |
| 15 | E2E tests for MTN and Orange disbursement flows (happy path + failure + idempotency) | all |

---

# 14. Open Questions

| # | Question | Recommendation |
|---|----------|----------------|
| 1 | Should large disbursements (> 500,000 XAF) require a two-step approval (initiate + confirm)? | Start without it; add as a separate phase if tenants request it. |
| 2 | Is a shared `Transaction` entity (with a `flow` column) better than a separate `Disbursement` entity? | Separate entity preferred — cleaner queries, no conditional branching on `flow` throughout the codebase. Reconciliation can join both tables on `reference`. |
| 3 | Should Orange IC2C be used for all Orange disbursements or only for internal channel clients? | Use IC2C for all tenant-initiated payouts. C2C (channel-to-channel) is for inter-merchant transfers and is out of scope here. |
| 4 | What is the minimum merchant wallet balance required before disbursements are allowed (circuit-breaker)? | Recommend 10,000 XAF platform-wide floor; configurable per tenant. Prevents a race condition from draining the wallet to exactly zero. |
| 5 | Does the Orange IC2C endpoint require a prior `/mp/init` call? | No — IC2C is a direct transfer endpoint with no init step. |
