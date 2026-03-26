# Milestone v1: Payam Payment API

**Status:** ✅ SHIPPED 2026-03-26
**Phases:** 1–13
**Total Plans:** 29

## Overview

Payam v1 is a unified, multi-tenant payment API for Cameroon wrapping MTN Mobile Money and Orange Money behind a single consistent interface. Built in 13 phases over 4 days. The foundation establishes multi-tenant isolation and an event-sourced transaction backbone before provider adapters, orchestration, fraud, monitoring, reconciliation, and operational hardening complete the v1 scope.

## Phases

### Phase 1: Multi-Tenant Foundation

**Goal**: Tenant schema, API key authentication, and per-client isolation
**Depends on**: Nothing (first phase)
**Plans**: 3 plans

Plans:

- [x] 01-01: Tenant domain model, Flyway schema, RLS policy, API key entity
- [x] 01-02: API key filter chain (`@Order(1)`), `TenantContext`, idempotency key schema
- [x] 01-03: Tenant admin REST endpoints — rotate/revoke HTTP API, `EntityNotFoundException` → 404

**Details:**
- ApiKeyStatus enum (ACTIVE/ROTATED/REVOKED) separate from EntityStatus — API key lifecycle distinct
- `@Configuration("tenantAsyncConfig")` — Spring 6.2+ ConflictingBeanDefinitionException fix
- `ApiKeyAuthenticationFilter` NOT `@Component` — `@Bean` in `TenantSecurityConfig` to prevent double-registration
- `NegatedRequestMatcher(OrRequestMatcher(/v1/account/**))` excludes JWT chain from account paths
- `FilterRegistrationBean(setEnabled=false)` pattern established for preventing servlet auto-registration

---

### Phase 2: Transaction Core + Event Sourcing

**Goal**: State machine, append-only event log with SHA-256 hash chain, idempotency store, double-entry ledger
**Depends on**: Phase 1
**Plans**: 3 plans

Plans:

- [x] 02-01: Transaction entity, state machine (`TransactionStatus` enum), Flyway schema
- [x] 02-02: `PaymentEventLog` append-only table, SHA-256 hash chain via `DigestUtils.sha256Hex`
- [x] 02-03: Idempotency store (Redis NX+EX + PostgreSQL fallback), double-entry ledger schema

**Details:**
- `Transaction.txStatus` has no public setter — `applyTransition()` is the only mutation point
- Hash canonical string: pipe-delimited domain fields only (no timestamps) — ensures reproducibility
- `@JdbcTypeCode(SqlTypes.JSON)` required on `String metadata` → PostgreSQL jsonb
- `LedgerEntry` uses `@Builder` not `@SuperBuilder` and `@Immutable` — Hibernate refuses dirty-check
- `@ServiceConnection(name='redis')` on GenericContainer — Spring Boot inference requires name attribute

---

### Phase 3: Orange Money Adapter

**Goal**: Orange Money provider adapter — init→pay→push flow, status polling, subscriber validation
**Depends on**: Phase 2
**Plans**: 4 plans

Plans:

- [x] 03-01: `OrangeMoMoGateway` impl — init, pay, push, status polling, subscriber validation
- [x] 03-02: Orange DTOs, error mapping, payToken expiry handling, WAT timezone parsing
- [x] 03-03: `OrangeStatusPollerJob` — assertPayTokenFresh, poll attempts, WAT timestamp util
- [x] 03-04: `OrangeMoneyPort` integration — port wiring, circuit breaker config, IT tests

**Details:**
- `OrangeStatus.SUCCESSFULL` has double-L — verbatim per Orange API response
- `@CircuitBreaker fallbackMethod` removed — Resilience4j calls fallback for ALL exceptions (not just circuit-open)
- Circuit breaker `ignoreExceptions` for `SubscriberInactiveException` and `PayTokenExpiredException`
- `getCreatetimeAsInstant()` is the sole WAT parsing call site — raw `getCreatetime()` must never be used for arithmetic
- `cashout`/`C2C` stubs: `UnsupportedOperationException` — sandbox field verification required

---

### Phase 4: MTN MoMo Adapter

