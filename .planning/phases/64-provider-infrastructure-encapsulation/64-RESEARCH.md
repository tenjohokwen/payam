# Phase 64: Provider Infrastructure Encapsulation - Research

**Researched:** 2026-05-11
**Domain:** Java package refactoring — Spring Boot 3.5.11, Maven, pure package rename
**Confidence:** HIGH

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| PROV-01 | `mtn` package relocated to `payment.provider.mtn` (MTN clients, token services, callback controllers) with all imports updated | 23 prod + 5 test files to move; 11 prod + 27 test external callers identified; 3 YAML files with FQN references |
| PROV-02 | `orange` package relocated to `payment.provider.orange` (Orange clients, token services, callback controllers) with all imports updated | 25 prod + 5 test files to move; 11 prod + 27 test external callers identified; 3 YAML files with FQN references |
</phase_requirements>

## Summary

Phase 64 relocates two flat packages (`mtn` and `orange`) into the `payment.provider.*` umbrella namespace, completing the provider infrastructure encapsulation described in the v12 architecture spec. No new logic is introduced — only package declarations, import statements, and YAML FQN references change.

The mapping is:
- `com.softropic.payam.mtn.*` → `com.softropic.payam.payment.provider.mtn.*`
- `com.softropic.payam.orange.*` → `com.softropic.payam.payment.provider.orange.*`

There are **23 MTN production source files** and **25 Orange production source files** to relocate (48 total), plus **5 MTN test files** and **5 Orange test files** to relocate (10 total). Additionally, **11 external production files** and **27 external test files** stay in place but need their import statements updated. Three YAML configuration files contain FQN class references to `*.mtn.contract.exception` and `*.orange.contract.exception` that must be updated in Resilience4j `ignoreExceptions` lists. One test Java file (`PaymentOrchestratorIT.java`) contains FQN references in code bodies (not just import statements).

The success criteria reference `POST /v1/callbacks/mtn/payment/{ref}` — this endpoint does NOT currently exist. The existing MTN collection callback endpoint is `PUT /v1/callbacks/mtn` (no path variable), and the disbursement callback is `PUT /v1/callbacks/mtn/disbursement/{ref}`. The phrase in the success criterion appears to describe the collection callback path after the package move (confirming it still works); it does not imply creating a new endpoint. No URL path changes are in scope for this phase.

**Primary recommendation:** Execute in two waves — Wave 1 moves MTN files (PROV-01) and all callers atomically, Wave 2 moves Orange files (PROV-02) and all callers atomically. Run `mvn verify` after each wave. MTN and Orange have identical external caller scope (same 11 prod files each, sharing the same set), so a single combined wave is also viable if preferred — but two waves reduces blast radius of any compilation failure.

## Standard Stack

### Core (verified from source tree)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Boot | 3.5.11 | Application framework, component scan | Already in use |
| Maven Failsafe + Surefire | (via Spring Boot BOM) | `mvn verify` integration test execution | Already in use |
| Java 17 | 17 (LTS) | Compilation target | Already in use |
| macOS sed | BSD sed | File text replacement during refactoring | Platform constraint — no `\b` word boundary |

**Installation:** No new dependencies required. This is a pure package reorganization.

## Architecture Patterns

### Target Project Structure (after Phase 64)
```
src/main/java/com/softropic/payam
├── payment/
│   ├── core/               (done — Phase 63)
│   ├── disbursement/       (done — Phase 63)
│   ├── fee/                (done — Phase 63)
│   ├── fraud/              (done — Phase 63)
│   ├── ledger/             (done — Phase 63)
│   ├── reconciliation/     (done — Phase 63)
│   ├── webhook/            (done — Phase 63)
│   └── provider/           NEW in Phase 64
│       ├── mtn/            was: mtn/
│       │   ├── MtnModule.java
│       │   ├── config/
│       │   ├── contract/
│       │   ├── infrastructure/
│       │   ├── service/
│       │   └── web/
│       └── orange/         was: orange/
│           ├── OrangeModule.java
│           ├── config/
│           ├── contract/
│           ├── infrastructure/
│           ├── service/
│           └── web/
├── platform/               (done — Phase 62)
├── infrastructure/         (done — Phase 61)
└── common/                 (redistribution deferred to Phase 65)
```

