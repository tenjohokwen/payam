# Phase 15: MDC & Request Lifecycle - Research

**Researched:** 2026-03-26
**Domain:** SLF4J MDC, servlet filter ordering, structured logging with logstash-logback-encoder
**Confidence:** HIGH

---

## Summary

Phase 15 wires correlation context into every log line and emits structured lifecycle events
(`request_start`, `request_end`, `request_error`) for every HTTP request. The work is entirely
Java code — no configuration changes to `logback-spring.xml` are needed because Phase 14 already
deployed `LoggingEventCompositeJsonEncoder` with the `<mdc/>` provider, which flattens every
MDC key into top-level JSON fields automatically.

The codebase already has an existing `LoggingFilter` at
`security/audit/filter/LoggingFilter.java`, but it logs raw header dumps and response bodies in a
non-structured, non-MDC-aware way. This filter must be **replaced** — not extended — with a new
implementation that: (1) populates MDC before the first log fires, (2) emits the three structured
lifecycle events, and (3) clears MDC after the response is committed.

Tenant identity (`requestId`, `tenantId`) is available from two already-existing sources:
`RequestIdProvider` (reads from `Constants.REQUEST_ID_NAME` = `"requestId"` in MDC) and
`TenantContext` (thread-local `String tenantRef`). The `ApiKeyAuthenticationFilter` sets both
before the filter chain continues. Transaction context (`transactionId`, `externalReference`)
is set in `TransactionService.initiate()` via explicit `MDC.put()` calls using keys
`"transaction_id"` and `"external_reference"`.

**Primary recommendation:** Rewrite `LoggingFilter` in-place. Add MDC population at request
entry (before the chain proceeds), and replace the existing after-request string-dump with the
three structured lifecycle events using `StructuredArguments.kv()`.

---

## Standard Stack

### Core (all already present in pom.xml)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `net.logstash.logback:logstash-logback-encoder` | 8.1 | `StructuredArguments.kv()` for log-line key-value pairs; `entries()` for map args | The canonical way to emit structured fields that appear as top-level JSON via `<arguments/>` provider |
| `org.slf4j:slf4j-api` | managed by Spring Boot 3.5.x | `MDC.put()` / `MDC.remove()` / `MDC.clear()` | Single MDC API — all backed by Logback in this stack |
| `org.springframework:spring-web` | managed by Spring Boot 3.5.x | `OncePerRequestFilter`, `ContentCachingRequestWrapper` / `ContentCachingResponseWrapper` | Servlet filter base class already used by `LoggingFilter` |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `jakarta.servlet:jakarta.servlet-api` | managed | `HttpServletRequest`, `HttpServletResponse` | Already imported throughout the filter layer |

### No New Dependencies

Everything required is already declared. No `pom.xml` changes for Phase 15.

---

## Architecture Patterns

### Recommended Project Structure

No new packages needed. Changes land in existing packages:

```
src/main/java/com/softropic/payam/
├── security/
│   ├── audit/
│   │   └── filter/
│   │       └── LoggingFilter.java          # REWRITE — MDC + lifecycle events
│   └── common/
│       └── util/
│           ├── RequestIdProvider.java       # READ ONLY — already sets MDC "requestId"
│           └── TenantContext.java           # READ ONLY — already holds tenantRef
└── transaction/
    └── service/
        └── TransactionService.java          # READ ONLY — already sets MDC "transaction_id" etc.
```

### Pattern 1: MDC Lifecycle in a Servlet Filter

**What:** A `OncePerRequestFilter` that wraps the entire chain execution in a
try/finally block. MDC is populated before `filterChain.doFilter()` and cleared
in the `finally` block.

**When to use:** Any cross-cutting context that must appear in all log lines during
request processing.

**Key constraint:** The existing `LoggingFilter` already lives as a `OncePerRequestFilter`
and is already registered in `SecurityConfiguration` via
`.addFilterBefore(new LoggingFilter(PUBLIC_STATIC_RESOURCES), SecurityAdviceFilter.class)`.
This means `LoggingFilter` runs **before** `SecurityAdviceFilter`, which means it runs before
`ApiKeyAuthenticationFilter` sets `TenantContext`. See "Filter Order" section below for how
to handle this.

