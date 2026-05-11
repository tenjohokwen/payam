---
phase: 63-payment-domain-consolidation
plan: 03
subsystem: payments
tags: [java, spring, package-refactoring, fraud]

requires:
  - phase: 63-payment-domain-consolidation
    plan: 02
    provides: "payment.reconciliation.* established — payment.fraud follows same pattern"

provides:
  - "payment.fraud.contract.FraudSignal — fraud signal enum under new package"
  - "payment.fraud.contract.FraudDecision — fraud decision record under new package"
  - "payment.fraud.repo.FraudRule — fraud JPA entity under new package"
  - "payment.fraud.repo.FraudRuleRepository — fraud rule repository under new package"
  - "payment.fraud.service.FraudScoringService — fraud scoring service under new package"
  - "payment.fraud.service.FraudRuleCache — fraud rule cache component under new package"
  - "payment.fraud.service.VelocityCheckService — Redis-backed velocity check service under new package"

affects: [63-04-thru-07, 64-provider-infrastructure-encapsulation, 65-common-package-redistribution]

tech-stack:
  added: []
  patterns:
    - "git mv for package relocations: preserves rename history at 91-99% similarity"
    - "Two-pass sed for package declarations: explicit per-package-suffix patterns (no \b which macOS sed does not support)"
    - "Atomic package move: package declarations, within-package imports, and 23 external caller imports all updated in single commit"
    - "transaction.* imports inside fraud files preserved verbatim — PAY-02 (Plan 07) sweeps these later"

key-files:
  created:
    - src/main/java/com/softropic/payam/payment/fraud/contract/FraudSignal.java
    - src/main/java/com/softropic/payam/payment/fraud/contract/FraudDecision.java
    - src/main/java/com/softropic/payam/payment/fraud/repo/FraudRule.java
    - src/main/java/com/softropic/payam/payment/fraud/repo/FraudRuleRepository.java
    - src/main/java/com/softropic/payam/payment/fraud/service/FraudScoringService.java
    - src/main/java/com/softropic/payam/payment/fraud/service/FraudRuleCache.java
    - src/main/java/com/softropic/payam/payment/fraud/service/VelocityCheckService.java
    - src/test/java/com/softropic/payam/payment/fraud/FraudScoringServiceIT.java
    - src/test/java/com/softropic/payam/payment/fraud/FraudEngineIT.java
    - src/test/java/com/softropic/payam/payment/fraud/FraudVelocityOrderingIT.java
  modified:
    - src/main/java/com/softropic/payam/disbursement/service/DisbursementFraudEvaluationService.java
    - src/main/java/com/softropic/payam/disbursement/service/DisbursementOrchestrator.java
    - src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java
    - src/main/java/com/softropic/payam/payment/fee/repo/FeeRule.java
    - src/test/java/com/softropic/payam/disbursement/service/DisbursementFraudEvaluationServiceTest.java
    - src/test/java/com/softropic/payam/disbursement/service/DisbursementOrchestratorTest.java
    - src/test/java/com/softropic/payam/domain/FraudThresholdGuardTest.java
    - src/test/java/com/softropic/payam/e2e/domain/ApiKeyRotationGracePeriodTest.java
    - src/test/java/com/softropic/payam/e2e/domain/CallbackUrlSsrfGuardTest.java
    - src/test/java/com/softropic/payam/e2e/domain/ConcurrentIdempotencyRaceTest.java
    - src/test/java/com/softropic/payam/e2e/domain/FraudBeforeProviderCallTest.java
    - src/test/java/com/softropic/payam/e2e/domain/HashChainIntegrityTest.java
    - src/test/java/com/softropic/payam/e2e/domain/IdempotencyNoDoubleChargeTest.java
    - src/test/java/com/softropic/payam/e2e/domain/InitBeforeProviderCallTest.java
    - src/test/java/com/softropic/payam/e2e/domain/LedgerDoubleEntryTest.java
    - src/test/java/com/softropic/payam/e2e/domain/MtnPathMatrixTest.java
    - src/test/java/com/softropic/payam/e2e/domain/OrangePathMatrixTest.java
    - src/test/java/com/softropic/payam/e2e/domain/TenantIsolationTest.java
    - src/test/java/com/softropic/payam/e2e/domain/TransactionBoundaryTest.java
    - src/test/java/com/softropic/payam/e2e/domain/VelocityCounterFloodTest.java
    - src/test/java/com/softropic/payam/e2e/domain/WebhookPollingRaceTest.java
    - src/test/java/com/softropic/payam/e2e/fraud/FraudVelocityBlockE2ETest.java
    - src/test/java/com/softropic/payam/e2e/payment/FraudBlockedPaymentE2ETest.java
    - src/test/java/com/softropic/payam/e2e/payment/PaymentIdempotencyE2ETest.java