### Pattern: Atomic Two-Pass sed (macOS constraint)
**What:** macOS `sed` does not support `\b` word boundaries. A two-pass sed is required for package declaration rewrites: first pass for sub-package declarations (line ending with dot), second pass for root package declaration (line ending with semicolon).
**When to use:** All package declaration rewriting in this codebase.
**Example (from Phase 63-02 decision):**
```bash
# Pass 1: sub-package declarations (e.g., "package com.softropic.payam.mtn.config;")
sed -i '' 's/package com\.softropic\.payam\.mtn\./package com.softropic.payam.payment.provider.mtn./g' FILE.java
# Pass 2: root package declaration (e.g., "package com.softropic.payam.mtn;")
sed -i '' 's/package com\.softropic\.payam\.mtn;/package com.softropic.payam.payment.provider.mtn;/g' FILE.java
```

### Pattern: Import Sweep with FQN Body Scan
**What:** After updating import statements, grep for FQN references in code bodies (method bodies, annotations, YAML strings) — Phase 63-07 discovered that `WebhookDoubleCheckHandler` used FQNs in method bodies, not just imports.
**When to use:** After every import sweep.
**Example:**
```bash
# Check for any remaining FQN references (imports + bodies)
grep -rn "com\.softropic\.payam\.mtn\." src --include="*.java"
grep -rn "com\.softropic\.payam\.orange\." src --include="*.java"
# Also check YAML files
grep -rn "com\.softropic\.payam\.mtn\.\|com\.softropic\.payam\.orange\." src/main/resources
```

### Pattern: Atomic Single Commit
**What:** File moves AND all caller import updates must land in a single commit. A partial commit leaves the codebase uncompilable (Phase 61 decision). For this phase, each wave (MTN or combined) should be committed atomically.
**When to use:** Any package move operation.

### Anti-Patterns to Avoid
- **Partial commit:** Moving files without updating callers in the same commit — leaves codebase in non-compiling state.
- **Skipping FQN body scan:** Only sweeping `import` blocks — FQN references in code bodies (like `PaymentOrchestratorIT.java` lines 173/187-188 with `MtnAccountInactiveException.class`, `SubscriberInactiveException.class`, `PayTokenExpiredException.class`) will cause compilation failures.
- **Forgetting YAML FQN references:** The Resilience4j `ignoreExceptions` lists in `application.yaml`, `application-uat.yaml`, and `application-dev.yaml` contain class FQNs that must be updated.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Batch import rewriting | Custom parser | `sed` with per-suffix patterns | Already proven in Phases 61–63; regex is sufficient for this codebase |
| Component scan registration | Explicit `@ComponentScan` additions | None needed — `@SpringBootApplication` already scans all `com.softropic.payam.*` sub-packages | The root application scan covers `payment.provider.mtn` and `payment.provider.orange` automatically |

**Key insight:** No Spring configuration changes are required. `@SpringBootApplication` at `com.softropic.payam` scans all sub-packages including the new `payment.provider.*` depth. Quartz job class references (`MtnStatusPollerJob`, `OrangeStatusPollerJob`) in `MtnSchedulerConfig`/`OrangeSchedulerConfig` are resolved via their Spring bean classes, not package paths — the scheduler configs move with their classes, so no Quartz job key changes are needed.

## Runtime State Inventory

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | None — no database column or Redis key stores a Java package FQN for mtn/orange | None |
| Live service config | None — no external service (n8n, Datadog, etc.) references the Java package names | None |
| OS-registered state | Quartz job identities (`mtn-status-poller`, `orange-status-poller`) are string keys set at runtime by `MtnSchedulerConfig`/`OrangeSchedulerConfig` — these are NOT FQNs, just job names; they are unaffected by package rename | None |
| Secrets/env vars | None — no env var name references `mtn.*` or `orange.*` package paths; env vars `MTN_API_USER_ID`, `MTN_API_KEY`, etc. reference config prefix keys (`mtn.*`), which are YAML property prefixes not Java FQNs, and are unchanged | None |
| Build artifacts | None identified — no compiled binaries or installed artifacts specific to mtn/orange packages | None |

**YAML FQN references (require code edit, not data migration):** Three YAML files contain class FQNs in the Resilience4j `ignoreExceptions` lists:
- `src/main/resources/application.yaml` lines 256–264
- `src/main/resources/application-uat.yaml` lines 244–252
- `src/main/resources/application-dev.yaml` lines 245–253