**Template:**
```java
// Source: codebase pattern from existing OncePerRequestFilter implementations
@Override
protected void doFilterInternal(HttpServletRequest request,
                                HttpServletResponse response,
                                FilterChain filterChain) throws ServletException, IOException {
    long startMs = System.currentTimeMillis();
    String requestId = UUID.randomUUID().toString();
    String path = request.getRequestURI();
    String method = request.getMethod();
    String clientIp = resolveClientIp(request);
    String operation = deriveOperation(method, path);

    MDC.put("requestId", requestId);
    // tenantId injected AFTER chain runs (see Filter Order section)

    try {
        log.info("Request received",
            kv("event", "request_start"),
            kv("operation", operation),
            kv("method", method),
            kv("path", path),
            kv("requestId", requestId),
            kv("clientIp", clientIp));

        filterChain.doFilter(request, response);

        long durationMs = System.currentTimeMillis() - startMs;
        int httpStatus = response.getStatus();
        String status = httpStatus >= 500 ? "ERROR" : "SUCCESS";

        if (httpStatus >= 500) {
            log.error("Request completed",
                kv("event", "request_error"),
                kv("operation", operation),
                kv("durationMs", durationMs),
                kv("errorCode", "HTTP_" + httpStatus),
                kv("status", "ERROR"),
                kv("httpStatus", httpStatus));
        } else {
            log.info("Request completed",
                kv("event", "request_end"),
                kv("operation", operation),
                kv("durationMs", durationMs),
                kv("status", status),
                kv("httpStatus", httpStatus));
        }
    } finally {
        MDC.remove("requestId");
    }
}
```

### Pattern 2: StructuredArguments.kv() for Structured Log Fields

**What:** `kv("fieldName", value)` from `net.logstash.logback.argument.StructuredArguments`
emits a key-value pair that appears as a top-level JSON field via the `<arguments/>` provider.

**Import path (already used in `ApiAdvice` and `SendMailListener`):**
```java
import static net.logstash.logback.argument.StructuredArguments.kv;
```

**Rule:** ALL structured fields must use `kv()`. String interpolation in the log message
(e.g. `"requestId={}"`) must NOT be used for fields that need to be queryable in Loki.

**Example output:**
```json
{
  "message": "Request received",
  "event": "request_start",
  "operation": "initiate_payment",
  "method": "POST",
  "path": "/v1/payments",
  "requestId": "a1b2c3d4-...",
  "clientIp": "10.0.0.1"
}
```

### Pattern 3: MDC Key Names — Matching Constants.REQUEST_ID_NAME

The codebase defines MDC key names in `Constants.java`:

| Constant | Value | Used By |
|----------|-------|---------|
| `Constants.REQUEST_ID_NAME` | `"requestId"` | `RequestIdProvider.addReqIdToThread()` |
| `Constants.TXN_ID_NAME` | `"txnId"` | `TransactionIdProvider` (legacy path) |

**Note on conflict:** `TransactionService.initiate()` sets `MDC.put("transaction_id", ...)` (snake_case)
while the requirement (LOG-MDC-02) calls for `transactionId` (camelCase). The requirements doc
and `logging.md` standard use camelCase. Phase 15 must align on camelCase keys for the fields
it owns — **do not introduce new snake_case MDC keys**. The `transaction_id` key set by
`TransactionService` is a pre-existing inconsistency addressed if needed by updating
`TransactionService` (see Open Questions).

### Filter Order: Critical Constraint

**What the filter order looks like in `SecurityConfiguration.filterChain()`:**

```
1. ForwardedHeaderFilter          (Ordered.HIGHEST_PRECEDENCE — servlet container level)
2. LoggingFilter                  (addFilterBefore SecurityAdviceFilter)
3. SecurityAdviceFilter           (Component, calls RequestMetadataProvider.initRequestMetadata())
4. JWTAuthenticationFilter        (addFilterAfter UsernamePasswordAuthenticationFilter)
5. JWTAuthorizationFilter         (addFilterAfter BasicAuthenticationFilter)
6. SecondFactorLoginFilter        (addFilterAfter SecurityAdviceFilter)
7. SessionRefreshFilter           (addFilterAfter JWTAuthorizationFilter)
```

