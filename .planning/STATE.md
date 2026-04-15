---
gsd_state_version: 1.0
milestone: v1.0.2
milestone_name: milestone
status: executing
stopped_at: Completed 38-03-PLAN.md — sign-off FAILED, VelocityCounterFloodTest regression
last_updated: "2026-04-15T04:29:37.407Z"
last_activity: 2026-04-15
progress:
  total_phases: 11
  completed_phases: 9
  total_plans: 24
  completed_plans: 24
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-04-14 — Milestone v7 started)

**Core value:** Reliable, fraud-resistant payment processing with full traceability — no double charges, no blind trust of webhooks, no silent failures.
**Current focus:** Phase 38 — transaction-boundary-fraud-ordering

## Current Position

Phase: 39
Plan: Not started
Status: Executing Phase 38
Last activity: 2026-04-15

```
Progress [░░░░░░░░░░░░░░░░░░░░] 0% — 0 of 6 phases complete
```

## Performance Metrics

**Velocity:**

- Total plans completed: 82 (across v1–v6, including phase 34)
- Average duration: —
- Total execution time: —

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.

Key context carried forward from v6:

- Service layer is complete — v6 is purely HTTP surface, notification wiring, and frontend
- All 6 TenantService operations are implemented and tested; no new domain logic required
- Hibernate Envers audit trail active on tenant + api_key tables
- ApiKeyService uses saveAndFlush ordering pattern for constraint-safe rotation
- Quartz RotatedKeyCleanupJob running every 5 minutes (AKEY-05)
- PREFIX_UUID key format in place (AKEY-01)

Key context from v6 research:

- TenantAdminResource already exists at /v1/admin/tenants — Phase 31 adds 8 new methods
- ApiKeyAuthenticationFilter already JOIN FETCHes tenant — TENT-09 needs one condition only
- webhook_secret stored plaintext (V8 migration) — correct for HMAC signing; no migration needed
- rawKey already returned in ApiKeyDto on create/rotate — AKEY-07 is frontend-only
- Email pattern: @EventListener on listener, Envelope -> MailManager @TransactionalEventListener(AFTER_COMMIT)
- @EnableMethodSecurity active — use method-level @PreAuthorize only (class-level breaks @ExceptionHandler)
- No Flyway migration needed — all columns exist in V21 schema
- Phase 32 and Phase 33 are independent after Phase 31 (can parallelize if needed)
- [Phase 30]: SUSPENDED check placed after authenticate() and before TenantContext.set(): ensures suspended tenants never populate SecurityContext
- [Phase 30]: response.sendError(SC_FORBIDDEN) body is Tomcat HTML page — test assertions check HTTP 403 status only, not body text
- [Phase 31-01]: TenantQueryService separate from TenantService — isolates readOnly transactions from mutation operations
- [Phase 31-01]: TenantDetailDto excludes webhookSecret — secret reveal is dedicated WSEC-03 endpoint
- [Phase 31-01]: status @RequestParam is String with manual TenantStatus.valueOf() — avoids Spring binding failure on absent optional enum param
- [Phase 31]: POST /webhook-secret returns 204 not the secret string — consistent with WSEC-03 dedicated GET endpoint for secret reveal
- [Phase 31]: IllegalStateException -> 409 Conflict in ApiAdvice prevents 500 on ApiKeyService double-reactivate scenario
- [Phase 31]: HttpComponentsClientHttpRequestFactory required for PATCH in integration tests; SimpleClientHttpRequestFactory does not support PATCH
- [Phase 32-01]: Plain-record domain events (no ApplicationEvent), security constraint at contract layer (no raw key/secret in events or templates), th:switch for conditional tenantStatusChanged rendering
- [Phase 32-02]: [32-02] No event publishing in generateAndStore() — callers publish semantically correct events at the business operation level to avoid double-event on rotation
- [Phase 32-02]: [32-02] updateEmail captures oldEmail before setter — ensures EMAIL_CHANGED event carries pre-mutation address for correct D-03 routing to old address
- [Phase 32]: reactivate() mirrors revoke() structure; AKEY-02 guard prevents dual active keys; 204 No Content response (no new raw key)
- [Phase 33-01]: generateKey endpoint injects TenantRepository for entity resolution from tenantRef; 409 guard lives in ApiKeyService.generateAndStore (IllegalStateException) mapped by ApiAdvice
- [Phase 33-02]: OneTimeKeyModal resets copied state via watch on modelValue to prevent stale checkbox state on reopen
- [Phase 33-02]: generateKey passes env as query param via { params: { env } } not request body
- [Phase 33]: onRequest passes p.page - 1 to API (Spring 0-indexed vs Quasar 1-indexed correction)
- [Phase 33]: statusFilter 'ALL' maps to undefined API param to avoid sending literal 'ALL' string to backend
- [Phase 33]: axios interceptor returns response.data directly — resp.data.X accesses corrected to resp.X in TenantDetailPage
- [Phase 33]: clearTimers() in onUnmounted prevents countdown interval leak; rawKey.value = null on modal close (D-11)
- [Phase 34]: description field appended last in PaymentRequest/PaymentCommand records for backward-compatible evolution
- [Phase 34]: PlatformConfigService.findByProvider uses IllegalStateException consistent with existing error contract
- [Phase 34]: callbackHmacSecret removed from OrangeMoneyConfig — HMAC verification was speculative; Orange does not confirm this header in v1.0.2; callbackUrl added instead
- [Phase 34]: OrangePathMatrixTest also updated (not in plan scope) — had /infos/merchant stub that would cause silent test failures after adapter rewrite
- [Phase 35]: Conflict target uses column-list form (tenant_id, idempotency_key) not ON CONFLICT ON CONSTRAINT — consistent with reserve() and avoids constraint-name coupling
- [Phase 35]: Postgres-first write ordering in IdempotencyService.store(): repo.upsert() before redis.set(); Redis in isolated try/catch (IDEM-01)
- [Phase 36]: Surefire Docker-context errors in SecurityFilterChainIT and TenantAdminResourceIT are pre-existing, not Phase 36 regressions — same classes pass in failsafe runner; Maven exit 0 confirms build success
- [Phase 37-webhook-subsystem-fixes]: CONNECT_TIMEOUT_MS=5000ms READ_TIMEOUT_MS=10000ms on SimpleClientHttpRequestFactory in WebhookConfig.noRetryRestTemplate (WEBHOOK-03)
- [Phase 37-webhook-subsystem-fixes]: WebhookConfigTest uses reflection on private fields (no public getters on SimpleClientHttpRequestFactory) to pin timeout values
- [Phase 37-webhook-subsystem-fixes]: Retain single-arg attemptDelivery overload alongside new two-arg variant — enqueue() path still uses single-arg; no callers broken
- [Phase 37-webhook-subsystem-fixes]: [37-02] WebhookDeliveryService hosts onEnqueueRequested listener — delivery logic co-located; webhookDeliveryService field kept in WebhookTransitionService to preserve collaborator documentation
- [Phase 38-transaction-boundary-fraud-ordering]: feeRuleIdVal extracted via .map(r -> r.getId()) — FeeRule not imported into PaymentOrchestrator; pre-lock cache reads hoisted above transactionTemplate block
- [Phase 38-transaction-boundary-fraud-ordering]: VelocityCounterFloodTest regression: probe/consume split in Plan 02 (OPS-02) is incompatible with CONC-03 invariant — non-consuming probe allows 100 concurrent requests to bypass IP_VELOCITY gate; Plan 02 must be revised

### Roadmap Evolution

- Phase 34 added: Orange Money flow improvements - align OrangeMoneyClient and OrangeMoneyPort with the Orange Money spec Use Case 1
- Phases 35–40 added: v7 Backend Hardening & Bug Fixes roadmap (2026-04-14)

### Pending Todos

None.

### Blockers/Concerns

- Phase 38 VelocityCounterFloodTest regression blocks sign-off

## Session Continuity

Last session: 2026-04-14T23:41:50.890Z
Stopped at: Completed 38-03-PLAN.md — sign-off FAILED, VelocityCounterFloodTest regression
Resume file: None