key-decisions:
  - "macOS sed does not support \\b (word boundary) — applied explicit per-suffix patterns: s|^package com.softropic.payam.fraud.contract;|...|, s|^package com.softropic.payam.fraud.repo;|...|, s|^package com.softropic.payam.fraud.service;|...|, and s|^package com.softropic.payam.fraud;|...| for test root package"
  - "FeeRule.java Javadoc @link updated from fraud.repo.FraudRule to payment.fraud.repo.FraudRule — not a Java import but kept consistent with relocated class (same decision as Phase 63-payment-domain-consolidation PlatformConfig Javadoc fix)"
  - "transaction.* imports in FraudScoringService preserved verbatim — PaymentCommand is in common.payment.*, which stays until Phase 65 common redistribution"
  - "Spring annotations (@Service, @Entity, @Component) preserved byte-for-byte — Spring component-scan picks up payment.fraud.* automatically via com.softropic.payam base package"
  - "Zero stale com.softropic.payam.fraud.* references remain — confirmed by grep returning empty output"

patterns-established:
  - "Wave-3 pattern: fraud moved after fee (Wave-1) and reconciliation (Wave-2) — same git mv + two-pass sed approach scales to smaller 7-file packages"
  - "External caller density: fraud has the most external callers (23 files) of any package moved so far due to E2E test infrastructure coupling — batched explicit file list sed avoids sweeping unrelated files"

requirements-completed: [PAY-06]

duration: ~8 minutes
completed: 2026-05-11
---

# Phase 63 Plan 03: Fraud Package Relocation Summary

**7 production + 3 test files relocated from `fraud.*` to `payment.fraud.*` with sub-package structure (contract/, repo/, service/) preserved; 3 external production callers (DisbursementFraudEvaluationService, DisbursementOrchestrator, PaymentOrchestrator) and 20 external test callers updated; FeeRule.java Javadoc @link updated; old fraud/ directories deleted; zero stale com.softropic.payam.fraud.* references remain; mvn test-compile exits 0; PAY-06 satisfied.**

## Performance

- **Duration:** ~8 minutes
- **Started:** 2026-05-11T13:24:07Z
- **Completed:** 2026-05-11T13:31:57Z
- **Tasks:** 1
- **Files modified:** 34 (7 prod + 3 test relocated; 3 prod + 20 test + 1 FeeRule.java external callers updated)

## Accomplishments

- Relocated all 7 production fraud files:
  - `contract/`: `FraudSignal.java`, `FraudDecision.java`
  - `repo/`: `FraudRule.java`, `FraudRuleRepository.java`
  - `service/`: `FraudScoringService.java`, `FraudRuleCache.java`, `VelocityCheckService.java`
- Relocated 3 test files to `payment.fraud/`:
  - `FraudScoringServiceIT.java`, `FraudEngineIT.java`, `FraudVelocityOrderingIT.java`
- Updated package declarations in all 10 moved files from `com.softropic.payam.fraud.*` to `com.softropic.payam.payment.fraud.*`
- Updated within-package imports in all moved files (FraudScoringService, FraudRuleCache, FraudRuleRepository, FraudRule)
- Updated 3 external production callers:
  - `DisbursementFraudEvaluationService.java` — `fraud.contract.FraudDecision` import updated
  - `DisbursementOrchestrator.java` — `fraud.contract.FraudDecision` import updated
  - `PaymentOrchestrator.java` — `fraud.contract.FraudDecision`, `fraud.service.FraudScoringService` imports updated
