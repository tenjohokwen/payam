# Payam

## What This Is

Payam is a unified, multi-tenant payment API for Cameroon that wraps MTN Mobile Money and Orange Money behind a single, consistent interface. It handles the full payment lifecycle — initiation, async provider communication, webhook verification, fraud screening, and reconciliation — so consuming applications integrate once and work with both providers without touching provider-specific details.

## Core Value

Reliable, fraud-resistant payment processing with full traceability — no double charges, no blind trust of webhooks, no silent failures.

## Requirements

### Validated

<!-- Existing capabilities confirmed from codebase -->

- ✓ User registration, activation, and account management — existing
- ✓ JWT authentication with refresh tokens — existing
- ✓ Two-factor login — existing
- ✓ Password reset via email — existing
- ✓ Role-based access control (USER / ADMIN) — existing
- ✓ Per-user/IP rate limiting — existing
- ✓ Admin user management — existing
- ✓ Email notifications (transactional) — existing
- ✓ Fraud-aware authentication manager — existing
- ✓ Spring Security audit hooks — existing
- ✓ Circuit breaker / retry for outbound calls (Resilience4j) — existing
- ✓ Flyway database migrations — existing
- ✓ Vue 3 + Quasar frontend SPA — existing

### Active

<!-- Payment layer — current build scope -->

- [ ] Unified payment initiation API (MTN MoMo + Orange Money behind one endpoint)
- [ ] Transaction lifecycle state machine: INITIATED → AUTH_PENDING → AUTHORIZED → PROCESSING → SUCCESS | FAILED | REVERSED
- [ ] Idempotency key enforcement — reject or return cached response for duplicate requests
- [ ] Orange Money adapter (merchant payment, cashout, C2C, account validation, bulk status)
- [ ] MTN MoMo adapter (request-to-pay, disbursement, account validation, KYC, balance)
- [ ] Webhook receiver with IP whitelist + HMAC signature verification + replay protection
- [ ] Double-check pattern: re-verify every webhook against provider status API before state change
- [ ] Immutable event-sourced transaction log with SHA-256 hash chain
- [ ] Distributed trace IDs (trace_id / transaction_id / external_reference) propagated throughout
- [ ] Multi-tenant API key management (generation, rotation, revocation, per-client scoping)
- [ ] Fraud engine: velocity checks (per IP/user/app), risk scoring (0–100), device fingerprinting
- [ ] Internal double-entry ledger (debit customer, credit provider clearing)
- [ ] Daily reconciliation against MTN/Orange reports (detect missing, mismatched, delayed)
- [ ] Real-time admin dashboard: TPS, success/failure rates, fraud rate, provider latency
- [ ] Transaction investigation tools: search by transaction_id, phone, trace_id; show full event timeline
- [ ] Fee management: configurable fixed fee per transaction, per-client or global rules
- [ ] Real-time alerts: fraud spikes, repeated failures, callback anomalies

### Out of Scope

- ML/anomaly-detection fraud models — deferred to future; rule-based engine ships first
- Multi-currency support — XAF only; Cameroon market only for now
- Merchant settlement APIs — not in v1 scope
- Direct refund/reversal endpoints — neither MTN nor Orange expose this in current API versions; handled via disbursement transfer or back-office process
- Mobile SDK / client libraries — API only; consumers build their own clients

## Context

- **Existing foundation:** Spring Boot 3.5 + Spring Security + Spring Data JPA + Resilience4j. Security module is production-grade (JWT, 2FA, rate limiting, fraud-aware auth). Email module has circuit breaker. Payment module is net-new.
- **Provider reality:** Both MTN MoMo and Orange Money are async-first — payment outcomes arrive via webhook or polling. Orange Money has no balance endpoint in v1.0.2 and no native refund/reversal; MTN refunds go through the disbursement API.
- **Network environment:** Cameroon networks are unstable — idempotency and retry-safety are non-negotiable, not nice-to-have.
- **Architecture target:** Layered monolith following the established module pattern (`contract → repo → service → infrastructure → api → config`). New `payment` module follows same structure. Event bus (Kafka/RabbitMQ) for audit/analytics pipeline.
- **Data stores:** PostgreSQL (primary), Redis (idempotency cache, velocity counters, risk scores).
- **Frontend:** Admin dashboard and monitoring UI extend the existing Quasar SPA.

## Constraints

- **Tech stack:** Java 17 / Spring Boot 3.5 — matches existing codebase; no framework change
- **Database:** PostgreSQL + Flyway migrations — established pattern, must continue
- **Module pattern:** New payment module must follow `contract/repo/service/infrastructure/api/config` layering already in use
- **Provider API limits:** Orange Money v1.0.2 has no balance or refund endpoints — architecture must accommodate this without pretending they exist
- **Security:** TLS everywhere, HMAC request signing, no plaintext sensitive data, Zero Trust between internal services
- **Performance:** <300ms p99 for synchronous operations; async processing for all provider I/O

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Monolith (not microservices) | Matches existing architecture; team size doesn't justify service overhead | — Pending |
| Event sourcing for transactions | Required for tamper-proof audit trail and full traceability | — Pending |
| Double-check webhook pattern | Cameroon fraud risk; webhooks can be forged; provider API is ground truth | — Pending |
| Redis for idempotency/velocity | Fast read/write for high-frequency checks; PostgreSQL for durability | — Pending |
| MTN + Orange from day one | Building the abstraction layer for one is the same effort; both in scope | — Pending |

---
*Last updated: 2026-03-23 after initialization*
