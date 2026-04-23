# Project Milestones: Payam

## v9 Ledger Disbursement Support (Shipped: 2026-04-23)

**Delivered:** Extended the double-entry ledger to support disbursement/cashout flows — `LedgerFlow` enum + `LedgerPosting` record in `transaction/contract`; `LedgerService` routes to COLLECTION (2-entry) and DISBURSEMENT (3-entry) builders; `OrangeMoneyPort.initiateCashout` wired with real HTTP call + 3-row balanced ledger entry via `TransactionTemplate`.

**Phases completed:** 46–49 (4 phases, 8 plans)

**Stats:**
- 95 files changed, 9,412 insertions, 1,295 deletions
- 3 days (2026-04-21 → 2026-04-23)

**Key accomplishments:**

- Flyway V25: drops V23 unique constraint, adds deferrable PL/pgSQL CONSTRAINT TRIGGER asserting SUM(DEBIT)==SUM(CREDIT) per entry group at commit; relaxes `amount > 0` to `amount >= 0`; adds nullable `flow VARCHAR(20)` to `main.transaction` + `main.transaction_aud` (Phase 46)
- `LedgerFlow` enum (COLLECTION/DISBURSEMENT) + `LedgerPosting` Java 17 record with compact-constructor validation using `compareTo(ZERO)` for scale-insensitive BigDecimal checks; two static factories: `collection(principal, currency)` and `disbursement(principal, fee, currency)` (Phase 47)
- `LedgerService.postEntry(txId, tenantId, LedgerPosting)` rewritten with exhaustive switch routing: COLLECTION → 2 entries (DEBIT CUSTOMER_WALLET + CREDIT PROVIDER_CLEARING); DISBURSEMENT → 3 entries (DEBIT MERCHANT_WALLET gross + CREDIT CUSTOMER_WALLET + CREDIT PROVIDER_FEE); old 4-arg signature deleted; all call sites atomically migrated; `Transaction.flow` nullable JPA field + `getEffectiveFlow()` returns COLLECTION for null pre-v9 rows (Phase 47)
- Full disbursement test coverage: 2 unit tests in `LedgerBalanceGuardTest` (fee>0 and fee=0), 100% PITest mutation kill rate on `LedgerService`; `LedgerServiceIT.postEntry_disbursement_persistsThreeBalancedRows` in real Testcontainers PostgreSQL with V25 balance-check trigger (Phase 48)
- `LedgerVerifier.assertDisbursementLedgerBalanced(txId, principal, fee)` reusable E2E helper with 5 unit tests; existing `assertLedgerBalanced` collection helper untouched (Phase 48)
- `PaymentCommand` gains 14th nullable `feeAmount` field with 13-arg backward-compat constructor + `withFeeAmount` wither; `PaymentOrchestrator` enriches in-flight command from `FeeEvaluationService` before port dispatch; `OrangeMoneyPort.initiateCashout()` calls real POST `/cashout`, posts `LedgerPosting.disbursement` in `TransactionTemplate` block; null fee falls back to `BigDecimal.ZERO`; 8/8 `OrangeMoneyPortIT` tests green (Phase 49)

**Archive:** `.planning/milestones/v9-ROADMAP.md`

---

## v8 Platform Config PIN (Shipped: 2026-04-21)

**Delivered:** AES256-encrypted PIN field on `PlatformConfig` — admins can store, reveal (masked with 60s auto-expiry), and receive email notification for provider credential changes, with GAP-01 (Add Provider PIN persistence) closed in Phase 45.

**Phases completed:** 41–45 (5 phases, 8 plans)

**Key accomplishments:**

- Flyway V24: nullable `pin` VARCHAR(500) column on `main.platform_config`, `platform_config_aud` Envers table, `PayamPlatformProperties.pinEncryptionSecret` bound to `PLATFORM_PIN_ENCRYPTION_SECRET` env var — full AES256 storage foundation (Phase 41)
- `Cryptopher` bean wired via `pinCryptopher` @Bean; `PlatformConfigService.update()` encrypts and persists PIN atomically with MSISDN; `PUT /v1/admin/platform-config/{provider}` validates alphanumeric 4–8 chars; `PlatformConfigDto` gains `pinConfigured: boolean` (Phase 42)
- `GET /v1/admin/platform-config/{provider}/pin` reveals decrypted plaintext PIN on demand; 404 when not configured; no ciphertext leakage via `@JsonInclude(NON_NULL)` — full PIN-03/04/05 backend coverage (Phase 42)
- Per-provider masked PIN input with Quasar eye-toggle on `PlatformConfigPage.vue`; eye-click calls reveal endpoint, populates field, starts strict 60s countdown; re-click before expiry re-masks immediately; Save preserves existing PIN when field left empty (Phase 43)
- `PlatformConfigChangedEvent` widened to carry `msisdnChanged`, `pinChanged`, `changedBy`; fires only on real change, suppressed on no-op and first-time PIN creation; `PlatformConfigEmailListener` extended with conditional MSISDN/PIN rows + admin username + timestamp — PIN value never in email (Phase 44)
- GAP-01 closed: `orElseGet` branch in `PlatformConfigService.update()` now encrypts and persists PIN on new-row creation, mirroring the `map` branch; Add Provider success notify shows "(PIN set)" confirmation when `pinConfigured=true` (Phase 45)

