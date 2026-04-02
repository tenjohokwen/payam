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
- ✓ E2E test infrastructure: Testcontainers (real PostgreSQL + Redis), WireMock (MTN + Orange), TestDataCleaner, E2ESecurityConfig — v3
- ✓ 10 domain invariant verifiers + 8 test data builders — every invariant assertable in one call — v3
- ✓ MTN/Orange full payment lifecycle E2E: happy path, polling fallback, payToken expiry, fraud-blocked, idempotency race (20 threads), circuit breaker — v3
- ✓ Inbound webhook double-check + Redis replay protection (MTN + Orange); outbound delivery with HMAC-SHA256 signing, exponential backoff, retry verification — v3
- ✓ Fraud velocity block, daily reconciliation (matched/missing/mismatched/WAT-offset), admin tenant-scoped transaction search — all E2E verified — v3
- ✓ Domain invariants proven: hash chain, ledger double-entry, idempotency, tenant isolation, state machine legality, webhook double-check, fraud ordering, SSRF guard, init-before-provider, Orange WAT offset — v3
- ✓ Concurrency races: concurrent idempotency (20 threads → exactly 1 payment row), webhook/polling race, velocity flood, API key rotation grace period — v3
- ✓ SM path matrix (all 32 illegal transitions throw without DB mutation); TXN boundary tests; PITest mutationThreshold=90 on 6 critical domain classes — v3

- ✓ Platform MSISDN management: admin can view/update Orange + MTN platform MSISDNs; email notification on every change — v4
- ✓ Spring Boot Actuator `/manage/health` reflects live provider MSISDN validation + circuit breaker state for both providers — v4
- ✓ Admin health dashboard: all Actuator component results visible to ROLE_ADMIN; access-denied banner for non-admins — v4

### Active

<!-- v5 Tenant & API Key Management -->

- Tenant status lifecycle (ACTIVE / SUSPENDED) — suspension immediately revokes all keys across all environments
- Per-environment API key scoping (PROD / DEV / SANDBOX) — one ACTIVE key per environment per tenant
- API key prefix format: first 3 chars of tenant name (uppercase, 0-padded to 3) — immutable per tenant even if name changes
- One-time raw key display on generation; backend stores only the bcrypt/SHA-256 hash — key never retrievable again
- Key rotation with 24-hour ROTATED grace period — automated job moves ROTATED → REVOKED after 24h
- Tenant reactivation auto-generates a new PROD key and shows it to admin
- WebhookSecret: unique UUID per tenant, admin-regeneratable, revealable via eye icon in admin UI
- Email notifications for 6 events: key generation/rotation, key revocation/reactivation, webhook secret generation, tenant status change, webhookUrl change, tenant email change
- All tenant + key state changes audited via Hibernate Envers with admin ID + timestamp per event
- Admin UI: tenant management screens (create, edit, status toggle, key management per env)

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
| Testcontainers over mocks for E2E tests | JSONB quoting bug found during Phase 20 test authoring — mocks would have missed it | ✓ Good — real database catches production-class bugs that mock tests miss |
| PITest targetClasses narrowed then expanded | Started with 3 pure domain classes (MUT-01); gap closure (23-05) expanded to all 6 MUT-02 targets | ⚠ Revisit — define PITest scope upfront; plan correction round adds friction |
| QueryCountVerifier + 4 builders built but not wired | Created in Phase 19 for future use; no Phase 20-23 tests consume them | — Pending — available for future regression detection; revisit in next test expansion |
| `getHealth()` hardcodes management port 8367 | Simple approach for single-server deployment; JWT cookie auth on port 8367 confirmed working | — Pending — reconfigure if management port changes or moves behind reverse proxy |
| `@EventListener` on PlatformConfigEmailListener (not `@TransactionalEventListener`) | MailManager handles AFTER_COMMIT on the Envelope event; double-wrapping would break | ✓ Good — consistent with AccountChangeEmailListener pattern |

## Current Milestone: v5 Tenant & API Key Management

**Goal:** Implement the complete tenant lifecycle and API key management specification — tenant status, per-environment key scoping, rotation grace period, suspension/reactivation flows, webhook secret management, email notifications, and admin UI.

**Target features:**
- Tenant status (ACTIVE/SUSPENDED) with suspension-triggered key revocation
- Per-environment API key scoping (PROD/DEV/SANDBOX) with one-time display
- API key prefix derived from tenant name (first 3 chars, 0-padded, immutable)
- Key rotation with 24-hour ROTATED grace period → automated REVOKED job
- Reactivation auto-generates new PROD key + shows it to admin
- WebhookSecret management with admin reveal UI
- Email notifications for 6 key/tenant state events
- Audit trail: Hibernate Envers + per-event admin ID + timestamp
- Admin UI: tenant management screens

## Current State

**Shipped:** v4 (2026-04-02) — 26 phases total (13 v1 + 4 v2 + 6 v3 + 3 v4), 64 plans
**Codebase:** Spring Boot 3.5 + Spring Security + Spring Data JPA + Resilience4j + Quartz + Bucket4j + logstash-logback-encoder + micrometer-tracing-bridge-otel + Vue 3 + Quasar
**Observability:** Full Loki-queryable structured logging + Spring Boot Actuator health with live provider MSISDN validation + CB state
**Test coverage:** Machine-checked E2E suite (32 test classes) + domain invariants + concurrency races + SM path matrix + PITest ≥90% mutation coverage
**Known tech debt:** v1 items in `.planning/milestones/v1-MILESTONE-AUDIT.md` (11 non-critical); B2B-01/B2B-02 (OrangeClient channelUserMsisdn fix) deferred from v4

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd:transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd:complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-04-02 — v5 milestone started*
