# Phase 63: Payment Domain Consolidation - Research

**Researched:** 2026-05-07
**Domain:** Java package refactoring — Spring Boot 3.5.11, Maven, pure package rename
**Confidence:** HIGH

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| PAY-01 | `payment` package (collection orchestration, MSISDN routing) relocated to `payment.core` with all imports updated | 10 prod + 1 test files to move; 1 prod + 27 test external callers identified |
| PAY-02 | `transaction` package (ledger, idempotency, transaction repository) relocated to `payment.ledger` with all imports updated | 19 prod + 8 test files to move; 24 prod + 26 test external callers identified |
| PAY-03 | `disbursement` package (payout orchestration) relocated to `payment.disbursement` with all imports updated | 39 prod + 28 test files to move; 4 prod + 11 test external callers identified |
| PAY-04 | `fee` package (fee evaluation and rules) relocated to `payment.fee` with all imports updated | 6 prod + 1 test files to move; 1 prod + 1 test external callers identified |
| PAY-05 | `reconciliation` package (provider reconciliation) relocated to `payment.reconciliation` with all imports updated | 19 prod + 4 test files to move; 0 prod + 1 test external callers identified |
| PAY-06 | `fraud` package (fraud detection) relocated to `payment.fraud` with all imports updated | 7 prod + 3 test files to move; 3 prod + 20 test external callers identified |
| PAY-07 | `webhook` package (outbound delivery subsystem) relocated to `payment.webhook` with all imports updated | 13 prod + 8 test files to move; 3 prod + 5 test external callers identified |
</phase_requirements>

## Summary

Phase 63 consolidates seven flat packages (`payment`, `transaction`, `disbursement`, `fee`, `reconciliation`, `fraud`, `webhook`) into the `payment.*` umbrella namespace. No new logic is introduced — only package declarations and import statements change. The phase involves **113 production source files** and **53 test source files** across 7 source packages.

The mapping is:
- `payment` → `payment.core` (collection orchestration, MSISDN routing)
- `transaction` → `payment.ledger` (ledger service, idempotency, transaction repository)
- `disbursement` → `payment.disbursement` (payout orchestration, wallet balance)
- `fee` → `payment.fee` (fee evaluation and rules)
- `reconciliation` → `payment.reconciliation` (provider reconciliation jobs)
- `fraud` → `payment.fraud` (fraud detection, velocity checks)
- `webhook` → `payment.webhook` (outbound delivery subsystem)

There are **10 external production files** and **45 external test files** that stay in place but need their import statements updated. The largest external caller groups are: `mtn.service` (3 prod, 2 test), `orange.service` (3 prod, 2 test), `platform.admin` (3 prod), `e2e.*` test suite (20+ test files), and `domain.*` tests (5 test files). `transaction` is the most widely referenced package externally (24 prod, 26 test callers) because `TransactionStatus`, `Transaction`, `TransactionRepository`, `LedgerService`, and `EventLogService` are used across mtn, orange, platform.admin, webhook, reconciliation, and disbursement.

`@SpringBootApplication` on `PayamApplication` implicitly scans all sub-packages under `com.softropic.payam`, so adding `payment.*` sub-packages requires zero component-scan configuration changes.

**Primary recommendation:** Execute in seven discrete waves (one per PAY requirement), running `mvn verify` after each wave. Order from fewest external callers to most: PAY-04 and PAY-05 first (zero or one external caller), then PAY-06, then PAY-07, then PAY-01, then PAY-03, then PAY-02 last (most external callers — 24 prod, 26 test).

## Standard Stack

### Core (verified from source tree)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Boot | 3.5.11 | Application framework, component scan | Already in use |
| Maven Failsafe + Surefire | (via Spring Boot BOM) | `mvn verify` integration test execution | Already in use |
| Java 17 | 17 (LTS) | Compilation target | Already in use |

**Installation:** No new dependencies required. This is a pure package reorganization.

**Version verification:** All versions confirmed in `pom.xml` (Spring Boot 3.5.11, spring-cloud 2025.0.1, Java 17, Maven 3.9.9).

## Architecture Patterns

