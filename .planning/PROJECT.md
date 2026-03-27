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
- ✓ Multi-tenant API key management (generation, rotation, revocation, per-client scoping) — v1
- ✓ Transaction lifecycle state machine: INITIATED → AUTH_PENDING → AUTHORIZED → PROCESSING → SUCCESS | FAILED | REVERSED — v1
- ✓ Idempotency key enforcement — reject or return cached response for duplicate requests — v1
- ✓ Immutable event-sourced transaction log with SHA-256 hash chain — v1
- ✓ Distributed trace IDs (trace_id / transaction_id / external_reference) propagated throughout — v1
- ✓ Internal double-entry ledger (debit customer, credit provider clearing) — v1
- ✓ Orange Money adapter (merchant payment, cashout deferred) — v1
- ✓ MTN MoMo adapter (request-to-pay, disbursement, account validation, KYC, balance) — v1
- ✓ Unified payment initiation API (MTN MoMo + Orange Money behind one endpoint) — v1
- ✓ Webhook receiver with IP whitelist + HMAC signature verification + replay protection — v1
- ✓ Double-check pattern: re-verify every webhook against provider status API before state change — v1
- ✓ Fraud engine: velocity checks (per IP/user/app), risk scoring (0–100), device fingerprinting — v1
- ✓ Real-time admin dashboard: TPS, success/failure rates, fraud rate, provider latency — v1
- ✓ Transaction investigation tools: search by transaction_id, phone, trace_id; show full event timeline — v1
- ✓ Daily reconciliation against MTN/Orange reports (detect missing, mismatched, delayed) — v1
- ✓ Fee management: configurable fixed fee per transaction, per-client or global rules — v1
- ✓ Real-time alerts: fraud spikes, repeated failures, callback anomalies — v1
- ✓ JSON stdout logging pipeline via `LoggingEventCompositeJsonEncoder` with OTel traceId/spanId MDC flattening — v2
- ✓ Per-request MDC enrichment: requestId, tenantId, transactionId, externalReference in every log line — v2
- ✓ Structured request lifecycle events (request_start, request_end, request_error) with durationMs — v2
- ✓ 7 business event types queryable in Loki: initiate_payment, transaction_state_change, webhook_received, webhook_delivery, fraud_evaluation, provider HTTP latency, reconciliation_run — v2
- ✓ Full codebase LOG-CODE-01/02/03 compliance: zero {} interpolation, zero code-flow logs, BodySanitizer covers all payment fields — v2

### Active

<!-- Next milestone scope — TBD -->

(None defined — run `/gsd:define-requirements` to scope next milestone)

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
| Monolith (not microservices) | Matches existing architecture; team size doesn't justify service overhead | ✓ Good — v1 built cleanly in layered monolith |
| Event sourcing for transactions | Required for tamper-proof audit trail and full traceability | ✓ Good — SHA-256 hash chain + `@Immutable` PaymentEventLog working |
| Double-check webhook pattern | Cameroon fraud risk; webhooks can be forged; provider API is ground truth | ✓ Good — `WebhookDoubleCheckHandler` always re-queries provider before state change |
| Redis for idempotency/velocity | Fast read/write for high-frequency checks; PostgreSQL for durability | ✓ Good — Redis NX+EX atomic reservation with PostgreSQL fallback |
| MTN + Orange from day one | Building the abstraction layer for one is the same effort; both in scope | ✓ Good — shared `ProviderGateway` interface worked well |
| Spring Modulith events (not Kafka/RabbitMQ) | Research confirmed sufficient for v1; no operational overhead | ✓ Good — `@TransactionalEventListener(AFTER_COMMIT)` + PostgreSQL Event Publication Registry |
| `@CircuitBreaker` without `fallbackMethod` | Fallback fires for ALL exceptions, not just circuit-open | ✓ Good — `ignoreExceptions` for domain exceptions; `CallNotPermittedException` propagates |
| No `@Transactional` on `PaymentOrchestrator.initiate()` | Holding DB connection during provider HTTP exhausts connection pool | ✓ Good — `TransactionTemplate` for discrete DB operations |
| `FilterRegistrationBean(setEnabled=false)` for `ApiKeyAuthenticationFilter` | Prevents double-registration as servlet container filter | ✓ Good — established pattern for any `OncePerRequestFilter` defined as `@Bean` |
| Ledger caller deferred to audit gap closure | Infrastructure existed in Phase 2 but production caller added only in Phase 13 | ⚠ Revisit — wire ledger caller in same phase as infrastructure next time |

## Current State

**Shipped:** v2 (2026-03-27) — 17 phases total (13 v1 + 4 v2), 41 plans, ~25,400 LOC Java
**Codebase:** Spring Boot 3.5 + Spring Security + Spring Data JPA + Resilience4j + Quartz + Bucket4j + logstash-logback-encoder + micrometer-tracing-bridge-otel + Vue 3 + Quasar
**Observability:** Full Loki-queryable structured logging — every log line is valid JSON with traceId, spanId, requestId, tenantId, transactionId as top-level fields
**Known tech debt:** v1 items in `.planning/milestones/v1-MILESTONE-AUDIT.md` (11 non-critical); none from v2

---
*Last updated: 2026-03-27 after v2 milestone completion*
