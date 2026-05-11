---
phase: 63-payment-domain-consolidation
plan: 01
subsystem: payments
tags: [java, spring, package-refactoring, fee-engine]

requires:
  - phase: 62-platform-layer-reorganization
    provides: "platform.admin.repo.PlatformConfig and platform.security — callers this plan updates"
provides:
  - "payment.fee.api.FeeRuleAdminResource — fee admin REST resource under new package"
  - "payment.fee.contract.FeeType — FeeType enum under new package"
  - "payment.fee.repo.FeeRule — FeeRule JPA entity under new package"
  - "payment.fee.repo.FeeRuleRepository — FeeRule repository under new package"
  - "payment.fee.service.FeeEvaluationService — fee evaluation service under new package"
  - "payment.fee.service.FeeRuleCache — fee rule cache under new package"
  - "payment.fee.FeeEngineIT — fee engine integration test under new package"
affects: [64-provider-infrastructure-encapsulation, 65-common-package-redistribution]

tech-stack:
  added: []
  patterns:
    - "git mv for package relocations: preserves rename history at 93-98% similarity"
    - "Atomic package move: package declarations, within-package imports, and external caller imports all updated in a single commit"
    - "Javadoc FQN links updated alongside Java imports to keep documentation consistent with code"

key-files:
  created:
    - src/main/java/com/softropic/payam/payment/fee/api/FeeRuleAdminResource.java
    - src/main/java/com/softropic/payam/payment/fee/contract/FeeType.java
    - src/main/java/com/softropic/payam/payment/fee/repo/FeeRule.java
    - src/main/java/com/softropic/payam/payment/fee/repo/FeeRuleRepository.java
    - src/main/java/com/softropic/payam/payment/fee/service/FeeEvaluationService.java
    - src/main/java/com/softropic/payam/payment/fee/service/FeeRuleCache.java
    - src/test/java/com/softropic/payam/payment/fee/FeeEngineIT.java
  modified:
    - src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java
    - src/main/java/com/softropic/payam/platform/admin/repo/PlatformConfig.java
    - src/test/java/com/softropic/payam/payment/PaymentOrchestratorIT.java

key-decisions:
  - "PlatformConfig.java only had a Javadoc @link reference (not a Java import) to fee.repo.FeeRule — updated the Javadoc FQN link to payment.fee.repo.FeeRule to keep documentation accurate"
  - "PaymentOrchestratorIT.java had both an import (FeeRuleCache) and a FQN reference (@MockitoSpyBean FeeEvaluationService) — both updated to payment.fee.* paths"
  - "Pre-existing TenantServiceIT/RotatedKeyCleanupJobIT revinfo FK violations confirmed as pre-existing known tech debt (documented in PROJECT.md) unrelated to fee package move"

patterns-established:
  - "Wave-1 pattern: move smallest-footprint domain package first to establish payment.* namespace and validate approach"

requirements-completed: [PAY-04]

duration: ~40min
completed: 2026-05-11
---

# Phase 63 Plan 01: Fee Package Relocation Summary

**6 production + 1 test file relocated from `fee.*` to `payment.fee.*` with sub-package structure preserved; 3 external caller imports updated; zero stale references; Spring annotations preserved byte-for-byte; mvn verify green on all fee-related tests**

## Performance

- **Duration:** ~40 min
- **Started:** 2026-05-11T10:30:00Z
- **Completed:** 2026-05-11T11:13:08Z
- **Tasks:** 1
- **Files modified:** 10 (7 relocated + 3 callers updated)

