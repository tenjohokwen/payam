---
phase: 18-test-infrastructure
plan: 01
subsystem: testing
tags: [junit5, spring-boot-test, wiremock, testcontainers, template-method, e2e]

# Dependency graph
requires: []
provides:
  - Abstract E2E base class hierarchy (4 classes) in com.softropic.payam.e2e
  - AbstractPayamE2ETest: root base with @SpringBootTest, @EnableWireMock, Redis flush, circuit breaker reset
  - AbstractPaymentFlowTest: four-phase template (setupPreconditions/executeFlow/simulateProviderCallback/verifyFinalState)
  - AbstractWebhookFlowTest: webhook specialisation sealing simulateProviderCallback/verifyFinalState, adding 3 abstract hooks
  - AbstractFailureFlowTest: failure-injection template (setupPreconditions/injectFault/executeFlow/verifyFailureHandled)
affects:
  - 18-02 (config classes that AbstractPayamE2ETest imports)
  - 20-payment-flow-tests (extends AbstractPaymentFlowTest or AbstractWebhookFlowTest)
  - 21-webhook-flow-tests (extends AbstractWebhookFlowTest)
  - 22-failure-flow-tests (extends AbstractFailureFlowTest)
  - 23-reconciliation-tests (extends AbstractPayamE2ETest or AbstractFailureFlowTest)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - Template method pattern enforcing four-phase E2E flow via final orchestrator methods
    - Abstract class hierarchy: AbstractPayamE2ETest -> AbstractPaymentFlowTest -> AbstractWebhookFlowTest (and AbstractPayamE2ETest -> AbstractFailureFlowTest)
    - stubTokenEndpoints() hook: overrideable default that stubs both provider token endpoints

key-files:
  created:
    - src/test/java/com/softropic/payam/e2e/AbstractPayamE2ETest.java
    - src/test/java/com/softropic/payam/e2e/AbstractPaymentFlowTest.java
    - src/test/java/com/softropic/payam/e2e/AbstractWebhookFlowTest.java
    - src/test/java/com/softropic/payam/e2e/AbstractFailureFlowTest.java
  modified: []

key-decisions:
  - "AbstractFailureFlowTest extends AbstractPayamE2ETest directly (not AbstractPaymentFlowTest) — failure flows inject faults before executeFlow, a different phase structure from normal payment flows"
  - "stubTokenEndpoints() is protected and overrideable — default stubs both mtn and orange token endpoints using WireMockConfig constants; circuit-breaker tests can override to re-stub after Redis flush"
  - "final on runFlow()/runFailureScenario() is mandatory — prevents subclasses from accidentally overriding the orchestration phase order"

patterns-established:
  - "Template method pattern: final @Test method calls abstract protected phases in sequence — subclasses implement phases, never override the test entry point"
  - "AbstractWebhookFlowTest sealing: simulateProviderCallback() and verifyFinalState() are final overrides, replaced by three more granular abstract hooks (dispatchInboundWebhook, verifyDoubleCheckTriggered, verifyTransactionState)"

# Metrics
duration: 3min
completed: 2026-03-27
---

# Phase 18 Plan 01: E2E Abstract Base Class Hierarchy Summary

**Four abstract base classes establishing the structural skeleton for all v3 E2E tests using the template method pattern with final orchestrator methods.**

## Performance

- **Duration:** 3 min
- **Started:** 2026-03-27T12:03:46Z
- **Completed:** 2026-03-27T12:06:20Z
- **Tasks:** 2
- **Files modified:** 4 created

## Accomplishments
- Created the `com.softropic.payam.e2e` package with four abstract base classes
- AbstractPayamE2ETest provides full test infrastructure wiring — WireMock servers, Redis flush, circuit breaker reset, token stub defaults
- AbstractPaymentFlowTest and AbstractFailureFlowTest enforce their respective four-phase flow contracts via `final` test methods
- AbstractWebhookFlowTest adds webhook-specific hooks while sealing the two inherited phases as final

## Task Commits

Each task was committed atomically:

1. **Task 1: AbstractPayamE2ETest — root E2E base class** - `acd861b` (feat)
2. **Task 2: AbstractPaymentFlowTest, AbstractWebhookFlowTest, AbstractFailureFlowTest** - `d07d606` (feat)

**Plan metadata:** (docs commit — see below)

## Files Created/Modified
- `src/test/java/com/softropic/payam/e2e/AbstractPayamE2ETest.java` - Root base class with Spring wiring annotations, 6 protected fields, baseSetUp/baseTearDown lifecycle, overrideable stubTokenEndpoints()
- `src/test/java/com/softropic/payam/e2e/AbstractPaymentFlowTest.java` - Four-phase template: final runFlow() orchestrates 4 abstract methods
- `src/test/java/com/softropic/payam/e2e/AbstractWebhookFlowTest.java` - Webhook specialisation extending AbstractPaymentFlowTest, seals 2 phases, adds 3 abstract hooks
- `src/test/java/com/softropic/payam/e2e/AbstractFailureFlowTest.java` - Failure injection template: final runFailureScenario() orchestrates 4 abstract methods

## Decisions Made
- AbstractFailureFlowTest extends AbstractPayamE2ETest directly rather than AbstractPaymentFlowTest because failure flows inject faults before execution (not after callback) — different phase structure requiring a separate hierarchy branch
- `stubTokenEndpoints()` is protected and overrideable to support circuit-breaker tests that flush Redis (clearing token cache) and need to re-stub token endpoints mid-test
- `final` on both orchestrator methods (`runFlow()`, `runFailureScenario()`) is mandatory per plan spec to prevent structural divergence in subclasses

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None. Compilation errors are only for the plan-18-02 config classes (E2ESecurityConfig, PostgresContainerConfig, RedisContainerConfig, TestClockConfig, TestDataCleaner, WireMockConfig) that don't exist yet — expected per plan spec.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Four abstract base classes ready for plan 18-02 to create the config classes they import
- Once plan 18-02 completes, all four base classes will compile cleanly
- E2E test classes in phases 20-23 can then extend these bases immediately

---
*Phase: 18-test-infrastructure*
*Completed: 2026-03-27*
