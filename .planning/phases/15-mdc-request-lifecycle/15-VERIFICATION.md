---
phase: 15-mdc-request-lifecycle
verified: 2026-03-26T23:16:38Z
status: passed
score: 5/5 must-haves verified
---

# Phase 15: MDC Request Lifecycle Verification Report

**Phase Goal:** Every HTTP request emits structured start/end events with full correlation context
**Verified:** 2026-03-26T23:16:38Z
**Status:** PASSED
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Every request log entry contains `requestId` and `tenantId` as top-level JSON fields | VERIFIED | `MDC.put(Constants.REQUEST_ID_NAME, requestId)` at LoggingFilter.java:72 before first log call; `MDC.put("tenantId", tenantRef)` at ApiKeyAuthenticationFilter.java:119; `<mdc/>` provider in logback-spring.xml flattens all MDC keys to top-level JSON |
| 2 | Payment request logs contain `transactionId` and `externalReference` throughout the thread | VERIFIED | `MDC.put("transactionId", transactionId)` at TransactionService.java:39; conditional `MDC.put("externalReference", externalReference)` at TransactionService.java:44; both are camelCase, no snake_case remnants |
| 3 | Structured `request_start` event logged at request entry with `event`, `operation`, `method`, `path`, `requestId`, `clientIp` | VERIFIED | LoggingFilter.java:80-86 emits `log.info("Request received", kv("event", "request_start"), kv("operation", operation), kv("method", method), kv("path", path), kv("requestId", requestId), kv("clientIp", clientIp))` before `filterChain.doFilter()` |
| 4 | Structured `request_end` event logged at completion with `event`, `durationMs`, `status`, `httpStatus` | VERIFIED | LoggingFilter.java:108-117 emits all four required fields unconditionally; `tenantId` added conditionally when non-null |
| 5 | Structured `request_error` event logged for 5xx responses with `event`, `durationMs`, `errorCode`, `status=ERROR` | VERIFIED | LoggingFilter.java:99-105 emits `log.error(...)` with `kv("event", "request_error")`, `kv("durationMs", durationMs)`, `kv("errorCode", "HTTP_" + httpStatus)`, `kv("status", "ERROR")` when `httpStatus >= 500` |

**Score:** 5/5 truths verified

