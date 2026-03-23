# Research Summary: Payam Payment Gateway

**Researched:** 2026-03-23
**Domain:** Multi-tenant MTN MoMo + Orange Money wrapper, Cameroon
**Stack:** Java 17 / Spring Boot 3.5 / PostgreSQL / Redis / Vue 3 + Quasar
**Overall confidence:** HIGH — all 4 dimensions grounded in existing codebase inspection, provider API docs, and live Spring documentation

---

## Executive Summary

Payam is well-positioned. The existing codebase provides almost everything needed — only 3 net-new Maven dependencies are required for the entire payment layer. The security architecture (JWT, 2FA, rate limiting, fraud-aware auth) is production-grade. The observability stack (LGTM — Prometheus, Loki, Tempo, Grafana) is fully configured. Resilience4j circuit breakers are in place.

The core risks are not technical uncertainty — they are specific implementation traps in async mobile money flows that are easy to miss and expensive to fix post-launch:

1. **Commit the INIT row before calling the provider** — not in the same transaction. Orange Money can deliver a webhook within milliseconds of the payment call returning.
2. **MTN callbacks arrive via HTTP PUT, not POST.** Copying the Orange webhook controller pattern for MTN silently discards all MTN confirmations.
3. **Idempotency keys must be scoped to `(tenantId, idempotencyKey)`** — not key alone. Multi-tenant collision is exploitable.
4. **Never allow tenants to supply `notifUrl`** — Payam must always be the callback target. Client-supplied URLs are SSRF.
5. **Orange `createtime` has no timezone.** If parsed as UTC instead of WAT (UTC+1), reconciliation drifts 1 hour every day.

---

## Key Findings by Dimension

### Stack — 3 new dependencies, everything else already present

The existing `pom.xml` already contains: full LGTM observability stack, HMAC primitives (`commons-codec`), rate limiting (`bucket4j-core`), phone validation (`libphonenumber`), Redis client (Lettuce via Bucket4j), Resilience4j, Spring Retry, Hibernate Envers, `hypersistence-utils` (JSONB), MapStruct.

**New additions required:**

| Library | Purpose | Version |
|---------|---------|---------|
| `spring-modulith-starter-jpa` | Durable async event bus (Event Publication Registry on PostgreSQL) | 1.4.9 (needs own BOM) |
| `spring-boot-starter-data-redis` | Idempotency key cache, velocity counters, webhook dedup | Spring Boot BOM |
| `spring-boot-starter-quartz` | Distributed reconciliation scheduler (JDBC job store) | Spring Boot BOM |

**Critical conflict:** Quartz auto-initializes its schema. Set `spring.quartz.jdbc.initialize-schema=never` and provide the Quartz DDL as a Flyway migration.

Spring Modulith replaces Kafka/RabbitMQ entirely. Its Event Publication Registry gives durable at-least-once delivery within the monolith using the existing PostgreSQL connection — no new infrastructure.

### Features — Multi-tenancy is the differentiator

All three Cameroon competitors (Campay, Monetbil, Notchpay) are single-merchant integrations. None support a platform model with per-client API key isolation, per-client fee rules, or per-client fraud configuration. This is Payam's structural moat.

**Table stakes** (must ship for any integration):
- Unified initiation endpoint (MTN + Orange, one call)
- Idempotency enforcement (no competitor enforces this)
- Outbound webhook delivery + HMAC signing
- Webhook retry (minimum 3 attempts)
- Transaction status endpoint + polling fallback
- Standardized error codes across both providers
- API key management (create, rotate, revoke)