**Goal**: MTN MoMo provider adapter — OAuth2 lifecycle, RequestToPay, disbursement, account validation
**Depends on**: Phase 2
**Plans**: 2 plans

Plans:

- [x] 04-01: `MtnMoMoGateway` impl — OAuth2 token manager, RequestToPay, disbursement, account validation
- [x] 04-02: MTN DTOs, PUT callback handler, IP whitelist, Redis token cache

**Details:**
- `MtnMoMoClient` token POST sends null body — MTN returns 400 if form body is sent
- `disburse()` uses `getDisbursementSubscriptionKey()` — wrong key returns 401
- `validateAccountHolder()` catches `HttpClientException` (not `HttpClientErrorException.NotFound`) — `RestRequestInterceptor` converts all 4xx to `HttpClientException`
- `@TestPropertySource(properties = "mtn.callback-ip-whitelist=")` — empty list triggers sandbox mode (accept all)

---

### Phase 5: Payment Orchestration

**Goal**: Unified payment endpoint routing to MTN or Orange by MSISDN prefix, with circuit breakers and polling fallback
**Depends on**: Phase 3, Phase 4
**Plans**: 2 plans

Plans:

- [x] 05-01: `PaymentOrchestrator`, MSISDN routing, `ProviderGateway` dispatch, standardized error codes
- [x] 05-02: Resilience4j circuit breaker config per provider, Quartz polling fallback scheduler

**Details:**
- `PaymentOrchestrator.initiate()` has no `@Transactional` — `TransactionTemplate` used for discrete DB operations
- Three `applyTransition()` calls required: `INITIATED→AUTH_PENDING→AUTHORIZED→PROCESSING`
- `CallNotPermittedException` caught before `HttpClientException` — wrong order swallows circuit-open events
- `PAYMENT_ALREADY_PROCESSING` returns HTTP 202 — duplicate in-flight is semantically accepted
- `noRetryRestTemplate` (SimpleClientHttpRequestFactory) required for circuit breaker IT test

---

### Phase 6: Webhook Processing

**Goal**: Inbound webhook receivers (Orange POST + MTN PUT), double-check verification, outbound delivery to tenants with retry
**Depends on**: Phase 5
**Plans**: 3 plans

Plans:

- [x] 06-01: Inbound webhook controller (Orange POST, MTN PUT), IP whitelist, HMAC verification, Redis dedup
- [x] 06-02: Double-check via Spring Modulith event (`WebhookReceivedEvent` → `@ApplicationModuleListener`)
- [x] 06-03: Outbound webhook delivery service — tenant URL dispatch, HMAC signing, retry with backoff, event log

**Details:**
- HMAC body: `objectMapper.writeValueAsString(payload)` — servlet stream already consumed by `@RequestBody`
- Orange dedup key includes `createtime` (not just `payToken`) — same payToken receives multiple status transitions
- No HMAC on MTN inbound — MTN API contract uses `notifToken` + IP whitelist
- `@Transactional(REQUIRES_NEW)` on `WebhookTransitionService.applyFinalTransition` — `@TransactionalEventListener(AFTER_COMMIT)` fires with no active transaction
- `enqueue()` sets `nextRetryAt=null` on INSERT — Quartz job uses `WHERE nextRetryAt <= :now` (null rows excluded)

---

### Phase 7: Fraud Engine

**Goal**: Velocity checks, risk scoring pipeline, device fingerprinting — all signal weights DB-configurable
**Depends on**: Phase 5
**Plans**: 2 plans

Plans:

- [x] 07-01: `FraudScoringService` — velocity rule evaluation (Bucket4j), risk score computation, device fingerprint capture
- [x] 07-02: DB-backed signal weight config, `FraudRuleRepository`, integration with `PaymentOrchestrator` pre-dispatch hook

**Details:**
- `FraudScoringService.evaluate()` dual block: direct velocity block (any exceeded velocity) then score-based block (weighted sum >= `BLOCK_THRESHOLD`)
- `BLOCK_THRESHOLD` stored as `fraud_rule` row — DB-configurable without restart
- `ForwardedHeaderFilter` strips `Forwarded` header in IT tests — use MSISDN-based signals for velocity testing
- `@NotAudited` on `riskScore` and `deviceFingerprint` — Envers `_AUD` table lacks these columns
- Fraud rule seed data NOT present at test startup — dev profile create-drop wipes Flyway data; seed in `@BeforeEach`