- Updated FeeRule.java Javadoc `@link com.softropic.payam.fraud.repo.FraudRule` to `payment.fraud.repo.FraudRule`
- Updated 20 external test callers (2 disbursement/service, 1 domain, 13 e2e/domain, 1 e2e/fraud, 2 e2e/payment)
- Deleted old `fraud/` directories from `src/main/java` and `src/test/java`
- Preserved Spring annotations (`@Service`, `@Entity`, `@Component`) and all bean names unchanged
- Preserved `common.payment.PaymentCommand` import in FraudScoringService verbatim (PAY-02 sweeps later)
- Git rename similarity: 91-99% across all 10 files — full history preserved

## Task Commits

1. **Task 1: Move fraud package to payment.fraud, update 3 prod + 20 test callers** — `daf422d` (refactor)

## Files Created/Modified

**7 production files relocated (fraud/ → payment/fraud/):**

| Sub-package | Files | Count |
|-------------|-------|-------|
| contract/ | FraudSignal.java, FraudDecision.java | 2 |
| repo/ | FraudRule.java, FraudRuleRepository.java | 2 |
| service/ | FraudScoringService.java, FraudRuleCache.java, VelocityCheckService.java | 3 |

**3 test files relocated (test/fraud/ → test/payment/fraud/):**
- `FraudScoringServiceIT.java`, `FraudEngineIT.java`, `FraudVelocityOrderingIT.java`

**3 external production callers updated:**
- `disbursement/service/DisbursementFraudEvaluationService.java`
- `disbursement/service/DisbursementOrchestrator.java`
- `payment/service/PaymentOrchestrator.java`

**1 Javadoc-only update:**
- `payment/fee/repo/FeeRule.java` — `@link` Javadoc reference updated; no Java import (FeeRule.java has no functional dependency on fraud package)

**20 external test callers updated:**
- `disbursement/service/DisbursementFraudEvaluationServiceTest.java`
- `disbursement/service/DisbursementOrchestratorTest.java`
- `domain/FraudThresholdGuardTest.java`
- `e2e/domain/ApiKeyRotationGracePeriodTest.java`
- `e2e/domain/CallbackUrlSsrfGuardTest.java`
- `e2e/domain/ConcurrentIdempotencyRaceTest.java`
- `e2e/domain/FraudBeforeProviderCallTest.java`
- `e2e/domain/HashChainIntegrityTest.java`
- `e2e/domain/IdempotencyNoDoubleChargeTest.java`
- `e2e/domain/InitBeforeProviderCallTest.java`
- `e2e/domain/LedgerDoubleEntryTest.java`
- `e2e/domain/MtnPathMatrixTest.java`
- `e2e/domain/OrangePathMatrixTest.java`
- `e2e/domain/TenantIsolationTest.java`
- `e2e/domain/TransactionBoundaryTest.java`
- `e2e/domain/VelocityCounterFloodTest.java`
- `e2e/domain/WebhookPollingRaceTest.java`
- `e2e/fraud/FraudVelocityBlockE2ETest.java`
- `e2e/payment/FraudBlockedPaymentE2ETest.java`
- `e2e/payment/PaymentIdempotencyE2ETest.java`

## Decisions Made

- **macOS sed `\b` word boundary not supported**: Applied explicit per-package-suffix sed patterns (`.fraud.contract;`, `.fraud.repo;`, `.fraud.service;`, `.fraud;`) instead of `\b`. Consistent with decision documented in 63-02-SUMMARY.md.
- **FeeRule.java Javadoc update**: The plan listed `FeeRule.java` as an external caller. In reality, FeeRule has no Java import of the fraud package — it only has a Javadoc `@link` reference to `fraud.repo.FraudRule`. The Javadoc link was updated to `payment.fraud.repo.FraudRule` to keep documentation consistent, matching the same approach taken for PlatformConfig Javadoc in Phase 63-01.
- **transaction.* imports preserved**: `FraudScoringService` imports `com.softropic.payam.common.payment.PaymentCommand` — this is in the `common` package which Phase 65 redistributes. Left unchanged per plan spec.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] macOS sed `\b` word boundary not supported**

