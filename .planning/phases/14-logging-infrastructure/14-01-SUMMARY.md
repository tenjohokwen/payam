---
phase: 14-logging-infrastructure
plan: 01
subsystem: infra
tags: [logback, logstash-logback-encoder, json-logging, otel, micrometer, loki, spring-boot]

# Dependency graph
requires:
  - phase: v1-complete
    provides: working Spring Boot application with micrometer-tracing-bridge-otel already in POM and sampling probability 1.0
provides:
  - LoggingEventCompositeJsonEncoder stdout-only JSON logging pipeline
  - Service identity fields (service, environment, version) in every log entry via springProperty + pattern provider
  - MDC flattening via <mdc/> so traceId/spanId appear as top-level JSON fields
  - logging.config activated in both application.yaml and application-dev.yaml
  - Loki4j network appender removed from POM and config
affects:
  - 14-logging-infrastructure (plans 02+)
  - 15-mdc-request-context
  - 16-business-events
  - 17-code-standards

# Tech tracking
tech-stack:
  added: []
  patterns:
    - LoggingEventCompositeJsonEncoder as canonical JSON log encoder (not PatternLayoutEncoder)
    - ConsoleAppender stdout-only transport (no file, no network)
    - MDC flattening via <mdc/> provider for OTel trace context propagation
    - springProperty sourcing Spring properties (not raw env vars) for reliable resolution across profiles

key-files:
  created: []
  modified:
    - src/main/resources/config/logback-spring.xml
    - src/main/resources/application.yaml
    - src/main/resources/application-dev.yaml
    - pom.xml

key-decisions:
  - "Use <springProperty source='app.environment'> (Spring property) not ${ENVIRONMENT} (raw env var) so the app: block in YAML serves as the resolver; env-var override still works via ${ENVIRONMENT:prod} interpolation in the YAML"
  - "MDC provider handles traceId/spanId automatically — micrometer-tracing-bridge-otel already populates MDC on every traced request; no Java code needed"
  - "Remove loki-logback-appender from POM entirely — no Java source imported Loki4j classes, safe to remove cleanly"
  - "Hard-coded root level=INFO in logback-spring.xml rather than springProperty — eliminates external property dependency for log level"

patterns-established:
  - "LoggingEventCompositeJsonEncoder: all JSON log output goes through this encoder, never PatternLayoutEncoder"
  - "stdout-only: ConsoleAppender is the sole appender; no file appender, no network appender"
  - "MDC flattening: <mdc/> provider writes all MDC keys as top-level JSON fields (enables traceId/spanId lookup in Loki)"
  - "Eight-provider standard: timestamp, logLevel, threadName, loggerName, message, mdc, arguments, stackTrace, pattern"
  - "app: YAML block as indirection layer: springProperty reads app.environment/app.version, YAML block resolves from env vars with per-profile defaults"

# Metrics
duration: 1min
completed: 2026-03-26
---

# Phase 14 Plan 01: Logging Infrastructure - JSON Stdout Pipeline Summary

**LoggingEventCompositeJsonEncoder stdout-only JSON pipeline replacing PatternLayoutEncoder+file+Loki4j, with traceId/spanId MDC flattening and service identity fields in every log entry**

## Performance

- **Duration:** 1 min
- **Started:** 2026-03-26T09:05:26Z
- **Completed:** 2026-03-26T09:06:56Z
- **Tasks:** 2
- **Files modified:** 4

## Accomplishments

- Replaced broken logback-spring.xml (PatternLayoutEncoder, file appender, Loki4j network appender) with a single ConsoleAppender using LoggingEventCompositeJsonEncoder and all eight standard providers
- Activated logging.config in both application.yaml (prod) and application-dev.yaml (dev) so Spring Boot loads the config on startup; added app.environment and app.version properties with env-var interpolation and per-profile fallback defaults
- Removed loki-logback-appender dependency from pom.xml; mvn compile -q passes cleanly

## Task Commits

Each task was committed atomically:

1. **Task 1: Replace logback-spring.xml with JSON stdout-only config** - `1afb416` (feat)
2. **Task 2: Activate logging config, add service identity props, remove Loki4j** - `294acde` (feat)

**Plan metadata:** (see docs commit below)

## Files Created/Modified

- `src/main/resources/config/logback-spring.xml` - Complete rewrite: ConsoleAppender + LoggingEventCompositeJsonEncoder with 8 providers; no file or network appender; springProperty for service identity
- `src/main/resources/application.yaml` - Uncommented logging.config; added app: block with environment (prod default) and version (env-var driven)
- `src/main/resources/application-dev.yaml` - Uncommented logging.config; added app: block with environment (dev default) and version (env-var driven)
- `pom.xml` - Removed loki-logback-appender dependency

## Decisions Made

- **springProperty indirection pattern:** `<springProperty source="app.environment">` reads a Spring property (not a raw env var). The `app:` block in each YAML file resolves that property from `${ENVIRONMENT:prod}` (or `${ENVIRONMENT:dev}`). This means: (1) env-var override works at runtime, (2) profile-appropriate defaults work without an env var, (3) Logback resolution is reliable even before the environment is fully bootstrapped.
- **Hard-coded root level=INFO:** Removed the `${logLevel}` springProperty that referenced `logging.level.root`. The old config would produce a null/empty level if that property was missing, causing unpredictable behavior. Hard-coded INFO is the correct production default; per-package overrides can still be added via YAML `logging.level.*`.
- **Loki4j removed from POM:** Zero Java source files imported Loki4j classes (confirmed by grep), so removing the dependency is safe and eliminates a transitive dependency that could conflict with logstash-logback-encoder.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required. Runtime env-var overrides (ENVIRONMENT, APP_VERSION) are optional; sensible defaults (prod/dev, unknown) are already set.

## Next Phase Readiness

- JSON logging pipeline is active; every log line emitted to stdout is valid JSON parseable by Loki/Alloy
- traceId and spanId will appear as top-level fields on any request traced by micrometer-tracing-bridge-otel (already configured with sampling.probability=1.0)
- service, environment, version identity fields are present in every entry
- Ready for Phase 14 Plan 02 (MDC request context enrichment) which will add requestId, userId, etc. to MDC — all will automatically appear as top-level JSON fields via the <mdc/> provider already in place

---
*Phase: 14-logging-infrastructure*
*Completed: 2026-03-26*