---

## v5 Tenant & API Key Management Service Layer (Shipped: 2026-04-06)

**Delivered:** Complete service layer for tenant lifecycle and API key management — `TenantService` lifecycle operations, `ApiKeyService` guards, Hibernate Envers audit trail, PREFIX_UUID key format, and Quartz automated cleanup job. Service layer fully verified; HTTP REST surface and Admin UI deferred to v6.

**Phases completed:** 27–29 (4 phases, 6 plans)

**Key accomplishments:**

- Flyway V18/V19: `key_prefix` column (immutable, tenant-name-derived), `ApiKeyEnvironment` enum (PROD/DEV/SANDBOX replacing LIVE), partial unique index `uidx_tenant_api_key_active_env` enforcing one ACTIVE key per env per tenant, UNIQUE constraint on `key_hash`
- Full TenantService lifecycle: `updateName`, `updateEmail`, `updateWebhookUrl`, `suspend` (atomic bulk key revocation), `reactivate` (auto-generates new PROD key), `regenerateWebhookSecret` — all implemented and tested against real DB
- ApiKeyService guards: AKEY-02 duplicate-active prevention, AKEY-08 pre-rotate revocation of overlapping ROTATED keys; `saveAndFlush` ordering fix preventing constraint violations
- Flyway V20 + Hibernate Envers: `main.revinfo`, `main.tenant_aud`, `main.tenant_api_key_aud` tables; admin identity captured per revision via `SpringSecurityAuditorAware`
- 18 integration tests: TenantServiceIT (9), TenantAuditIT (3), TenantProvisioningIT (6) — all TENT/AKEY/WSEC/AUDIT requirements verified against live PostgreSQL
- AKEY-01 format fix (Phase 28.1): raw keys now `ACM_550e8400-e29b-41d4-a716-446655440000` (PREFIX_UUID) via `generateSecureKey()` rewrite; `ApiKeyBuilder` and `ApiKeyAuthenticationFilter` updated
- Quartz rotation cleanup job (Phase 29): `RotatedKeyCleanupJob` runs every 5 minutes, revokes ROTATED keys past 24-hour grace period; Flyway V21 migrates `rotated_at` to `TIMESTAMPTZ`; 4 integration tests green

**Stats:**

- 30 commits
- 4 phases, 6 plans
- 4 days (2026-04-03 → 2026-04-06)

**Known deferred work (v6):**

- HTTP REST endpoints for 6 TenantService operations (TENT-02/03/04/07/08, WSEC-03) — service layer complete, no HTTP surface yet
- Email notifications (NOTIF-01..06)
- Admin UI tenant management screens
- One-time key display modal (AKEY-07)
- WebhookSecret reveal UI (WSEC-02)

**Archive:** `.planning/milestones/v5-ROADMAP.md`

**What's next:** v6 — REST API surface, email notifications, Admin UI

---

## v4 Platform Config & Health (Shipped: 2026-04-02)

**Delivered:** Admin-facing platform operations layer — admins can update provider MSISDNs, receive email on change, and monitor live provider health (MSISDN validation + circuit breaker state) through a dedicated admin UI dashboard backed by Spring Boot Actuator.

**Phases completed:** 24–26 (5 plans total)

**Key accomplishments:**

- Platform MSISDN CRUD: Flyway V17 migration, PlatformConfig entity + service + REST API (GET + PUT) with event-driven email notification on every change
- Email notification on MSISDN change via PlatformConfigEmailListener + platformConfigChanged.html Thymeleaf template — AFTER_COMMIT delivery via MailManager
- Platform Config admin UI: Vue 3 Composition API page with per-provider save buttons and instant persistence feedback
- Two Spring Boot Actuator HealthIndicator beans — OrangePlatformHealthIndicator + MtnPlatformHealthIndicator — calling validateSubscriber() and including circuit breaker state on every `/manage/health` poll
- Health dashboard UI: admin-only component display via `show-details: when-authorized + ROLE_ADMIN`, access-denied banner for non-admins; live-verified showing mtnPlatform + orangePlatform components with CB state

**Stats:**

- 23 commits
- 3 phases, 5 plans
- 4 days (2026-03-30 → 2026-04-02)

**Git range:** `docs(24)` → `docs(26): verify health dashboard complete`

**Archive:** `.planning/milestones/v4-ROADMAP.md`

**What's next:** v5 — TBD

---

## v3 E2E Test Suite (Shipped: 2026-03-28)