### Target Project Structure (after Phase 63)
```
src/main/java/com/softropic/payam
├── payment/                             # NEW: payment bounded context umbrella
│   ├── core/                            # was: payment/
│   │   ├── api/PaymentResource.java
│   │   ├── contract/
│   │   │   ├── OrchestratorError.java
│   │   │   ├── PaymentRequest.java
│   │   │   ├── PaymentResponse.java
│   │   │   └── exception/UnknownMsisdnPrefixException.java
│   │   ├── repo/
│   │   │   ├── MsisdnPrefixRoute.java
│   │   │   └── MsisdnPrefixRouteRepository.java
│   │   └── service/
│   │       ├── MsisdnPrefixRouteCache.java
│   │       ├── MsisdnRouter.java
│   │       └── PaymentOrchestrator.java
│   ├── ledger/                          # was: transaction/
│   │   ├── contract/
│   │   ├── repo/
│   │   └── service/
│   ├── disbursement/                    # was: disbursement/
│   │   ├── api/
│   │   ├── config/
│   │   ├── contract/
│   │   ├── repo/
│   │   └── service/
│   ├── fee/                             # was: fee/
│   │   ├── api/
│   │   ├── contract/
│   │   ├── repo/
│   │   └── service/
│   ├── reconciliation/                  # was: reconciliation/
│   │   ├── api/
│   │   ├── config/
│   │   ├── contract/
│   │   ├── port/
│   │   ├── repo/
│   │   └── service/
│   ├── fraud/                           # was: fraud/
│   │   ├── contract/
│   │   ├── repo/
│   │   └── service/
│   └── webhook/                         # was: webhook/
│       ├── api/
│       ├── config/
│       ├── contract/
│       ├── repo/
│       └── service/
├── platform/                            # COMPLETE (Phase 62)
├── infrastructure/                      # COMPLETE (Phase 61)
├── mtn/                                 # stays here until Phase 64
├── orange/                              # stays here until Phase 64
└── common/                              # stays here until Phase 65
```

### Pattern 1: Package Declaration + Import Sweep (sole mechanical operation)
**What:** Change `package` declaration in each moved file, then update all `import` statements in callers.
**When to use:** Every file move in this phase.

```java
// BEFORE
package com.softropic.payam.transaction.service;

// AFTER
package com.softropic.payam.payment.ledger.service;
```

Callers change correspondingly:
```java
// BEFORE (in mtn/service/MtnMoMoPort.java)
import com.softropic.payam.transaction.contract.TransactionStatus;
import com.softropic.payam.transaction.service.LedgerService;

// AFTER
import com.softropic.payam.payment.ledger.contract.TransactionStatus;
import com.softropic.payam.payment.ledger.service.LedgerService;
```

### Pattern 2: Intra-Domain Imports That Also Change Path
**What:** Several packages import from each other (e.g., `disbursement` imports from `payment` and `transaction`; `webhook` imports from `transaction`). After the move these are still cross-sub-package imports but the paths change. They must be updated in the same commit as the source package move.
**Why critical:** `disbursement.service.DisbursementOrchestrator` imports `payment.contract.exception.UnknownMsisdnPrefixException` and `payment.service.MsisdnRouter`. When `payment` moves to `payment.core`, those imports must become `payment.core.contract.exception.UnknownMsisdnPrefixException` and `payment.core.service.MsisdnRouter`.

Key intra-domain dependencies between packages moving in this phase:
```
disbursement → payment (UnknownMsisdnPrefixException, MsisdnRouter)
disbursement → transaction (CachedResponse, IdempotencyKeyRepository, TransactionStatus, LedgerFlow, Transaction, TransactionRepository)
payment → transaction (CachedResponse, TransactionEventType, TransactionStatus, Transaction, TransactionRepository, EventLogService, IdempotencyService, TransactionService)
webhook → transaction (LedgerFlow, TransactionStatus, LedgerPosting, TransactionEventType, Transaction, TransactionRepository, EventLogService, LedgerService)
webhook → disbursement (WebhookDoubleCheckHandler imports DisbursementCallbackTransitionService)
reconciliation → transaction (Transaction, TransactionRepository)
```

### Pattern 3: External Callers That Stay In Place
**What:** `mtn`, `orange`, `platform.admin`, `platform.notification`, and test packages (`e2e.*`, `domain.*`) are not moving in Phase 63 but import from the 7 packages that are. Their import statements must be updated in each wave's commit.

