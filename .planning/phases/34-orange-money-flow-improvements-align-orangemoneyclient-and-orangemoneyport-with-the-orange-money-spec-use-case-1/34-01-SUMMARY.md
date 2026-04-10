---
phase: 34-orange-money-flow-improvements-align-orangemoneyclient-and-orangemoneyport-with-the-orange-money-spec-use-case-1
plan: "01"
subsystem: payments
tags: [orange-money, payment-command, payment-request, platform-config, domain]

# Dependency graph
requires:
  - phase: 03-orange-money-adapter
    provides: OrangeMoneyPort and PaymentCommand used by the adapter
  - phase: 24-platform-configuration
    provides: PlatformConfigService and PlatformConfigRepository
provides:
  - nullable description field in PaymentRequest (API surface)
  - nullable description field in PaymentCommand (domain command)
  - PaymentOrchestrator passes description through to adapter
  - PlatformConfigService.findByProvider(String) for adapter MSISDN lookup
  - PaymentRequestBuilder.withDescription() for test builders
affects: [34-02, orange-adapter, payment-orchestration]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Nullable optional fields appended LAST in records for backward-compatible evolution"
    - "PlatformConfigService.findByProvider uses provider.toUpperCase() normalization + orElseThrow IllegalStateException"

key-files:
  created: []
  modified:
    - src/main/java/com/softropic/payam/payment/contract/PaymentRequest.java
    - src/main/java/com/softropic/payam/common/payment/PaymentCommand.java
    - src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java
    - src/main/java/com/softropic/payam/platform/service/PlatformConfigService.java
    - src/test/java/com/softropic/payam/platform/service/PlatformConfigServiceTest.java
    - src/test/java/com/softropic/payam/e2e/builder/PaymentRequestBuilder.java
    - src/test/java/com/softropic/payam/fraud/FraudScoringServiceIT.java
    - src/test/java/com/softropic/payam/mtn/MtnMoMoPortIT.java
    - src/test/java/com/softropic/payam/orange/OrangeMoneyPortIT.java
    - src/test/java/com/softropic/payam/domain/FraudThresholdGuardTest.java

key-decisions:
  - "description field appended last in both PaymentRequest and PaymentCommand records — backward-compatible field addition"
  - "findByProvider uses IllegalStateException (not NotFoundException) — consistent with existing PlatformConfigService error pattern"

patterns-established:
  - "Record field extension: always append nullable optional fields LAST to avoid breaking all construction sites"

requirements-completed: []

# Metrics
duration: 15min
completed: 2026-04-10
---

# Phase 34 Plan 01: Core domain changes — description field + PlatformConfigService.findByProvider() Summary

**Nullable `description` field threaded from `PaymentRequest` through `PaymentCommand` to orchestrator, plus `PlatformConfigService.findByProvider()` for Orange adapter MSISDN lookup — pure plumbing, zero behaviour change**

## Performance

- **Duration:** 15 min
- **Started:** 2026-04-10T10:40:00Z
- **Completed:** 2026-04-10T10:55:00Z
- **Tasks:** 7 steps (executed as single atomic commit)
- **Files modified:** 10

## Accomplishments
- `PaymentRequest` record extended with nullable `String description` field (appended last, no validation)
- `PaymentCommand` record extended with nullable `String description` field (appended last)
- `PaymentOrchestrator.initiate()` passes `request.description()` as 13th argument to `PaymentCommand`
- `PlatformConfigService.findByProvider(String)` added with `@Transactional(readOnly = true)` and `IllegalStateException` on missing provider
- 2 new unit tests added to `PlatformConfigServiceTest` (happy path + not-found path) — all 4 tests pass
- `PaymentRequestBuilder` updated with `description` field, `withDescription()` setter, and 7-arg `build()`
- 8 `PaymentCommand` construction sites in test code fixed (4 in OrangeMoneyPortIT, 1 in MtnMoMoPortIT, 1 in FraudScoringServiceIT, 2 in FraudThresholdGuardTest) — all gain `null` as 13th arg
- `mvn test-compile` and targeted unit tests pass with zero errors

## Task Commits

Each task was committed atomically:

1. **All 7 steps: description field + findByProvider + call-site fixes** - `79c4dbd` (feat)

## Files Created/Modified
- `src/main/java/com/softropic/payam/payment/contract/PaymentRequest.java` - Added nullable `description` field after `deviceFingerprint`
- `src/main/java/com/softropic/payam/common/payment/PaymentCommand.java` - Added nullable `description` field after `deviceFingerprint`
- `src/main/java/com/softropic/payam/payment/service/PaymentOrchestrator.java` - Pass `request.description()` as 13th arg to `PaymentCommand`
- `src/main/java/com/softropic/payam/platform/service/PlatformConfigService.java` - Added `findByProvider(String)` method
- `src/test/java/com/softropic/payam/platform/service/PlatformConfigServiceTest.java` - Added 2 new test methods for `findByProvider`
- `src/test/java/com/softropic/payam/e2e/builder/PaymentRequestBuilder.java` - Added `description` field, `withDescription()`, 7-arg `build()`
- `src/test/java/com/softropic/payam/fraud/FraudScoringServiceIT.java` - Fixed `buildCommand()` to pass `null` as 13th arg
- `src/test/java/com/softropic/payam/mtn/MtnMoMoPortIT.java` - Fixed 1 `PaymentCommand` construction site
- `src/test/java/com/softropic/payam/orange/OrangeMoneyPortIT.java` - Fixed 4 `PaymentCommand` construction sites
- `src/test/java/com/softropic/payam/domain/FraudThresholdGuardTest.java` - Fixed 2 `PaymentCommand` construction sites

## Decisions Made
- `description` field appended last in both records — backward-compatible evolution without touching all existing constructor call sites
- `findByProvider` uses `IllegalStateException` on missing provider — consistent with existing PlatformConfigService error contract

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Plan 34-02 can now wire `cmd.description()` and `findByProvider("ORANGE")` into the Orange adapter (`OrangeMoneyClient` / `OrangeMoneyPort`)
- No compile errors; `OrangeMoneyPortIT` and `MtnMoMoPortIT` integration tests are not broken (WireMock stub issues expected until 34-02)

---
*Phase: 34-orange-money-flow-improvements*
*Completed: 2026-04-10*
