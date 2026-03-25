---
phase: 10-operational-hardening
plan: 02
subsystem: alerts
tags: [micrometer, spring-events, alert-rules, postgres, jpa, jdbc, spring-boot-test]

# Dependency graph
requires:
  - phase: 08-admin-dashboard
    provides: PaymentMetricsService with payment.success.total/payment.failed.total/payment.fraud.blocked.total Micrometer counters
  - phase: 01-multi-tenant-foundation
    provides: AbstractAuditingEntity base class, SecurityConstants.HAS_ADMIN_ROLE, JWT filter chain
provides:
  - DB-configurable alert rules (V15 schema) with two seeded defaults (FAILURE_RATE 20%, FRAUD_SPIKE_RATE 5%)
  - AlertRuleCache: AtomicReference-backed in-memory cache hot-reloaded from DB on schedule
  - AlertEvaluationService: @Scheduled evaluator reading Micrometer counters, publishing AlertFiredEvent
  - AlertFiredEvent record as contract type between evaluator and listener
  - AlertNotificationListener: @EventListener handling AlertFiredEvent via LOG and EMAIL channels
  - AlertRuleAdminResource: POST/GET/PUT /v1/admin/alerts for runtime threshold tuning without restart
  - AlertRuleIT: 4/4 integration tests passing
affects:
  - 10-03-PLAN (future alert enhancements)
  - Any operational dashboard reading /v1/admin/alerts

# Tech tracking
tech-stack:
  added: []
  patterns:
    - AlertFiredEventCaptor @TestConfiguration pattern for capturing Spring events in integration tests (avoids @SpyBean on interface)
    - AlertRuleCache mirrors FraudRuleCache AtomicReference hot-reload pattern
    - MINIMUM_SAMPLE_SIZE guard (value=10) prevents false alert fires at startup

key-files:
  created:
    - src/main/resources/db/migration/V15__alert_rule_schema.sql
    - src/main/java/com/softropic/payam/alert/repo/AlertRule.java
    - src/main/java/com/softropic/payam/alert/repo/AlertRuleRepository.java
    - src/main/java/com/softropic/payam/alert/service/AlertRuleCache.java
    - src/main/java/com/softropic/payam/alert/contract/AlertFiredEvent.java
    - src/main/java/com/softropic/payam/alert/service/AlertEvaluationService.java
    - src/main/java/com/softropic/payam/alert/service/AlertNotificationListener.java
    - src/main/java/com/softropic/payam/alert/api/AlertRuleAdminResource.java
    - src/test/java/com/softropic/payam/alert/AlertRuleIT.java
  modified: []

key-decisions:
  - "AlertRule.metricName stored as plain String (not enum) — allows adding new metric names via DB without code change or restart"
  - "AlertFiredEventCaptor registered via @TestConfiguration inner class, not @Component — static inner @Component not picked up by SpringBootTest component scan"
  - "alertRuleCache.refresh() called explicitly in tests after JDBC insert — @Scheduled cache does not pick up test rows until next interval"
  - "V15 DDL excludes version column — AbstractAuditingEntity has no @Version field; dev profile create-drop creates table from entity, Flyway CREATE TABLE IF NOT EXISTS is no-op; including version causes JDBC insert failure"
  - "AlertNotificationListener.onAlertFired uses String.format for decimal formatting — SLF4J {} does not support printf format specifiers like {:.4f}"
  - "CALLBACK_ANOMALY metric returns -1.0 permanently — reserved metric name; computeMetricValue returns -1.0 for unknown/unimplemented metrics, skipped by evaluator"

patterns-established:
  - "AlertFiredEventCaptor pattern: register a test-only @EventListener bean via @TestConfiguration to capture application events without @SpyBean on interface"
  - "Pitfall 8 guard: MINIMUM_SAMPLE_SIZE=10 prevents alerts firing when counters are near startup baseline"

# Metrics
duration: 30min
completed: 2026-03-25
---

# Phase 10 Plan 02: Alert Rules Engine Summary

**DB-configurable alert rules engine — threshold rules (FAILURE_RATE, FRAUD_SPIKE_RATE) evaluated @Scheduled against Micrometer counters, firing AlertFiredEvents handled via LOG/EMAIL with MINIMUM_SAMPLE_SIZE=10 guard and runtime CRUD via /v1/admin/alerts**

## Performance

- **Duration:** 30 min
- **Started:** 2026-03-25T00:05:21Z
- **Completed:** 2026-03-25T00:35:35Z
- **Tasks:** 2 of 2
- **Files modified:** 9 created

## Accomplishments

- V15 alert_rule schema with two seeded default rules (FAILURE_RATE threshold=0.20, FRAUD_SPIKE_RATE threshold=0.05)
- AlertRuleCache with AtomicReference hot-reload — exact pattern from FraudRuleCache; no restart needed when DB rules change
- AlertEvaluationService reads Micrometer in-memory counters (no DB call), fires AlertFiredEvent on threshold breach with Pitfall 8 MINIMUM_SAMPLE_SIZE=10 guard
- AlertNotificationListener handles LOG and EMAIL channels; mail exceptions caught and logged, never rethrown
- AlertRuleAdminResource: POST/GET/PUT /v1/admin/alerts — full CRUD for operators to tune thresholds at runtime
- AlertRuleIT 4/4 pass: CRUD, threshold breach, below-threshold guard, CALLBACK_ANOMALY skip (Pitfall 8 verification)

## Task Commits

Each task was committed atomically:

1. **Task 1: Alert rule entity, migration, cache, evaluation service, notification listener** - `adb5d1b` (feat)
2. **Task 2: Alert admin API + AlertRuleIT integration test** - `e20a71b` (feat)

## Files Created/Modified

- `src/main/resources/db/migration/V15__alert_rule_schema.sql` - alert_rule table DDL + 2 seeded rules
- `src/main/java/com/softropic/payam/alert/repo/AlertRule.java` - entity extending AbstractAuditingEntity; metricName as String
- `src/main/java/com/softropic/payam/alert/repo/AlertRuleRepository.java` - findAllByEnabledTrue()
- `src/main/java/com/softropic/payam/alert/service/AlertRuleCache.java` - AtomicReference hot-reload cache
- `src/main/java/com/softropic/payam/alert/contract/AlertFiredEvent.java` - plain record (metricName, actualValue, threshold, channel)
- `src/main/java/com/softropic/payam/alert/service/AlertEvaluationService.java` - @Scheduled evaluator with MINIMUM_SAMPLE_SIZE guard
- `src/main/java/com/softropic/payam/alert/service/AlertNotificationListener.java` - @EventListener; LOG always + EMAIL try/catch
- `src/main/java/com/softropic/payam/alert/api/AlertRuleAdminResource.java` - POST/GET/PUT /v1/admin/alerts; @PreAuthorize HAS_ADMIN_ROLE
- `src/test/java/com/softropic/payam/alert/AlertRuleIT.java` - 4 tests; CaptorConfig @TestConfiguration pattern

## Decisions Made

- **metricName as String not enum:** Allows adding CALLBACK_ANOMALY and future metrics via DB insert without code change or restart
- **V15 DDL excludes version column:** AbstractAuditingEntity has no @Version; dev profile create-drop runs Hibernate DDL first, then Flyway CREATE TABLE IF NOT EXISTS is a no-op — version column in seed INSERT would fail PSQLException
- **AlertFiredEventCaptor via @TestConfiguration:** Static inner @Component not registered by SpringBootTest component scan; @TestConfiguration inner class + @Bean registration is the correct pattern
- **alertRuleCache.refresh() in tests:** @Scheduled cache doesn't reload between test method JDBC inserts; must call refresh() explicitly before evaluate()
- **SLF4J format fix:** Used String.format("%.4f") inside log.warn("{}", ...) — SLF4J {} is positional substitution, not printf format specifier
- **CALLBACK_ANOMALY reserved:** computeMetricValue returns -1.0 for unknown metric names; reserved name documents the contract for future implementation

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] @SpyBean ApplicationEventPublisher fails in Spring Boot 3.5+**
- **Found during:** Task 2 (AlertRuleIT)
- **Issue:** ApplicationEventPublisher is an interface; Spring Boot 3.5 cannot create a spy bean for an interface type — BeanInstantiationException: Specified class is an interface
- **Fix:** Replaced @SpyBean with AlertFiredEventCaptor class registered via @TestConfiguration inner class; @EventListener on captor captures all AlertFiredEvent publications
- **Files modified:** AlertRuleIT.java
- **Verification:** Context loads, all 4 tests pass
- **Committed in:** e20a71b (Task 2 commit)

**2. [Rule 1 - Bug] V15 DDL version column causes JDBC insert failure in dev profile**
- **Found during:** Task 2 (AlertRuleIT tests 2, 3, 4)
- **Issue:** dev profile uses hibernate.ddl-auto=create-drop which creates alert_rule table from entity definition (no version column). Flyway CREATE TABLE IF NOT EXISTS is then a no-op. Test JDBC inserts explicitly named version column → PSQLException: column does not exist
- **Fix:** Removed version column from V15 CREATE TABLE DDL and all INSERT statements, consistent with V10 fraud_rule pattern
- **Files modified:** V15__alert_rule_schema.sql, AlertRuleIT.java
- **Verification:** 4/4 tests pass
- **Committed in:** e20a71b (Task 2 commit)

**3. [Rule 1 - Bug] SLF4J log format used {:.4f} printf syntax**
- **Found during:** Task 2 (visible in test log output)
- **Issue:** AlertNotificationListener used log.warn("...actual={:.4f}...") — SLF4J uses {} for positional substitution, not Java printf format specifiers; {:.4f} printed literally
- **Fix:** Changed to String.format("%.4f", value) passed as argument to log.warn("{}", ...)
- **Files modified:** AlertNotificationListener.java
- **Verification:** Compiler clean; WARN log shows formatted float values
- **Committed in:** e20a71b (Task 2 commit)

---

**Total deviations:** 3 auto-fixed (all Rule 1 bugs)
**Impact on plan:** All auto-fixes necessary for correctness. No scope creep. Core plan delivered as specified.

## Issues Encountered

- Frontend maven plugin blocks standard `mvn test` lifecycle — bypassed using `mvn resources:resources resources:testResources compiler:compile compiler:testCompile surefire:test` (documented in STATE.md pending todos)

## Next Phase Readiness

- OPS-02 satisfied: alert rules are threshold-configurable via admin API without restart
- Alert evaluation is purely in-memory (no DB call per evaluation cycle) — safe for high-frequency scheduling
- CALLBACK_ANOMALY metric is reserved but unimplemented — add implementation in future plan when provider callback tracking is available
- Phase 10 plan 03 can proceed (MSISDN prefix routing, fee engine from plan 10-01 first)

---
*Phase: 10-operational-hardening*
*Completed: 2026-03-25*
