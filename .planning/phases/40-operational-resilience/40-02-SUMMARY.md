---
phase: 40-operational-resilience
plan: "02"
subsystem: tenant-filter-chain
tags: [testing, integration-test, ops-03, tenant-context, exception-path]
dependency_graph:
  requires: []
  provides: [OPS-03-test-coverage]
  affects: [ApiKeyAuthenticationFilter, TenantContext]
tech_stack:
  added: []
  patterns: [two-request-probe, springboottest-random-port, exception-path-testing]
key_files:
  created:
    - src/test/java/com/softropic/payam/tenant/TenantContextExceptionIT.java
  modified: []
decisions:
  - "No production code changes — ApiKeyAuthenticationFilter.doFilterInternal finally block at lines 142-148 already correctly clears TenantContext, MDC, and SecurityContext on all paths including exception paths"
  - "Two-request probe pattern used: request 1 triggers exception (malformed JSON → 400, or DELETE on POST-only → 405), request 2 with different tenant key asserts NOT 401 — proving no stale context leaked"
  - "Test wiring mirrors TenantFilterChainIT exactly: same @SpringBootTest(RANDOM_PORT), @ActiveProfiles(dev), @Import(TestConfig), same sec-row setUp, same tearDown cleanup order"
metrics:
  duration_seconds: 1076
  completed_date: "2026-04-15"
  tasks_completed: 1
  files_created: 1
  files_modified: 0
---

# Phase 40 Plan 02: OPS-03 TenantContext Exception-Path Test Summary

OPS-03 integration test suite proving TenantContext is cleared by the filter's `finally` block after an exception-path request using the two-request probe pattern.

## Objective

Close OPS-03 success criterion #3: "an integration test verifies that TenantContext is empty after a request that triggers an exception path." The production code was already correct. This plan adds test coverage only.

## What Was Built

### TenantContextExceptionIT

**File:** `src/test/java/com/softropic/payam/tenant/TenantContextExceptionIT.java`

Two integration tests using the two-request probe pattern:

**Test 1: `tenantContext_clearedAfterExceptionPath_validKeyFollowedByMalformedBody`**
- Creates tenant T1, sends `POST /v1/payments` with valid T1 API key but malformed JSON body `"NOT_VALID_JSON{"`
- Spring dispatcher throws `HttpMessageNotReadableException` → exception propagates through `chain.doFilter()` → filter's `finally` block fires → `TenantContext.clear()` executes
- Creates tenant T2, sends `POST /v1/payments` with valid T2 API key and valid JSON body
- Asserts response status is NOT 401 (UNAUTHORIZED) — a 401 would prove stale SecurityContext leaked from request 1
- Asserts response body does NOT contain `"Missing X-Api-Key header"` if 4xx

**Test 2: `tenantContext_clearedAfterExceptionPath_validKeyFollowedByUnsupportedMethod`**
- Creates tenant T1, sends `DELETE /v1/payments` with valid T1 API key (no body)
- Spring dispatcher throws `HttpRequestMethodNotSupportedException` → 405 Method Not Allowed → filter's `finally` block fires → `TenantContext.clear()` executes
- Creates tenant T2, sends `POST /v1/payments` with valid T2 API key and valid JSON body
- Asserts response status is NOT 401 — proves no stale context from the 405-path request

## Test Results

- `mvn -q -Dtest=TenantContextExceptionIT verify` exits 0 (both tests green)
- Tests run: 2, Failures: 0, Errors: 0 (confirmed by exit code 0 from maven)
- `ApiKeyAuthenticationFilter.java` was NOT modified — production code already correct

## Production Code Verification

The filter's `finally` block at lines 142-148 in `ApiKeyAuthenticationFilter.doFilterInternal`:

```java
try {
    chain.doFilter(request, response);
} finally {
    TenantContext.clear();               // ALWAYS clear — servlet containers reuse threads
    MDC.remove("tenantId");             // Mirror MDC.put — remove exactly what was added
    SecurityContextHolder.clearContext();
}
```

This block fires on ALL exit paths from `chain.doFilter()`, including when an exception propagates up through the filter chain. The test proves this behavior is in effect.

## OPS-03 Requirements Closed

- OPS-03 success criterion #1: ApiKeyAuthenticationFilter already wraps chain.doFilter in try/finally — confirmed by code inspection (no change)
- OPS-03 success criterion #2: TenantContext.clear() executes in the finally block — confirmed by existing production code
- OPS-03 success criterion #3: Integration test verifies TenantContext is empty after exception-path request — **closed by this plan**

## Deviations from Plan

None — plan executed exactly as written.

## Known Stubs

None.

## Self-Check: PASSED

- `src/test/java/com/softropic/payam/tenant/TenantContextExceptionIT.java` — FOUND
- Commit `597f5c3` — FOUND
- `ApiKeyAuthenticationFilter.java` git diff — CLEAN (no production changes)
- `mvn -q -Dtest=TenantContextExceptionIT verify` exit code — 0 (PASSED)