**Tenant API key path** (`TenantSecurityConfig`, `@Order(1)`):**
```
ApiKeyAuthenticationFilter        (addFilterBefore UsernamePasswordAuthenticationFilter)
```

**Consequence:** `LoggingFilter` runs BEFORE `ApiKeyAuthenticationFilter`. At the time
`LoggingFilter.doFilterInternal()` is called, `TenantContext` is empty and
`SecurityContextHolder` holds no `TenantPrincipal`.

**Solution:** Log `tenantId` (if available) in the `request_end`/`request_error` events
AFTER the chain returns. By that point, `ApiKeyAuthenticationFilter` has run, but has also
cleared `TenantContext` in its own `finally` block. The correct approach is to read `TenantContext`
**inside the chain** before the filter's `finally` block clears it. This is done by reading
`TenantContext.get()` right after `filterChain.doFilter()` returns but before the `finally`
block runs (see implementation note below).

**Alternative for `requestId`:** `RequestIdProvider.addReqIdToThread()` is called by
`SecurityAdviceFilter` via `RequestMetadataProvider.initRequestMetadata()`. But `LoggingFilter`
runs before `SecurityAdviceFilter`. Therefore, `LoggingFilter` must generate its own `requestId`
(UUID) and put it in MDC directly, or delegate to `RequestIdProvider.addNewRequestIdToThread()`
which already does the right thing. The simpler and more robust path is:
`LoggingFilter` generates the `requestId` via `UUID.randomUUID()` and calls
`MDC.put(Constants.REQUEST_ID_NAME, requestId)`. `SecurityAdviceFilter`'s call to
`RequestIdProvider.addReqIdToThread(request)` then checks for an incoming `X-Request-Id` header;
if absent it will call `addNewRequestIdToThread()` which will **overwrite** the MDC value.
To avoid overwrite: check whether to hook into `RequestIdProvider` or read the header in
`LoggingFilter` itself and set it once. The simplest approach that satisfies requirements:
`LoggingFilter` reads `X-Request-Id` from the request header; if absent, generates a new UUID;
stores it via `MDC.put(Constants.REQUEST_ID_NAME, ...)`. This is consistent with the existing
behavior in `RequestIdProvider.addReqIdToThread(HttpServletRequest)`.

### Pattern 4: Deriving `operation` from Request Path

The requirements say `operation` must appear in all three lifecycle events. No pre-existing
`operation` derivation logic exists in the codebase. The standard approach from `logging.md` uses
strings like `"initiate_payment"` or `"create_order"`.

**Recommended derivation rule** (path-to-operation mapping):

```java
private static String deriveOperation(String method, String path) {
    // Strip path parameters: /v1/payments/abc-123 -> /v1/payments/{id}
    // Use a simple method+path pattern lookup map, or derive from the path segments.

    // Example minimal implementation:
    if (path.startsWith("/v1/payments") && "POST".equals(method)) return "initiate_payment";
    if (path.startsWith("/v1/callbacks/orange"))                   return "orange_callback";
    if (path.startsWith("/v1/callbacks/mtn"))                      return "mtn_callback";
    if (path.startsWith("/v1/admin/transactions"))                  return "admin_transactions";
    if (path.startsWith("/v1/admin/metrics"))                       return "admin_metrics";
    if (path.startsWith("/v1/account"))                             return "account_" + method.toLowerCase();

    // Fallback: METHOD_/path/segments
    return method.toLowerCase() + "_" + path.replace("/", "_").replaceAll("^_", "");
}
```

This can be a simple private method in `LoggingFilter`. A Map-based approach is cleaner for
maintainability but not required. The critical constraint: `operation` must be a **low-cardinality**
value (no UUIDs, no amounts, no request-specific data). It represents the endpoint type, not
a specific request instance.

