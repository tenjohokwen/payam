# Payam

## What This Is

Payam is a unified, multi-tenant payment API for Cameroon that wraps MTN Mobile Money and Orange Money behind a single, consistent interface. It handles the full payment lifecycle — initiation, async provider communication, webhook verification, fraud screening, and reconciliation — so consuming applications integrate once and work with both providers without touching provider-specific details.

## Core Value

Reliable, fraud-resistant payment processing with full traceability — no double charges, no blind trust of webhooks, no silent failures.

## Current State

v12 complete — shipped 2026-05-12. All 5 phases of the v12 architectural refactor shipped and archived. The codebase now has explicit bounded contexts: `payment.*` (core, ledger, disbursement, fee, reconciliation, fraud, webhook, provider), `platform.*` (tenant, security, notification, monitoring, admin), `infrastructure.*` (persistence, config, web). The `com.softropic.payam.common` package no longer exists — all 685 Java source files compile under the new hierarchy with `mvn verify` green (775+ tests).

**Next:** `/gsd:new-milestone` — define v13 requirements

## Shipped Milestone: v12 Architectural Reorganization ✅

**Shipped:** 2026-05-12 — 5 phases (61–65), 22 plans

**Delivered:** Restructured the flat `com.softropic.payam` package hierarchy into explicit bounded contexts. Created `infrastructure.*` layer (persistence, config, web). Consolidated `payment.*` domain (core, ledger, disbursement, fee, reconciliation, fraud, webhook). Encapsulated provider adapters under `payment.provider.{mtn,orange}`. Grouped platform services under `platform.*`. Eliminated `common` package entirely — zero `common.*` imports remain. All 24 v12 requirements satisfied (INFRA-01..03, PLAT-01..05, PAY-01..07, PROV-01..02, CMN-01..04, BUILD-01..03).

## Shipped Milestone: v11 Transaction-Backed Disbursements ✅

**Shipped:** 2026-05-05 — 7 phases (54–60), 17 plans

