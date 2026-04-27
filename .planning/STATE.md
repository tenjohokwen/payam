---
gsd_state_version: 1.0
milestone: v1.0.2
milestone_name: milestone
status: executing
stopped_at: Completed 52-04-PLAN.md
last_updated: "2026-04-27T10:58:50.588Z"
last_activity: 2026-04-27
progress:
  total_phases: 24
  completed_phases: 13
  total_plans: 36
  completed_plans: 38
  percent: 0
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-04-24 — v10 roadmap created)

**Core value:** Reliable, fraud-resistant payment processing with full traceability — no double charges, no blind trust of webhooks, no silent failures.
**Current focus:** Phase 51 — orchestrator-public-api

## Current Position

Phase: 52
Plan: 03 complete — resume at Plan 04
Status: Executing Phase 52 — plan 03 complete
Last activity: 2026-04-27

Progress: [░░░░░░░░░░] 0% (0/4 phases complete)

## Performance Metrics

**Velocity:**

- Total plans completed: 106 (across v1–v9)
- v9 duration: 3 days (2026-04-21 → 2026-04-23)
- v9 files changed: 95 files, 9,412 insertions, 1,295 deletions

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.

**52-03 decisions:**

- Disbursement callback controllers do NOT inject `StringRedisTemplate` — dedup centralized in port layer (`callbacks:dsb:` Redis namespace), unlike `OrangeCallbackController` (collection) which deduplicates in the controller
- Javadoc on both controllers explains NOT @Transactional rationale inline — keeps non-obvious pitfall visible to future maintainers

**52-02 decisions:**

- `DisbursementCallbackTransitionService` is a separate bean (not inner handler logic): `@Transactional` self-invocation in `WebhookDoubleCheckHandler` bypasses Spring AOP proxy — same pattern as `WebhookTransitionService` for collection flow
- Wallet release inside `REQUIRES_NEW` (atomic with state transition): prevents half-committed state where row is FAILED but balance still held
- Conservative `resolveTarget` default (non-SUCCESS → FAILED): handler already returns early on `result.pending()`, so reaching `resolveTarget` with PROCESSING is defensive only
- `OrangeMoneyPort` gains `StringRedisTemplate` (was absent before this plan): safe addition — Spring autowires by type, no bean ambiguity
- `disbursementId` doubles as `traceId` in `WebhookReceivedEvent` for callback paths: no separate traceId propagated through to callbacks

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

### Pending Todos

None.

### Blockers/Concerns

- Phase 51: Read `OrangeMoneyClient.cashout()` HTTP path before writing any disbursement port code — if it calls `/cashout` (not `/ic2c/pay`), a new `ic2cTransfer()` method is needed
- Phase 53: Add second `@ConfigureWireMock` for `mtn.disbursement-base-url` to E2E base class before first disbursement test stub is written

## Session Continuity

Last session: 2026-04-27T10:58:50.580Z
Stopped at: Completed 52-04-PLAN.md
Resume: Execute 52-04-PLAN.md (outbound webhook delivery)