**Delivered:** Machine-checked E2E test suite proving correctness of the Payam payment system — every critical invariant, race condition, and state machine transition covered, with ≥90% mutation testing across 6 critical domain classes. Built on Testcontainers (real PostgreSQL + Redis) + WireMock; zero mocking of the database.

**Phases completed:** 18–23 (18 plans total)

**Key accomplishments:**

- Abstract E2E base class hierarchy (template method pattern) with final orchestrator methods — structural contract prevents orchestration phase reordering across all 32 test classes
- 10 domain invariant verifiers + 8 test data builders — every invariant assertable in a single method call, all scenarios constructable with deterministic builders
- Full MTN/Orange E2E payment flows: happy path, polling fallback, payToken expiry, fraud-blocked (zero provider calls), idempotency (20-thread race), circuit breaker; 2 production JSONB quoting bugs discovered and fixed
- Inbound webhook double-check + Redis replay protection (both providers); outbound delivery with HMAC-SHA256 signing, exponential backoff, and ≥3 retry attempts verified
- Fraud velocity block (stops before provider call), daily reconciliation covering matched/missing/mismatched/WAT-offset entries, admin tenant-scoped transaction search
- 10 domain invariant E2E tests + 4 concurrency race tests + SM path matrix (all 32 illegal transitions) + PITest with mutationThreshold=90 killing all 6 critical mutations

**Stats:**

- 131 commits
- 6 phases, 18 plans
- 3 days (2026-03-26 → 2026-03-28)

**Git range:** `docs(18)` → `docs(23): complete phase 23`

**Archive:** `.planning/milestones/v3-ROADMAP.md`

**What's next:** v4 — Platform Config & Health

---

## v2 Logging Standardization (Shipped: 2026-03-27)

**Delivered:** Full-stack observability layer added to the payment API — every payment event is now traceable from Loki logs through Tempo traces to Prometheus metrics with structured kv() fields, zero PII, and no string interpolation anywhere in the codebase.

**Phases completed:** 14–17 (12 plans total)

**Key accomplishments:**

- JSON stdout logging pipeline via `LoggingEventCompositeJsonEncoder` with OTel traceId/spanId MDC flattening — every log line is valid Loki-parseable JSON
- Per-request MDC enrichment: requestId and tenantId on every HTTP request thread; transactionId and externalReference on every payment thread
- Structured request lifecycle events (request_start, request_end, request_error) with durationMs and operation enabling HTTP SLO queries in Loki
- Business event coverage: 7 event types — initiate_payment, transaction_state_change, webhook_received, webhook_delivery, fraud_evaluation, provider HTTP latency, reconciliation_run — all queryable by transactionId/tenantId/provider
- Full codebase LOG-CODE-01/02/03 enforcement: zero {} string interpolation, zero code-flow/decorative logs, BodySanitizer covers all payment fields (tokens, MSISDNs, passwords, merchant keys)

**Stats:**

- 95 files modified
- ~7,100 lines changed (+6,660 / -473)
- 4 phases, 12 plans
- 1 day (2026-03-26 → 2026-03-27)

**Git range:** `docs(14-logging-infrastructure)` → `docs(17): mark LOG-CODE-01/02/03 complete`

**Archive:** `.planning/milestones/v2-ROADMAP.md`

**What's next:** v3 — TBD

---

## v1 Payment API (Shipped: 2026-03-26)

**Delivered:** Unified, multi-tenant payment API for Cameroon wrapping MTN MoMo and Orange Money behind a single interface with full fraud protection, event-sourced audit trail, and daily reconciliation.

**Phases completed:** 1–13 (29 plans total)

**Key accomplishments:**

- Multi-tenant API key authentication with per-client isolation, rotation, and revocation
- Event-sourced transaction core with SHA-256 hash chain, Redis+PostgreSQL idempotency, and double-entry ledger
- Orange Money and MTN MoMo adapters behind `POST /v1/payments` with MSISDN prefix routing and Resilience4j circuit breakers
- Webhook pipeline with IP whitelist + HMAC verification, double-check (re-verify before state change), and outbound delivery with exponential backoff
- Fraud engine with Bucket4j velocity rules, DB-configurable risk scoring (0–100), and device fingerprinting — fires pre-dispatch
- Admin dashboard with SSE live metrics, transaction investigation UI, and Micrometer counters (Quasar SPA)
- Daily Quartz reconciliation against MTN/Orange provider reports with discrepancy flagging and CSV/JSON export
- Operational hardening: fee engine, real-time alert rules (FAILURE_RATE + FRAUD_SPIKE + CALLBACK_ANOMALY), TLS startup assertion, provider circuit breaker status endpoint

**Stats:**

- 315 files created/modified
- ~41,200 lines of code (Java + Vue)
- 13 phases, 29 plans
- 4 days (2026-03-23 → 2026-03-26)

**Git range:** `feat(01-01)` → `docs(phase-13)`

**Archive:** `.planning/milestones/v1-ROADMAP.md`

**What's next:** v2 — TBD

---