**Delivered:** Replaced the pre-funded wallet-balance model with claim-based locking — every disbursement is explicitly backed by validated collection transactions. `DisbursementTransactionRef` claim lifecycle (PENDING → CLAIMED | RELEASED); admin approval gate with Quartz auto-expiry; idempotency retry recovery; insufficient funds alerting; V31 + V32 Flyway migrations. All 24 v11 requirements satisfied.

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
- ✓ `LedgerFlow` enum (COLLECTION/DISBURSEMENT) + `LedgerPosting` record (flow, principal, fee, currency; compact constructor uses `compareTo(ZERO)` for scale-safe validation; two static factories) — v9 (Phase 47): CONTRACT-01, CONTRACT-02, CONTRACT-03, CONTRACT-04
- ✓ `LedgerService.postEntry(txId, tenantId, LedgerPosting)` routes via `switch(posting.flow())` to COLLECTION (2-entry) and DISBURSEMENT (3-entry) private builders; old 4-arg signature deleted; 4 account-code strings are private constants — all call sites migrated atomically — v9 (Phase 47): SERVICE-01, SERVICE-02, SERVICE-03, SERVICE-04, SERVICE-05
- ✓ `Transaction.flow` nullable field `@Enumerated(EnumType.STRING)` mapping V25 `flow VARCHAR(20)` column; `getEffectiveFlow()` null-coalesces to `LedgerFlow.COLLECTION` for pre-v9 rows — v9 (Phase 47): SERVICE-06
- ✓ `LedgerBalanceGuardTest`: 2 new `@Test` methods for DISBURSEMENT (`fee > 0` and `fee == 0`) in `com.softropic.payam.domain` package — PITest mutation kill rate 100% (4/4) on LedgerService — v9 (Phase 48): TEST-01, TEST-02, TEST-03, TEST-04, TEST-05
- ✓ `LedgerServiceIT.postEntry_disbursement_persistsThreeBalancedRows`: Testcontainers + real PostgreSQL integration test; V25 balance-check trigger accepts DISBURSEMENT group at commit; shared `entry_group_id` across all 3 rows — v9 (Phase 48): TEST-06
- ✓ `LedgerVerifier.assertDisbursementLedgerBalanced(txId, principal, fee)`: reusable E2E helper for Phase 49 downstream tests; 5 unit tests in `LedgerVerifierTest`; existing `assertLedgerBalanced` untouched — v9 (Phase 48): TEST-07
- ✓ V31 schema migration: `disbursement_transaction_ref` table with partial unique index; `admin_note`/`retry_count` on disbursement; `reserved_amount` removed; `PENDING_ADMIN_APPROVAL` state; wallet balance application-layer retired; pre-flight DO $$ guard — v11 (Phase 54): SCHEMA-01, SCHEMA-02, SCHEMA-03
- ✓ `TransactionClaimValidationService.validateAndClaim()`: tenant ownership, SUCCESS/COLLECTION validation, active-claim guard, amount equality, deadlock-safe `SELECT FOR UPDATE` with ascending `transaction_id` order; `DisbursementClaimConcurrencyIT` proves no deadlock — v11 (Phase 55): TXN-01..06, FEE-01, FEE-02
- ✓ `DisbursementClaimTransitionService` bulk JPQL: PENDING→CLAIMED on SUCCESS, PENDING→RELEASED on FAILED/admin-approval expiry; CLAIM-05 invariant: PROCESSING→EXPIRED leaves claims in CLAIMED for ops reconciliation — v11 (Phase 56): CLAIM-01..05
- ✓ Admin approval gate: disbursements > `payam.disbursement.admin-approval-threshold` → `PENDING_ADMIN_APPROVAL`; `DisbursementAdminApprovalExpiryJob` auto-expires with claim release; `DisbursementOpsAlertEmailListener` fires for admin-approval and insufficient funds — v11 (Phase 56): ADMIN-01..03, ALERT-01
- ✓ `DisbursementRetryClassifier` + `handleRetry()`: RETRIABLE codes reactivate RELEASED claims to PENDING (no new rows), increment `retry_count`, re-enter provider dispatch; TERMINAL codes return cached response; `FAILED→INITIATED` transition added to state machine; V32 migration scaffolded — v11 (Phase 57): IDEM-01..03, SCHEMA-04
- ✓ Full E2E suite: MTN/Orange claim lifecycle, admin-approval expiry path, idempotency retry recovery, CLAIM-05 PROCESSING→EXPIRED invariant via raw SQL on `disbursement_transaction_ref`; `mvn verify` 474 unit + 301 IT, 0 failures — v11 (Phases 58, 60)
- ✓ `PaymentCommand` gains 14th nullable `BigDecimal feeAmount` component; backward-compat 13-arg constructor delegates to canonical with `feeAmount=null`; `withFeeAmount(BigDecimal)` wither method; `PaymentOrchestrator.initiate()` enriches in-flight command via `cmd = cmd.withFeeAmount(fee)` before port dispatch — v9 (Phase 49): CASHOUT-01
- ✓ `OrangeMoneyPort.initiateCashout()` calls `orangeMoneyClient.cashout()`, guards on `is2xxSuccessful()`, posts `LedgerPosting.disbursement(principal, fee, currency)` via `transactionTemplate.execute` (no `@Transactional` on method); null `feeAmount` falls back to `BigDecimal.ZERO` — `OrangeMoneyPortIT`: 8/8 tests green — v9 (Phase 49): CASHOUT-02

### Active

*(v13 requirements TBD — run `/gsd:new-milestone` to define)*

#### Phase 50 complete — Validated in Phase 50: BAL-01, BAL-02, BAL-03
- ✓ Flyway V28: `main.disbursement`, `main.disbursement_aud`, `main.merchant_wallet_balance`, `main.merchant_wallet_balance_aud` with named constraints — v10 (Phase 50)
- ✓ `DisbursementStatus` enum (INITIATED → PENDING_CONFIRMATION → PROCESSING → SUCCESS | FAILED | EXPIRED); EXPIRED terminal state (BAL-03); 14 state machine unit tests — v10 (Phase 50)
- ✓ `WalletBalanceService.checkAndReserve()` + `release()` with PESSIMISTIC_WRITE lock; guard-before-mutate; 20-thread concurrency IT proves no overdraft (1 success, 19 InsufficientBalanceException, final balance = 0) — v10 (Phase 50)

