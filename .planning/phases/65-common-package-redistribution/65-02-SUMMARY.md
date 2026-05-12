---
phase: 65-common-package-redistribution
plan: "02"
subsystem: infra
tags: [java, spring-boot, package-refactoring, common, infrastructure]

# Dependency graph
requires:
  - phase: 65-common-package-redistribution
    plan: "01"
    provides: "infrastructure.exception, infrastructure.message, infrastructure.config, infrastructure.logging at new paths (required by intra-wave HttpClientException/JsonUtil imports)"
  - phase: 64-provider-infrastructure-encapsulation
    provides: "payment.provider.mtn and payment.provider.orange at correct paths"
provides:
  - "infrastructure.client (8 files incl exception/ sub-package) — HTTP client base classes"
  - "infrastructure.threadpool (5 files) — MDC/TenantContext task decorators"
  - "infrastructure.util (11 files: 6 from common.util + 5 root utilities) — ClockProvider, Constants, BodySanitizer, JsonUtil, etc."
  - "infrastructure.validation (19 files: 18 from common.validation + PhoneNumberDto) — Bean Validation constraints, CamMobileValidator"
  - "TestClockProvider co-located with ClockProvider in infrastructure.util (package-private setClock() access preserved)"
  - "common/ now contains only: payment/, refund/, enums/, consumer/, Gender.java (Plans 03-04 will handle)"
affects: [65-03, 65-04, 65-05]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Two-pass sed for package declaration rewrites on macOS BSD (sub-package suffix dot vs root semicolon)"
    - "Broad sed sweep (no import-only anchor) catches both import statements and FQN body references in catch blocks"
    - "TestClockProvider co-located in same package as ClockProvider to preserve package-private method access"
    - "PhoneNumberDto placed in infrastructure.validation (not infrastructure.dto) — both validators and utils depend on it; infrastructure cannot depend upward on platform"

key-files:
  created:
    - "src/main/java/com/softropic/payam/infrastructure/client/* (8 files)"
    - "src/main/java/com/softropic/payam/infrastructure/threadpool/* (5 files)"
    - "src/main/java/com/softropic/payam/infrastructure/util/* (11 files)"
    - "src/main/java/com/softropic/payam/infrastructure/validation/* (19 files)"
    - "src/test/java/com/softropic/payam/infrastructure/util/TestClockProvider.java"
    - "src/test/java/com/softropic/payam/infrastructure/util/BodySanitizerTest.java"
    - "src/test/java/com/softropic/payam/infrastructure/validation/CamMobileValidatorTest.java"
    - "src/test/java/com/softropic/payam/infrastructure/validation/InputValidatorTest.java"
  modified:
    - "~40 external caller files across payment, platform, infrastructure packages"
    - "src/main/java/com/softropic/payam/common/consumer/Consumer.java (PhoneNumber import updated)"
    - "src/test/java/com/softropic/payam/common/HttpTestClient.java (Client import updated)"
    - "src/test/java/com/softropic/payam/platform/security/infrastructure/jwt/JwtManagerImplTest.java (TestClockProvider import)"
    - "src/test/java/com/softropic/payam/platform/security/service/LoginInfoServiceIT.java (TestClockProvider import)"

key-decisions:
  - "HttpTestClient.java + Consumer.java were not captured by the initial external caller grep (excluded common/ paths) — fixed as Rule 1 auto-fix during Step I zero-stale check"
  - "JwtManagerImplTest.java + LoginInfoServiceIT.java import TestClockProvider from common package — missed by grep since they don't reference moved subpackages; fixed as Rule 1 auto-fix when test-compile failed"
  - "PhoneNumber.java in infrastructure.validation is a JPA @Embeddable entity class (not a @Constraint annotation) — audit check for @Constraint triplet was validating the wrong file; actual constraint annotations are CamPhone.java and Phone.java (both verified intact)"
  - "CamMobileValidator.java is a static utility class (not a ConstraintValidator implementation) — constraint validation for CamPhone is handled by CamPhoneValidator.java which correctly implements ConstraintValidator<CamPhone, String>"

patterns-established:
  - "Plan note: grep exclusion of common/ paths can miss test files inside common/ that import non-common classes (HttpTestClient.java) AND can miss test files outside common/ that import TestClockProvider (which was in common/)"
  - "Always run mvn test-compile immediately after package declaration rewrites and external caller updates — catches missed callers before full mvn verify"

requirements-completed: [CMN-02]

# Metrics
duration: 18min
completed: 2026-05-12
---

# Phase 65 Plan 02: Common Package Redistribution Wave 2 Summary

**Relocated 37 production files (common.client/threadpool/util/validation/dto + 5 root utilities) and 4 test utilities to infrastructure.* sub-packages, updating all ~40 external callers plus FQN body references, with mvn verify green.**

## Performance

- **Duration:** ~18 min
- **Started:** 2026-05-12T07:00:00Z
- **Completed:** 2026-05-12T07:18:29Z
- **Tasks:** 1 (single atomic task per plan structure)
- **Files modified:** 88

## Accomplishments
- Moved 8 HTTP client files (`AbstractClient`, `Client`, `ClientConfiguration`, `ClientRequest`, `RestRequestInterceptor`, `TcpConfiguration`, `exception/HttpClientException`, `exception/MomoError`) from `common.client` to `infrastructure.client`
- Moved 5 thread pool files (`ClientThreadContext`, `ExecutorExceptionHandler`, `MdcDecorator`, `MdcWrapper`, `TenantContextTaskDecorator`) from `common.threadpool` to `infrastructure.threadpool`
- Moved 11 utility files to `infrastructure.util`: 6 from `common.util` (`BodySanitizer`, `EnvUtil`, `JsonUtil`, `PhoneNumberUtil`, `RandomUtil`, `TimeUtil`) plus 5 root utilities (`ClockProvider`, `Constants`, `Predicate`, `TimeGuru`, `TransactionIdProvider`)
- Moved 19 validation files to `infrastructure.validation`: 18 from `common.validation` + `PhoneNumberDto` (migrated from `common.dto`)
- Relocated 4 test utilities including `TestClockProvider` co-located with `ClockProvider` in `infrastructure.util` for package-private `setClock()` access
- `common/` now contains only: `payment/`, `refund/`, `enums/`, `consumer/`, `Gender.java`
- `mvn verify` (BUILD-01 gate) passes green

