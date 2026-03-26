# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-26)

**Core value:** Full-stack observability — every payment event traceable from Loki logs through Tempo traces to Prometheus metrics without manual correlation.
**Current focus:** Phase 15 — MDC & Request Lifecycle

## Current Position

Phase: 15 of 17 (v2: MDC & Request Lifecycle)
Plan: 1 of ? in phase
Status: In progress
Last activity: 2026-03-26 — Completed 15-01-PLAN.md (request_start/request_end/request_error, requestId + tenantId MDC)

Progress: █████████████████████████ v1 complete | ███░░░░░░░ v2 30%

## Performance Metrics

**Velocity:**
- Total plans completed: 30 (29 v1 + 1 v2)
- Average duration: —
- Total execution time: —

**By Phase (v2):**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 14 (plan 01) | 1 | 1 min | 1 min |

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

### Pending Todos

None.

### Blockers/Concerns

None.

## Session Continuity

Last session: 2026-03-26T23:11:40Z
Stopped at: Completed 15-01-PLAN.md — structured request lifecycle events active, requestId + tenantId in MDC
Resume file: None