#### Phase 53 complete — Validated in Phase 53: TEST-01, TEST-02, TEST-03, TEST-04
- ✓ `MtnDisbursementE2EIT`: full MTN disbursement HTTP lifecycle E2E — initiate→PROCESSING→callback SUCCESS+ledger / FAILED+wallet release / replay dedup — v10 (Phase 53): TEST-01
- ✓ `OrangeDisbursementE2EIT`: full Orange disbursement HTTP lifecycle E2E — initiate→SUCCESSFULL/insufficient balance(422)/replay dedup; `OrangeMoneyPort.initiateDisbursement()` payToken extraction bug fixed — v10 (Phase 53): TEST-02
- ✓ `StepUpConfirmationE2EIT`: step-up gate (>500K XAF→PENDING_CONFIRMATION, zero provider calls), confirm dispatch (→PROCESSING, 1 MTN transfer), INVALID_STATE rejection — v10 (Phase 53): TEST-03
- ✓ `DisbursementExpiryE2EIT`: HTTP-initiated step-up → direct `DisbursementExpiryJob.executeInternal()` → EXPIRED; BAL-03 invariant (wallet held); GET shows EXPIRED — v10 (Phase 53): TEST-03
- ✓ `DisbursementConcurrencyRaceIT`: 20-thread CyclicBarrier HTTP race, single-spend wallet; exactly 1 PROCESSING + 19 INSUFFICIENT_BALANCE; BAL-01 invariant proven — v10 (Phase 53): TEST-04
- ✓ `DisbursementFraudBlockE2EIT`: Redis blocklist+new-recipient (score 95>80)→FRAUD_BLOCK (zero rows, zero provider calls); idempotency race (20 threads, same key→exactly 1 row) — v10 (Phase 53): TEST-01, TEST-04

#### Phase 52 complete — Validated in Phase 52: SEC-05, SEC-06
- ✓ V29 Flyway: `poll_attempts INTEGER NOT NULL DEFAULT 0` on `main.disbursement`; V30: `transaction_status VARCHAR(20)` on `main.webhook_delivery_log` with backfill — v10 (Phase 52)
- ✓ `MtnDisbursementCallbackController` (PUT `/v1/callbacks/mtn/disbursement/{ref}`) + `OrangeDisbursementCallbackController` (POST `/v1/callbacks/orange/disbursement`); both IP-whitelisted, always return 200 OK, swallow exceptions; dedup on `callbacks:dsb:<providerRef>:<status>` Redis namespace — v10 (Phase 52): SEC-05
- ✓ `DisbursementCallbackTransitionService.applyDisbursementTransition()` with `@Transactional(REQUIRES_NEW)` + `PESSIMISTIC_WRITE` lock; atomic wallet release on FAILED; idempotent replay guard (silent return on terminal → terminal attempt) — v10 (Phase 52): SEC-05
- ✓ `OutboundWebhookPayload.of()` factory derives status from `TransactionStatus` enum (replaces `eventType.contains("SUCCESS")`); `DISBURSEMENT_COMPLETED`/`DISBURSEMENT_FAILED` map correctly — v10 (Phase 52): SEC-06
- ✓ `WebhookDoubleCheckHandler` routes `LedgerFlow.DISBURSEMENT` events to `DisbursementCallbackTransitionService`; `COLLECTION` branch unchanged — v10 (Phase 52)
- ✓ 12 integration tests: `MtnDisbursementCallbackControllerIT` + `OrangeDisbursementCallbackControllerIT` (SEC-05 E2E, replay dedup), `DisbursementWebhookDeliveryIT` (SEC-06 HMAC delivery + retry scheduling) — v10 (Phase 52)