## Accomplishments
- Relocated all 6 production fee files: `fee/{api,contract,repo,service}/` → `payment/fee/{api,contract,repo,service}/`
- Relocated 1 test file: `fee/FeeEngineIT.java` → `payment/fee/FeeEngineIT.java`
- Updated package declarations in all 7 moved files from `com.softropic.payam.fee.*` to `com.softropic.payam.payment.fee.*`
- Updated 3 external callers: `PaymentOrchestrator.java` (import), `PlatformConfig.java` (Javadoc FQN), `PaymentOrchestratorIT.java` (import + FQN spy reference)
- Deleted old empty `fee/` directories from `src/main/java` and `src/test/java`
- Zero stale `com.softropic.payam.fee.*` references remain in `src/` (grep returns clean)
- Spring `@Service`, `@Entity`, `@RestController`, `@Repository` annotations preserved byte-for-byte
- FeeEngineIT (4 tests) and PaymentOrchestratorIT pass — no regressions introduced

## Task Commits

1. **Task 1: Move fee package to payment.fee, update 3 callers, run mvn verify** - `fa927b9` (refactor)

## Files Created/Modified

- `src/main/java/com/softropic/payam/payment/fee/api/FeeRuleAdminResource.java` — moved + package updated
- `src/main/java/com/softropic/payam/payment/fee/contract/FeeType.java` — moved + package updated
- `src/main/java/com/softropic/payam/payment/fee/repo/FeeRule.java` — moved + package + Javadoc FQN updated
- `src/main/java/com/softropic/payam/payment/fee/repo/FeeRuleRepository.java` — moved + package + Javadoc FQN updated
- `src/main/java/com/softropic/payam/payment/fee/service/FeeEvaluationService.java` — moved + package updated
- `src/main/java/com/softropic/payam/payment/fee/service/FeeRuleCache.java` — moved + package updated
- `src/test/java/com/softropic/payam/payment/fee/FeeEngineIT.java` — moved + package updated
- `src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java` — import updated
- `src/main/java/com/softropic/payam/platform/admin/repo/PlatformConfig.java` — Javadoc FQN updated
- `src/test/java/com/softropic/payam/payment/PaymentOrchestratorIT.java` — import + FQN spy updated

## Decisions Made

- `PlatformConfig.java` listed as a "caller" in the plan had only a Javadoc `@link` reference (not a Java import) to `fee.repo.FeeRule`. Updated the Javadoc FQN from `com.softropic.payam.fee.repo.FeeRule` to `com.softropic.payam.payment.fee.repo.FeeRule` to keep documentation consistent with the relocated class. The `grep -rn 'com.softropic.payam.fee.'` catches Javadoc links as well as imports, so this was correct to update.
- `PaymentOrchestratorIT.java` uses `@MockitoSpyBean` with a fully-qualified class name (`com.softropic.payam.fee.service.FeeEvaluationService feeSpy`) in addition to the `import com.softropic.payam.fee.service.FeeRuleCache` import — both updated to `payment.fee.*` paths. If only the import had been updated, the FQN reference on line 95 would have caused a compile error.

## Deviations from Plan

None - plan executed exactly as written. The pre-existing `TenantServiceIT`/`RotatedKeyCleanupJobIT`/`TenantAuditIT` failures on `revinfo` FK constraint are documented in PROJECT.md as known tech debt ("TenantProvisioningIT.tearDown() does not clean audit tables") and were present before this plan's changes. All fee-related and payment-orchestrator tests pass.

## Issues Encountered

None - the fee package has minimal external footprint (3 callers as planned). The Wave-1 choice was validated: fee was the cleanest possible first move for establishing the `payment.*` namespace.

## Known Stubs

None - this is a pure package relocation with no data wiring or UI rendering.

## Next Phase Readiness

- `payment.fee.*` namespace established under `src/main/java/com/softropic/payam/payment/`
- `payment.service.*` already exists (PaymentOrchestrator) and now correctly imports from `payment.fee.*`
- Wave 2 (63-02, PAY-01 through PAY-03) can proceed: `payment.core`, `payment.ledger`, `payment.fraud` consolidation
- Spring component-scan picks up `payment.fee.*` automatically — `@ComponentScan` covers the full `com.softropic.payam` base package

---
*Phase: 63-payment-domain-consolidation*
*Completed: 2026-05-11*