### Anti-Patterns to Avoid

- **String interpolation for structured fields:** `log.info("requestId={}", id)` puts the value
  in the message text, not as a queryable JSON field. Use `kv()` instead.
- **MDC.clear() in LoggingFilter:** Using `MDC.clear()` instead of `MDC.remove()` for specific
  keys will wipe out `traceId`/`spanId` injected by Micrometer Tracing. The `finally` block must
  only `MDC.remove("requestId")` — not `MDC.clear()`.
- **Logging before MDC is populated:** Putting `log.info(...)` before `MDC.put(...)` means the
  first log line won't have the correlation fields. MDC must be set before any log call.
- **Reading response status before chain completes:** `response.getStatus()` returns the committed
  status code. This is only valid after `filterChain.doFilter()` returns. The existing filter's
  `afterRequest()` pattern (called in `finally`) correctly reads status after chain execution.
- **Wrapping request/response without copying response body:** The existing `LoggingFilter` uses
  `ContentCachingResponseWrapper` and calls `response.copyBodyToResponse()`. The new implementation
  does not need to cache body content (no body logging required by these requirements). Use plain
  `HttpServletRequest`/`HttpServletResponse` or keep the wrappers only if response status needs
  to be read before `copyBodyToResponse()`.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| JSON field emission | String interpolation in message | `StructuredArguments.kv()` | kv() goes through `<arguments/>` provider as top-level JSON; message text is not queryable |
| Request ID generation | Custom ID format | `UUID.randomUUID().toString()` + `MDC.put(Constants.REQUEST_ID_NAME, ...)` | Consistent with `RequestIdProvider.addNewRequestIdToThread()`; matches existing MDC key name |
| tenantId in MDC | Custom ThreadLocal | `TenantContext.get()` | Already populated by `ApiKeyAuthenticationFilter` before the chain returns |
| transactionId MDC | New MDC calls in LoggingFilter | `TransactionService.initiate()` already sets `MDC.put("transaction_id", ...)` | MDC propagates automatically; filter does not need to touch it |
| Client IP resolution | Custom header parsing | Existing `RequestMetadataProvider.initRequestMetadata()` pattern | Already handles `Forwarded` header and falls back to `remoteAddr`; replicate the same logic |