#### Phase 51 complete — Validated in Phase 51: DISB-01, DISB-02, DISB-03, DISB-04, PROV-01, PROV-02, PROV-03, SEC-01, SEC-02, SEC-03, SEC-04
- ✓ `DisbursementIdempotencyService` using `idempotency:dsb:<tenantId>:<key>` Redis namespace; Postgres-first ordering; shares `IdempotencyKeyRepository` with collection path — v10 (Phase 51): SEC-01
- ✓ `DisbursementVelocityService`: Bucket4j-on-Redis, 3 gates (20/min per tenant, 200/hr per tenant, 10/day per MSISDN); `VelocityExceededException` + `DailyLimitExceededException` — v10 (Phase 51): SEC-02
- ✓ `DisbursementFraudEvaluationService`: 3 signals (new recipient +15, amount outlier +30, blocklist +80); blocks at score > 80; fail-open for tenants with <10 SUCCESS rows — v10 (Phase 51): SEC-03
- ✓ `DisbursementOrchestrator`: 11-step initiate() + confirm() + releaseAndFail(); TransactionTemplate (no class-level @Transactional); MTN + Orange ports wired; PESSIMISTIC_WRITE re-lock on confirm — v10 (Phase 51): DISB-01, DISB-04, PROV-01, PROV-02, PROV-03
- ✓ `DisbursementResource`: 4 REST endpoints (POST initiate, GET by id, GET list, POST confirm); 202 for PROCESSING/PENDING_CONFIRMATION; tenant-scoped 404 — v10 (Phase 51): DISB-02, DISB-03
- ✓ `DisbursementExpiryJob` (Quartz, 60s): ages PENDING_CONFIRMATION → EXPIRED after 15 min; PESSIMISTIC_WRITE re-check; no wallet balance touch (BAL-03 safe) — v10 (Phase 51): SEC-04
- Note: PROV-01/PROV-02 5-minute callback polling fallback deferred to Phase 52 (requires Quartz job + disbursement-specific poller wiring)

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
| `compareTo(BigDecimal.ZERO)` for LedgerPosting zero checks (v9) | `.equals()` is scale-sensitive — `new BigDecimal("0.00").equals(ZERO)` is false; `compareTo` is the correct idiom | ✓ Good — established pattern for all BigDecimal zero comparisons in domain code |
| Atomic commit for LedgerService rewrite + all call sites (v9) | Build only compiles when new 3-arg signature and all 3 migrated call sites are committed together — partial commits would break CI | ✓ Good — required approach for any method signature change with immediate deletion of the old signature |
| No `@Builder.Default` on `Transaction.flow` nullable field (v9) | Pre-v9 rows must remain `null` in DB; `@Builder.Default` would set COLLECTION on new builder calls masking the null — fallback belongs in `getEffectiveFlow()` only | ✓ Good — correct nullable JPA field pattern; null-coalescing in accessor is the right isolation |
| No `@Transactional` on `OrangeMoneyPort.initiateCashout()` (v9) | Consistent with `PaymentOrchestrator.initiate()` pattern — holding DB connection during provider HTTP exhausts pool; `TransactionTemplate` scopes DB work precisely | ✓ Good — established pattern for any port method that mixes HTTP I/O with discrete DB writes |
| Null `feeAmount` falls back to `BigDecimal.ZERO` in `initiateCashout()` (v9) | PROVIDER_FEE entry is always written (zero-amount allowed by V25 `amount >= 0`); prevents conditional branching in ledger posting for zero-fee providers | ✓ Good — zero-fee disbursement always produces a balanced 3-entry group |

## Shipped Milestone: v9 Ledger Disbursement Support ✅

**Shipped:** 2026-04-23 — 4 phases (46–49), 8 plans

**Delivered:** Full disbursement/cashout ledger support — `LedgerFlow` enum + `LedgerPosting` record in `transaction/contract`; `LedgerService` routes to COLLECTION (2-entry) and DISBURSEMENT (3-entry) builders; Flyway V25 schema migration; `Transaction.flow` column; `OrangeMoneyPort.initiateCashout` wired with real provider call + balanced 3-row ledger entry; `PaymentCommand.feeAmount` propagated from `FeeEvaluationService` through orchestrator to port. All 16 requirements (SCHEMA-01..04, CONTRACT-01..04, SERVICE-01..06, TEST-01..08, CASHOUT-01..02) satisfied.