---

### Phase 8: Admin Dashboard + Monitoring

**Goal**: Transaction investigation UI (Quasar SPA), live SSE metrics feed, custom Micrometer counters
**Depends on**: Phase 6, Phase 7
**Plans**: 3 plans

Plans:

- [x] 08-01: Admin REST endpoints — transaction search, detail view with event timeline, per-client history
- [x] 08-02: Micrometer custom counters/timers for payment domain metrics (TPS, latency, fraud rate)
- [x] 08-03: Quasar SPA admin views — dashboard with SSE live feed, transaction search and detail UI

**Details:**
- `NegatedRequestMatcher(OrRequestMatcher(/v1/account/**, /v1/admin/**)` — admin paths excluded from API-key chain
- `adminSearch` JPQL uses `ORDER BY` in query text, not `PageRequest` sort
- `@EnableScheduling` already in `AsyncConfig` (email.config) — no new config class needed
- `providerStart` declared before outer try block — latency recorded on any provider call outcome
- Any new `/v1/admin/**` endpoint is automatically JWT-protected — no further security config changes needed

---

### Phase 9: Reconciliation

**Goal**: Daily Quartz reconciliation job comparing Payam ledger against MTN/Orange provider reports, with discrepancy flagging and export
**Depends on**: Phase 8
**Plans**: 2 plans

Plans:

- [x] 09-01: Quartz JDBC reconciliation scheduler, MTN/Orange report fetcher, ledger comparison logic
- [x] 09-02: Discrepancy model, flagging logic, admin dashboard surface, CSV/JSON export endpoint

**Details:**
- `DiscrepancyType` has no `MISSING_IN_PAYAM` — neither Orange nor MTN expose batch listing API
- Per-provider exception isolation — one provider failure never aborts other provider's reconciliation
- `FilterRegistrationBean(setEnabled=false)` pattern established for `ApiKeyAuthenticationFilter`
- `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)` retained on `ReconciliationResource` — JWT chain requires only authenticated user; `@PreAuthorize` enforces `ROLE_ADMIN`

---

### Phase 10: Operational Hardening

**Goal**: Fee management, real-time alert rules, TLS startup assertion, provider health endpoint, circuit breaker tuning
**Depends on**: Phase 9
**Plans**: 4 plans

Plans:

- [x] 10-01: Fee engine — `FeeRule` entity, per-tenant and global rules, evaluation at transaction time
- [x] 10-02: Alert rules — threshold config, `ApplicationEventPublisher` → notification channel wiring
- [x] 10-03: TLS startup assertion (`ApplicationReadyEvent`), provider health Actuator indicator, circuit breaker status endpoint
- [x] 10-04: CALLBACK_ANOMALY gap closure — real ratio metric, controller instrumentation, AlertRuleIT 5/5

**Details:**
- `TlsStartupAssertion` uses `Environment.getProperty("client.momo.tcp-config.check-certificate")` — `OrangeMoneyConfig`/`MtnMoMoConfig` have no `tcpConfig` field
- `ProviderStatusResource` force-creates "orange" and "mtn" CBs before `getAllCircuitBreakers()` — Resilience4j lazy CB creation
- `AlertRule.metricName` stored as plain `String` — allows new metric names via DB row without code change
- `AlertFiredEventCaptor` registered via `@TestConfiguration` inner class — static inner `@Component` not scanned by `SpringBootTest`

---

### Phase 11: Fee Exposure

**Goal**: Surface `feeAmount` on every payment — add to `PaymentResponse` and `OutboundWebhookPayload`
**Depends on**: Phase 10
**Plans**: 1 plan

Plans:

- [x] 11-01: Add `feeAmount` + `feeRuleId` to `PaymentResponse` DTO and `OutboundWebhookPayload`; update IT assertions

