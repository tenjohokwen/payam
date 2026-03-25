# Roadmap: Payam

## Overview

Payam is built in 10 phases. The foundation establishes multi-tenant isolation and the transaction
event-sourced backbone before any provider adapter is written. Orange and MTN adapters are built
independently against a shared `ProviderGateway` interface, then wired together through the
orchestration layer. Webhook processing, fraud, and monitoring follow to create a production-safe
system. Reconciliation and operational hardening complete the v1 scope.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [x] **Phase 1: Multi-Tenant Foundation** - Tenant schema, API key auth, per-client isolation
- [x] **Phase 2: Transaction Core + Event Sourcing** - State machine, append-only event log, idempotency, ledger
- [x] **Phase 3: Orange Money Adapter** - Orange init→pay→push flow, polling, subscriber validation
- [x] **Phase 4: MTN MoMo Adapter** - OAuth2 lifecycle, RequestToPay, disbursement, polling
- [x] **Phase 5: Payment Orchestration** - Unified initiation endpoint, provider routing, circuit breakers, polling fallback
- [ ] **Phase 6: Webhook Processing** - Inbound verification, double-check, outbound delivery with retry
- [ ] **Phase 7: Fraud Engine** - Velocity rules, risk scoring pipeline, device fingerprinting
- [ ] **Phase 8: Admin Dashboard + Monitoring** - Transaction investigation UI, live metrics, SSE feed
- [ ] **Phase 9: Reconciliation** - Daily Quartz job, discrepancy flagging, CSV/JSON export
- [x] **Phase 10: Operational Hardening** - Fee management, alerts, TLS assertion, circuit breaker tuning

## Phase Details

### Phase 1: Multi-Tenant Foundation
**Goal**: Tenant schema, API key authentication, and per-client isolation that underpins every subsequent phase
**Depends on**: Nothing (first phase)
**Requirements**: TENANT-01
**Research flag**: Unlikely — standard patterns, existing security module provides principal resolution
**Success Criteria** (what must be TRUE):
  1. Admin can create a new tenant and receive a scoped API key
  2. Tenant API key authenticates via a dedicated `@Order(1)` filter chain, independent of JWT
  3. One tenant cannot access another tenant's data — isolation enforced at query level
  4. API keys can be rotated (old + new both valid during grace period) and revoked immediately
  5. Idempotency keys are scoped to `(tenantId, idempotencyKey)` — cross-tenant collision is impossible (P2.1)
**Plans**: TBD

Plans:
- [ ] 01-01: Tenant domain model, Flyway schema, RLS policy, API key entity
- [ ] 01-02: API key filter chain (`@Order(1)`), `TenantContext`, idempotency key schema

### Phase 2: Transaction Core + Event Sourcing
**Goal**: The transaction backbone — state machine, append-only event log with hash chain, idempotency store, double-entry ledger
**Depends on**: Phase 1
**Requirements**: TX-01, TX-02, TX-03, TX-04, TX-05
**Research flag**: Unlikely — architecture validated in SUMMARY.md; Spring Modulith + PostgreSQL pattern confirmed
**Success Criteria** (what must be TRUE):
  1. A payment initiation creates an INITIATED record with transaction_id, trace_id, and external_reference before any provider call
  2. Every state transition appends an immutable event; SHA-256 hash chain links each event to the previous
  3. A duplicate request with the same idempotency key returns the cached response — the provider is never called again
  4. Every event carries trace_id, transaction_id, and external_reference in all logs and spans
  5. Every state transition that moves money creates balanced debit/credit ledger entries
**Plans**: TBD

Plans:
- [ ] 02-01: Transaction entity, state machine (`TransactionStatus` enum), Flyway schema
- [ ] 02-02: `PaymentEventLog` append-only table, SHA-256 hash chain via `DigestUtils.sha256Hex`
- [ ] 02-03: Idempotency store (Redis key + PostgreSQL fallback), double-entry ledger schema

### Phase 3: Orange Money Adapter
**Goal**: Orange Money provider adapter — init→pay→push flow, status polling, subscriber validation
**Depends on**: Phase 2
**Requirements**: ADAPT-01
**Research flag**: LIKELY — verify Orange webhook HMAC header and IP ranges with Orange partner; verify payToken TTL in sandbox (P1.3, P5.1)
**Success Criteria** (what must be TRUE):
  1. Merchant payment (MP) completes end-to-end: /init → /pay → push notification → state transition
  2. Subscriber account validation returns active/inactive status via `/infos/subscriber`
  3. Cashout, C2C, and IC2C transaction types are initiated and tracked
  4. Expired payToken is detected before each poll attempt — fresh re-initiation is Phase 5
     PaymentOrchestrator responsibility (P1.3; assertPayTokenFresh() wired in OrangeStatusPollerJob)
  5. All Orange `createtime` values are parsed as WAT (UTC+1), not UTC (P5.1 fix)
**Plans**: TBD

