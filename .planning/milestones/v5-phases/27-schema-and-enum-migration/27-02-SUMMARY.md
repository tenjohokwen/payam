---
plan: 27-02
phase: 27-schema-and-enum-migration
status: complete
completed: 2026-04-03
duration: 30 min
tasks_completed: 2
files_modified: 18
requirements: [AKEY-01, AKEY-03]
depends_on: [27-01]

key-decisions:
  - "saveAndFlush before generateAndStore in ApiKeyService.rotate() to avoid uidx_tenant_api_key_active_env constraint violation from batched INSERT/UPDATE ordering"
  - "TenantProvisioningIT prefix assertion updated from rawKey.startsWith(keyPrefix) to direct equality check against name-derived prefix (ACM) — v5 keyPrefix is tenant-name derived, not rawKey-prefix"

key-files:
  modified:
    - src/test/java/com/softropic/payam/e2e/builder/TenantBuilder.java
    - src/test/java/com/softropic/payam/e2e/builder/ApiKeyBuilder.java
    - src/test/java/com/softropic/payam/tenant/TenantProvisioningIT.java
    - src/test/java/com/softropic/payam/tenant/TenantFilterChainIT.java
    - src/test/java/com/softropic/payam/tenant/TenantAdminResourceIT.java
    - src/test/java/com/softropic/payam/orange/OrangeMoneyPortIT.java
    - src/test/java/com/softropic/payam/mtn/MtnMoMoPortIT.java
    - src/test/java/com/softropic/payam/payment/PaymentOrchestratorIT.java
    - src/test/java/com/softropic/payam/fraud/FraudEngineIT.java
    - src/test/java/com/softropic/payam/fraud/FraudScoringServiceIT.java
    - src/test/java/com/softropic/payam/fee/FeeEngineIT.java
    - src/test/java/com/softropic/payam/transaction/TransactionStateMachineIT.java
    - src/test/java/com/softropic/payam/transaction/PaymentEventLogIT.java
    - src/test/java/com/softropic/payam/transaction/LedgerServiceIT.java
    - src/test/java/com/softropic/payam/transaction/IdempotencyServiceIT.java
    - src/test/java/com/softropic/payam/webhook/WebhookDeliveryIT.java
    - src/test/java/com/softropic/payam/webhook/WebhookDoubleCheckIT.java
    - src/main/java/com/softropic/payam/tenant/service/ApiKeyService.java

tech-stack:
  patterns:
    - "JpaRepository.saveAndFlush() to flush entity state before inserting a constrained sibling row in same transaction"
---

# Phase 27 Plan 02: LIVE-to-PROD Call Site Migration — Summary

## One-liner

Migrated all test call sites from String "LIVE" to ApiKeyEnvironment.PROD with saveAndFlush fix for rotate() constraint ordering.

## What Was Built

**Task 1 — Test builder updates:**
- `TenantBuilder.java`: Added `ApiKeyEnvironment` import, typed `environment` field as `ApiKeyEnvironment`, default changed to `ApiKeyEnvironment.PROD`, `withEnvironment()` signature updated to accept `ApiKeyEnvironment`
- `ApiKeyBuilder.java`: Default String `environment` changed from `"LIVE"` to `"PROD"`, Javadoc example updated

**Task 2 — Integration test call site migration:**
- 14 integration test files: all `createTenant(..., "LIVE")` calls replaced with `createTenant(..., ApiKeyEnvironment.PROD)` and `ApiKeyEnvironment` import added to each
- `TenantFilterChainIT.java`: `Map.of("environment", "LIVE")` HTTP body values replaced with `"PROD"` (5 occurrences)
- `FraudScoringServiceIT.java` and `ReconciliationJobIT.java`: used `"dev"` (not `"LIVE"`) — also migrated to `ApiKeyEnvironment.PROD` (caught during compilation, not in plan's file list)

**Production fix — `ApiKeyService.rotate()`:**
- Changed `keyRepository.save(old)` to `keyRepository.saveAndFlush(old)` so the ROTATED status UPDATE is flushed to PostgreSQL before the new ACTIVE key INSERT, preventing `uidx_tenant_api_key_active_env` constraint violation

**Test fix — `TenantProvisioningIT.createTenant_persistsEntities()`:**
- Updated prefix assertion from `rawKey.startsWith(key.getKeyPrefix())` to `key.getKeyPrefix().isEqualTo("ACM")` — v5 `keyPrefix` is tenant-name-derived (first 3 chars of name uppercased), not raw-key-derived

## Verification

- Zero `"LIVE"` occurrences in `src/` (confirmed via `grep -rn '"LIVE"' src/`)
- `mvn compile test-compile` exits 0
- `mvn test -Dspring.profiles.active=dev` exits 0 — 234 tests, 0 failures, 0 errors
- Flyway V18 and V19 applied cleanly in Testcontainers PostgreSQL for every test class

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] FraudScoringServiceIT and ReconciliationJobIT used "dev" string**
- **Found during:** Task 2 compilation (these files were not in the plan's file list)
- **Issue:** Both files called `createTenant(..., "dev")` — not `"LIVE"` so missed by initial grep, but still incompatible with `ApiKeyEnvironment` parameter type
- **Fix:** Added `ApiKeyEnvironment` import and replaced string arg with `ApiKeyEnvironment.PROD`
- **Files modified:** `FraudScoringServiceIT.java`, `ReconciliationJobIT.java`
- **Commit:** 66d52d8

**2. [Rule 1 - Bug] Python regex corruption in FraudScoringServiceIT and ReconciliationJobIT**
- **Found during:** Task 2 — regex `(import com\.softropic\.payam\.[a-z])` matched only the `c` character, corrupting the file
- **Fix:** Binary-level fix restoring `import com.softropic.payam.common.payment.MobilePaymentProvider;`
- **Files modified:** `FraudScoringServiceIT.java`, `ReconciliationJobIT.java`
- **Commit:** 66d52d8

**3. [Rule 1 - Bug] ApiKeyService.rotate() violated uidx_tenant_api_key_active_env constraint**
- **Found during:** Task 2 — TenantProvisioningIT.rotate_oldKeyValidDuringGrace and authenticate_rotatedKeyExpired_throws failed
- **Issue:** `save(old)` in a `@Transactional` method was batched by Hibernate — the UPDATE to ROTATED and the INSERT of new ACTIVE key flushed in wrong order, violating the partial unique index
- **Fix:** Changed `save(old)` to `saveAndFlush(old)` to force the UPDATE to flush before the INSERT
- **Files modified:** `ApiKeyService.java`
- **Commit:** 66d52d8

**4. [Rule 1 - Bug] TenantProvisioningIT.createTenant_persistsEntities prefix assertion wrong**
- **Found during:** Task 2 — test asserted `rawKey.startsWith(key.getKeyPrefix())` which was true in v1 (prefix = first 8 chars of raw key) but false in v5 (prefix = first 3 chars of tenant name uppercased)
- **Fix:** Updated assertion to `key.getKeyPrefix().isEqualTo("ACM")` matching the v5 specification
- **Files modified:** `TenantProvisioningIT.java`
- **Commit:** 66d52d8

## Known Stubs

None.

## Self-Check

Verified files created:
- `.planning/phases/27-schema-and-enum-migration/27-02-SUMMARY.md` — this file

Verified commits:
- 927caff (task 1)
- 66d52d8 (task 2)
