# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-23)

**Core value:** Reliable, fraud-resistant payment processing with full traceability — no double charges, no blind trust of webhooks, no silent failures.
**Current focus:** Phase 1 — Multi-Tenant Foundation

## Current Position

Phase: 1 of 10 (Multi-Tenant Foundation)
Plan: 1 of 2 in phase (01-01 complete, 01-02 pending)
Status: In progress
Last activity: 2026-03-23 — Completed 01-01-PLAN.md (Tenant Foundation)

Progress: █░░░░░░░░░ ~6% (1 of ~17 plans)

## Performance Metrics

**Velocity:**
- Total plans completed: 1
- Average duration: 71 min
- Total execution time: 1.2 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 01-multi-tenant-foundation | 1/2 | 71 min | 71 min |

**Recent Trend:**
- Last 5 plans: 71 min
- Trend: Baseline established

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- Research confirmed: Spring Modulith replaces Kafka/RabbitMQ — durable events via PostgreSQL Event Publication Registry
- Research confirmed: Only 3 new Maven dependencies needed (spring-modulith-starter-jpa, spring-boot-starter-data-redis, spring-boot-starter-quartz)
- Research confirmed: Two Spring Security filter chains needed — `@Order(1)` API key chain for payment paths, `@Order(2)` existing JWT chain
- 01-01 decision: ApiKeyStatus is separate enum (ACTIVE/ROTATED/REVOKED) from EntityStatus — API key lifecycle is distinct
- 01-01 decision: tenant_status and key_status DDL columns used (not `status`) to avoid AbstractAuditingEntity.status clash
- 01-01 decision: JOIN FETCH k.tenant added to findValidKeyByHash — prevents LazyInitializationException in API key filter
- 01-01 decision: Flyway no-op FlywayMigrationStrategy removed from DataSourceConfig — V1 migration now runs on startup

### Pending Todos

- Run `mvn resources:resources resources:testResources` before `mvn surefire:test` when bypassing lifecycle (frontend plugin blocks full lifecycle)

### Blockers/Concerns

- Environment: `.mvn/wrapper/maven-wrapper.properties` missing — use system `mvn` not `./mvnw`
- Environment: Frontend plugin (`generate-resources` phase) broken due to missing quasar module — bypass with `mvn compiler:compile` or invoke goals directly
- Phase 3: Orange webhook HMAC header existence unconfirmed — verify with Orange partner before implementation
- Phase 4: MTN PUT callback confirmed in docs — verify in sandbox before relying on it
- Phase 9: Orange daily report format undocumented — requires partner verification before parser implementation

## Session Continuity

Last session: 2026-03-23T22:48:45Z
Stopped at: Completed 01-01-PLAN.md (Tenant Foundation — 3 tasks, 6 ITs green)
Resume file: None