Plans:
- [ ] 03-01: `OrangeMoMoGateway` impl — init, pay, push, status polling, subscriber validation
- [ ] 03-02: Orange DTOs, error mapping, payToken expiry handling, WAT timezone parsing

### Phase 4: MTN MoMo Adapter
**Goal**: MTN MoMo provider adapter — OAuth2 lifecycle, RequestToPay, disbursement, account validation
**Depends on**: Phase 2
**Requirements**: ADAPT-02
**Research flag**: LIKELY — verify PUT callback in sandbox; verify production IP ranges for whitelist (P1.4)
**Success Criteria** (what must be TRUE):
  1. RequestToPay initiates and receives async confirmation when customer approves
  2. OAuth2 access tokens are cached in Redis and refreshed automatically before expiry
  3. MTN sends callbacks via HTTP PUT — these are received and processed correctly, not discarded (P1.4 fix)
  4. Account holder validation and balance query (merchant wallet) return correct responses
  5. MTN callback requests from non-whitelisted IPs are rejected before processing
**Plans**: TBD

Plans:
- [ ] 04-01: `MtnMoMoGateway` impl — OAuth2 token manager, RequestToPay, disbursement, account validation
- [ ] 04-02: MTN DTOs, PUT callback handler, IP whitelist, Redis token cache

### Phase 5: Payment Orchestration
**Goal**: Unified payment endpoint that routes to MTN or Orange by MSISDN prefix, with circuit breakers and polling fallback
**Depends on**: Phase 3, Phase 4
**Requirements**: PAY-01
**Research flag**: Unlikely — Resilience4j already in codebase; WebClient async pattern is standard
**Success Criteria** (what must be TRUE):
  1. `POST /v1/payments` routes to the correct provider based on MSISDN prefix (6X→MTN, 6[5/9]→Orange) with no client configuration required
  2. All provider HTTP calls are non-blocking (WebClient) — no database connections held during provider wait (P8.1 fix)
  3. Circuit breakers open when a provider's error rate exceeds threshold; subsequent calls fail fast with a clear error
  4. A Quartz scheduler polls pending transactions if no webhook arrives within the configured timeout window
  5. Provider error codes are normalized to Payam's standardized error vocabulary before returning to client
**Plans**: TBD

Plans:
- [ ] 05-01: `PaymentOrchestrator`, MSISDN routing, `ProviderGateway` dispatch, standardized error codes
- [ ] 05-02: Resilience4j circuit breaker config per provider, Quartz polling fallback scheduler

### Phase 6: Webhook Processing
**Goal**: Inbound webhook receivers (Orange POST + MTN PUT), double-check verification, outbound delivery to tenants with retry
**Depends on**: Phase 5
**Requirements**: WH-01, WH-02
**Research flag**: LIKELY — verify Orange payToken TTL empirically in sandbox before implementing dedup TTL (P1.3)
**Success Criteria** (what must be TRUE):
  1. Inbound Orange webhooks (POST) and MTN webhooks (PUT) are received on separate endpoints with IP whitelist + HMAC checks
  2. No state transition occurs from a webhook alone — provider status API is always re-queried first (double-check — P1.4 fix)
  3. Duplicate webhook IDs are rejected within the dedup TTL window (Redis dedup store)
  4. Tenant's configured webhook URL receives a HMAC-SHA256-signed event on every final state change
  5. Failed outbound deliveries retry at minimum 3 times with exponential backoff; delivery status is queryable
**Plans**: TBD

Plans:
- [ ] 06-01: Inbound webhook controller (Orange POST, MTN PUT), IP whitelist, HMAC verification, Redis dedup
- [ ] 06-02: Double-check via Spring Modulith event (`WebhookReceivedEvent` → `@ApplicationModuleListener`)
- [ ] 06-03: Outbound webhook delivery service — tenant URL dispatch, HMAC signing, retry with backoff, event log

### Phase 7: Fraud Engine
**Goal**: Velocity checks, risk scoring pipeline, device fingerprinting — all signal weights DB-configurable
**Depends on**: Phase 5
**Requirements**: FRAUD-01
**Research flag**: Unlikely — Bucket4j already in codebase; `hypersistence-utils` JSONB for score storage is confirmed
**Success Criteria** (what must be TRUE):
  1. A transaction from an IP/user/app exceeding velocity thresholds is blocked before reaching the provider
  2. Every transaction receives a risk score (0–100) computed from configured signals before provider dispatch
  3. Device fingerprint data from the client request is stored with the transaction for future pattern analysis
  4. All fraud signal weights are stored in the database and hot-reloadable — no restart needed to adjust rules (P7.1 fix)
  5. SIM-sharing household patterns can be down-weighted via config, reducing false positives (P7.1 fix)
**Plans**: TBD

Plans:
- [ ] 07-01: `FraudScoringService` — velocity rule evaluation (Bucket4j), risk score computation, device fingerprint capture
- [ ] 07-02: DB-backed signal weight config, `FraudRuleRepository`, integration with `PaymentOrchestrator` pre-dispatch hook

