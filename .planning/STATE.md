---
gsd_state_version: 1.0
milestone: v1.0.2
milestone_name: milestone
status: executing
stopped_at: Completed 62-02-PLAN.md (PLAT-03 — merge email + alert into platform.notification)
last_updated: "2026-05-07T00:13:59.920Z"
last_activity: 2026-05-07
progress:
  total_phases: 36
  completed_phases: 23
  total_plans: 69
  completed_plans: 65
  percent: 0
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-05-06 — v12 milestone started)

**Core value:** Reliable, fraud-resistant payment processing with full traceability — no double charges, no blind trust of webhooks, no silent failures.
**Current focus:** Phase 62 — platform-layer-reorganization

## Current Position

Phase: 62 (platform-layer-reorganization) — EXECUTING
Plan: 2 of 5
Status: Ready to execute
Last activity: 2026-05-06

Progress: [░░░░░░░░░░] 0%

## Performance Metrics

**Velocity:**

- Total plans completed: 106+ (across v1–v11)
- v11 duration: 7 days (2026-04-28 → 2026-05-05), 7 phases, 17 plans
- v12 estimate: pure refactoring — no Flyway, no schema changes, no new endpoints

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.

**Key carry-forward for v12:**

- Last Flyway migration: **V32** (merchant_wallet_balance drop — scaffolded Phase 57)
- No new Flyway migrations in v12 — package moves do not touch DDL
- Spring component-scan, Flyway config class, and security filter registration are the three highest-risk breakage points when packages move
- `FilterRegistrationBean(setEnabled=false)` pattern for `ApiKeyAuthenticationFilter` — must be preserved in `infrastructure.web` exactly as-is
- BUILD-01/02/03 are cross-cutting: `mvn verify` must pass green after every phase commit (no deferred red)
- `common` redistribution (Phase 65) is last because it has the most dependents; all destination packages must exist first
- Provider packages (PROV-01/02, Phase 64) depend on `payment.core` types — payment domain must move first (Phase 63)
- Platform layer (Phase 62) depends on infrastructure base classes — infrastructure must move first (Phase 61)
- [Phase 61]: @Component(AuditingDateTimeProvider.NAME) annotation preserved byte-for-byte — Spring @EnableJpaAuditing resolves dateTimeProvider by name convention; changing it would silently break JPA auditing
- [Phase 61]: Atomic single commit for 8 moved files + 42 caller updates — partial commit leaves codebase uncompilable; both tasks must ship together
- [Phase 61]: RotatedKeyCleanupSchedulerConfig stays in tenant.config (Quartz scheduler, not web infrastructure); moves with tenant package in Phase 62
- [Phase 61]: infrastructure.web sub-package consolidates all Spring servlet filter infrastructure (ApiKeyAuthenticationFilter, TenantSecurityConfig, LoggingFilter); FilterRegistrationBean(setEnabled=false) is the critical pattern that prevents ApiKeyFilter from auto-registering globally
- [Phase 62-01]: Package declaration changed only in platform.monitoring move — existing imports of platform.contract/service preserved for Plan 03/PLAT-05 atomic move
- [Phase 62-01]: Single atomic rename commit for health/ and ops/ → platform/monitoring/: git detects 97-99% similarity preserving full history

### v12 Phase Map

| Phase | Name | Requirements |
|-------|------|--------------|
| 61 | Infrastructure Layer Creation | INFRA-01, INFRA-02, INFRA-03 |
| 62 | Platform Layer Reorganization | PLAT-01, PLAT-02, PLAT-03, PLAT-04, PLAT-05 |
| 63 | Payment Domain Consolidation | PAY-01, PAY-02, PAY-03, PAY-04, PAY-05, PAY-06, PAY-07 |
| 64 | Provider Infrastructure Encapsulation | PROV-01, PROV-02 |
| 65 | Common Package Redistribution | CMN-01, CMN-02, CMN-03, CMN-04 |

BUILD-01, BUILD-02, BUILD-03 are cross-cutting and apply to every phase.
| Phase 61 P01 | 35 | 2 tasks | 50 files |
| Phase 61 P03 | 40 | 1 tasks | 7 files |
| Phase 62-platform-layer-reorganization P01 | 39 | 2 tasks | 4 files |
| Phase 62-platform-layer-reorganization P02 | 39 | 2 tasks | ~30 files |

**52-01 decisions:**

- Null-safe legacy fallback in `attemptDeliveryInternal`: pre-V30 rows fall back to `eventType.contains("SUCCESS")` — avoids breaking in-flight retries during zero-downtime deploy
- `OutboundWebhookPayload.of()` factory is additive — original record constructor preserved for existing test code
- V30 backfill UPDATE derives from `event_type LIKE '%SUCCESS%'` for collection-era rows — consistent history serialization

**51-02 decisions:**