**Key insight:** MDC is thread-scoped. Once `TransactionService.initiate()` runs inside the
request thread and calls `MDC.put("transaction_id", transactionId)`, all subsequent log
statements in the same thread (including in the filter's `request_end` event) will carry
`transaction_id` automatically — because `<mdc/>` in `logback-spring.xml` flattens the entire
MDC into every log line.

---

## Common Pitfalls

### Pitfall 1: Clearing too much MDC in the filter's finally block

**What goes wrong:** `MDC.clear()` is called in `LoggingFilter.finally`, wiping out `traceId`,
`spanId`, and any business context set by downstream services.
**Why it happens:** The existing `MdcDecorator.decorate()` (used for async threads) calls
`MDC.clear()` in its finally block — that's correct for async. But the request filter should
only remove the keys it put in.
**How to avoid:** Use `MDC.remove(Constants.REQUEST_ID_NAME)` for each key the filter explicitly
set. Do not call `MDC.clear()`. Note: `SecurityAdviceFilter.finally` already calls
`RequestMetadataProvider.cleanup()` which calls `RequestIdProvider.removeReqIdFromThread()`
which calls `MDC.remove(Constants.REQUEST_ID_NAME)`. If `LoggingFilter` and `SecurityAdviceFilter`
both manage `requestId`, one may double-remove. Assign ownership clearly: `LoggingFilter` owns
requestId; `SecurityAdviceFilter` can skip the remove if LoggingFilter's finally handles it.
**Warning signs:** `traceId` field disappears from log lines after LoggingFilter processes the
request.

### Pitfall 2: tenantId not available when request_start is logged

**What goes wrong:** `request_start` event has no `tenantId` because `ApiKeyAuthenticationFilter`
hasn't run yet.
**Why it happens:** `LoggingFilter` is registered before `ApiKeyAuthenticationFilter` in the filter
chain.
**How to avoid:** Emit `request_start` without `tenantId`. Emit `tenantId` only in `request_end`
and `request_error` by reading `TenantContext.get()` after the chain returns. Alternatively, emit
`tenantId` into MDC at start and the `<mdc/>` provider will pick it up automatically — but only
if MDC is populated before the log statement fires, which requires reading it after the chain runs
and backfilling (not possible without re-logging). The clean solution: `request_end` carries
`tenantId`; `request_start` does not. This satisfies LOG-MDC-01 because MDC is populated at
request entry (by `ApiKeyAuthenticationFilter`) and all downstream log statements inherit it.
**Warning signs:** `tenantId` missing from all log lines — likely because `MDC.put("tenantId")`
is never called; check that `ApiKeyAuthenticationFilter` puts tenantId into MDC (it currently
only sets `TenantContext`, not MDC). See "Open Questions" #1.

### Pitfall 3: The two-filter interaction (LoggingFilter + SecurityAdviceFilter both touch requestId MDC)

**What goes wrong:** `SecurityAdviceFilter.doFilterInternal()` calls
`RequestMetadataProvider.initRequestMetadata(request)`, which calls
`RequestIdProvider.addReqIdToThread(request)`. If `LoggingFilter` already set `requestId` in MDC,
this call checks the `X-Request-Id` header: if present, it overwrites MDC with the header value;
if absent, it calls `addNewRequestIdToThread()` which generates a NEW UUID and overwrites the one
`LoggingFilter` set. Result: the `requestId` in `request_start` event differs from all subsequent
log lines.
**How to avoid:** `LoggingFilter` should read `X-Request-Id` header first (same as
`RequestIdProvider.addReqIdToThread(HttpServletRequest)`), and set the same value that
`SecurityAdviceFilter` will use. Or: `LoggingFilter` reads `MDC.get(Constants.REQUEST_ID_NAME)`
after the chain returns instead of before — but then `request_start` has no `requestId` in
the event body (though it will be in MDC for subsequent lines). Best approach: `LoggingFilter`
derives `requestId` the same way `RequestIdProvider` does (read `X-Request-Id` header, fall back
to UUID), puts it in MDC via `MDC.put(Constants.REQUEST_ID_NAME, ...)`, and then
`SecurityAdviceFilter` will find a non-blank value in `RequestIdProvider.addReqIdToThread(request)`
... but wait: `RequestIdProvider.addReqIdToThread(HttpServletRequest)` always reads the header and
either uses it or generates new — it does NOT check existing MDC. So there is still a potential
overwrite. **Final resolution:** The `LoggingFilter` and `SecurityAdviceFilter` must be
coordinated. Since `SecurityAdviceFilter` is a `@Component` while `LoggingFilter` is not, the
safest approach is: `LoggingFilter` generates and sets `requestId`, and `SecurityAdviceFilter`
delegates to `RequestIdProvider.provideRequestId()` (which checks MDC first and only generates if
absent) rather than `addReqIdToThread(request)`. Alternatively, restructure to add the MDC-setting
responsibility entirely to `LoggingFilter` and have `SecurityAdviceFilter` skip `requestId`
management. This requires a coordinated change to `SecurityAdviceFilter` or `RequestIdProvider`.
**Warning signs:** `requestId` in `request_start` event differs from `requestId` in `request_end`
event for the same request.

### Pitfall 4: MDC.put("tenantId") never called (LOG-MDC-01 gap)

**What goes wrong:** `tenantId` does not appear in log lines because no code calls
`MDC.put("tenantId", ...)`.
**Why it happens:** `ApiKeyAuthenticationFilter` sets `TenantContext.set(tenantRef)` and
`SecurityContextHolder`, but does NOT call `MDC.put("tenantId", ...)`. The requirements say MDC
must contain `tenantId` before the first log fires. Currently it does not.
**How to avoid:** `ApiKeyAuthenticationFilter.doFilterInternal()` must call
`MDC.put("tenantId", tenantRef)` after `TenantContext.set(tenantRef)`, and
`MDC.remove("tenantId")` in its `finally` block. This is a small addition to
`ApiKeyAuthenticationFilter`.
**Warning signs:** No `tenantId` field in any log line for `/v1/payments` requests.

### Pitfall 5: 5xx detection on response status

**What goes wrong:** The `request_error` event at ERROR level is only required for 5xx responses.
But `response.getStatus()` may return 200 (the default) if the response has not been committed
yet when read.
**Why it happens:** `ContentCachingResponseWrapper` buffers the response body; the status is set
when `sendError()` or a `writeHeader()` equivalent is called. After `filterChain.doFilter()`
returns, the status is set on the wrapper.
**How to avoid:** Read `response.getStatus()` AFTER `filterChain.doFilter()` returns. The existing
`LoggingFilter` already does this correctly in `logResponse()`. Keep this pattern.
**Warning signs:** All requests treated as 5xx or all treated as 2xx.

---

## Code Examples

### MDC population in ApiKeyAuthenticationFilter (addition required)

```java
// Source: existing pattern in ApiKeyAuthenticationFilter.doFilterInternal()
// ADD after: TenantContext.set(tenantRef);
MDC.put("tenantId", tenantRef);  // satisfies LOG-MDC-01

// ADD in finally block (after TenantContext.clear()):
MDC.remove("tenantId");
```

Import to add:
```java
import org.slf4j.MDC;
```

### LoggingFilter — structured request_start event

```java
// Source: logging.md section 7, requirements LOG-REQ-01
// Import: import static net.logstash.logback.argument.StructuredArguments.kv;
log.info("Request received",
    kv("event",     "request_start"),
    kv("operation", operation),
    kv("method",    request.getMethod()),
    kv("path",      request.getRequestURI()),
    kv("requestId", requestId),
    kv("clientIp",  clientIp));
```

### LoggingFilter — structured request_end event

```java
// Source: logging.md section 7, requirements LOG-REQ-02
log.info("Request completed",
    kv("event",      "request_end"),
    kv("operation",  operation),
    kv("durationMs", durationMs),
    kv("status",     "SUCCESS"),
    kv("httpStatus", httpStatus));
```

### LoggingFilter — structured request_error event (5xx only)

```java
// Source: logging.md section 7, requirements LOG-REQ-03
// Logged at ERROR level (not INFO) when httpStatus >= 500
log.error("Request error",
    kv("event",      "request_error"),
    kv("operation",  operation),
    kv("durationMs", durationMs),
    kv("errorCode",  "HTTP_" + httpStatus),
    kv("status",     "ERROR"),
    kv("httpStatus", httpStatus));
```

### TransactionService MDC additions (satisfies LOG-MDC-02 camelCase alignment)

```java
// Source: existing TransactionService.initiate() — current keys use snake_case
// Change: align to camelCase per logging.md standard
MDC.put("transactionId",      transactionId);   // was "transaction_id"
MDC.put("externalReference",  externalReference); // was "external_reference"
// Keep "trace_id" as-is (it's the OTel trace, not the transaction trace field)
```

### Client IP extraction (replicate existing RequestMetadataProvider pattern)

```java
// Source: RequestMetadataProvider.initRequestMetadata() lines 55-59
private static String resolveClientIp(HttpServletRequest request) {
    String ip = request.getHeader("Forwarded");
    if (ip == null || ip.isBlank()) {
        ip = request.getRemoteAddr();
    }
    return ip;
}
```

---

## Existing Code Inventory

### What Already Exists (do not duplicate)

| Class | Location | What It Does | Phase 15 Relationship |
|-------|----------|--------------|----------------------|
| `LoggingFilter` | `security/audit/filter/` | Logs request headers/body after chain — non-structured | REWRITE |
| `RequestIdProvider` | `security/common/util/` | Generates/reads `requestId` in MDC via `Constants.REQUEST_ID_NAME` | USE its constant; coordinate to avoid double-set |
| `TenantContext` | `security/common/util/` | ThreadLocal `tenantRef` string | READ in LoggingFilter after chain |
| `RequestMetadataProvider` | `security/common/util/` | ThreadLocal `RequestMetadata`; calls `RequestIdProvider` | Coordinate — avoid double-setting `requestId` |
| `ApiKeyAuthenticationFilter` | `tenant/config/` | Sets `TenantContext`; registers `TenantPrincipal` in SecurityContext | ADD `MDC.put("tenantId", ...)` here |
| `TransactionService.initiate()` | `transaction/service/` | Sets `MDC.put("transaction_id", ...)` and `MDC.put("external_reference", ...)` | Consider renaming to camelCase in this phase |
| `MdcDecorator` | `common/threadpool/` | Copies MDC to async threads | NOT touched by Phase 15 |
| `Constants.REQUEST_ID_NAME` | `common/Constants.java` | `"requestId"` — canonical MDC key name | USE this constant in LoggingFilter |

### What Does NOT Exist (must be created or added)

| Gap | Where to Fix |
|-----|-------------|
| `MDC.put("tenantId", ...)` | Add to `ApiKeyAuthenticationFilter.doFilterInternal()` |
| Structured `request_start` event | Rewrite `LoggingFilter` |
| Structured `request_end` event | Rewrite `LoggingFilter` |
| Structured `request_error` event (5xx, ERROR level) | Rewrite `LoggingFilter` |
| `operation` field derivation | New private method in `LoggingFilter` |
| camelCase MDC keys for transaction context | Update `TransactionService.initiate()` (see Open Questions) |

---

## State of the Art

| Old Approach | Current Approach | Impact |
|--------------|------------------|--------|
| `LoggingFilter` logs raw header dumps | Structured `kv()` events with defined fields | Loki-queryable; no free-text parsing |
| String interpolation: `log.info("id={}", id)` | `log.info("msg", kv("id", id))` | Field appears as top-level JSON via `<arguments/>` provider |
| tenantId in `TenantContext` only | `TenantContext` + `MDC.put("tenantId", ...)` | Appears in every log line without any per-call effort |
| transactionId set once at creation, referenced manually | `MDC.put("transactionId", ...)` in `TransactionService` | All downstream logs in same thread inherit it |

---

## Open Questions

### 1. Should `ApiKeyAuthenticationFilter` be modified to call `MDC.put("tenantId", ...)`?

**What we know:** The filter already sets `TenantContext.set(tenantRef)` and clears it in
`finally`. It does NOT currently touch MDC. `LOG-MDC-01` requires `tenantId` in MDC before the
first log statement fires.
**What's unclear:** Whether to modify `ApiKeyAuthenticationFilter` (cleanest: each filter owns
its own MDC keys) or add a hook in `LoggingFilter` that reads `TenantContext.get()` at chain
entry and sets MDC (hacky: reads another filter's state).
**Recommendation:** Modify `ApiKeyAuthenticationFilter`. It's the natural owner of the `tenantId`
MDC key. One `MDC.put` + one `MDC.remove` in `finally`. The planner should create a task for this
as a pre-condition to LOG-MDC-01 being fully satisfied.

### 2. Should `TransactionService.initiate()` keys change from snake_case to camelCase?

**What we know:** Current keys are `"transaction_id"` and `"external_reference"` (snake_case).
`LOG-MDC-02` specifies `transactionId` and `externalReference` (camelCase). The `logging.md`
standard uses camelCase throughout.
**What's unclear:** Whether changing these key names could break any existing log parsing rules or
dashboards already built on the snake_case keys.
**Recommendation:** Change to camelCase in Phase 15 (same phase that owns MDC consistency). No
dashboards are established yet (Phase 14 just landed). The planner should include a task to update
`TransactionService.initiate()` and update `Constants.TXN_ID_NAME = "txnId"` to
`"transactionId"` if that constant is used (it is, in `TransactionIdProvider`).

### 3. How should JWT-path requests (non-API-key paths) get `tenantId` in MDC?

**What we know:** `/v1/admin/**` and `/v1/account/**` go through the JWT filter chain, not
`ApiKeyAuthenticationFilter`. After JWT authentication, the `SecurityContextHolder` has a
`UserDetails` object (not `TenantPrincipal`). There is no `TenantContext` for these paths.
**What's unclear:** Whether `tenantId` is meaningful for admin/account requests (it may not be —
admin users are not tenants).
**Recommendation:** Skip `tenantId` MDC for non-tenant paths. `LoggingFilter.request_end` reads
`TenantContext.get()` and logs `tenantId` only if non-null. The planner should note this as
conditional logic in `LoggingFilter`.

