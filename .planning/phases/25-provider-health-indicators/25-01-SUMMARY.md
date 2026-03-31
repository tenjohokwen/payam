---
phase: 25-provider-health-indicators
plan: 01
subsystem: actuator
tags: [spring-actuator, health-indicator, resilience4j, circuit-breaker, platform-health]

# Dependency graph
requires:
  - phase: 24-01
    provides: PlatformConfigService.findAll() returning List<PlatformConfigDto> with provider MSISDNs
  - phase: orange-adapter
    provides: OrangeMoneyPort.validateSubscriber(String msisdn) — no @CircuitBreaker
  - phase: mtn-adapter
    provides: MtnMoMoPort.validateSubscriber(String msisdn) — no @CircuitBreaker
  - phase: admin-dashboard
    provides: CircuitBreakerRegistry bean and force-create pattern (from ProviderStatusResource)
provides:
  - OrangePlatformHealthIndicator @Component — appears as 'orangePlatform' in /manage/health
  - MtnPlatformHealthIndicator @Component — appears as 'mtnPlatform' in /manage/health
  - HLTH-01, HLTH-02, HLTH-03, HLTH-04, HLTH-05 requirements satisfied
affects:
  - Phase 26 (health dashboard UI will call GET /manage/health to display results)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "HealthIndicator @Component — Spring Boot auto-discovers and registers as health component; no explicit bean registration needed"
    - "Inject concrete port type (OrangeMoneyPort, not MobileMoneyPort) — two beans implement interface; concrete injection avoids @Qualifier"
    - "circuitBreakerRegistry.circuitBreaker('name') force-create pattern — matches ProviderStatusResource; prevents empty state on fresh boot"
    - "isBlank() guard before provider call — platformMsisdn seeded as '' by V17 migration; blank triggers DOWN with descriptive reason"
    - "No @CircuitBreaker on health() — health traffic must not affect payment CB failure rate"

key-files:
  created:
    - src/main/java/com/softropic/payam/health/OrangePlatformHealthIndicator.java
    - src/main/java/com/softropic/payam/health/MtnPlatformHealthIndicator.java

key-decisions:
  - "[25-01] New package com.softropic.payam.health — cross-cutting actuator concern; not co-located with orange/mtn provider packages"
  - "[25-01] Concrete port injection (OrangeMoneyPort, MtnMoMoPort) — avoids NoUniqueBeanDefinitionException from two MobileMoneyPort impls"
  - "[25-01] No @CircuitBreaker on health() — health poll traffic must not degrade payment CB failure rate counters"
  - "[25-01] isBlank() (not isEmpty()) — defensive; catches whitespace-only strings as well as empty"

patterns-established:
  - "Platform health indicator pattern: inject port by concrete type + PlatformConfigService + CircuitBreakerRegistry; isBlank guard; try/catch Exception; CB state always in details"

# Metrics
duration: 1min
completed: 2026-03-31
---

# Phase 25 Plan 01: Provider Health Indicators Summary

**Two Spring Boot Actuator HealthIndicator beans wired to validateSubscriber() + circuit breaker state for /manage/health**

## Performance

- **Duration:** 1 min
- **Completed:** 2026-03-31
- **Tasks:** 1 (+ human-verify checkpoint — approved)
- **Files created:** 2

## Accomplishments

- `OrangePlatformHealthIndicator` and `MtnPlatformHealthIndicator` registered as `@Component` beans
- Spring Boot auto-discovers both; health keys are `orangePlatform` and `mtnPlatform`
- Each indicator: reads platform MSISDN from `PlatformConfigService.findAll()`, returns DOWN if blank, calls `validateSubscriber()`, includes `circuitBreaker` detail from `CircuitBreakerRegistry`
- Human verification approved: both components visible in `/manage/health` with CB detail
- All 5 HLTH requirements (HLTH-01 through HLTH-05) satisfied

## Task Commits

1. **Task 1: OrangePlatformHealthIndicator + MtnPlatformHealthIndicator** - `ebd35e0` (feat)

## Files Created

- `src/main/java/com/softropic/payam/health/OrangePlatformHealthIndicator.java` — validates Orange platform MSISDN; CB name "orange"
- `src/main/java/com/softropic/payam/health/MtnPlatformHealthIndicator.java` — validates MTN platform MSISDN; CB name "mtn"

## Decisions Made

1. **New `com.softropic.payam.health` package** — health indicators are cross-cutting actuator infrastructure, not provider-domain code. Placing them in `orange.service` or `mtn.service` would mix concerns.

2. **Inject concrete port types** — `OrangeMoneyPort` and `MtnMoMoPort` both implement `MobileMoneyPort`. Injecting the interface without `@Qualifier` throws `NoUniqueBeanDefinitionException`. Concrete injection is simpler and correct here.

3. **No `@CircuitBreaker` on `health()`** — health poll traffic must not count as failures against the payment circuit breaker. If the provider is down, `validateSubscriber()` throws and the indicator returns DOWN, but the CB failure rate is unchanged.

4. **CB state in details regardless of validation outcome** — an operator calling `/manage/health` needs the CB state even when the MSISDN is blank (unconfigured), so `circuitBreaker` is always included.

## Deviations from Plan

None.

## Issues Encountered

None — `mvn compile` succeeded with zero errors on first attempt.

## Phase 25 Complete

HLTH-01 ✅ — Orange platform MSISDN validated via `OrangeMoneyPort.validateSubscriber()` on every poll
HLTH-02 ✅ — MTN platform MSISDN validated via `MtnMoMoPort.validateSubscriber()` on every poll
HLTH-03 ✅ — Health DOWN when either MSISDN blank, inactive, or provider throws
HLTH-04 ✅ — Orange circuit breaker state in `orangePlatform` component detail
HLTH-05 ✅ — MTN circuit breaker state in `mtnPlatform` component detail

---
*Phase: 25-provider-health-indicators*
*Completed: 2026-03-31*