These reference:
- `com.softropic.payam.orange.contract.exception.SubscriberInactiveException`
- `com.softropic.payam.orange.contract.exception.PayTokenExpiredException`
- `com.softropic.payam.mtn.contract.exception.MtnAccountInactiveException`

All three must be updated to the new `payment.provider.*` paths.

## Common Pitfalls

### Pitfall 1: FQN References in Test Code Bodies
**What goes wrong:** Import sweep updates the `import` block, but `PaymentOrchestratorIT.java` lines 173 and 187-188 reference `MtnAccountInactiveException.class`, `SubscriberInactiveException.class`, and `PayTokenExpiredException.class` as inline FQNs in Java code bodies — these will not be caught by an import-only sed pass.
**Why it happens:** Some code uses FQNs inside expressions (e.g., Resilience4j builder `.ignoreExceptions(com.softropic.payam.mtn.contract.exception.MtnAccountInactiveException.class)`). Same pattern caused Phase 63-07 to require an extra FQN sweep.
**How to avoid:** After the import sweep, run `grep -rn "com\.softropic\.payam\.mtn\.\|com\.softropic\.payam\.orange\." src --include="*.java"` and fix any remaining hits.
**Warning signs:** Compilation succeeds but grep reports residual FQNs — means FQNs are in comments or javadoc (acceptable) vs code bodies (must fix).

### Pitfall 2: Forgetting YAML FQN Updates
**What goes wrong:** Java compiles cleanly but Resilience4j fails to load its circuit-breaker config because `ignoreExceptions` class names no longer resolve.
**Why it happens:** YAML is not compiled — errors surface only at Spring context startup (runtime, not compile time).
**How to avoid:** Explicitly update all three YAML files as part of the same wave/commit that moves the exception classes. Verify with `mvn verify` (not just `mvn compile`).
**Warning signs:** `ClassNotFoundException` or `BeanCreationException` during Spring context startup in tests.

### Pitfall 3: MtnWebConfig and OrangeWebConfig Web Interceptor Registration
**What goes wrong:** `MtnWebConfig` and `OrangeWebConfig` implement `WebMvcConfigurer` and register interceptors for specific path patterns. After the move, if Spring fails to pick up these `@Configuration` classes (e.g., due to a typo in the package declaration), the IP whitelist interceptors stop registering silently — no compile error, no startup error, but callbacks are unguarded.
**Why it happens:** `WebMvcConfigurer` beans are discovered via component scan; a bad package declaration would exclude them from the scan.
**How to avoid:** Verify the interceptor path registrations survive the move by checking `MtnWebConfig` and `OrangeWebConfig` package declarations match their new directory paths. The `mvn verify` E2E tests that exercise the callback path (e.g., `MtnPutCallbackAcceptanceE2ETest`) will catch this.
**Warning signs:** Callback E2E tests that rely on IP whitelist interception start failing differently (400 vs 200).

### Pitfall 4: Collision Between MtnDisbursementCallbackController (in payment.disbursement) and MtnCallbackController (moving to payment.provider.mtn)
**What goes wrong:** After the move, the codebase will have `MtnCallbackController` at `payment.provider.mtn.web` and `MtnDisbursementCallbackController` at `payment.disbursement.api`. Both reference `MtnMoMoPort` (now at `payment.provider.mtn.service`). The disbursement callback controllers stay in their current location (`payment.disbursement.api`) — they are NOT moved in this phase. Only their imports of `mtn.*` and `orange.*` change.
**Why it happens:** Easy to accidentally move the disbursement callback controllers, which already live under `payment.*` and are NOT part of the `mtn.*`/`orange.*` flat packages.
**How to avoid:** Scope the file move strictly to files currently under `src/main/java/com/softropic/payam/mtn/` and `src/main/java/com/softropic/payam/orange/`. The disbursement API controllers are already in `payment.disbursement.api` — leave them in place, only update their imports.

### Pitfall 5: Test Package Declaration Mismatch After Move
**What goes wrong:** Test files in `src/test/java/com/softropic/payam/mtn/` and `src/test/java/com/softropic/payam/orange/` need both their physical path AND their `package` declaration updated. Missing the declaration update causes a compile error ("class X is public, should be declared in a file named X.java" or package mismatch).
**Why it happens:** Same two-pass issue as production files — physical move is insufficient without package declaration rewrite.
**How to avoid:** Apply sed package declaration rewrites to test files in the same wave as production files.