### 4. Does `LoggingFilter` still need `ContentCachingRequestWrapper` and `ContentCachingResponseWrapper`?

**What we know:** The current `LoggingFilter` uses these wrappers to read and log request/response
bodies. Phase 15 does NOT require body logging — only structured lifecycle events and MDC.
**What's unclear:** Whether some other consumer downstream depends on these wrappers being present
(unlikely — they are created within `LoggingFilter` scope only).
**Recommendation:** Remove body caching wrappers from `LoggingFilter`. Simpler code. The
`response.getStatus()` can be read from the raw response after chain execution. The
`BodySanitizer` import can be dropped.

---

## Sources

### Primary (HIGH confidence — direct codebase inspection)

All findings below are drawn from reading the actual source files. No inference or assumption.

- `LoggingFilter.java` — existing filter implementation; filter registration in `SecurityConfiguration`
- `ApiKeyAuthenticationFilter.java` — how/when `TenantContext` is set; filter order relative to JWT chain
- `TenantContext.java` — ThreadLocal string holder for `tenantRef`
- `TenantPrincipal.java` — fields: `tenantRef` (String UUID), `tenantId` (Long DB ID)
- `TransactionService.java` — MDC.put calls: `"transaction_id"`, `"trace_id"`, `"external_reference"`
- `Constants.java` — MDC key names: `REQUEST_ID_NAME = "requestId"`, `TXN_ID_NAME = "txnId"`
- `RequestIdProvider.java` — requestId MDC management; `X-Request-Id` header handling
- `RequestMetadataProvider.java` — ThreadLocal RequestMetadata; calls RequestIdProvider
- `SecurityAdviceFilter.java` — calls `RequestMetadataProvider.initRequestMetadata()` and cleanup
- `SecurityConfiguration.java` — filter registration order (LoggingFilter before SecurityAdviceFilter)
- `TenantSecurityConfig.java` — ApiKeyAuthenticationFilter at @Order(1), added before UsernamePasswordAuthenticationFilter
- `logback-spring.xml` (config/) — Phase 14 output: LoggingEventCompositeJsonEncoder with `<mdc/>` and `<arguments/>` providers
- `ApiAdvice.java` — existing use of `import static net.logstash.logback.argument.StructuredArguments.entries`
- `SendMailListener.java` — existing use of `StructuredArguments.entries()`
- `PaymentOrchestrator.java` — call to `transactionService.initiate()` at Step 3; how `transactionId` flows
- `requirements/logging.md` — official logging standard with structured argument examples and lifecycle event spec

### Secondary (MEDIUM confidence)

- pom.xml: `logstash-logback-encoder` version 8.1 confirmed present; no new dependencies needed

---

## Metadata

**Confidence breakdown:**
- Existing filter structure: HIGH — read source directly
- Filter order: HIGH — read `SecurityConfiguration.filterChain()` and `TenantSecurityConfig` directly
- MDC key names: HIGH — read `Constants.java`, `TransactionService.java`, `RequestIdProvider.java`
- tenantId MDC gap: HIGH — confirmed `ApiKeyAuthenticationFilter` never calls `MDC.put("tenantId")`
- StructuredArguments usage: HIGH — confirmed existing imports in `ApiAdvice` and `SendMailListener`
- camelCase key naming: HIGH — `logging.md` standard, requirements spec, and `TransactionService` code all compared

**Research date:** 2026-03-26
**Valid until:** 2026-05-26 (codebase is under active development; re-verify filter order if
`SecurityConfiguration` or `TenantSecurityConfig` changes)