External production files requiring import updates (10 total):
```
mtn/service/MtnMoMoPort.java               → update transaction.*, disbursement.*, webhook.*
mtn/service/MtnStatusMapper.java            → update transaction.*
mtn/service/MtnStatusPollerJob.java         → update transaction.*
orange/service/OrangeMoneyPort.java         → update transaction.*, disbursement.*, webhook.*
orange/service/OrangeStatusMapper.java      → update transaction.*
orange/service/OrangeStatusPollerJob.java   → update transaction.*
platform/admin/api/AuditResource.java       → update transaction.*
platform/admin/service/AdminTransactionQueryService.java → update transaction.*
platform/admin/service/PaymentMetricsService.java → update transaction.*
platform/notification/infrastructure/listener/DisbursementOpsAlertEmailListener.java → update disbursement.*
```

External test files requiring import updates (45 total — stay where they are, only imports change):
- `domain/` tests (5 files) — import `transaction.*`, `fraud.*`
- `e2e/builder/PaymentRequestBuilder.java` — imports `payment.*`
- `e2e/disbursement/` E2E tests (7 files) — import `disbursement.*`
- `e2e/domain/` tests (15 files) — import `payment.*`, `fraud.*`
- `e2e/fraud/FraudVelocityBlockE2ETest.java` — imports `payment.*`, `fraud.*`
- `e2e/payment/` tests (8 files) — import `payment.*`, `fraud.*`
- `e2e/reconciliation/DailyReconciliationE2ETest.java` — imports `reconciliation.*`
- `e2e/webhook/OutboundWebhookDeliveryE2ETest.java` — imports `webhook.*`
- `mtn/MtnMoMoPortIT.java`, `mtn/service/MtnMoMoPortDisbursementCallbackTest.java` — import `transaction.*`, `disbursement.*`
- `orange/OrangeMoneyPortIT.java`, `orange/service/OrangeMoneyPortDisbursementCallbackTest.java` — import `transaction.*`, `disbursement.*`
- `platform/monitoring/OperationalIT.java` — imports `transaction.*`
- `platform/notification/.../DisbursementOpsAlertEmailListenerTest.java` — imports `disbursement.*`

### Pattern 4: ReconciliationModule Marker Class
**What:** `reconciliation/ReconciliationModule.java` is a documentation marker class at the top level of the `reconciliation` package. It has no imports from other internal packages. It moves to `payment/reconciliation/ReconciliationModule.java` and its package declaration changes to `package com.softropic.payam.payment.reconciliation;`. No other changes needed.

### Anti-Patterns to Avoid
- **Splitting a wave mid-package:** Moving half the files in `disbursement/` and committing breaks compilation. Each wave must move ALL files in the source package atomically (both prod and test).
- **Forgetting intra-domain imports when a wave moves one package:** When moving `payment` to `payment.core` in PAY-01, `disbursement.service.DisbursementOrchestrator` (moving later in PAY-03) still has `import com.softropic.payam.payment.*`. This import becomes stale as soon as PAY-01 commits. Either move disbursement in the same commit as payment, OR defer PAY-01 until disbursement is also ready — best to batch into one commit or execute PAY-01 and PAY-03 together.
- **Forgetting to update mtn/orange callers:** `MtnMoMoPort`, `MtnStatusMapper`, `MtnStatusPollerJob`, `OrangeMoneyPort`, `OrangeStatusMapper`, `OrangeStatusPollerJob` are NOT moving in this phase (they move in Phase 64), but they import from packages that ARE moving. Their imports must be updated in the same commit as the package that moves.
- **Forgetting test file package declarations:** Test files physically in `src/test/.../transaction/` have `package com.softropic.payam.transaction;` declarations. When moved to `src/test/.../payment/ledger/`, these declarations must also be updated.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Updating 300+ import lines across 100+ files | Manual text search-and-replace script | `sed -i` import sweep + `mvn compile -q` verification | Compiler is the authoritative import validator; `sed` handles bulk updates |
| Verifying no stale imports remain | Custom assertion script | `grep -rn "com.softropic.payam.OLD_PACKAGE\." src/` — zero results expected | grep is deterministic |
| Detecting Spring context failures | Manual HTTP test | Existing integration tests via `mvn verify` | Already cover full startup + wiring |