### Pitfall 6: AppEndpoints.PUBLIC_ENDPOINTS URL Paths Are Unchanged
**What goes wrong:** The callback URL paths (`/v1/callbacks/mtn`, `/v1/callbacks/mtn/disbursement/*`, `/v1/callbacks/orange`, `/v1/callbacks/orange/disbursement`) are defined as string literals in `AppEndpoints.java` and in `MtnWebConfig`/`OrangeWebConfig`. These are HTTP paths, not Java package names — they must NOT be changed.
**Why it happens:** The requirement mentions `POST /v1/callbacks/mtn/payment/{ref}` in the success criteria, which does not exist and is not a target endpoint for this phase. The existing paths remain as-is.
**How to avoid:** Do not touch any `@PutMapping`, `@PostMapping`, or `AppEndpoints` path string literals during this phase.

## Code Examples

### Sed Pattern for Package Declaration Rewrite (MTN, macOS)
```bash
# Two-pass: sub-package first, then root package
find src -name "*.java" -path "*/mtn/*" | xargs sed -i '' \
  's/package com\.softropic\.payam\.mtn\./package com.softropic.payam.payment.provider.mtn./g'
find src -name "*.java" -path "*/mtn/*" | xargs sed -i '' \
  's/package com\.softropic\.payam\.mtn;/package com.softropic.payam.payment.provider.mtn;/g'
```

### Sed Pattern for Import Sweep (External Callers)
```bash
# Sweep all Java files for mtn import lines
find src -name "*.java" | xargs grep -l "com\.softropic\.payam\.mtn\." | xargs sed -i '' \
  's/import com\.softropic\.payam\.mtn\./import com.softropic.payam.payment.provider.mtn./g'
# Also sweep FQN references in code bodies
find src -name "*.java" | xargs grep -l "com\.softropic\.payam\.mtn\." | xargs sed -i '' \
  's/com\.softropic\.payam\.mtn\./com.softropic.payam.payment.provider.mtn./g'
```