### Phase 8: Admin Dashboard + Monitoring
**Goal**: Transaction investigation UI (Quasar SPA), live SSE metrics feed, custom Micrometer counters
**Depends on**: Phase 6, Phase 7
**Requirements**: ADMIN-01, ADMIN-02
**Research flag**: Unlikely — Quasar SPA and Micrometer/Grafana stack already configured
**Success Criteria** (what must be TRUE):
  1. Admin can view live TPS, success/failure rates, fraud rate, and provider latency in the Quasar dashboard
  2. Admin can search for any transaction by transaction_id, phone number, or trace_id and retrieve full details
  3. Transaction detail view shows every state transition with timestamp, actor, and associated event payload
  4. Micrometer custom counters expose payment-domain metrics to the existing Prometheus/Grafana stack
  5. Per-client transaction history is scoped to the authenticated tenant — cross-tenant data never appears
**Plans**: TBD

Plans:
- [ ] 08-01: Admin REST endpoints — transaction search, detail view with event timeline, per-client history
- [ ] 08-02: Micrometer custom counters/timers for payment domain metrics (TPS, latency, fraud rate)
- [ ] 08-03: Quasar SPA admin views — dashboard with SSE live feed, transaction search and detail UI

### Phase 9: Reconciliation
**Goal**: Daily Quartz reconciliation job comparing Payam ledger against MTN/Orange provider reports, with discrepancy flagging and export
**Depends on**: Phase 8
**Requirements**: RECON-01
**Research flag**: LIKELY — verify Orange daily transaction report format before building parser (format not documented)
**Success Criteria** (what must be TRUE):
  1. A Quartz job runs daily, fetching MTN and Orange transaction reports and comparing them against the Payam ledger
  2. Missing transactions (present in Payam but not in provider, or vice versa) are flagged with severity
  3. Mismatched amounts or statuses are flagged and surfaced in the admin dashboard
  4. Reconciliation report exports as CSV and JSON for finance team consumption
  5. All Orange `createtime` values during reconciliation are treated as WAT (UTC+1) — no 1-hour drift (P5.1 fix)
**Plans**: TBD

Plans:
- [ ] 09-01: Quartz JDBC reconciliation scheduler, MTN/Orange report fetcher, ledger comparison logic
- [ ] 09-02: Discrepancy model, flagging logic, admin dashboard surface, CSV/JSON export endpoint

### Phase 10: Operational Hardening
**Goal**: Fee management, real-time alert rules, TLS startup assertion, provider health endpoint, circuit breaker tuning
**Depends on**: Phase 9
**Requirements**: OPS-01, OPS-02
**Research flag**: Unlikely — startup assertions and Actuator health indicators are standard Spring Boot patterns
**Success Criteria** (what must be TRUE):
  1. Fee rules (fixed fee per tenant or global) are configurable via admin API without restart or code change
  2. Alert rules fire when failure rate, fraud spike rate, or callback anomaly thresholds are breached
  3. On startup, the application asserts TLS is enabled for all provider connections — misconfigured production deployments fail fast (P10.3 fix)
  4. `GET /providers/status` exposes circuit breaker state (CLOSED/OPEN/HALF_OPEN) for each provider
  5. Log-based audit tool can verify SHA-256 hash chain integrity across the full event log on demand
**Plans**: TBD

Plans:
- [x] 10-01: Fee engine — `FeeRule` entity, per-tenant and global rules, evaluation at transaction time
- [x] 10-02: Alert rules — threshold config, `ApplicationEventPublisher` → notification channel wiring
- [x] 10-03: TLS startup assertion (`ApplicationReadyEvent`), provider health Actuator indicator, circuit breaker status endpoint
- [x] 10-04: CALLBACK_ANOMALY gap closure — real ratio metric, controller instrumentation, AlertRuleIT 5/5

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10

Note: Phases 3 and 4 can run in parallel once Phase 2 completes and `ProviderGateway` interface + DTOs are defined.

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Multi-Tenant Foundation | 3/3 | Complete | 2026-03-23 |
| 2. Transaction Core + Event Sourcing | 3/3 | Complete | 2026-03-23 |
| 3. Orange Money Adapter | 4/4 | Complete | 2026-03-24 |
| 4. MTN MoMo Adapter | 2/2 | Complete | 2026-03-24 |
| 5. Payment Orchestration | 2/2 | Complete | 2026-03-24 |
| 6. Webhook Processing | 3/3 | Complete | 2026-03-24 |
| 7. Fraud Engine | 2/2 | Complete | 2026-03-24 |
| 8. Admin Dashboard + Monitoring | 3/3 | Complete | 2026-03-24 |
| 9. Reconciliation | 2/2 | Complete | 2026-03-25 |
| 10. Operational Hardening | 4/4 | Complete | 2026-03-25 |