- Block threshold strictly > 80 (score == 80 allows through per SEC-03 spec) — blocklist alone doesn't block; combined signal does
- Outlier signal skipped for tenants with <10 SUCCESS rows — fail-open for new tenants
- DisbursementIdempotencyService created in 51-02 (Rule-3 deviation) to unblock compilation; uses idempotency:dsb: namespace confirmed distinct from collection path
- Median computed from repository ORDER BY ASC — no in-service sorting needed

Key context carried forward for v10:

- Last Flyway migration: **V30** (transaction_status column on webhook_delivery_log + V29 poll_attempts on disbursement)
- `LedgerService.postEntry(txId, tenantId, LedgerPosting)` is the current API — 3-arg, switch-routed
- `OrangeMoneyClient.cashout()` calls `/cashout` (v9 path) — Phase 51 must verify whether this is `/ic2c/pay` or a different endpoint before wiring ic2cDisbursement
- `MtnMoMoPort.initiateDisbursement()` and `fetchDisbursementToken()` exist — wire via `disbursementTransfer()` wrapper
- No `@Transactional` on orchestrator methods that make HTTP calls — use `TransactionTemplate` (established pattern)
- Idempotency namespace for disbursements: `idempotency:dsb:<tenantId>:<key>` (distinct from collections)
- E2E base class (`AbstractPayamE2ETest`) needs a second WireMock server for `mtn.disbursement-base-url` before any disbursement E2E tests are written
- `WalletBalance` must use `@Lock(PESSIMISTIC_WRITE)` — optimistic retry allows second drain after first succeeds
- [Phase 50-schema-balance-infrastructure]: disbursement_status column name avoids AbstractAuditingEntity.status collision; reserved_amount on both disbursement + wallet tables for per-row precision + operational visibility
- [Phase 50-schema-balance-infrastructure]: PESSIMISTIC_WRITE lock over optimistic-only for WalletBalanceService: optimistic retry allows second drain after first succeeds — defeats BAL-01 invariant
- [Phase 50-schema-balance-infrastructure]: release() throws IllegalStateException on missing wallet (programmer bug contract) vs InsufficientBalanceException on missing wallet in checkAndReserve (tenant cannot disburse)
- [Phase 51]: DisbursementIdempotencyService shares IdempotencyKeyRepository with IdempotencyService; no schema split needed — Redis namespace isolation (idempotency:dsb: vs idempotency:) prevents key collisions
- [Phase 51-04]: findForTenant uses native SQL (not JPQL) to avoid PostgreSQL null enum type inference errors
- [Phase 51-04]: findExpiredCandidates uses NOW() - INTERVAL DB-side to avoid Hibernate 6 Instant->TIMESTAMPTZ vs TIMESTAMP column skew
- [Phase 52-04]: Standalone IT pattern (no AbstractPayamE2ETest): each IT configures own WireMock topology including mtn-disbursement server
- [Phase 52-04]: JDBC seeding over JPA save in callback ITs: silent JPA failures in transactional test contexts; direct jdbcTemplate.update() is deterministic
- [Phase 52-04]: walletRepo.findByTenantId() not findById(): BaseEntity id is TSID-generated Long, not the tenantId business key
- [Phase 53-e2e-test-suite]: Ledger entries written at provider initiation time (not on callback outcome) — assertNoLedgerEntries for FAILED disbursements is incorrect by design
- [Phase 53-e2e-test-suite]: OrangeMoneyPort.initiateDisbursement() production bug: was returning null providerRef; fixed to extract payToken from cashout response Map for callback correlation
- [Phase 53-e2e-test-suite]: DisbursementVelocityService per-MSISDN daily bucket (capacity=10): concurrency race test must use unique MSISDN per thread to avoid DAILY_LIMIT_EXCEEDED contaminating INSUFFICIENT_BALANCE count
- [Phase 53-e2e-test-suite]: DisbursementExpiryJob.executeInternal() cross-package test requires reflection (setAccessible=true) — do NOT make it public for test convenience
- [Phase 62-01]: Package declaration changed only in platform.monitoring move — existing imports of platform.contract/service preserved for Plan 03/PLAT-05 atomic move
- [Phase 62-01]: Single atomic rename commit for health/ and ops/ → platform/monitoring/: git detects 97-99% similarity preserving full history
- [Phase 62-02]: Sed import sweep does not catch FQN references outside import blocks or package declarations without trailing dot — must complement with mvn test-compile verification
- [Phase 62-02]: PLAT-03 complete: email/ and alert/ merged into platform.notification/ — AlertNotificationListener co-located with MailManager eliminates cross-package coupling

### Pending Todos

None.

### Blockers/Concerns

None — roadmap is defined, requirements are 100% mapped.

## Session Continuity

Last session: 2026-05-07T00:13:59.911Z
Stopped at: Completed 62-02-PLAN.md (PLAT-03 — merge email + alert into platform.notification)
Resume: `/gsd:execute-phase 62` — Wave 2 (62-03 admin consolidation)