## Task Commits

1. **Task 1: Move all common utility subpackages to infrastructure.*** - `2148fb7` (refactor)

## Files Created/Modified

Key new destination packages:
- `src/main/java/com/softropic/payam/infrastructure/client/` (8 files)
- `src/main/java/com/softropic/payam/infrastructure/threadpool/` (5 files)
- `src/main/java/com/softropic/payam/infrastructure/util/` (11 files)
- `src/main/java/com/softropic/payam/infrastructure/validation/` (19 files)
- `src/test/java/com/softropic/payam/infrastructure/util/` (2 test files)
- `src/test/java/com/softropic/payam/infrastructure/validation/` (2 test files)

Caller updates included `payment.provider.mtn.infrastructure.MtnMoMoClient` (FQN body reference on line 171 catch block), `infrastructure.config.AsyncConfig`, `infrastructure.persistence.AuditingDateTimeProvider`, `infrastructure.web.LoggingFilter`, plus all platform security/tenant/notification callers of `ClockProvider`.

## Decisions Made

- `PhoneNumberDto` placed in `infrastructure.validation` (not a separate `infrastructure.dto`): both `PhoneNumberUtil` and `CamMobileValidator` depend on it; after Plan 02 infrastructure cannot depend upward on platform, so `infrastructure.validation` is the correct home.
- `TestClockProvider` co-located with `ClockProvider` in `infrastructure.util`: `setClock()` is package-private and requires same-package test access — research Pitfall 6 from 65-RESEARCH.md.
- Two-pass sed remains required for macOS BSD: first pass for sub-package declarations (trailing dot), second pass for root declarations (trailing semicolon) — consistent with Phase 63 pattern.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] HttpTestClient.java missed by initial external caller grep**
- **Found during:** Task 1, Step I (zero-stale check)
- **Issue:** `src/test/java/com/softropic/payam/common/HttpTestClient.java` imports `common.client.Client` but was excluded from the external caller grep (grep excluded `src/test/java/com/softropic/payam/common/` paths); left 1 stale `common.client.*` reference
- **Fix:** Applied sed replacement to update import to `infrastructure.client.Client`
- **Files modified:** `src/test/java/com/softropic/payam/common/HttpTestClient.java`
- **Verification:** Zero stale `common.client.*` references confirmed
- **Committed in:** `2148fb7` (part of atomic task commit)

**2. [Rule 1 - Bug] Consumer.java missed by initial external caller grep**
- **Found during:** Task 1, Step I (zero-stale check)
- **Issue:** `src/main/java/com/softropic/payam/common/consumer/Consumer.java` imports `common.validation.PhoneNumber` — excluded from grep because grep excluded `src/main/java/com/softropic/payam/common/` paths (since common/* files are the SOURCE of this wave, the grep excluded them)
- **Fix:** Updated import to `infrastructure.validation.PhoneNumber`
- **Files modified:** `src/main/java/com/softropic/payam/common/consumer/Consumer.java`
- **Verification:** Zero stale `common.validation.*` references confirmed
- **Committed in:** `2148fb7` (part of atomic task commit)

**3. [Rule 1 - Bug] JwtManagerImplTest.java + LoginInfoServiceIT.java import old TestClockProvider path**
- **Found during:** Task 1, Step K (mvn test-compile)
- **Issue:** Two test files outside `common/` imported `com.softropic.payam.common.TestClockProvider` — these weren't captured by the external caller grep (which searched for `common.{client,threadpool,util,validation,dto}` and root utility patterns, not `TestClockProvider`)
- **Fix:** Updated both files to import `com.softropic.payam.infrastructure.util.TestClockProvider`
- **Files modified:** `src/test/java/com/softropic/payam/platform/security/infrastructure/jwt/JwtManagerImplTest.java`, `src/test/java/com/softropic/payam/platform/security/service/LoginInfoServiceIT.java`
- **Verification:** `mvn test-compile` exits 0
- **Committed in:** `2148fb7` (part of atomic task commit)

---

**Total deviations:** 3 auto-fixed (3x Rule 1 - files missed by initial grep sweep)
**Impact on plan:** All auto-fixes necessary for correctness. No scope creep. Root cause: grep excluded `common/` paths (intentional to skip the SOURCE files being moved) but this also excluded test utilities and interface files that live in common/ but import from the moved subpackages.

## Issues Encountered

- Pre-flight revealed worktree was 15 commits behind `main` (Plan 01 and Phase 64 work not merged). Resolved via `git merge main` fast-forward before starting Plan 02 execution.

## Known Stubs

None — pure refactoring plan, no data stubs introduced.

## Next Phase Readiness

- `infrastructure.{client,threadpool,util,validation}` packages fully populated and all callers updated
- `common/` contains only: `payment/`, `refund/`, `enums/`, `consumer/`, `Gender.java`
- Plan 03 ready to execute: move `common.payment.*` to `payment.core.contract` / `payment.ledger.contract`
- Plan 04 ready to execute: move `common.consumer`, `common.enums`, `common.Gender` to their owning domain packages
- No blockers

---
*Phase: 65-common-package-redistribution*
*Completed: 2026-05-12*
