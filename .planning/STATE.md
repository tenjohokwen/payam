# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-26)

**Core value:** Full-stack observability — every payment event traceable from Loki logs through Tempo traces to Prometheus metrics without manual correlation.
**Current focus:** Phase 16 — Business Event Logging

## Current Position

Phase: 16 of 17 (v2: Business Event Logging)
Plan: 4 of 5 complete (16-01, 16-03, 16-04, 16-05 done; 16-02 remains)
Status: In progress
Last activity: 2026-03-27 — Completed 16-04-PLAN.md (LOG-BUS-06 MtnMoMoClient + OrangeMoneyClient provider HTTP call latency)

Progress: █████████████████████████ v1 complete | ████████░░ v2 80%

## Performance Metrics

**Velocity:**
- Total plans completed: 30 (29 v1 + 1 v2)
- Average duration: —
- Total execution time: —

**By Phase (v2):**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 14 (plan 01) | 1 | 1 min | 1 min |
| 16 (plan 01) | 1 | 6 min | 6 min |

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- v2 uses 4 phases: Infrastructure → MDC/Request → Business Events → Code Standards
- Phase 14 starts at 14 (continuous numbering from v1's 13 phases)
- **[14-01] springProperty indirection pattern:** `<springProperty source="app.environment">` reads a Spring property, not a raw env var. The `app:` YAML block resolves from `${ENVIRONMENT:prod}` (or `:dev`). Enables per-profile defaults AND runtime env-var override.
- **[14-01] Hard-coded root level=INFO in logback:** Eliminates null/empty level risk from missing `logging.level.root` property. Per-package overrides still possible via YAML `logging.level.*`.
- **[14-01] LoggingEventCompositeJsonEncoder as canonical encoder:** All JSON log output goes through this encoder. PatternLayoutEncoder must not be used for structured logging.
- **[14-01] MDC flattening via `<mdc/>` provider:** traceId/spanId injected by micrometer-tracing-bridge-otel appear as top-level JSON fields automatically — no Java code needed.
- **[15-01] tenantRef as MDC tenantId value:** `tenantRef` (String UUID) is used for the "tenantId" MDC key, not the Long database PK. Matches TenantContext and is the canonical Loki-queryable tenant identifier.
- **[15-01] MDC.remove() ownership split:** LoggingFilter owns requestId (set/remove in its own try/finally). ApiKeyAuthenticationFilter owns tenantId (set/remove in its own try/finally). Each filter cleans up exactly what it set.
- **[15-01] Conditional tenantId in request_end:** tenantId appended to args list only when TenantContext.get() != null — absent for JWT paths, always present for API-key paths. request_error intentionally omits it.
- **[15-02] Do not call MDC.put("traceId", ...) in application code:** micrometer-tracing-bridge-otel injects traceId automatically via `<mdc/>` provider for every active OTel span. Manual put was redundant and used the wrong snake_case key.
- **[15-02] TXN_ID_NAME canonical MDC key is "transactionId":** Updated from "txnId" (was never aligned with what TransactionService wrote). All TransactionIdProvider operations now use the correct key.
- **[15-02] MDC camelCase contract enforced:** All application-owned MDC fields use camelCase. OTel-owned fields (traceId, spanId) must not be manually overridden.
- **[16-01] tenantId log event field uses TenantContext.get() (UUID tenantRef):** Consistent with MDC decision 15-01. PaymentOrchestrator receives Long tenantId param but log event uses the UUID string — the Loki-queryable canonical tenant identifier.
- **[16-01] LOG-BUS-01 excluded paths:** Unknown MSISDN prefix path (no transactionId yet) and idempotency replay path (not a new initiation) are intentionally not logged under initiate_payment.
- **[16-01] Fraud allowed path upgraded DEBUG → INFO:** Every payment passes through fraud evaluation; DEBUG is invisible in production. INFO makes allow decisions traceable alongside block decisions.
- **[16-01] LOG-BUS-05 start timer before velocity checks:** Declared as first line of evaluate() so durationMs covers all 4 Redis velocity lookups, not just score computation.
- **[16-05] runForProviderAndDate() returns int[] {totalChecked, discrepancyCount}:** Minimal structural change to accumulate cross-provider totals in runForDate(); no changes to comparison logic.
- **[16-05] reconciliation_run status="SUCCESS" unconditional:** runForDate() always completes normally (provider exceptions caught inside try/catch); discrepancyCount communicates financial outcome, status reflects execution completion.
- **[16-03] Orange webhook_received log inside present-branch lambda:** txId is only available after transactionRepository.findByPayToken() resolves; log emitted there to include transactionId in structured event. Top-level log.info removed.
- **[16-03] MTN externalReference = financialTransactionId (nullable):** payload.getFinancialTransactionId() may be null at callback time — passed as-is, Loki omits null fields.
- **[16-03] deliveryStart timer before try block in WebhookDeliveryService:** Covers all 4 outcome paths so durationMs is always computable in any catch branch.
- **[16-03] httpStatus=-1 for network errors (generic Exception):** Sentinel signals no HTTP response received, consistent with delivery.setHttpStatus() only called on actual responses.
- **[16-04] LOG-BUS-06 co-exists with RestRequestInterceptor log:** Interceptor logs raw debug strings; kv() events provide structured Loki-queryable fields. Both intentional.
- **[16-04] externalLatencyMs scope is makeHttpRequest() only:** long start immediately before call, log immediately after call returns before any conditional throw.
- **[16-04] validateAccountHolder exception path omits log:** start inside try block; on HttpClientException (404), log.info() line is never reached — acceptable, exception is the signal.
- **[16-04] cashout/c2c direct-return pattern:** Assign makeHttpRequest() to local variable, log, then return — required to access response for status check before returning.

### Pending Todos

None.

### Blockers/Concerns

None.

## Session Continuity

Last session: 2026-03-27
Stopped at: Completed 16-04-PLAN.md — LOG-BUS-06 (MtnMoMoClient 7 methods + OrangeMoneyClient 7 methods provider HTTP call latency)
Resume file: None