**Key insight:** This is a mechanical rename. The only verification tool that matters is `mvn verify`. Every wave commit must be green before proceeding.

## Runtime State Inventory

This is a pure Java package reorganization — package names are not stored at runtime.

| Category | Items Found | Action Required |
|----------|-------------|-----------------|
| Stored data | None — package names are not stored in the database schema, Flyway migrations, or any JPA column definitions | None |
| Live service config | None — package names are not referenced in `application.yml`, `application-test.yml`, or any Spring property files | None |
| OS-registered state | None — Quartz scheduler beans are resolved by bean name/type, not package path | None |
| Secrets/env vars | None — no env var names embed Java package paths | None |
| Build artifacts | `target/` directory contains `.class` files at old package paths | Run `mvn clean` before first wave |

## Common Pitfalls

### Pitfall 1: Intra-Wave Import Cycles (most dangerous pitfall in this phase)
**What goes wrong:** PAY-01 moves `payment` → `payment.core`. After PAY-01 commits, `disbursement.service.DisbursementOrchestrator` (not yet moved) imports `com.softropic.payam.payment.service.MsisdnRouter` — but that class now lives at `payment.core.service.MsisdnRouter`. The build is broken between PAY-01 and PAY-03.
**Why it happens:** Unlike Phase 62 where moved packages only had one-way outbound dependencies, here several packages import from each other. Moving one without the other creates a broken intermediate state.
**How to avoid:** Two safe options:
  1. **Bundle dependent packages into the same wave/commit.** The `payment` → `payment.core` move (PAY-01) and `disbursement` → `payment.disbursement` move (PAY-03) can be combined into one atomic commit since disbursement imports from payment.
  2. **Sequential waves with caller updates included.** When committing PAY-01, include the `DisbursementOrchestrator` import update in the same commit (even though disbursement itself moves later). This keeps each wave green even before the disbursement move.
**Warning signs:** Compile failure on files not in the current wave's package.

### Pitfall 2: 24 External `transaction.*` Callers That Stay In Place
**What goes wrong:** `mtn.service` (3 files), `orange.service` (3 files), `platform.admin` (3 files), `reconciliation.port/service` (2 files), `webhook` (multiple files), `disbursement` (multiple files) all import from `transaction.*`. When `transaction` moves to `payment.ledger`, ALL of these files must have their `transaction.*` imports updated in the same commit as the `transaction` move.
**Why it happens:** `transaction` is the most widely referenced package in the codebase. Forgetting even one external caller causes a compile failure.
**How to avoid:** After moving transaction files, run: `grep -rn "com.softropic.payam.transaction\." src/ --include="*.java"` — zero results expected. Then `mvn compile -q`.
**Warning signs:** Compile failure in `mtn.service.MtnMoMoPort`, `orange.service.OrangeMoneyPort`, `platform.admin.service.AdminTransactionQueryService`, or any of the 24 prod callers.

### Pitfall 3: Test File Package Declarations Must Match Physical Location
**What goes wrong:** `mvn verify` succeeds on production compile but fails on test compile because a test file physically at `src/test/java/com/softropic/payam/payment/ledger/TransactionStateMachineIT.java` still has `package com.softropic.payam.transaction;`.
**Why it happens:** IDEs may scope "Move" to `src/main/java` only.
**How to avoid:** Explicitly include `src/test/java` in each package move. Run `mvn test-compile -q` after each wave before running full `mvn verify`.
**Warning signs:** Maven Surefire reporting package mismatch or class-not-found for test classes.