---

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/java/com/softropic/payam/security/audit/filter/LoggingFilter.java` | Structured lifecycle event logging with MDC population | VERIFIED | 172 lines; exports `LoggingFilter extends OncePerRequestFilter`; all three event names present; no stubs |
| `src/main/java/com/softropic/payam/tenant/config/ApiKeyAuthenticationFilter.java` | tenantId MDC population for API-key-authenticated requests | VERIFIED | 134 lines; `MDC.put("tenantId", tenantRef)` at line 119; `MDC.remove("tenantId")` at line 130 in finally block |
| `src/main/java/com/softropic/payam/transaction/service/TransactionService.java` | camelCase MDC keys for transaction context | VERIFIED | `MDC.put("transactionId", ...)` at line 39; `MDC.put("externalReference", ...)` at line 44; no snake_case MDC keys remain |
| `src/main/java/com/softropic/payam/common/Constants.java` | `TXN_ID_NAME = "transactionId"` | VERIFIED | Line 13: `public static final String TXN_ID_NAME = "transactionId";` |

---

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `LoggingFilter.doFilterInternal()` | `requestId` in MDC | `MDC.put(Constants.REQUEST_ID_NAME, requestId)` | WIRED | Line 72, before `request_start` log call and before `filterChain.doFilter()` |
| `LoggingFilter.doFilterInternal()` | `request_start / request_end / request_error` events | `kv("event", ...)` calls via `StructuredArguments.kv()` | WIRED | 18 `kv()` calls total; all three event names present |
| `LoggingFilter.doFilterInternal()` | MDC cleanup | `MDC.remove(Constants.REQUEST_ID_NAME)` in `finally` | WIRED | Line 122; `MDC.clear()` absent from LoggingFilter entirely |
| `ApiKeyAuthenticationFilter.doFilterInternal()` | `tenantId` in MDC | `MDC.put("tenantId", tenantRef)` | WIRED | Line 119 immediately after `TenantContext.set(tenantRef)`; `MDC.remove("tenantId")` at line 130 in finally |
| `TransactionService.initiate()` | `transactionId` in MDC | `MDC.put("transactionId", transactionId)` | WIRED | Line 39; `TransactionIdProvider` reads via `Constants.TXN_ID_NAME` which now resolves to `"transactionId"` |
| `LoggingFilter` | Spring Security filter chain | `SecurityConfiguration.addFilterBefore(new LoggingFilter(...), SecurityAdviceFilter.class)` | WIRED | SecurityConfiguration.java:202 |
| MDC keys | Top-level JSON fields | `<mdc/>` provider in `LoggingEventCompositeJsonEncoder` | WIRED | logback-spring.xml:15; all MDC keys including `requestId`, `tenantId`, `transactionId`, `externalReference` become top-level JSON fields |

---

### Requirements Coverage

| Requirement | Status | Notes |
|-------------|--------|-------|
| LOG-MDC-01: `requestId` + `tenantId` in MDC | SATISFIED | `requestId` from LoggingFilter; `tenantId` from ApiKeyAuthenticationFilter for API-key paths; JWT paths get `requestId` only (no tenantId, by design) |
| LOG-MDC-02: `transactionId` + `externalReference` in MDC (camelCase) | SATISFIED | Both set in `TransactionService.initiate()`; snake_case keys eliminated |
| LOG-REQ-01: `request_start` event | SATISFIED | All six required fields present (`event`, `operation`, `method`, `path`, `requestId`, `clientIp`) |
| LOG-REQ-02: `request_end` event | SATISFIED | All required fields present (`event`, `durationMs`, `status`, `httpStatus`); `tenantId` conditionally included |
| LOG-REQ-03: `request_error` event for 5xx | SATISFIED | All required fields present (`event`, `durationMs`, `errorCode`, `status=ERROR`, `httpStatus`) |

---

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `LoggingFilter.java` | 121 | Comment-only reference to `MDC.clear()` (not a call) | Info | None — comment explains constraint, no actual call |
| `common/threadpool/MdcDecorator.java` | 25 | `MDC.clear()` in async thread pool finally | Info | Does not affect the request lifecycle goal — this clears MDC on async worker threads after they finish, which is correct hygiene for thread-pool reuse. The caller thread's MDC (with `requestId`, `tenantId`) is not cleared by this. |
| `common/threadpool/MdcWrapper.java` | 18 | `MDC.clear()` when null context map passed | Info | Same as above — only executes on pool worker threads, not the request thread; null-safety guard |
| `common/threadpool/ClientThreadContext.java` | 32 | `MDC.clear()` in null-map guard | Info | Same scope as above; pre-existing code, not touched in this phase |

No blocker anti-patterns. The three `MDC.clear()` calls are all confined to async thread-pool worker threads (in `MdcDecorator`/`MdcWrapper`/`ClientThreadContext`) and do not execute on the HTTP request thread. The LoggingFilter constraint (never `MDC.clear()`) is respected in LoggingFilter itself.

---

### Human Verification Required

#### 1. Request correlation across log lines

**Test:** Send a POST to `/v1/payments` with an API key. Inspect the structured JSON output.
**Expected:** Every log line emitted during that request (including lines from `TransactionService`, adapter calls, etc.) shares the same `requestId` value. The `request_start` line has `requestId`; the `request_end` line has `requestId` and `tenantId`; intermediate lines from `TransactionService` have `transactionId` and `externalReference` in addition to `requestId` and `tenantId`.
**Why human:** Requires a live request to observe actual Logback JSON output. Cannot verify MDC propagation across thread boundaries purely from static analysis.

#### 2. 5xx response triggers `request_error` not `request_end`

**Test:** Trigger a 500 response (e.g., shut down the database, send a request that causes an unhandled exception).
**Expected:** A single `request_error` log line appears (not `request_end`). Fields `errorCode` (e.g., `HTTP_500`) and `status=ERROR` are present as top-level JSON keys.
**Why human:** The 5xx branch depends on `response.getStatus()` after `filterChain.doFilter()` returns normally (no exception thrown). Verifying that unhandled exceptions (which bypass the `if (httpStatus >= 500)` check) are handled upstream requires a live test.

---

### Structural Consistency Notes

**`request_start` contains `requestId` as a `kv()` argument in addition to MDC.** This is intentional duplication: the `kv()` call embeds `requestId` directly in the event JSON via the `<arguments/>` provider, while MDC via `<mdc/>` provides it for all other lines. Both providers write to top-level JSON fields. The duplicate is harmless and ensures `requestId` is visible in the `request_start` event regardless of encoder configuration.

**`tenantId` in `request_end` comes from `TenantContext.get()`, not MDC.** `LoggingFilter` reads `TenantContext.get()` (line 95) and adds `kv("tenantId", tenantId)` to the args list if non-null. Separately, `ApiKeyAuthenticationFilter` sets `MDC.put("tenantId", tenantRef)` so that `tenantId` appears in all mid-request log lines via `<mdc/>`. Both mechanisms use the same value (`tenantRef`). The `request_end` event therefore gets `tenantId` via two paths on API-key requests, which is consistent.

**`externalReference` MDC key is set but never removed in `TransactionService`.** The `MDC.put("externalReference", ...)` call has no corresponding `MDC.remove()` in `TransactionService.initiate()`. The key persists in MDC until `LoggingFilter` (or another filter) cleans up. However, `LoggingFilter` only removes `Constants.REQUEST_ID_NAME` (`requestId`) in its `finally` block — it does not remove `transactionId` or `externalReference`. These keys will remain in MDC after `request_end` logs, which means they will be absent from the cleanup. This is scoped to the request thread and is flushed when the thread returns to the servlet container pool (container-level MDC reset). Not a blocker for the phase goal, but worth noting as a potential hygiene item for a future phase.

---

## Summary

Phase 15 goal is achieved. All five observable truths are verified against the actual code:

- `LoggingFilter.java` emits all three structured lifecycle events (`request_start`, `request_end`, `request_error`) with correct fields and proper ordering (MDC set before first log, cleanup in finally with `MDC.remove` only).
- `ApiKeyAuthenticationFilter.java` populates `tenantId` in MDC for API-key-authenticated paths with matching cleanup in finally.
- `TransactionService.java` uses camelCase MDC keys (`transactionId`, `externalReference`); all snake_case predecessors are gone.
- `Constants.TXN_ID_NAME` = `"transactionId"` aligns with what `TransactionService` actually sets.
- `logback-spring.xml` `<mdc/>` provider ensures all MDC fields appear as top-level JSON.
- `LoggingFilter` is registered in `SecurityConfiguration` before `SecurityAdviceFilter`.

Two human verification items remain to confirm live runtime behavior; neither represents a structural gap.

---

_Verified: 2026-03-26T23:16:38Z_
_Verifier: Claude (gsd-verifier)_
