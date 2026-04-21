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

- ✓ Tenant lifecycle service layer: `TenantService` with updateName/Email/WebhookUrl, suspend, reactivate, regenerateWebhookSecret; `ApiKeyService` with AKEY-02 duplicate-active guard and AKEY-08 pre-rotate revoke — v5 (Phase 28)
- ✓ Hibernate Envers audit trail: Flyway V20 DDL for `main.revinfo`, `main.tenant_aud`, `main.tenant_api_key_aud`; `default_schema: main` in all profiles; admin identity captured per revision — v5 (Phase 28)
- ✓ 18 integration tests: TenantServiceIT (9), TenantAuditIT (3), TenantProvisioningIT (6); all TENT/AKEY/WSEC/AUDIT requirement IDs verified against real DB — v5 (Phase 28)
- ✓ AKEY-01 API key format: generateSecureKey() returns PREFIX_UUID, ApiKeyBuilder derives prefix from tenant table, filter parses prefix via underscore delimiter — v5 (Phase 28.1)
- ✓ AKEY-05 Quartz rotation cleanup job: `RotatedKeyCleanupJob` runs every 5 minutes, revokes ROTATED keys past 24h grace period; Flyway V21 TIMESTAMPTZ migration for correct timezone handling — v5 (Phase 29)
- ✓ TENT-09: SUSPENDED tenants blocked at `ApiKeyAuthenticationFilter` with HTTP 403 before `SecurityContext` or `TenantContext` population — zero-query check on JOIN-FETCHed tenant entity — v6 (Phase 30)
- ✓ Tenant REST API surface: 3 GET endpoints (paginated list, tenant detail, webhook secret reveal) + 6 mutation endpoints (PATCH name/email/webhookUrl, POST suspend/reactivate/webhook-secret) on `TenantAdminResource`; `IllegalStateException→409` in `ApiAdvice`; 14 integration tests — v6 (Phase 31) — Validated in Phase 31: TENT-02, TENT-03, TENT-04, TENT-05, TENT-06, TENT-07, TENT-08, TENT-10, WSEC-03
- ✓ Admin UI tenant management: TenantListPage (paginated q-table, status filter, row-click nav), TenantDetailPage (inline field edit, status toggle, key lifecycle per env, OneTimeKeyModal, webhook secret reveal with 30s auto-mask), plus POST /keys/generate backend endpoint — v6 (Phase 33) — Validated in Phase 33: UI-01, UI-02, UI-03, UI-04
- ✓ Orange Money adapter aligned with Use Case 1 spec: PayRequest has correct 7-field /mp/pay body, initTransaction() calls POST /mp/init (replacing getMerchantInfo/GET /infos/merchant), PlatformConfigService.findByProvider() resolves channelMsisdn, HMAC verification removed from OrangeCallbackController, description field propagated PaymentRequest→PaymentCommand→OrangeMoneyPort — Phase 34
- ✓ Idempotency storage durability: IdempotencyKeyRepository.upsert() native INSERT...ON CONFLICT replaces racy find+save; IdempotencyService.store() writes Postgres before Redis — no stale cache on Postgres failure (IDEM-01), no DataIntegrityViolationException under concurrent load (IDEM-02) — Validated in Phase 35: IDEM-01, IDEM-02
- ✓ Reconciliation memory + stuck-state hardening: ReconciliationProviderRunner @Service with REQUIRES_NEW isolation on all public methods; paged fetch ≤1000 rows per batch (ORDER BY id ASC) with incremental discrepancy persistence; markFailed() runs in independent transaction so crashes never leave reports stuck at IN_PROGRESS; LedgerSnapshotService deleted — Validated in Phase 36: RECON-01, RECON-02
- ✓ Webhook subsystem fixes: N+1 tenant query eliminated via `loadTenants(Set<Long>)` + one `findAllById` IN-clause SELECT in `WebhookDeliveryJob`; enqueue decoupled from state-transition via `@TransactionalEventListener(AFTER_COMMIT) + REQUIRES_NEW` on `WebhookDeliveryService.onEnqueueRequested`; `WebhookConfig` RestTemplate gets 5s connect / 10s read timeouts — Validated in Phase 37: WEBHOOK-01, WEBHOOK-02, WEBHOOK-03
- ✓ Concurrency guards & DB constraints: `@Version long version` on `TenantApiKey` (V22 migration: main + AUD Envers parity) serializes concurrent rotations — loser gets `ObjectOptimisticLockingFailureException` → HTTP 409 via `ApiAdvice`; deferrable unique constraint `uq_ledger_entry_group_direction` on `main.ledger_entry(entry_group_id, direction) DEFERRABLE INITIALLY DEFERRED` (V23 migration with pre-flight DO $$ guard) rejects unbalanced ledger writes at commit time — Validated in Phase 39: AKEY-09, LEDGER-01
- ✓ Operational resilience: `@Transactional(timeout = 300)` on MTN and Orange poller `executeInternal()` bounds worst-case Postgres advisory lock hold time to 300 seconds — crash or hung provider call can no longer block the 5-minute Quartz re-fire interval; `TenantContextExceptionIT` closes exception-path coverage gap proving `finally { TenantContext.clear(); }` fires on malformed-body and 405 paths — Validated in Phase 40: OPS-01, OPS-03

