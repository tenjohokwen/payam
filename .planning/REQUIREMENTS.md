# Milestone v10 Requirements — Client Disbursement API

**Milestone:** v10
**Status:** Active
**Created:** 2026-04-24
**Source:** `requirements/disbursement-request.md`

---

## v10 Requirements

### DISB — Disbursement API Surface

- [ ] **DISB-01**: Tenant can initiate a disbursement via `POST /v1/disbursements` with fields: `recipientMsisdn`, `amount`, `currency`, `reference` (required) and `description`, `metadata` (optional); receives `202 Accepted` with `disbursementId` and `status: PROCESSING` (or `PENDING_CONFIRMATION` for amounts > 500,000 XAF)
- [ ] **DISB-02**: Tenant can query disbursement status via `GET /v1/disbursements/{disbursementId}`; response is tenant-scoped (another tenant's ID returns `404 Not Found`)
- [ ] **DISB-03**: Tenant can list their disbursements via `GET /v1/disbursements` with pagination and filters (status, date range); results are tenant-scoped
- [ ] **DISB-04**: Tenant can confirm a large disbursement (amount > 500,000 XAF) via `POST /v1/disbursements/{disbursementId}/confirm`; only disbursements in `PENDING_CONFIRMATION` status can be confirmed; confirmation triggers the provider transfer

### BAL — Balance Management

- [x] **BAL-01**: System checks `MERCHANT_WALLET` balance covers `principal + fee` before any provider call, using a pessimistic write lock (`SELECT FOR UPDATE`) to prevent concurrent overdraft; returns `422 INSUFFICIENT_BALANCE` if balance is insufficient
- [x] **BAL-02**: System releases the reserved balance back to `MERCHANT_WALLET` when a disbursement reaches `FAILED` terminal state
- [x] **BAL-03**: System sets disbursement status to `EXPIRED` (not `FAILED`) when the provider accepted the transfer but a subsequent internal error (e.g., ledger write failure) prevents clean state update; reserved balance is held pending manual ops resolution; an ops alert is triggered

### PROV — Provider Integration

- [ ] **PROV-01**: System routes disbursement to MTN MoMo via `MtnMoMoPort.disbursementTransfer()` (wrapping existing `MtnMoMoClient.transfer()`) based on recipient MSISDN prefix; uses a separate OAuth2 disbursement token; polls `GET /disbursement/v1_0/transfer/{id}` as fallback if callback not received within 5 minutes
- [ ] **PROV-02**: System routes disbursement to Orange Money via `OrangeMoneyPort.ic2cDisbursement()` calling `/ic2c/pay` based on recipient MSISDN prefix; polls `GET /ic2c/paystatus/{payToken}` as fallback if callback not received within 5 minutes
- [ ] **PROV-03**: System validates recipient account holder is active via `MobileMoneyPort.validateAccountHolder()` before initiating the provider transfer; returns `422 RECIPIENT_NOT_FOUND` if inactive

### SEC — Security, Fraud & Approval Controls

- [ ] **SEC-01**: System enforces `Idempotency-Key` header on every `POST /v1/disbursements` request using a distinct Redis namespace (`idempotency:dsb:<tenantId>:<key>`); duplicate requests within 24-hour TTL return the cached response without calling the provider
- [ ] **SEC-02**: System applies disbursement-specific velocity limits: > 20 disbursements/minute per tenant returns `429`; > 200 disbursements/hour per tenant returns `429`; > 10 disbursements to same MSISDN/day returns `422 DAILY_LIMIT_EXCEEDED`
- [ ] **SEC-03**: System applies disbursement-specific fraud score signals on top of the existing `FraudScoringService`: new recipient MSISDN (+15), amount > 3× tenant median payout (+30), recipient on known-fraud list (+80); score > 80 blocks with `FRAUD_BLOCK`
- [ ] **SEC-04**: System requires a two-step flow for disbursements > 500,000 XAF: `POST /v1/disbursements` returns `202` with `status: PENDING_CONFIRMATION`; tenant must call `POST /v1/disbursements/{id}/confirm` to proceed to the provider; disbursement expires automatically after 15 minutes if unconfirmed
- [ ] **SEC-05**: System validates inbound provider callbacks (MTN + Orange disbursement paths) via IP whitelist, HMAC/token signature verification, double-check against provider status API, and Redis replay deduplication on `providerReferenceId`; callbacks arrive at distinct paths (`/v1/callbacks/mtn/disbursement/{ref}`, `/v1/callbacks/orange/disbursement`)
- [ ] **SEC-06**: System delivers outbound webhooks to the tenant's configured URL for terminal disbursement states with events `disbursement.completed` and `disbursement.failed`; payload is signed with `X-Payam-Signature` (HMAC-SHA256); non-2xx triggers exponential backoff with max 5 retries

### TEST — E2E Test Coverage

- [ ] **TEST-01**: E2E test suite covers MTN disbursement: happy path (initiate → PROCESSING → callback SUCCESS → SUCCESS), callback FAILED → FAILED with balance release, idempotency race (20 concurrent requests → exactly 1 disbursement row), fraud block (no provider call), and MTN callback replay (second identical callback ignored)
- [ ] **TEST-02**: E2E test suite covers Orange disbursement: happy path (initiate → PROCESSING → callback → SUCCESS), insufficient balance (422, no provider call), and Orange callback replay protection
- [ ] **TEST-03**: E2E test covers step-up confirmation flow: large disbursement returns `PENDING_CONFIRMATION`, confirm endpoint triggers provider call, unconfirmed disbursement expires after 15 minutes
- [ ] **TEST-04**: Concurrency test: 20 simultaneous disbursements against the same MERCHANT_WALLET with balance covering only 1 — exactly 1 succeeds (PROCESSING), 19 return `422 INSUFFICIENT_BALANCE`; no overdraft

---

## Future Requirements (Deferred to v11+)

- Admin wallet top-up endpoint (`POST /v1/admin/wallet/{tenantId}/topup`) — production funding mechanism
- Batch disbursement (`POST /v1/disbursements/batch`) — submit multiple payouts in a single request
- Disbursement reversal (`POST /v1/disbursements/{id}/reverse`) — blocked until MTN/Orange expose reversal in their APIs
- Disbursement reconciliation report — extend existing daily reconciliation to cover disbursement flows
- Admin disbursement investigation UI — extend existing transaction search to cover disbursements

---

## Out of Scope (Explicit Exclusions)

| Feature | Reason |
|---------|--------|
| Admin wallet top-up endpoint | Adds 1–2 phases; production wallets funded via direct DB in v10; revisit v11 |
| Batch disbursements | Significant new flow; single-disbursement API ships first |
| Disbursement reversal | Neither MTN nor Orange expose reversal; back-office process only |
| ML fraud models | Deferred; rule-based scoring sufficient for v10 volume |
| Multi-currency disbursements | XAF only; consistent with collections constraint |

---

## Traceability

| REQ-ID | Phase | Plan |
|--------|-------|------|
| DISB-01 | Phase 51 | — |
| DISB-02 | Phase 51 | — |
| DISB-03 | Phase 51 | — |
| DISB-04 | Phase 51 | — |
| BAL-01 | Phase 50 | — |
| BAL-02 | Phase 50 | — |
| BAL-03 | Phase 50 | — |
| PROV-01 | Phase 51 | — |
| PROV-02 | Phase 51 | — |
| PROV-03 | Phase 51 | — |
| SEC-01 | Phase 51 | — |
| SEC-02 | Phase 51 | — |
| SEC-03 | Phase 51 | — |
| SEC-04 | Phase 51 | — |
| SEC-05 | Phase 52 | — |
| SEC-06 | Phase 52 | — |
| TEST-01 | Phase 53 | — |
| TEST-02 | Phase 53 | — |
| TEST-03 | Phase 53 | — |
| TEST-04 | Phase 53 | — |

---

*Last updated: 2026-04-24*
