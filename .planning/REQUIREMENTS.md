# Requirements: Payam v7 — Backend Hardening & Bug Fixes

**Milestone:** v7
**Goal:** Fix 13 audit-identified bugs and risk areas — critical data-consistency issues, transaction boundary problems, and medium concurrency hazards — so the system is production-safe under concurrent load.
**Created:** 2026-04-14
**Status:** Active

---

## v7 Requirements

### Idempotency (IDEM)

- [x] **IDEM-01**: When a Postgres write fails during idempotency store, Redis does NOT hold a stale value — Postgres is written first, Redis updated only on success
- [x] **IDEM-02**: Concurrent requests with the same idempotency key produce exactly one DB row — a single UPSERT replaces the current TOCTOU find+save pattern

### Reconciliation (RECON)

- [x] **RECON-01**: Reconciliation processes transactions in bounded pages (≤1000 rows per batch) — no full-day set is loaded into heap; discrepancies are persisted incrementally
- [x] **RECON-02**: When discrepancy persistence fails, the ReconciliationReport transitions to FAILED state — no report is left stuck in IN_PROGRESS

### Transaction Boundaries (TXN)

- [ ] **TXN-01**: Fee evaluation executes before the transaction boundary in PaymentOrchestrator — the locked section covers state writes only, not fee computation

### Webhook Infrastructure (WEBHOOK)

- [x] **WEBHOOK-01**: Tenant data is loaded in one query per job tick (not per delivery) in WebhookDeliveryService — N deliveries produce 1 query, not N+1
- [ ] **WEBHOOK-02**: Webhook enqueue fires only after the status-transition transaction commits — uses @TransactionalEventListener(phase = AFTER_COMMIT); enqueue failure does not roll back the state transition
- [x] **WEBHOOK-03**: Webhook RestTemplate has an explicit connect timeout (≤5s) and read timeout (≤10s) — a hanging tenant endpoint cannot block a Quartz thread indefinitely

### API Key Concurrency (AKEY)

- [ ] **AKEY-09**: Concurrent rotations on the same API key are serialized — no two nodes can simultaneously succeed; protected by @Version or a unique constraint on (tenant_id, environment, status)

### Ledger Integrity (LEDGER)

- [ ] **LEDGER-01**: The database enforces that every entry_group_id has exactly one DEBIT and one CREDIT — unbalanced ledger posts are rejected at the DB layer

### Operational Resilience (OPS)

- [ ] **OPS-01**: MTN and Orange poller transactions have an explicit timeout so advisory locks are bounded — no indefinite lock hold on node crash
- [ ] **OPS-02**: Fraud velocity token consumption occurs only after the idempotency result is successfully cached — a failed cache write does not consume a rate-limit token
- [ ] **OPS-03**: TenantContext is cleared in a finally block on all request paths including exception paths — an integration test verifies the context is empty after an exception-path request

---

## Future Requirements

*Not in scope for v7*

- Audit log viewer for tenant and API key changes (Envers data captured; viewer deferred)
- Key permission scopes (read-only, write-only API keys)
- Self-service tenant portal
- DEV/SANDBOX key auto-generation on tenant create
- Bulk tenant operations

---

## Out of Scope

- ML/anomaly-detection fraud models — rule-based engine is sufficient; ML deferred
- Multi-currency support — XAF only
- Merchant settlement APIs
- Direct refund/reversal endpoints

---

## Traceability

| REQ-ID | Phase | Status |
|--------|-------|--------|
| IDEM-01 | Phase 35 | Complete |
| IDEM-02 | Phase 35 | Complete |
| RECON-01 | Phase 36 | Complete |
| RECON-02 | Phase 36 | Complete |
| TXN-01 | Phase 38 | Pending |
| WEBHOOK-01 | Phase 37 | Complete |
| WEBHOOK-02 | Phase 37 | Pending |
| WEBHOOK-03 | Phase 37 | Complete |
| AKEY-09 | Phase 39 | Pending |
| LEDGER-01 | Phase 39 | Pending |
| OPS-01 | Phase 40 | Pending |
| OPS-02 | Phase 38 | Pending |
| OPS-03 | Phase 40 | Pending |

---

*Last updated: 2026-04-14*