### YAML FQN Update
```bash
# Update all three YAML files atomically
sed -i '' 's/com\.softropic\.payam\.mtn\.contract\.exception\./com.softropic.payam.payment.provider.mtn.contract.exception./g' \
  src/main/resources/application.yaml \
  src/main/resources/application-uat.yaml \
  src/main/resources/application-dev.yaml
sed -i '' 's/com\.softropic\.payam\.orange\.contract\.exception\./com.softropic.payam.payment.provider.orange.contract.exception./g' \
  src/main/resources/application.yaml \
  src/main/resources/application-uat.yaml \
  src/main/resources/application-dev.yaml
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Flat `mtn.*` package at root | `payment.provider.mtn.*` under payment bounded context | Phase 64 (this phase) | All callers update their imports; no behavioral change |
| Flat `orange.*` package at root | `payment.provider.orange.*` under payment bounded context | Phase 64 (this phase) | All callers update their imports; no behavioral change |

## Open Questions

1. **Success Criteria endpoint `POST /v1/callbacks/mtn/payment/{ref}`**
   - What we know: No such endpoint exists in the codebase. The existing collection callback is `PUT /v1/callbacks/mtn` (no path variable) in `MtnCallbackController`.
   - What's unclear: Whether the success criterion intends the existing `PUT /v1/callbacks/mtn` endpoint (collection), or whether it accidentally references a non-existent path.
   - Recommendation: Treat the success criterion as referring to the existing `PUT /v1/callbacks/mtn` endpoint — verify it processes a WireMock-mocked MTN callback and transitions the transaction to SUCCESS after the package move (test already exists: `MtnPutCallbackAcceptanceE2ETest`). Do NOT create a new endpoint. This is a pure package rename phase.

## Environment Availability

Step 2.6: SKIPPED — this phase is purely code/config changes with no new external dependencies. All required tools (Maven, Java 17, macOS `sed`) are already in use from Phases 61–63.

## Validation Architecture

Nyquist validation key absent from `.planning/config.json` — treat as enabled.

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test (via Maven Failsafe/Surefire) |
| Config file | `pom.xml` — Surefire for unit tests, Failsafe for `*IT.java` |
| Quick run command | `mvn test-compile -q` (compilation gate) |
| Full suite command | `mvn verify` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| PROV-01 | MTN files compile and run at new package path | compile gate | `mvn test-compile -q` | N/A (compilation) |
| PROV-01 | MTN callback (`PUT /v1/callbacks/mtn`) processes and transitions to SUCCESS | integration (E2E) | `mvn verify -Dit.test=MtnPutCallbackAcceptanceE2ETest` | ✅ |
| PROV-01 | MTN disbursement callback (`PUT /v1/callbacks/mtn/disbursement/{ref}`) works | integration (E2E) | `mvn verify -Dit.test=MtnDisbursementE2EIT` | ✅ |
| PROV-01 | Callback replay dedup (MTN) | integration (E2E) | `mvn verify -Dit.test=MtnWebhookDoubleCheckE2ETest` | ✅ |
| PROV-01 | Double-check handler routing (MTN) | unit | `mvn test -Dtest=WebhookDoubleCheckHandlerFlowRoutingTest` | ✅ |
| PROV-02 | Orange callback (`POST /v1/callbacks/orange`) processes and transitions to SUCCESS | integration | `mvn verify -Dit.test=OrangeCallbackControllerIT` | ✅ |
| PROV-02 | Orange disbursement callback works | integration (E2E) | `mvn verify -Dit.test=OrangeDisbursementE2EIT` | ✅ |
| PROV-02 | Callback replay dedup (Orange) | integration (E2E) | `mvn verify -Dit.test=WebhookReplayProtectionE2ETest` | ✅ |
| PROV-02 | Double-check handler routing (Orange) | unit | `mvn test -Dtest=WebhookDoubleCheckHandlerFlowRoutingTest` | ✅ |
| BUILD-01 | `mvn verify` passes green after each wave | integration | `mvn verify` | N/A (gate) |
| BUILD-02 | No functional behavior changes (APIs, schema, Flyway unchanged) | integration | `mvn verify` | N/A (gate) |
| BUILD-03 | Spring component-scan, security filter, Resilience4j circuit-breakers verified functional | integration | `mvn verify` | N/A (gate) |

### Sampling Rate
- **Per wave commit:** `mvn test-compile -q` (catches any import/package declaration mistakes before the full run)
- **Per wave gate:** `mvn verify` (full suite green required before moving to next wave)
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
None — existing test infrastructure covers all phase requirements. No new test files or framework config needed.

## Complete File Inventory

### MTN Production Files to Move (23 files)
All currently under `src/main/java/com/softropic/payam/mtn/`:
- `MtnModule.java`
- `config/MtnConfig.java`
- `config/MtnMoMoConfig.java`
- `config/MtnSchedulerConfig.java`
- `contract/MtnCallbackPayload.java`
- `contract/MtnTransactionStatus.java`
- `contract/dto/AccountBalanceResponse.java`
- `contract/dto/AccountHolderInfoResponse.java`
- `contract/dto/DisbursementRequest.java`
- `contract/dto/MtnTokenResponse.java`
- `contract/dto/RequestToPayRequest.java`
- `contract/dto/RequestToPayStatusResponse.java`
- `contract/dto/TransferStatusResponse.java`
- `contract/exception/MtnAccountInactiveException.java`
- `contract/exception/MtnApiException.java`
- `infrastructure/MtnMoMoClient.java`
- `service/MtnMoMoPort.java`
- `service/MtnStatusMapper.java`
- `service/MtnStatusPollerJob.java`
- `service/MtnTokenService.java`
- `web/MtnCallbackController.java`
- `web/MtnIpWhitelistInterceptor.java`
- `web/MtnWebConfig.java`

### MTN Test Files to Move (5 files)
All currently under `src/test/java/com/softropic/payam/mtn/`:
- `MtnMoMoPortIT.java`
- `MtnTokenServiceIT.java`
- `service/MtnMoMoPortDisbursementCallbackTest.java`
- `service/MtnStatusPollerJobTimeoutTest.java`
- `web/MtnWebConfigTest.java`

### Orange Production Files to Move (25 files)
All currently under `src/main/java/com/softropic/payam/orange/`:
- `OrangeModule.java`
- `config/OrangeConfig.java`
- `config/OrangeMoneyConfig.java`
- `config/OrangeSchedulerConfig.java`
- `contract/OrangeStatus.java`
- `contract/OrangeTransactionType.java`
- `contract/OrangeWebhookPayload.java`
- `contract/dto/C2CRequest.java`
- `contract/dto/CashoutRequest.java`
- `contract/dto/InitTransactionResponse.java`
- `contract/dto/OrangeTokenResponse.java`
- `contract/dto/PayRequest.java`
- `contract/dto/PayResponse.java`
- `contract/dto/SubscriberInfoResponse.java`
- `contract/exception/OrangeApiException.java`
- `contract/exception/PayTokenExpiredException.java`
- `contract/exception/SubscriberInactiveException.java`
- `infrastructure/OrangeMoneyClient.java`
- `service/OrangeMoneyPort.java`
- `service/OrangeStatusMapper.java`
- `service/OrangeStatusPollerJob.java`
- `service/OrangeTimeUtil.java`
- `service/OrangeTokenService.java`
- `web/OrangeCallbackController.java`
- `web/OrangeIpWhitelistInterceptor.java`
- `web/OrangeWebConfig.java`

### Orange Test Files to Move (5 files)
All currently under `src/test/java/com/softropic/payam/orange/`:
- `OrangeMoneyPortIT.java`
- `OrangeTimeUtilTest.java`
- `OrangeTokenServiceIT.java`
- `service/OrangeMoneyPortDisbursementCallbackTest.java`
- `service/OrangeStatusPollerJobTimeoutTest.java`
- `web/OrangeWebConfigTest.java`

### External Production Callers (11 files — update imports only, do NOT move)
| File | Imported mtn/orange symbols |
|------|-----------------------------|
| `payment/core/service/PaymentOrchestrator.java` | `mtn.contract.exception.MtnAccountInactiveException`, `mtn.service.MtnMoMoPort`, `orange.contract.exception.SubscriberInactiveException`, `orange.service.OrangeMoneyPort` |
| `payment/disbursement/api/MtnDisbursementCallbackController.java` | `mtn.contract.MtnCallbackPayload`, `mtn.service.MtnMoMoPort` |
| `payment/disbursement/api/OrangeDisbursementCallbackController.java` | `orange.contract.OrangeWebhookPayload`, `orange.service.OrangeMoneyPort` |
| `payment/disbursement/service/DisbursementCallbackTransitionService.java` | `mtn.service.MtnStatusMapper`, `orange.service.OrangeStatusMapper` |
| `payment/disbursement/service/DisbursementOrchestrator.java` | `mtn.service.MtnMoMoPort`, `orange.service.OrangeMoneyPort` |
| `payment/reconciliation/port/MtnReportAdapter.java` | `mtn.service.MtnMoMoPort` |
| `payment/reconciliation/port/OrangeReportAdapter.java` | `orange.service.OrangeMoneyPort` |
| `payment/webhook/service/WebhookDoubleCheckHandler.java` | `mtn.service.MtnMoMoPort`, `orange.service.OrangeMoneyPort` |
| `payment/webhook/service/WebhookTransitionService.java` | `mtn.service.MtnStatusMapper`, `orange.service.OrangeStatusMapper` |
| `platform/monitoring/MtnPlatformHealthIndicator.java` | `mtn.service.MtnMoMoPort` |
| `platform/monitoring/OrangePlatformHealthIndicator.java` | `orange.service.OrangeMoneyPort` |

### External Test Callers (27 files — update imports only, do NOT move)
| File | Imported mtn/orange symbols |
|------|-----------------------------|
| `domain/OrangeTimestampOffsetTest.java` | `orange.service.OrangeTimeUtil` |
| `e2e/builder/MtnWebhookPayloadBuilder.java` | `mtn.contract.MtnCallbackPayload` |
| `e2e/builder/OrangeWebhookPayloadBuilder.java` | `orange.contract.OrangeWebhookPayload` |
| `e2e/domain/HashChainIntegrityTest.java` | `mtn.contract.MtnCallbackPayload` |
| `e2e/domain/LedgerDoubleEntryTest.java` | `mtn.contract.MtnCallbackPayload` |
| `e2e/domain/MtnPathMatrixTest.java` | `mtn.contract.MtnCallbackPayload`, `mtn.service.MtnStatusPollerJob` |
| `e2e/domain/OrangePathMatrixTest.java` | `orange.service.OrangeStatusPollerJob` |
| `e2e/domain/OrangeTimestampWatTest.java` | `orange.service.OrangeTimeUtil` |
| `e2e/domain/TransactionBoundaryTest.java` | `mtn.contract.MtnCallbackPayload` |
| `e2e/domain/WebhookDoubleCheckTest.java` | `mtn.contract.MtnCallbackPayload` |
| `e2e/domain/WebhookPollingRaceTest.java` | `mtn.contract.MtnCallbackPayload`, `mtn.service.MtnStatusPollerJob` |
| `e2e/payment/MtnPaymentInitiationE2ETest.java` | `mtn.contract.MtnCallbackPayload` |
| `e2e/payment/MtnPollingFallbackE2ETest.java` | `mtn.service.MtnStatusPollerJob` |
| `e2e/payment/OrangePayTokenExpiryE2ETest.java` | `orange.service.OrangeStatusPollerJob` |
| `e2e/reconciliation/DailyReconciliationE2ETest.java` | `mtn.service.MtnMoMoPort`, `orange.service.OrangeMoneyPort` |
| `e2e/webhook/MtnPutCallbackAcceptanceE2ETest.java` | `mtn.contract.MtnCallbackPayload` |
| `e2e/webhook/MtnWebhookDoubleCheckE2ETest.java` | `mtn.contract.MtnCallbackPayload` |
| `e2e/webhook/OutboundWebhookDeliveryE2ETest.java` | `mtn.contract.MtnCallbackPayload` |
| `e2e/webhook/WebhookReplayProtectionE2ETest.java` | `mtn.contract.MtnCallbackPayload` |
| `payment/core/PaymentOrchestratorIT.java` | FQN in body: `mtn.contract.exception.MtnAccountInactiveException`, `orange.contract.exception.SubscriberInactiveException`, `orange.contract.exception.PayTokenExpiredException` |
| `payment/disbursement/api/MtnDisbursementCallbackControllerTest.java` | `mtn.contract.MtnCallbackPayload`, `mtn.service.MtnMoMoPort` |
| `payment/disbursement/api/OrangeDisbursementCallbackControllerTest.java` | `orange.contract.OrangeWebhookPayload`, `orange.service.OrangeMoneyPort` |
| `payment/disbursement/service/DisbursementOrchestratorTest.java` | `mtn.service.MtnMoMoPort`, `orange.service.OrangeMoneyPort` |
| `payment/reconciliation/ReconciliationFailedStateIT.java` | `mtn.service.MtnMoMoPort`, `orange.service.OrangeMoneyPort` |
| `payment/reconciliation/ReconciliationJobIT.java` | `mtn.service.MtnMoMoPort`, `orange.service.OrangeMoneyPort` |
| `payment/webhook/service/WebhookDoubleCheckHandlerFlowRoutingTest.java` | `mtn.service.MtnMoMoPort`, `orange.service.OrangeMoneyPort` |
| `platform/security/api/AdminLoginResourceTest.java` | `mtn.web.MtnIpWhitelistInterceptor`, `orange.web.OrangeIpWhitelistInterceptor` |

### YAML Files Requiring FQN Update (3 files)
| File | FQN References |
|------|----------------|
| `src/main/resources/application.yaml` (lines 256–264) | `orange.contract.exception.SubscriberInactiveException`, `orange.contract.exception.PayTokenExpiredException`, `mtn.contract.exception.MtnAccountInactiveException` |
| `src/main/resources/application-uat.yaml` (lines 244–252) | Same three FQNs |
| `src/main/resources/application-dev.yaml` (lines 245–253) | Same three FQNs |

## Sources

### Primary (HIGH confidence)
- Direct source tree inspection — all file paths, import statements, and package declarations verified by `find`/`grep` against the actual source tree
- `src/main/resources/application.yaml` — Resilience4j FQN references confirmed at lines 256–264
- `.planning/phases/63-payment-domain-consolidation/63-RESEARCH.md` — established patterns for this refactoring style

### Secondary (MEDIUM confidence)
- Phase 63 decisions in `STATE.md` — two-pass sed pattern, FQN body scan requirement, atomic commit requirement, macOS sed limitation all confirmed from project history

## Metadata

**Confidence breakdown:**
- File inventory (production and test): HIGH — verified by direct `find`/`grep` enumeration
- External caller list: HIGH — verified by `grep -rn` across all Java source files
- YAML FQN references: HIGH — verified by direct grep of resource files
- Architecture patterns: HIGH — same patterns proved correct in Phases 61–63
- Pitfalls: HIGH — directly derived from Phase 63 decisions in STATE.md and confirmed by source inspection
- Spring component-scan behavior: HIGH — `@SpringBootApplication` root scan is established behavior

**Research date:** 2026-05-11
**Valid until:** 2026-06-11 (stable — no external dependencies)