- **Found during:** Task 1, Step C (Rewrite package declarations)
- **Issue:** The plan's sed command using `\b` to match word boundaries does not work on macOS BSD sed. The initial pass using `s|^package com\.softropic\.payam\.fraud\b|...|` left all 10 package declarations unchanged.
- **Fix:** Applied explicit per-suffix sed patterns in a second pass:
  - `s|^package com\.softropic\.payam\.fraud\.contract;|package com.softropic.payam.payment.fraud.contract;|`
  - `s|^package com\.softropic\.payam\.fraud\.repo;|package com.softropic.payam.payment.fraud.repo;|`
  - `s|^package com\.softropic\.payam\.fraud\.service;|package com.softropic.payam.payment.fraud.service;|`
  - `s|^package com\.softropic\.payam\.fraud;|package com.softropic.payam.payment.fraud;|` (for test root package)
- **Files modified:** All 10 moved files
- **Commit:** Included in `daf422d`

## Test Results

| Test | Status | Notes |
|------|--------|-------|
| `mvn test-compile` | PASS (exit 0) | All 10 moved files compile correctly with new package declarations |
| `DisbursementFraudEvaluationServiceTest` | PASS | Unit test — no Docker dependency; verifies updated imports compile and work |
| `DisbursementOrchestratorTest` | PASS | Unit test — no Docker dependency |
| `FraudThresholdGuardTest` | PASS | Unit test — no Docker dependency; verifies FraudDecision/FraudSignal imports |
| Testcontainers-based E2E/IT tests | N/A (Docker unavailable) | Docker daemon returning "Previous attempts to find a Docker environment failed" — pre-existing infrastructure issue identical to 63-02 baseline; all failures are Docker-only, none caused by fraud package move |

**Note on Docker failures:** Confirmed pre-existing: `MtnPutCallbackAcceptanceE2ETest` (no fraud dependency) fails with identical Docker error. Package move is correct — zero stale references, all package declarations updated, all caller imports updated, successful compilation.

## Known Stubs

None — this is a pure package relocation with no data wiring or UI rendering.

## Next Phase Readiness

- `payment.fraud.*` namespace fully populated under `src/main/java/com/softropic/payam/payment/`
- Old `fraud/` directories deleted from both `src/main/java` and `src/test/java`
- All 3 production callers point to `payment.fraud.*` imports
- All 20 external test callers point to `payment.fraud.*` imports
- Wave 4+ plans can proceed: `payment.core`, `payment.ledger`, and `payment.transaction` consolidation
- Spring component-scan picks up `payment.fraud.*` automatically — `@ComponentScan` covers the full `com.softropic.payam` base package
- `FraudRuleCache` `@Scheduled` refresh and `@PostConstruct` init survive the package move unchanged (bean name and method names unchanged)
- `VelocityCheckService` `@PostConstruct` init (Redis Lettuce ProxyManager) survives unchanged

## Self-Check: PASSED

- `find src/main/java/com/softropic/payam/payment/fraud -name '*.java' | wc -l` → 7 ✓
- `find src/test/java/com/softropic/payam/payment/fraud -name '*.java' | wc -l` → 3 ✓
- `test -d src/main/java/com/softropic/payam/fraud` → false ✓
- `test -d src/test/java/com/softropic/payam/fraud` → false ✓
- `head -1 src/main/java/com/softropic/payam/payment/fraud/contract/FraudSignal.java` → `package com.softropic.payam.payment.fraud.contract;` ✓
- `head -1 src/main/java/com/softropic/payam/payment/fraud/contract/FraudDecision.java` → `package com.softropic.payam.payment.fraud.contract;` ✓
- `head -1 src/main/java/com/softropic/payam/payment/fraud/service/FraudScoringService.java` → `package com.softropic.payam.payment.fraud.service;` ✓
- `head -1 src/test/java/com/softropic/payam/payment/fraud/FraudEngineIT.java` → `package com.softropic.payam.payment.fraud;` ✓
- `grep -rn stale refs outside /payment/fraud/` → 0 ✓
- `grep -c import payment.fraud. PaymentOrchestrator.java` → 2 ✓
- `grep -c import payment.fraud. DisbursementFraudEvaluationService.java` → 1 ✓
- `grep -c import payment.fraud. FraudThresholdGuardTest.java` → 6 ✓
- `grep @Service src/main/java/.../payment/fraud/service/FraudScoringService.java` → found ✓
- `grep @Entity src/main/java/.../payment/fraud/repo/FraudRule.java` → found ✓
- `git log --oneline | grep daf422d` → FOUND ✓

---
*Phase: 63-payment-domain-consolidation*
*Completed: 2026-05-11*