### Pitfall 4: 45 External Test Files With Stale Imports After Each Wave
**What goes wrong:** The 45 external test files (e2e/*, domain/*, mtn/*, orange/*, platform/*) are NOT moving. They keep their existing physical location. But their import statements must be updated to the new package paths for each wave. Forgetting one causes test-compile failure.
**How to avoid:** For each wave, after moving source files, run a grep verification: `grep -rn "com.softropic.payam.OLD_PACKAGE\." src/test/ --include="*.java"`. Zero results expected. Then run `mvn test-compile -q`.
**Warning signs:** Test compile failures in `e2e.*` or `domain.*` test classes.

### Pitfall 5: `payment` Package Becomes Ambiguous During Move
**What goes wrong:** The `payment` source package is being renamed to `payment.core`. After the move, `payment/` in the filesystem holds only `core/` as a sub-directory. But other packages being moved in this same phase also land under `payment/` (e.g., `payment/ledger/`, `payment/disbursement/`). If the moves happen in separate git commits, intermediate states create a `payment` directory with mixed content — some `payment.*` classes still at old paths, others at new `payment.core.*` paths.
**How to avoid:** Ensure each wave's commit is complete. The `payment.core` move (PAY-01) creates the parent `payment/` directory as a side effect — subsequent waves (PAY-02 through PAY-07) add sibling sub-directories under it. This is fine as long as each wave completes atomically.
**Warning signs:** Git status showing incomplete moves; any `package com.softropic.payam.payment;` (without `.core`) declarations in files that were supposed to move.

### Pitfall 6: `DisbursementModule`/`ReconciliationModule` Marker Classes
**What goes wrong:** `ReconciliationModule.java` lives at the top level of the `reconciliation` package (not in a sub-package). It has `package com.softropic.payam.reconciliation;`. After the move it must become `package com.softropic.payam.payment.reconciliation;`. If it's forgotten it will either cause a compile error (wrong package declaration) or not appear in a `grep` scan for stale imports (because it has no imports to grep for).
**How to avoid:** Include `ReconciliationModule.java` in the PAY-05 file list explicitly.
**Warning signs:** File still present in old location post-move.

## Code Examples

### PAY-01 — Complete Scope of Changes

**Production files (10): change package declaration + physically move**
```
payment/api/PaymentResource.java                              → payment/core/api/PaymentResource.java
payment/contract/OrchestratorError.java                       → payment/core/contract/OrchestratorError.java
payment/contract/PaymentRequest.java                          → payment/core/contract/PaymentRequest.java
payment/contract/PaymentResponse.java                         → payment/core/contract/PaymentResponse.java
payment/contract/exception/UnknownMsisdnPrefixException.java  → payment/core/contract/exception/UnknownMsisdnPrefixException.java
payment/repo/MsisdnPrefixRoute.java                           → payment/core/repo/MsisdnPrefixRoute.java
payment/repo/MsisdnPrefixRouteRepository.java                 → payment/core/repo/MsisdnPrefixRouteRepository.java
payment/service/MsisdnPrefixRouteCache.java                   → payment/core/service/MsisdnPrefixRouteCache.java
payment/service/MsisdnRouter.java                             → payment/core/service/MsisdnRouter.java
payment/service/PaymentOrchestrator.java                      → payment/core/service/PaymentOrchestrator.java
```

**Test files (1): change package declaration + physically move**
```
payment/PaymentOrchestratorIT.java → payment/core/PaymentOrchestratorIT.java
```

**External files needing import update in same commit:**
- `disbursement/service/DisbursementOrchestrator.java` — 2 import lines change (`payment.*` → `payment.core.*`)
- `disbursement/service/DisbursementOrchestratorTest.java` (test) — same 2 imports

**IMPORTANT:** To avoid a broken build between PAY-01 and PAY-03, include the 2 `DisbursementOrchestrator` import updates in the PAY-01 commit. The disbursement files themselves do NOT physically move in PAY-01.

### PAY-02 — Complete Scope of Changes (Largest External Impact)

**Production files (19): change package declaration + physically move**
All of `transaction/` sub-packages move to `payment/ledger/`, preserving sub-package structure:
```
transaction/contract/*.java (6)  → payment/ledger/contract/*.java
transaction/repo/*.java (8)      → payment/ledger/repo/*.java
transaction/service/*.java (4)   → payment/ledger/service/*.java
```

**Test files (8): change package declaration + physically move**
```
transaction/IdempotencyServiceIT.java    → payment/ledger/IdempotencyServiceIT.java
transaction/LedgerConstraintIT.java      → payment/ledger/LedgerConstraintIT.java
transaction/LedgerServiceIT.java         → payment/ledger/LedgerServiceIT.java
transaction/PaymentEventLogIT.java       → payment/ledger/PaymentEventLogIT.java
transaction/TransactionStateMachineIT.java → payment/ledger/TransactionStateMachineIT.java
transaction/contract/LedgerFlowTest.java   → payment/ledger/contract/LedgerFlowTest.java
transaction/contract/LedgerPostingTest.java → payment/ledger/contract/LedgerPostingTest.java
transaction/repo/TransactionFlowTest.java  → payment/ledger/repo/TransactionFlowTest.java
```

**External production files needing import updates (same commit — 10 files if disbursement moves in PAY-03 first, or inline if PAY-02 runs before PAY-03):**
- `mtn/service/MtnMoMoPort.java` — 6 transaction.* imports
- `mtn/service/MtnStatusMapper.java` — 1 transaction.* import
- `mtn/service/MtnStatusPollerJob.java` — 5 transaction.* imports
- `orange/service/OrangeMoneyPort.java` — 6 transaction.* imports
- `orange/service/OrangeStatusMapper.java` — 1 transaction.* import
- `orange/service/OrangeStatusPollerJob.java` — 5 transaction.* imports
- `platform/admin/api/AuditResource.java` — 3 transaction.* imports
- `platform/admin/service/AdminTransactionQueryService.java` — 4 transaction.* imports
- `platform/admin/service/PaymentMetricsService.java` — 2 transaction.* imports
- If `disbursement` not yet moved: all disbursement service files that import transaction
- If `reconciliation` not yet moved: `reconciliation/port/MtnReportAdapter.java`, `OrangeReportAdapter.java`, `reconciliation/service/ReconciliationProviderRunner.java`
- If `webhook` not yet moved: all webhook files that import transaction

**External test files needing import updates (same commit — ~26 test files)**

Post-verification: `grep -rn "com.softropic.payam.transaction\." src/ --include="*.java"` must return zero.

### PAY-03 — Disbursement Scope

**Production files (39): change package declaration + physically move**
All of `disbursement/` sub-packages → `payment/disbursement/`, preserving structure.

**Test files (28): change package declaration + physically move**
All 28 test files in `src/test/.../disbursement/` move to `src/test/.../payment/disbursement/`.

**External files needing import update in same commit:**
- `platform/notification/infrastructure/listener/DisbursementOpsAlertEmailListener.java` — disbursement.* imports
- `webhook/service/WebhookDoubleCheckHandler.java` (if webhook not yet moved) — 1 disbursement.* import
- `mtn/service/MtnMoMoPort.java` — 1 `disbursement.repo.DisbursementRepository` import
- `orange/service/OrangeMoneyPort.java` — 1 `disbursement.repo.DisbursementRepository` import
- E2E tests in `e2e/disbursement/` — disbursement.* imports
- `mtn/service/MtnMoMoPortDisbursementCallbackTest.java`, `orange/service/OrangeMoneyPortDisbursementCallbackTest.java`
- `platform/notification/.../DisbursementOpsAlertEmailListenerTest.java`
- `webhook/service/WebhookDoubleCheckHandlerFlowRoutingTest.java`

### Recommended Execution Order (Wave Plan)

Order based on fewest external callers → most external callers, accounting for intra-domain dependencies:

```
Wave 1: PAY-04 (fee → payment.fee)
  — 6 prod + 1 test files; 1 prod + 1 test external callers
  — Move 6 prod + 1 test, update PaymentOrchestrator.java import (if payment not yet moved, stays as-is)
  — After PAY-04: grep for stale fee.* — only payment/PaymentOrchestrator still has fee.* import (will be fixed in PAY-01 or PAY-04 commit)
  — Run: mvn verify
  — Commit if green

Wave 2: PAY-05 (reconciliation → payment.reconciliation)
  — 19 prod + 4 test files; 0 prod + 1 test external callers
  — Include ReconciliationModule.java in the move
  — External callers: transaction.* in reconciliation are intra-wave (already moved if PAY-02 went first) OR need inline update
  — Run: mvn verify
  — Commit if green

Wave 3: PAY-06 (fraud → payment.fraud)
  — 7 prod + 3 test files; 3 prod + 20 test external callers
  — External: payment/service/PaymentOrchestrator.java (2 fraud.* imports), disbursement/service/*.java (2 files)
  — 20 test files (e2e/domain/* + fraud e2e tests) need import updates
  — Run: mvn verify
  — Commit if green

Wave 4: PAY-07 (webhook → payment.webhook)
  — 13 prod + 8 test files; 3 prod + 5 test external callers
  — External: disbursement/service/DisbursementCallbackTransitionService.java, mtn/service/MtnMoMoPort.java, orange/service/OrangeMoneyPort.java
  — 5 test files: e2e/webhook/OutboundWebhookDeliveryE2ETest.java + webhook test files for mtn/orange
  — Run: mvn verify
  — Commit if green

Wave 5: PAY-01 (payment → payment.core)
  — 10 prod + 1 test files; 1 prod + 27 test external callers
  — CRITICAL: DisbursementOrchestrator.java still imports payment.* — include its import update in this commit
  — 27 test files (e2e/payment/*, e2e/domain/*, fraud E2E, fraud IT tests)
  — Run: mvn verify
  — Commit if green

Wave 6: PAY-03 (disbursement → payment.disbursement)
  — 39 prod + 28 test files; 4 prod + 11 test external callers
  — External prod: platform.notification.DisbursementOpsAlertEmailListener, mtn/MtnMoMoPort, orange/OrangeMoneyPort, webhook (already moved)
  — 11 test files: e2e/disbursement/*, mtn disbursement callback tests, orange disbursement callback tests
  — Run: mvn verify
  — Commit if green

Wave 7: PAY-02 (transaction → payment.ledger)
  — 19 prod + 8 test files; 24 prod + 26 test external callers (most complex wave)
  — External prod: mtn (3), orange (3), platform.admin (3), reconciliation (3 if not moved yet), webhook (multiple if not moved yet)
  — All transaction.* callers: grep sweep essential before committing
  — Run: mvn verify
  — Commit if green
```

**Alternative ordering note:** If the planner prefers batching intra-dependent packages (payment.core + disbursement together since disbursement imports from payment), that reduces the number of "caller import updates included in wrong wave" risks at the cost of larger atomic commits.

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `@ComponentScan(basePackages=...)` explicit | `@SpringBootApplication` implicit scan | Spring Boot 2.x | New `payment.*` sub-packages auto-discovered; no scan config needed |
| Manual filter registration | `FilterRegistrationBean(setEnabled=false)` | Phase 61 established | Unchanged; no impact on this phase |

**Scope boundary:** `common.payment` and `common.refund` are explicitly NOT moved in Phase 63. They are deferred to Phase 65 (CMN-01). The packages moving in Phase 63 (`payment`, `transaction`, `disbursement`, `fee`, `reconciliation`, `fraud`, `webhook`) do import from `common.payment.*` — those imports remain as-is after Phase 63.

## Open Questions

1. **Should PAY-01 and PAY-03 be a single commit?**
   - What we know: `disbursement.service.DisbursementOrchestrator` imports `payment.contract.exception.UnknownMsisdnPrefixException` and `payment.service.MsisdnRouter`. Moving payment without disbursement leaves stale imports in disbursement files.
   - What's unclear: Whether the planner prefers atomic commits per requirement or per-dependency batch.
   - Recommendation: Include the two stale `DisbursementOrchestrator` import updates in the PAY-01 commit. This keeps each wave green independently without requiring a combined commit.

2. **Wave ordering when PAY-02 (transaction) runs last — does PAY-05 (reconciliation) need transaction to be at the new path first?**
   - What we know: Reconciliation imports from `transaction.*` (3 files). If reconciliation moves before transaction, reconciliation's moved files will have stale `transaction.*` imports until transaction moves in PAY-02.
   - Recommendation: Move transaction (PAY-02) BEFORE reconciliation (PAY-05), OR include forward-updated `payment.ledger.*` imports in the PAY-05 commit even though transaction hasn't moved yet — this is clean but requires careful ordering. Easiest: swap wave order to move transaction first, then reconciliation.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java 17 | Compilation | Yes | 17.0.1 LTS | — |
| Maven 3.9.9 | `mvn verify` | Yes | 3.9.9 | — |
| Docker | Integration tests (Testcontainers) | Yes | 20.10.12 | — |
| PostgreSQL (Testcontainers) | Integration tests | Yes (via Docker) | Testcontainers image | — |
| Redis (Testcontainers) | Integration tests | Yes (via Docker) | Testcontainers image | — |

All dependencies available. No blocking items.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 via Spring Boot Test 3.5.11 |
| Config file | `pom.xml` (maven-surefire-plugin + maven-failsafe-plugin) |
| Quick compile check | `mvn test-compile -q` |
| Full suite command | `mvn verify` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| PAY-01 | `payment.core.*` present; `POST /v1/payments` initiates collection, state machine advances, correct response | integration | `mvn verify` — `PaymentOrchestratorIT` (moves to `payment/core/`), E2E payment tests | Yes (existing, will move) |
| PAY-02 | `payment.ledger.*` present; duplicate idempotency key returns cached response, no second DB row | integration | `mvn verify` — `IdempotencyServiceIT`, `LedgerServiceIT`, `TransactionStateMachineIT` | Yes (existing, will move) |
| PAY-03 | `payment.disbursement.*` present; `POST /v1/disbursements` initiates and routes to correct provider | integration | `mvn verify` — `DisbursementResourceIT`, `DisbursementOrchestratorIT`, E2E disbursement suite | Yes (existing, will move) |
| PAY-04 | `payment.fee.*` present; fee evaluation classes accessible | integration | `mvn verify` — `FeeEngineIT` | Yes (existing, will move) |
| PAY-05 | `payment.reconciliation.*` present; daily reconciliation job runs, discrepancies persisted | integration | `mvn verify` — `ReconciliationJobIT`, `ReconciliationApiIT` | Yes (existing, will move) |
| PAY-06 | `payment.fraud.*` present; fraud velocity checks block known-bad request before provider dispatch | integration | `mvn verify` — `FraudEngineIT`, `FraudVelocityOrderingIT`, `FraudVelocityBlockE2ETest` | Yes (existing, will move) |
| PAY-07 | `payment.webhook.*` present; outbound webhook delivery occurs after terminal state transition | integration | `mvn verify` — `WebhookDeliveryIT`, `WebhookDeliveryJobIT`, `OutboundWebhookDeliveryE2ETest` | Yes (existing, will move) |
| BUILD-01 | `mvn verify` green after every wave commit | integration | `mvn verify` | Yes (cross-cutting gate) |
| BUILD-02 | No behavioral changes — REST contracts unchanged | integration | `mvn verify` — all existing ITs | Yes |
| BUILD-03 | Spring context starts; security filter chain works; Flyway runs | integration | `mvn verify` — `OperationalIT`, `SecurityFilterChainIT` | Yes |

### Sampling Rate
- **Per wave commit:** `mvn verify` (full suite — this is a refactor; no shortcuts)
- **Per phase gate:** `mvn verify` green with zero test failures

### Wave 0 Gaps
None — existing test infrastructure covers all phase requirements. This phase adds no new behavior. All tests already exist and move with their source packages.

## Sources

### Primary (HIGH confidence)
- Direct source tree inspection — all 7 source packages read directly from `src/main/java/com/softropic/payam/`
- `find` and `grep -rl` scans of entire `src/` tree — file counts, import counts, caller lists verified
- `.planning/phases/62-platform-layer-reorganization/62-RESEARCH.md` — Phase 62 patterns and decisions (identical refactor methodology)
- `.planning/STATE.md` — Key carry-forward decisions (atomic commit requirement, BUILD-01/02/03 cross-cutting gates, mtn/orange import dependency on transaction)
- `requirements/architecture.md` — Target package hierarchy spec
- `.planning/REQUIREMENTS.md` — PAY-01 through PAY-07 requirement text

### Secondary (MEDIUM confidence)
- `.planning/phases/62-platform-layer-reorganization/62-01-SUMMARY.md` through `62-05-SUMMARY.md` — confirm Phase 62 execution patterns apply directly to Phase 63

### Tertiary (LOW confidence)
- None — all findings are from direct source inspection

## Metadata

**Confidence breakdown:**
- File inventory (what moves, where): HIGH — all 113 prod + 53 test source files counted and listed by grep/find
- External caller count (import update scope): HIGH — comprehensive grep -rl scan of entire src tree, cross-verified by package
- Wave ordering recommendation: HIGH — based on caller counts and intra-domain dependency graph
- Intra-domain import cycle risk: HIGH — specific files and import lines identified
- `common.payment` scope boundary: HIGH — requirements explicitly defer CMN-01 to Phase 65

**Research date:** 2026-05-07
**Valid until:** Stable — code-only refactoring; no external dependencies or API versions affect findings.
