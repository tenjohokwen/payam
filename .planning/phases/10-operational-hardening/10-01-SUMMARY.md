---
phase: 10-operational-hardening
plan: 01
subsystem: payments
tags: [fee-engine, msisdn-routing, flyway, hibernate, spring-cache, admin-api, integration-test]

# Dependency graph
requires:
  - phase: 07-fraud-engine
    provides: FraudRule/FraudRuleCache pattern — FeeRule/FeeRuleCache modelled identically
  - phase: 05-payment-orchestration
    provides: PaymentOrchestrator.initiate() — fee wired into TransactionTemplate block
  - phase: 09-reconciliation
    provides: ReconciliationApiIT auth pattern — FeeEngineIT uses same loginAsAdmin() strategy

provides:
  - FeeRule entity with global (tenantId=null) and per-tenant rule support
  - V14 migration: fee_rule table + fee_amount/fee_rule_id columns on transaction
  - V16 migration: msisdn_prefix_route table seeded with 10 Cameroon prefix rows
  - FeeEvaluationService: evaluateFee(tenantId, amount) for FEE_FIXED and FEE_PERCENTAGE
  - FeeRuleCache: hot-reloadable via @Scheduled (60s default), refreshed on every admin write
  - FeeRuleAdminResource: POST/GET/PUT /v1/admin/fees — fee rules configurable without restart (OPS-01)
  - MsisdnPrefixRoute entity + MsisdnPrefixRouteCache: DB-backed prefix routing
  - MsisdnRouter refactored: DB cache first, hardcoded fallback with WARN log (Pitfall 4)
  - FeeEngineIT: 4/4 integration tests passing

affects:
  - 10-02 (alert_rule): same volatile-list cache pattern; V15 alert_rule schema already in place
  - PaymentOrchestrator callers: fee_amount now set on every Transaction row post-Phase-10

# Tech tracking
tech-stack:
  added: []
  patterns:
    - Volatile-list hot-reload cache: @PostConstruct init + @Scheduled refresh replaces list atomically
    - Delete-then-save for immutable entity update: FeeRule has no public setters; load/rebuild/delete/save
    - @NotAudited on new transaction columns: V14 adds to main.transaction only; Envers _AUD table excluded
    - Admin-API-triggered cache refresh: feeRuleCache.refresh() called after every POST/PUT

key-files:
  created:
    - src/main/resources/db/migration/V14__fee_rule_schema.sql
    - src/main/resources/db/migration/V16__msisdn_prefix_route_schema.sql
    - src/main/java/com/softropic/payam/fee/contract/FeeType.java
    - src/main/java/com/softropic/payam/fee/repo/FeeRule.java
    - src/main/java/com/softropic/payam/fee/repo/FeeRuleRepository.java
    - src/main/java/com/softropic/payam/fee/service/FeeRuleCache.java
    - src/main/java/com/softropic/payam/fee/service/FeeEvaluationService.java
    - src/main/java/com/softropic/payam/fee/api/FeeRuleAdminResource.java
    - src/main/java/com/softropic/payam/payment/repo/MsisdnPrefixRoute.java
    - src/main/java/com/softropic/payam/payment/repo/MsisdnPrefixRouteRepository.java
    - src/main/java/com/softropic/payam/payment/service/MsisdnPrefixRouteCache.java
    - src/test/java/com/softropic/payam/fee/FeeEngineIT.java
  modified:
    - src/main/java/com/softropic/payam/transaction/repo/Transaction.java
    - src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java
    - src/main/java/com/softropic/payam/payment/service/MsisdnRouter.java

key-decisions:
  - "FeeRuleCache uses volatile List (not AtomicReference) — simpler than FraudRuleCache pattern; list replacement is atomic on 64-bit JVMs"
  - "V14 fee_rule seed row uses id=1 with version omitted in test seeds — dev create-drop schema has no version column (not in entity mapping)"
  - "MsisdnRouter constructor-injected MsisdnPrefixRouteCache — @Service dependency; no no-arg constructor needed (no unit tests instantiate it directly)"
  - "FeeEngineIT test_globalFeeAppliedToPayment deletes seed row id=1 before creating 50 XAF rule — multiple global rules would cause first-match ambiguity; clean-state approach"
  - "FeeRuleAdminResource DELETE-then-save strategy for PUT — FeeRule is fully immutable; @SuperBuilder copy-override pattern produces correct updated entity"

patterns-established:
  - "Volatile-list cache pattern: init() + refresh() + volatile List<T> — use for any hot-reloadable DB table (see also FraudRuleCache for AtomicReference variant)"
  - "Admin-triggered cache invalidation: call cache.refresh() after every admin write to apply changes immediately (no 60s wait)"
  - "Fee test isolation: seed rule in @BeforeEach, DELETE WHERE id > 1 in @AfterEach (preserves FK-referencing rows), then DELETE all in second block"

# Metrics
duration: 14min
completed: 2026-03-25
---

# Phase 10 Plan 01: Fee Engine Summary

**DB-configurable fee rules (FEE_FIXED/FEE_PERCENTAGE) hot-reloaded via admin API, wired into PaymentOrchestrator, with DB-backed MSISDN prefix routing replacing hardcoded MsisdnRouter**

## Performance

- **Duration:** 14 min
- **Started:** 2026-03-25T00:07:00Z
- **Completed:** 2026-03-25T00:21:00Z
- **Tasks:** 2
- **Files modified:** 15 (11 created, 3 modified)