- ✓ Platform MSISDN management: admin can view/update Orange + MTN platform MSISDNs; email notification on every change — v4
- ✓ Spring Boot Actuator `/manage/health` reflects live provider MSISDN validation + circuit breaker state for both providers — v4
- ✓ Admin health dashboard: all Actuator component results visible to ROLE_ADMIN; access-denied banner for non-admins — v4
- ✓ Email notifications for 6 tenant/key lifecycle events: key generation/rotation, revocation/reactivation, secret generation, tenant status change, webhookUrl change, tenant email change — Validated in Phase 32: NOTIF-01..06
- ✓ OPS-02: fraud velocity token consumption occurs only after idempotency result cached — proved via FraudVelocityOrderingIT idempotency-key replay path — Validated in Phase 38: OPS-02
- ✓ OPS-04 / TXN-01: fee evaluation hoisted before transaction boundary in PaymentOrchestrator — Validated in Phase 38: TXN-01
- ✓ AKEY-09: concurrent API key rotation serialized via @Version optimistic lock — loser receives HTTP 409 — Validated in Phase 39: AKEY-09
- ✓ LEDGER-01: deferrable unique constraint on ledger_entry(entry_group_id, direction) rejects unbalanced debit+credit pairs at commit time — Validated in Phase 39: LEDGER-01
- ✓ AES256-encrypted `pin` column on `PlatformConfig` entity — Flyway V24 migration, `pinCryptopher` @Bean backed by `payam.platform.pin-encryption-secret` / `PLATFORM_PIN_ENCRYPTION_SECRET` env var — v8 (Phase 41): PIN-01, PIN-02
- ✓ PUT `/v1/admin/platform-config/{provider}` accepts optional `pin` field with alphanumeric 4–8 char validation; encrypted via `pinCryptopher` and persisted atomically with MSISDN; `PlatformConfigDto` gains `pinConfigured: boolean` — v8 (Phase 42): PIN-03, PIN-04
- ✓ GET `/v1/admin/platform-config/{provider}/pin` reveal endpoint — decrypts and returns plaintext PIN; 404 if not configured; no ciphertext leakage via `@JsonInclude(NON_NULL)` on `PlatformConfigDto` — v8 (Phase 42): PIN-05
- ✓ Per-provider masked PIN input on `PlatformConfigPage.vue` — Quasar eye-toggle, eye-click reveals via GET /pin, 60s countdown auto-masks, manual re-click re-masks immediately, empty Save preserves existing PIN — v8 (Phase 43): PIN-06, PIN-07, PIN-08
- ✓ Add Provider dialog PIN field persisted on first row creation — `orElseGet` branch extended to mirror `map` branch; snackbar shows "(PIN set)" confirmation — v8 (Phase 45): PIN-09
- ✓ `PlatformConfigChangedEvent` carries `msisdnChanged`, `pinChanged`, `changedBy`; fires only on real change, suppressed on no-op and first-time PIN creation — v8 (Phase 44): PIN-10
- ✓ `PlatformConfigEmailListener` renders conditional MSISDN/PIN change rows + admin username + timestamp in email; PIN value never leaks — v8 (Phase 44): PIN-11

- ✓ Flyway V25 schema migration: drop `uq_ledger_entry_group_direction` unique constraint, add deferrable `check_ledger_balance` constraint trigger (SUM DEBIT == SUM CREDIT per entry group at commit), relax `amount >= 0` for zero-fee entries, add nullable `flow VARCHAR(20)` to `main.transaction` and `main.transaction_aud` — v9 (Phase 46): SCHEMA-01, SCHEMA-02, SCHEMA-03, SCHEMA-04

### Active