**Differentiators** to add before first external client:
- Webhook event log with replay (Notchpay doesn't have it; Campay/Monetbil have nothing)
- Per-client fee configuration
- Risk scoring + velocity rules
- Transaction investigation tools

**Anti-features (explicitly out of scope):**
- Native refund endpoint — neither provider supports it in current API versions
- Hosted payment page — wrong product model for a developer API
- Multi-currency — XAF only; COBAC regulatory risk
- ML fraud detection — no training data yet
- Customer wallet — requires becoming a financial institution under COBAC

### Architecture — Fits cleanly into existing module pattern

New `payment` module follows exact `contract/repo/service/infrastructure/api/config` layering. The payment module receives the authenticated principal from `SecurityContextHolder` but does not import from the `security` module's service or infrastructure layers.

**Two key structural decisions:**
1. **Two Spring Security filter chains:** `@Order(1)` API key chain for `/v1/payment/**` and `/v1/webhooks/**`; `@Order(2)` existing JWT chain for everything else.
2. **`ProviderGateway` interface** in `payment/contract/` with `MtnMoMoGateway` and `OrangeMoMoGateway` in `payment/infrastructure/`. All provider differences (2-step vs 1-step init, OAuth2 vs static tokens, POST vs PUT callbacks) are hidden behind the interface.

**Multi-tenant isolation:** `tenant_id` column on every table + PostgreSQL RLS. No schema-per-tenant (Flyway overhead not warranted).

**Event sourcing:** Append-only `payment_events` table. SHA-256 hash chain via `DigestUtils.sha256Hex` (already on classpath via `commons-codec`). No Axon, no event store server.

**Critical structural constraint:** The INIT row must be committed before the outbound provider HTTP call fires. Never wrap both operations in a single `@Transactional`. See PITFALLS.md P1.1.

### Pitfalls — 9 high-severity gaps in the current plan

The existing plan (idempotency keys, HMAC + IP whitelist, double-check, hash chain audit, velocity checks, daily reconciliation) is directionally correct but has specific gaps:

| ID | Pitfall | Severity | Phase |
|----|---------|----------|-------|
| P1.1 | Webhook-before-database race — provider webhook arrives before INIT row commits | CRITICAL | Phase 2 |
| P1.2 | Polling + webhook race — both update same PENDING row simultaneously | CRITICAL | Phase 2 |
| P1.3 | Orange payToken expiry between /init and /pay — idempotency blocks correct retry | HIGH | Phase 2 |
| P1.4 | MTN callback is PUT not POST — all MTN confirmations silently discarded | HIGH | Phase 4 |
| P2.1 | Idempotency key cross-tenant collision — key must be `(tenantId, key)` | CRITICAL | Phase 1 |
| P3.1 | SSRF via tenant-supplied `notifUrl` — Payam must own the callback URL | HIGH | Phase 4 |
| P5.1 | Orange `createtime` timezone is WAT (UTC+1) not UTC — 1-hour reconciliation drift | HIGH | Phase 9 |
| P7.1 | SIM-sharing household false positives — fraud signal weights must be DB-configured | HIGH | Phase 7 |
| P8.1 | PostgreSQL connection exhaustion — sync HTTP calls hold connections open 15–30s | HIGH | Phase 5 |

Full pitfall details (40 pitfalls across 10 categories) in PITFALLS.md.

---

## Implications for Roadmap

Based on research, suggested 10-phase structure:

### Phase 1: Multi-Tenant Foundation
Create the tenant + API key management layer. This unblocks everything else.
- Addresses: API key auth, per-client isolation, multi-tenant schema
- Avoids: P2.1 (idempotency cross-tenant collision — key design is decided here)
- Uses: Existing security module principal, `@Order(1)` second filter chain

### Phase 2: Transaction Core + Event Sourcing
Build the backbone: state machine, append-only event log, hash chain, ledger schema, idempotency store.
- Addresses: Transaction lifecycle, immutable audit trail, idempotency enforcement
- Avoids: P1.1 (INIT-before-provider constraint), P1.2 (concurrent update race via PESSIMISTIC_WRITE), P1.3 (payToken expiry + idempotency TTL rules)
- Uses: Spring Modulith events, PostgreSQL append-only tables, `commons-codec` hash chain

### Phase 3: Orange Money Adapter
Implement the Orange Money provider adapter: init→pay→push flow, status polling, subscriber validation.
- Addresses: All Orange Money transaction types (MP, cashout, C2C, IC2C)
- Avoids: P1.3 (payToken expiry handling), P5.1 (WAT timezone must be handled here)
- Uses: `OrangeMoMoGateway` in `payment/infrastructure/orange/`

### Phase 4: MTN MoMo Adapter
Implement the MTN MoMo provider adapter: OAuth2 token lifecycle, requesttopay, disbursement, polling.
- Addresses: All MTN transaction types, token refresh
- Avoids: P1.4 (PUT not POST), cached OAuth2 token refresh
- Uses: `MtnMoMoGateway` in `payment/infrastructure/mtn/`, Redis token cache

### Phase 5: Payment Orchestration + Unified Endpoint
Wire adapters through the orchestration layer: unified initiation endpoint, provider routing by MSISDN prefix, polling fallback scheduler, circuit breakers.
- Addresses: Single endpoint for both providers, async polling safety net
- Avoids: P8.1 (connection exhaustion — WebClient async calls here), circuit breaker tuning
- Uses: `PaymentOrchestrator` service, Resilience4j circuit breakers, Quartz polling scheduler

### Phase 6: Webhook Processing
Inbound webhook receivers (Orange POST + MTN PUT), IP whitelist, double-check pattern, outbound webhook delivery to client tenants with retry.
- Addresses: Full webhook pipeline, replay protection
- Avoids: P1.4 (PUT/POST separation), P3.1 (Payam owns notifUrl), webhook replay protection
- Uses: Spring Modulith events (`WebhookReceivedEvent` → `@ApplicationModuleListener` for double-check), Redis dedup store

### Phase 7: Fraud Engine
Velocity checks, risk scoring pipeline, device fingerprinting support, configurable signal weights.
- Addresses: Fraud prevention before first production transaction
- Avoids: P7.1 (SIM-sharing false positives — weights in DB, not hardcoded)
- Uses: Bucket4j velocity counters, `FraudScoringService` (plain Java), `hypersistence-utils` JSONB for score storage

### Phase 8: Admin Dashboard + Monitoring
Transaction investigation UI (Vue/Quasar), live SSE feed, search by ID/phone/trace, per-client transaction history, Micrometer custom counters/timers.
- Addresses: Operator tooling, real-time visibility
- Uses: Existing Quasar SPA + new admin REST endpoints, `SseEmitter`, Grafana for infrastructure ops

### Phase 9: Reconciliation
Daily Quartz job, Payam ledger vs provider status comparison, discrepancy flagging, CSV/JSON export.
- Addresses: Finance team requirements, missing/mismatched transaction detection
- Avoids: P5.1 (timezone handling critical here — all timestamps in WAT-aware ZonedDateTime)
- Uses: Quartz JDBC scheduler, bulk status endpoints from both providers

### Phase 10: Operational Hardening
TLS enforcement assertion on startup, alert rules (failure rate, fraud spikes, callback anomalies), provider health endpoint, circuit breaker tuning, log-based audit verification.
- Addresses: Production safety, regulatory audit requirements
- Avoids: P10.3 (TLS disabled for MoMo client — startup assertion prevents production misconfiguration)
- Uses: Spring Boot `ApplicationReadyEvent` for assertion, Actuator health indicators

---

### Phase Ordering Rationale

- **Phase 1 before everything:** Multi-tenant `tenant_id` schema must be in place before any payment table is created. Retrofitting tenant isolation into an existing schema is much harder than designing it in from the start.
- **Phase 2 before adapters:** The transaction event log and state machine are the contract that adapters must write into. Adapters should not define their own storage.
- **Phases 3 and 4 can run in parallel:** Orange and MTN adapters have no interdependency once the `ProviderGateway` interface and DTOs are defined in Phase 2.
- **Phase 5 after both adapters:** Orchestration wires adapters together; it cannot be built before both exist.
- **Phase 7 (fraud) before Phase 10 but after Phase 5:** Fraud rules need a live transaction pipeline to integrate with. Must ship before first production transaction — not after.
- **Phase 9 (reconciliation) can follow go-live:** Reconciliation requires live transaction data to be meaningful. It is operational hygiene, not a launch blocker.

---

### Research Flags for Phases

- **Phase 1:** Standard patterns, unlikely to need additional research.
- **Phase 3 (Orange):** Verify Orange webhook HMAC header existence and IP ranges with Orange partner before implementation. Current documentation does not confirm HMAC for inbound Orange webhooks.
- **Phase 4 (MTN):** Verify MTN sandbox callback HTTP method (PUT confirmed in docs — verify with sandbox). Verify MTN production IP ranges for whitelist.
- **Phase 6 (Webhooks):** Verify Orange Money payToken TTL empirically in sandbox before implementing idempotency TTL rules (P1.3).
- **Phase 9 (Reconciliation):** Verify Orange Money daily transaction report format before building parser. Reconciliation data format is not documented in project requirements.

---

## Open Questions

1. **Orange Money webhook HMAC:** Does Orange Money send a signature header on webhook delivery? The integration guide does not document one. This determines whether Payam's inbound webhook security relies solely on IP whitelist or adds HMAC verification.
2. **Orange Money payToken TTL:** Exact expiry window not documented. Field reports suggest 5–10 minutes. Verify in sandbox before implementing P1.3 mitigation.
3. **Orange `createtime` timezone:** Confirm WAT vs UTC with Orange partner before Phase 9 implementation.
4. **Notchpay refund mechanism:** If it is a disbursement wrapper, the same partner approval process applies to Payam. Start the disbursement partner approval process early — it is a commercial, not technical, dependency.
5. **Competitor docs verification:** Campay, Monetbil, and Notchpay docs should be fetched live before requirements phase to confirm gap analysis holds. Knowledge reflects August 2025 state.