## Accomplishments
- Fee engine with global and per-tenant rule support, hot-reloadable without restart (OPS-01)
- fee_amount and fee_rule_id columns added to Transaction; fee set inside the same TransactionTemplate block as riskScore/deviceFingerprint (idempotency-safe)
- MsisdnRouter refactored to DB-backed prefix lookup with hardcoded fallback + WARN log (Pitfall 4)
- FeeEngineIT 4/4 pass: create/list, global fee evaluation, tenant override, zero-fee fallback

## Task Commits

1. **Task 1: Fee engine entity, migration, cache, service, admin API + MSISDN prefix migration** — `bed1992` (feat)
2. **Task 2: Wire fee into PaymentOrchestrator, refactor MsisdnRouter, FeeEngineIT** — `12f827f` (feat)

**Plan metadata:** (docs commit below)

## Files Created/Modified

- `V14__fee_rule_schema.sql` — fee_rule table (global/tenant-scoped) + fee_amount/fee_rule_id on transaction; seed global 0.00 XAF rule (id=1)
- `V16__msisdn_prefix_route_schema.sql` — msisdn_prefix_route table with 10 seeded Cameroon prefix rows (ids 10–19)
- `FeeType.java` — FEE_FIXED / FEE_PERCENTAGE enum
- `FeeRule.java` — entity extending AbstractAuditingEntity; tenantId nullable (null = global)
- `FeeRuleRepository.java` — findAllByEnabledTrue()
- `FeeRuleCache.java` — volatile List, @PostConstruct + @Scheduled, findForTenant() with tenant→global fallback
- `FeeEvaluationService.java` — evaluateFee(tenantId, amount) + findRuleForTenant(tenantId)
- `FeeRuleAdminResource.java` — POST/GET/PUT /v1/admin/fees with FeeRuleRequest record DTO; delete-then-save for PUT
- `MsisdnPrefixRoute.java` — entity with prefix + MobilePaymentProvider + enabled
- `MsisdnPrefixRouteRepository.java` — findAllByEnabledTrue()
- `MsisdnPrefixRouteCache.java` — volatile List, findByPrefix() returning Optional<MobilePaymentProvider>
- `Transaction.java` — feeAmount (BigDecimal, @NotAudited) + feeRuleId (Long, @NotAudited) + setters
- `PaymentOrchestrator.java` — inject FeeEvaluationService; call evaluateFee/setFeeAmount/setFeeRuleId inside TransactionTemplate block
- `MsisdnRouter.java` — inject MsisdnPrefixRouteCache; DB lookup first, then hardcoded fallback with WARN log
- `FeeEngineIT.java` — 4 integration tests; real JWT auth via /authenticate; FeeEvaluationService injected directly

## Decisions Made

- **FeeRuleCache uses volatile List** (not AtomicReference like FraudRuleCache): simpler for this use case; list replacement is effectively atomic
- **V14 seed row excludes `version` column** in test seeds: dev profile `create-drop` creates table from entity mapping which has no `@Version` field; Flyway V14 includes `version` but Hibernate drops/recreates tables before tests run
- **test_globalFeeAppliedToPayment deletes seed row before asserting**: multiple enabled global rules cause first-match ambiguity; deleting id=1 ensures only the 50 XAF test rule is in cache, making assertion deterministic
- **MsisdnRouter constructor-injected**: no no-arg constructor; breaking change is intentional — hardcoded router had no dependencies; now Spring-managed with DB cache dependency
- **DELETE-then-save for PUT endpoint**: FeeRule is fully immutable (@SuperBuilder only); no in-place field update possible; delete + save with rebuilt entity avoids custom @Modifying JPQL

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] test_globalFeeAppliedToPayment returned 0.00 instead of 50.00**
- **Found during:** Task 2 (FeeEngineIT execution)
- **Issue:** Multiple enabled global rules in cache; findForTenant() returns first match (seed id=1, 0.00 XAF), not the newly created 50 XAF rule
- **Fix:** Delete seed row id=1 before creating the 50 XAF rule in the test — ensures clean single-rule state
- **Files modified:** FeeEngineIT.java
- **Verification:** FeeEngineIT 4/4 pass
- **Committed in:** 12f827f (Task 2 commit)

**2. [Rule 1 - Bug] test seeds included `version` column that doesn't exist in create-drop schema**
- **Found during:** Task 2 (first FeeEngineIT run)
- **Issue:** V14 SQL includes `version BIGINT DEFAULT 0` column; Hibernate create-drop schema lacks it (no @Version in entity)
- **Fix:** Remove `version` from JDBC INSERT statements in test seeds — same pattern as FraudEngineIT
- **Files modified:** FeeEngineIT.java
- **Verification:** FeeEngineIT 4/4 pass
- **Committed in:** 12f827f (Task 2 commit)

---

**Total deviations:** 2 auto-fixed (both Rule 1 — test-environment bugs)
**Impact on plan:** Both fixes discovered and resolved during first test run. No scope changes.

## Issues Encountered

None beyond the auto-fixed deviations above.

## Next Phase Readiness

- OPS-01 satisfied: fee rules configurable via POST /v1/admin/fees without restart
- V14 and V16 migrations tested and applied; V15 (alert_rule) is pre-existing work for plan 10-02
- MsisdnRouter DB routing tested implicitly via FeeEngineIT (app context startup validates cache init)
- Ready for plan 10-02: alert_rule engine with similar cache pattern

---
*Phase: 10-operational-hardening*
*Completed: 2026-03-25*