<!-- v9 Ledger Disbursement Support — requirements defined 2026-04-21 -->

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
| `saveAndFlush` on prior ROTATED key in `ApiKeyService.rotate()` | Hibernate batches UPDATE+INSERT in wrong order without explicit flush, causing partial unique index violation | ✓ Good — required pattern for any constrained sibling INSERT in same transaction |
| Bulk `@Modifying` JPQL for `TenantService.suspend()` key revocation | Atomicity: N individual saves risk partial failure if one key fails to save; single query is all-or-nothing | ✓ Good — use bulk JPQL for multi-row state transitions |
| Entity-level load+save (not bulk JPQL) for `revokeExpiredRotatedKeys()` | Envers captures each revocation as a separate audit revision; bulk JPQL bypasses Envers | ✓ Good — use entity-level ops when audit trail is required per row |
| Flyway V21 migrates `rotated_at` to `TIMESTAMPTZ` | Quartz job's `Instant` parameters compared against TIMESTAMP(no tz) caused timezone mismatch in Testcontainers (Postgres defaulted to Europe/Berlin) | ✓ Good — always use TIMESTAMPTZ for timestamp columns that are compared with JVM Instant values |
| `VARCHAR(500)` for `pin` ciphertext column (v8) | AES256 Base64 output for 4–8 char PIN is ~80–120 chars; 500 provides headroom without over-engineering | ✓ Good — correct sizing; CHAR(512) would have been equivalent |
| `platform_config_aud` created in V24 (not V20) (v8) | V20 already shipped; `CREATE TABLE IF NOT EXISTS` in V24 corrects the Envers gap idempotently | ✓ Good — idempotent CREATE TABLE IF NOT EXISTS pattern for retroactive Envers table creation |
| `updatePin(ciphertext)` called BEFORE `save(newConfig)` in `orElseGet` branch (v8) | JPA flushes at transaction commit; setting the field before save ensures the pin column is included in the INSERT | ✓ Good — required ordering for JPA transient-to-persistent PIN assignment |
| No `PlatformConfigChangedEvent` from `orElseGet` branch even when PIN set (v8) | PIN-10 semantics: first-time row creation does not count as a "change event" | ✓ Good — consistent with PIN-10 fire rules; matches audit's explicit exclusion |

## Current Milestone: v9 Ledger Disbursement Support

**Goal:** Extend the double-entry ledger to support disbursement/cashout flows — merchant wallet debited the gross amount, customer credited the principal, provider retains the fee.

**Target features:**
- `LedgerFlow` enum (COLLECTION / DISBURSEMENT) in `transaction/contract`
- `LedgerPosting` record — callers express intent, not account codes
- `LedgerService` rewrite routing to flow-specific entry builders
- Update existing collection call-sites to `LedgerPosting.collection()`
- `Transaction.flow` column (Flyway V25) for reconciliation without inferring intent from account codes
- Wire `LedgerPosting.disbursement()` into Orange cashout orchestration path
- Unit + integration tests; `mvn verify` must pass after every phase commit

## Shipped Milestone: v8 Platform Config PIN ✅

**Shipped:** 2026-04-21 — 5 phases (41–45), 8 plans

**Delivered:** AES256-encrypted PIN field on `PlatformConfig` — admins can store, reveal (masked with 60s auto-expiry), and receive email notification for provider credential changes. Add Provider dialog correctly persists PIN on first creation (GAP-01 closed in Phase 45). All 11 requirements (PIN-01..PIN-11) satisfied.

## Current State

**Shipped:** v8 (2026-04-21) — 45 phases total (13 v1 + 4 v2 + 6 v3 + 3 v4 + 4 v5 + 5 v6 + 6 v7 + 5 v8), 98 plans
**Next:** v9 — TBD (start with `/gsd:new-milestone`)
**Codebase:** Spring Boot 3.5 + Spring Security + Spring Data JPA + Resilience4j + Quartz + Bucket4j + logstash-logback-encoder + micrometer-tracing-bridge-otel + Vue 3 + Quasar + Hibernate Envers + Cryptopher/Jasypt AES256
**Observability:** Full Loki-queryable structured logging + Spring Boot Actuator health with live provider MSISDN validation + CB state
**Test coverage:** Machine-checked E2E suite (32 test classes) + domain invariants + concurrency races + SM path matrix + PITest ≥90% mutation coverage + 22 tenant/key integration tests + PIN integration tests (PlatformConfigAdminResourceIT: 12 tests)
**Constraint:** `mvn verify` (including integration tests) must pass before every commit
**Known tech debt:**
- `TenantProvisioningIT.tearDown()` does not clean audit tables — rows accumulate across test runs (non-critical)
- Dead `updatePlatformConfig(provider, platformMsisdn)` method in `admin.api.js` (TD-01) — no longer called after v8; risk of misuse by future devs
- `@EventListener` (synchronous) on `PlatformConfigEmailListener` — failure rolls back config update; matches project pattern but `@TransactionalEventListener` would be safer (TD-02, low risk)

## Evolution

This document evolves at phase transitions and milestone boundaries.

*Last updated: 2026-04-21 — Milestone v9 started

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
*Last updated: 2026-04-21 after v8 milestone