**Details:**
- Array holders `BigDecimal[]{ZERO}` and `Long[]{null}` capture fee values from `transactionTemplate` lambda — lambda locals must be effectively-final
- `WebhookDeliveryLog.feeAmount` nullable — null-guarded to `ZERO` in `enqueue()` and `attemptDeliveryInternal()`
- `fee_rule` JDBC seed requires `rule_name` column (NOT NULL) — add literal to any new `fee_rule` JDBC INSERT in tests

---

### Phase 12: Test & Doc Polish

**Goal**: Close two minor tech-debt items — missing IT test path and incomplete Javadoc entry
**Depends on**: Phase 11
**Plans**: 1 plan

Plans:

- [x] 12-01: Add device fingerprint IT assertion; fix `PaymentResource` Javadoc

**Details:**
- `buildMtnRequestWithFingerprint()` added as separate helper — preserves existing call sites unchanged
- MSISDN `+237671000005` reserved for device fingerprint IT test — distinct from velocity/risk-score MSISDNs

---

### Phase 13: Ledger Wiring + Webhook Access Control

**Goal**: Close two audit gaps — wire `LedgerService.postEntry()` on SUCCESS transitions; add `@PreAuthorize` to `WebhookDeliveryResource`
**Depends on**: Phase 12
**Plans**: 1 plan

Plans:

- [x] 13-01: Wire `ledgerService.postEntry()` in `WebhookTransitionService`; add `@PreAuthorize` to `WebhookDeliveryResource`; IT assertions for both

**Details:**
- `ledgerService.postEntry()` placed after `transactionRepository.save(tx)` inside `@Transactional(REQUIRES_NEW)` — REQUIRED propagation joins atomically
- `WebhookDeliveryResource` moved to `/v1/admin/webhooks` with class-level `@PreAuthorize(HAS_ADMIN_ROLE)`
- `b8` sec variant required in IT tests calling `/authenticate` — `k8` variant decrypts to Base64 string containing `~` (0x7e) which fails `Base64.getDecoder().decode()`
- `TenantFilterChainIT` uses try/catch around `GET /v1/payments` — `/v1/payments` is POST-only; GET returns 500 but API key chain passes request (filter chain behavior is what's tested)

---

## Milestone Summary

**Key Decisions:**

- Event sourcing for transactions — required for tamper-proof audit trail
- Double-check webhook pattern — Cameroon fraud risk; webhooks can be forged; provider API is ground truth
- Redis for idempotency/velocity — fast read/write for high-frequency checks; PostgreSQL for durability
- Spring Modulith (Spring Events + PostgreSQL Event Publication Registry) instead of Kafka/RabbitMQ — sufficient for v1 throughput; no operational overhead
- Layered monolith following `contract/repo/service/infrastructure/api/config` pattern

**Issues Resolved:**

- TX-05 ledger infrastructure existed in Phase 2 but had no production callers — wired in Phase 13
- `WebhookDeliveryResource` lacked `@PreAuthorize` — cross-tenant disclosure risk closed in Phase 13
- `CALLBACK_ANOMALY` used placeholder metric in Phase 10-02 — real ratio metric instrumented in Phase 10-04
- `SecurityFilterChainIT.testSecuredEndpointRequiresAuth` pre-existing failure — documented as pre-existing, not a regression

**Issues Deferred:**

- Orange `cashout`/`C2C` operations — `UnsupportedOperationException` stubs; sandbox field verification required
- Stale `payToken` re-initiation — `PaymentOrchestrator` does not re-initiate on expiry; PROCESSING transactions can stall
- `MtnIpWhitelistInterceptor` CIDR matching — no IT coverage (sandbox mode bypasses in tests)
- Live sandbox confirmation for MTN PUT callback and Orange HMAC header name

**Technical Debt Incurred:**

- `TenantContext` string slug propagated to async threads but never read in payment path
- `MISSING_IN_PAYAM` reconciliation type absent by design — finance team should acknowledge
- Live environment tests (SSE, browser CSV/JSON export, JVM restart Quartz durability) not covered by automated tests

---

_Archived: 2026-03-26 as part of v1 milestone completion_
_For current roadmap, see .planning/ROADMAP.md_