## Shipped Milestone: v8 Platform Config PIN ✅

**Shipped:** 2026-04-21 — 5 phases (41–45), 8 plans

**Delivered:** AES256-encrypted PIN field on `PlatformConfig` — admins can store, reveal (masked with 60s auto-expiry), and receive email notification for provider credential changes. Add Provider dialog correctly persists PIN on first creation (GAP-01 closed in Phase 45). All 11 requirements (PIN-01..PIN-11) satisfied.

## Current State

**Shipped:** v9 (2026-04-23) — 49 phases total (13 v1 + 4 v2 + 6 v3 + 3 v4 + 4 v5 + 5 v6 + 6 v7 + 5 v8 + 4 v9), 106 plans
**In progress:** v11 Transaction-Backed Disbursements — Phase 59 complete (2026-05-05); Phase 60 next
**Next:** `/gsd:plan-phase 60`
**Codebase:** Spring Boot 3.5 + Spring Security + Spring Data JPA + Resilience4j + Quartz + Bucket4j + logstash-logback-encoder + micrometer-tracing-bridge-otel + Vue 3 + Quasar + Hibernate Envers + Cryptopher/Jasypt AES256
**Observability:** Full Loki-queryable structured logging + Spring Boot Actuator health with live provider MSISDN validation + CB state
**Test coverage:** Machine-checked E2E suite (32 test classes) + domain invariants + concurrency races + SM path matrix + PITest ≥90% mutation coverage + 22 tenant/key integration tests + PIN integration tests (PlatformConfigAdminResourceIT: 12 tests)
**Constraint:** `mvn verify` (including integration tests) must pass before every commit
**Known tech debt:**
- `TenantProvisioningIT.tearDown()` does not clean audit tables — rows accumulate across test runs (non-critical)
- Dead `updatePlatformConfig(provider, platformMsisdn)` method in `admin.api.js` (TD-01) — no longer called after v8; risk of misuse by future devs
- `@EventListener` (synchronous) on `PlatformConfigEmailListener` — failure rolls back config update; matches project pattern but `@TransactionalEventListener` would be safer (TD-02, low risk)
- `LedgerConstraintIT.flowColumn_existsAndIsNullable` asserts `character_maximum_length = 20` but PostgreSQL stores `VARCHAR(20)` as `character varying` with length 20; test may show 255 vs 20 mismatch in some environments (TD-03, low risk)

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

- ✓ `infrastructure.persistence` (8 JPA base classes), `infrastructure.config` (AsyncConfig, DataSourceConfig, ObservabilityConfig), `infrastructure.web` (ApiKeyAuthenticationFilter, TenantSecurityConfig, LoggingFilter) — v12 (Phase 61): INFRA-01, INFRA-02, INFRA-03
- ✓ `platform.tenant`, `platform.security`, `platform.notification` (email+alert), `platform.monitoring` (health+ops), `platform.admin` (admin+platform config) — v12 (Phase 62): PLAT-01, PLAT-02, PLAT-03, PLAT-04, PLAT-05
- ✓ `payment.core` (collection), `payment.ledger` (transaction/idempotency/event-log), `payment.disbursement`, `payment.fee`, `payment.reconciliation`, `payment.fraud`, `payment.webhook` — v12 (Phase 63): PAY-01..07
- ✓ `payment.provider.mtn` and `payment.provider.orange` — hexagonal boundary enforced, no domain package references provider directly — v12 (Phase 64): PROV-01, PROV-02
- ✓ `com.softropic.payam.common` package fully eliminated — all types redistributed to owning bounded contexts; CMN-04 grep gate passes (zero matches) — v12 (Phase 65): CMN-01, CMN-02, CMN-03, CMN-04

---
*Last updated: 2026-05-12 — v12 complete: architectural reorganization shipped (Phases 61–65)*
