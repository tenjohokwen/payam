# Requirements: Payam

**Defined:** 2026-03-23
**Core Value:** Reliable, fraud-resistant payment processing with full traceability — no double charges, no blind trust of webhooks, no silent failures.

## v1 Requirements

Requirements for initial release. Each maps to roadmap phases.

### Multi-Tenant

- [x] **TENANT-01**: Multi-tenant API key management (generation, rotation, revocation, per-client scoping)

### Transaction

- [ ] **TX-01**: Transaction lifecycle state machine: INITIATED → AUTH_PENDING → AUTHORIZED → PROCESSING → SUCCESS | FAILED | REVERSED
- [ ] **TX-02**: Idempotency key enforcement — reject or return cached response for duplicate requests
- [ ] **TX-03**: Immutable event-sourced transaction log with SHA-256 hash chain
- [ ] **TX-04**: Distributed trace IDs (trace_id / transaction_id / external_reference) propagated throughout
- [ ] **TX-05**: Internal double-entry ledger (debit customer, credit provider clearing)

### Provider Adapters

- [ ] **ADAPT-01**: Orange Money adapter (merchant payment, cashout, C2C, account validation, bulk status)
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

- [ ] **OPS-01**: Fee management: configurable fixed fee per transaction, per-client or global rules
- [ ] **OPS-02**: Real-time alerts: fraud spikes, repeated failures, callback anomalies

## v2 Requirements

Deferred to future release. Tracked but not in current roadmap.

### Fraud

- **FRAUD-02**: ML/anomaly-detection fraud models — rule-based engine ships first; ML requires training data

### Provider

- **PROV-01**: Additional provider expansion (Wave, Moov Africa) — adapter pattern supports this; deferred until commercial agreements exist

### Reconciliation

- **RECON-02**: Discrepancy investigation workflow with assignable tickets — v1 flags discrepancies; v2 routes them

## Out of Scope

Explicitly excluded. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| Native refund/reversal endpoints | Neither Orange (v1.0.2) nor MTN Collections expose a refund API; route via disbursement or back-office |
| Multi-currency support | XAF only; COBAC regulatory risk; no cross-border demand yet |
| Merchant settlement APIs | Not in v1 scope |
| Hosted payment page / checkout widget | API-first product; merchants build their own UI |
| Card payment support | No Cameroon card provider in scope; requires PCI-DSS and acquiring relationship |
| Customer wallet / stored balance | Requires becoming a financial institution under COBAC; Payam routes, it does not hold funds |
| ML fraud detection | Needs labeled training data that doesn't exist yet; deterministic rules ship first |
| Recurring payment / subscription scheduler | MTN PreApproval is experimental and not production-proven in Cameroon |
| SDK generation for all languages | Provide OpenAPI spec; community generates SDKs |

## Traceability

Which phases cover which requirements. Updated by create-roadmap.

| Requirement | Phase | Status |
|-------------|-------|--------|
| TENANT-01 | Phase 1 | Complete |
| TX-01 | Phase 2 | Complete |
| TX-02 | Phase 2 | Complete |
| TX-03 | Phase 2 | Complete |
| TX-04 | Phase 2 | Complete |
| TX-05 | Phase 2 | Complete |
| ADAPT-01 | Phase 3 | Complete |
| ADAPT-02 | Phase 4 | Complete |
| PAY-01 | Phase 5 | Complete |
| WH-01 | Phase 6 | Pending |
| WH-02 | Phase 6 | Pending |
| FRAUD-01 | Phase 7 | Pending |
| ADMIN-01 | Phase 8 | Complete |
| ADMIN-02 | Phase 8 | Complete |
| RECON-01 | Phase 9 | Complete |
| OPS-01 | Phase 10 | Pending |
| OPS-02 | Phase 10 | Pending |

**Coverage:**
- v1 requirements: 17 total
- Mapped to phases: 17
- Unmapped: 0 ✓

---
*Requirements defined: 2026-03-23*
*Last updated: 2026-03-23 after roadmap creation*
