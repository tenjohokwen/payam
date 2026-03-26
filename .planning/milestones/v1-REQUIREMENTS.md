# Requirements Archive: v1 Payam Payment API

**Archived:** 2026-03-26
**Status:** ✅ SHIPPED

This is the archived requirements specification for v1.
For current requirements, see `.planning/REQUIREMENTS.md` (created for next milestone).

---

# Requirements: Payam

**Defined:** 2026-03-23
**Core Value:** Reliable, fraud-resistant payment processing with full traceability — no double charges, no blind trust of webhooks, no silent failures.

## v1 Requirements

Requirements for initial release.

### Multi-Tenant

- [x] **TENANT-01**: Multi-tenant API key management (generation, rotation, revocation, per-client scoping)

### Transaction

- [x] **TX-01**: Transaction lifecycle state machine: INITIATED → AUTH_PENDING → AUTHORIZED → PROCESSING → SUCCESS | FAILED | REVERSED
- [x] **TX-02**: Idempotency key enforcement — reject or return cached response for duplicate requests
- [x] **TX-03**: Immutable event-sourced transaction log with SHA-256 hash chain
- [x] **TX-04**: Distributed trace IDs (trace_id / transaction_id / external_reference) propagated throughout
- [x] **TX-05**: Internal double-entry ledger (debit customer, credit provider clearing)

### Provider Adapters

- [x] **ADAPT-01**: Orange Money adapter (merchant payment, cashout, C2C, account validation, bulk status)
- [x] **ADAPT-02**: MTN MoMo adapter (request-to-pay, disbursement, account validation, KYC, balance)

### Payment

- [x] **PAY-01**: Unified payment initiation API (MTN MoMo + Orange Money behind one endpoint)

### Webhook

- [x] **WH-01**: Webhook receiver with IP whitelist + HMAC signature verification + replay protection
- [x] **WH-02**: Double-check pattern: re-verify every webhook against provider status API before state change

### Fraud

- [x] **FRAUD-01**: Fraud engine: velocity checks (per IP/user/app), risk scoring (0–100), device fingerprinting

### Admin

- [x] **ADMIN-01**: Real-time admin dashboard: TPS, success/failure rates, fraud rate, provider latency
- [x] **ADMIN-02**: Transaction investigation tools: search by transaction_id, phone, trace_id; show full event timeline

### Reconciliation

- [x] **RECON-01**: Daily reconciliation against MTN/Orange reports (detect missing, mismatched, delayed)

### Operations

- [x] **OPS-01**: Fee management: configurable fixed fee per transaction, per-client or global rules
- [x] **OPS-02**: Real-time alerts: fraud spikes, repeated failures, callback anomalies

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| TENANT-01 | Phase 1 | Complete |
| TX-01 | Phase 2 | Complete |
| TX-02 | Phase 2 | Complete |
| TX-03 | Phase 2 | Complete |
| TX-04 | Phase 2 | Complete |
| TX-05 | Phase 2 + Phase 13 | Complete — infrastructure Phase 2; production caller wired Phase 13 |
| ADAPT-01 | Phase 3 | Complete (deviation: cashout/C2C deferred pending sandbox) |
| ADAPT-02 | Phase 4 | Complete |
| PAY-01 | Phase 5 | Complete |
| WH-01 | Phase 6 | Complete |
| WH-02 | Phase 6 | Complete |
| FRAUD-01 | Phase 7 | Complete |
| ADMIN-01 | Phase 8 | Complete |
| ADMIN-02 | Phase 8 | Complete |
| RECON-01 | Phase 9 | Complete |
| OPS-01 | Phase 10 + Phase 11 | Complete — fee engine Phase 10; feeAmount in response/webhook Phase 11 |
| OPS-02 | Phase 10 | Complete — CALLBACK_ANOMALY gap closed Phase 10-04 |

**Coverage:** 17/17 v1 requirements shipped

---

## Milestone Summary

**Shipped:** 17 of 17 v1 requirements

**Adjusted during implementation:**
- TX-05: Infrastructure built in Phase 2 but production caller deferred to Phase 13 (detected by audit)
- OPS-01: Fee engine built in Phase 10; `feeAmount` surface in API response/webhook added in Phase 11 (detected by audit)
- OPS-02: `CALLBACK_ANOMALY` used placeholder metric in Phase 10-02; real ratio metric implemented in Phase 10-04 (detected by audit)

**Deviations accepted:**
- ADAPT-01: Orange `cashout` and `C2C` operations ship as `UnsupportedOperationException` stubs — sandbox field verification required before wiring; merchant payment (primary use case) is fully implemented

**Dropped:** None

---
*Archived: 2026-03-26 as part of v1 milestone completion*
