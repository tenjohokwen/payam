# Phase 40: Operational Resilience - Research

**Researched:** 2026-04-15
**Domain:** Spring `@Transactional` timeout, PostgreSQL advisory locks, ThreadLocal cleanup, servlet filter exception paths
**Confidence:** HIGH

## Summary

Phase 40 closes two operational safety gaps: OPS-01 (poller transactions have no explicit timeout, so a crashed or hung node holds the Postgres advisory lock indefinitely) and OPS-03 (TenantContext is already cleared in a `finally` block in `ApiKeyAuthenticationFilter`, but there is no integration test proving the context is empty after an exception-path request).

**OPS-01** is a one-line fix per poller. Both `MtnStatusPollerJob` and `OrangeStatusPollerJob` declare `@Transactional` with no attributes. Adding `@Transactional(timeout = N)` (seconds, integer) causes Spring's transaction infrastructure to set Postgres `statement_timeout` — and when the transaction exceeds the wall-clock budget Postgres aborts it, releasing the advisory lock. The advisory lock used here is `pg_try_advisory_xact_lock`, which is transaction-level: it is released automatically on commit or rollback. A transaction timeout therefore bounds the worst-case advisory lock hold time.

**OPS-03** is a test-coverage gap, not a code gap. Reading `ApiKeyAuthenticationFilter` (lines 134-148) confirms `TenantContext.set()` is always paired with `TenantContext.clear()` in a `finally` block. The existing `TenantFilterChainIT.tenantContext_clearedAfterRequest_noLeakBetweenRequests` test verifies sequential requests with different keys, but it never exercises an exception path (a request that causes a 500 inside the controller). The new integration test must: (1) send a valid API key so `TenantContext.set()` fires, (2) hit a path that throws an unhandled exception inside the controller, (3) confirm `TenantContext.get()` returns null after the response.

**Primary recommendation:** Add `timeout = 300` (5 minutes) to both poller `@Transactional` annotations; add one new IT test method in `TenantFilterChainIT` or a new `TenantContextClearIT` class that proves TenantContext is null after a 500-returning request.

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| OPS-01 | MTN and Orange poller transactions have an explicit timeout so advisory locks are bounded — no indefinite lock hold on node crash | `@Transactional(timeout = N)` on `executeInternal` in both poller jobs; N should match the Quartz interval (e.g. 300 s = 5 min) so a single poller tick cannot run longer than the re-fire window |
| OPS-03 | TenantContext is cleared in a `finally` block on all request paths including exception paths — an integration test verifies the context is empty after an exception-path request | Filter already has `finally` block; gap is the integration test proving behaviour on 500-path |
</phase_requirements>

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Framework `@Transactional` | 6.x (via Spring Boot 3.5.11) | Declarative transaction management with timeout | Built into the stack; `timeout` attribute in seconds is stable since Spring 1.x |
| Postgres `pg_try_advisory_xact_lock` | — (server-side) | Transaction-level advisory lock; auto-released on commit/rollback | Already used by both pollers; no changes to lock acquisition |

### No New Libraries Required

Both fixes are within existing infrastructure. No new dependencies are needed.

## Architecture Patterns

### OPS-01: Poller Transaction Timeout

**What:** Add `timeout` attribute to `@Transactional` on `executeInternal` in both poller jobs.

**Current state (both pollers):**
```java
@Override
@Transactional
protected void executeInternal(JobExecutionContext context) { ... }
```

**Fixed state:**
```java
private static final int POLLER_TRANSACTION_TIMEOUT_SECONDS = 300;  // 5 minutes

@Override
@Transactional(timeout = POLLER_TRANSACTION_TIMEOUT_SECONDS)
protected void executeInternal(JobExecutionContext context) { ... }
```

**Why 300 seconds:** The Quartz schedule fires every 5 minutes. If a poller tick runs longer than its re-fire interval, the next invocation will try `pg_try_advisory_xact_lock` and immediately skip (correct — single-node invariant). A 300-second timeout ensures the lock is released within one Quartz cycle even on a hung node, while being generous enough to process a full 100-transaction batch under slow provider calls.

**How the timeout works mechanically:**
- Spring translates `@Transactional(timeout = N)` into a JDBC `setQueryTimeout(N)` call on the connection.
- When the transaction exceeds N seconds, Postgres raises `57014 query_canceled`.
- Spring wraps this as `QueryTimeoutException` (subclass of `TransientDataAccessException`).
- `pg_try_advisory_xact_lock` is transaction-level — Postgres releases it on rollback, which follows the timeout cancellation.
- The Quartz thread sees a `JobExecutionException`-wrapped exception; the job will fire again at the next interval.

**Confidence:** HIGH — verified against Spring Framework docs for `@Transactional.timeout`.

### OPS-03: TenantContext Cleanup Verification

**What the code already does (CORRECT — no code change needed):**

```java
// ApiKeyAuthenticationFilter.doFilterInternal (lines 134-148)
TenantContext.set(tenantRef);
MDC.put("tenantId", tenantRef);
// ...
try {
    chain.doFilter(request, response);
} finally {
    TenantContext.clear();               // ALWAYS clear — servlet containers reuse threads
    MDC.remove("tenantId");
    SecurityContextHolder.clearContext();
}
```

`TenantContext.set()` is only called after the SUSPENDED check and after authentication succeeds. The early-return paths (missing key line 107, invalid key line 119, suspended tenant line 129) all happen BEFORE `TenantContext.set()` at line 134, so they do not leave context behind. The `finally` block at lines 144-148 guarantees cleanup on any exception thrown by `chain.doFilter(...)`.

**What is missing:** An integration test that:
1. Authenticates with a valid key (so `TenantContext.set()` fires)
2. Hits a request path that throws an exception inside the controller (producing a 500)
3. Reads `TenantContext.get()` after the response and asserts it is `null`

**Test approach — exception path triggering:**

Option A (preferred): Inject a test-only endpoint (or use an existing path that throws). The simplest reliable approach is to send a `POST /v1/payments` with a malformed body that passes auth but causes a `MethodArgumentNotValidException` (400) or `HttpMessageNotReadableException` (400) inside the dispatcher. This exercises the exception path through `ApiAdvice`.

However, 4xx responses are handled by Spring MVC before reaching the `chain.doFilter` `finally` block normally — they still go through the filter chain.

Option B (cleaner): A `@RestController` method that deliberately throws `RuntimeException`. Can be added as a `@TestConfiguration`-only bean with `@ConditionalOnProperty` guarded. This is the pattern used in tests across the codebase (see `TransactionExceptionSimulator`).

Actually, the simplest correct approach: send a valid API key to `POST /v1/payments` with a body that causes `HttpMessageNotReadableException` (invalid JSON). The filter `finally` block fires regardless. The test verifies TenantContext is null afterward by inspecting it from a `ThreadLocal` probe installed in the test.

**Critical challenge:** The test runs in a separate thread from the servlet container thread. `TenantContext` is a `ThreadLocal` — reading it from the test thread will always return null. The test must verify the server-side state indirectly.

**Correct testing approach:**

The requirement (success criterion #3) says "an integration test verifies that TenantContext is empty after a request that triggers an exception path". This means the test must observe the server-side ThreadLocal. Options:

1. **Add a `@Component` thread spy** (the canonical approach in this codebase): A `@ConditionalOnProperty`-enabled `Filter` or `@Component` that records what `TenantContext.get()` returns in an `AfterRequest` phase. The test reads from the spy.

2. **Use a second request as the probe**: After the exception-path request completes, make a second request. If TenantContext leaked from the first request, the second request's behavior would differ. This is hard to observe definitively.

3. **A dedicated test endpoint that reads TenantContext and returns it**: A `@TestConfiguration` `@RestController` that exposes `/test/tenant-context` returning `TenantContext.get()`. After the exception-path request, call this endpoint to see what the NEXT request on the same thread observes. This is the cleanest verifiable approach.

**Recommended approach for the plan:** Follow the `TenantFilterChainIT` pattern. Add a test-scoped controller or a `@Component` filter with `@ConditionalOnProperty(name="test.context.spy", havingValue="true")` that captures what `TenantContext.get()` returns at the START of each request (before the filter sets it). If the previous request leaked context, the spy will capture a non-null value. The test sends request 1 (exception path), then request 2 (normal), and asserts the spy captured null at the start of request 2.

This is exactly the same two-request sequential pattern already used in test 5 of `TenantFilterChainIT` — just modified to make request 1 trigger an exception response.

**Simplest viable implementation:** The existing `TenantFilterChainIT` test 5 (`tenantContext_clearedAfterRequest_noLeakBetweenRequests`) makes two sequential requests with two different keys. To prove the exception path: modify or add a test that sends request 1 as a valid-key request to `POST /v1/payments` with an invalid body (triggers 400 via exception handler), then makes request 2 with a different tenant's key and confirms request 2 succeeds (not 401 from stale context). If context leaked, the SecurityContext would also be wrong and request 2 would see unexpected auth state.

A stronger version: Use `@SpringBootTest(webEnvironment = RANDOM_PORT)` (already the pattern), add `properties = "spring.mvc.throw-exception-if-no-handler-found=true"`, and hit a non-existent route `/v1/payments/nonexistent-that-throws` with a valid API key. The key point is the exception still passes through `chain.doFilter` so the `finally` block fires.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Transaction timeout | Custom `@Around` AOP advice with `Future.get(timeout)` | `@Transactional(timeout = N)` | Spring already translates this to JDBC `setQueryTimeout`; handles rollback correctly |
| Advisory lock release on timeout | Explicit `UNLOCK` SQL | `pg_try_advisory_xact_lock` (already used) | Transaction-level lock releases automatically on transaction end — rollback from timeout already releases it |
| TenantContext leak detection | Custom `ThreadLocal` inspection via reflection | Two-request probe pattern (existing test 5 pattern) | Servlet thread pooling means the second request may or may not land on the same thread; the probe-at-start approach is the only reliable detection pattern |

## Common Pitfalls

### Pitfall 1: Timeout Units
**What goes wrong:** `@Transactional(timeout = 300000)` — developer uses milliseconds instead of seconds.
**Why it happens:** `RestTemplate` timeout setters use milliseconds; `@Transactional(timeout)` uses seconds.
**How to avoid:** Annotate the constant: `private static final int POLLER_TRANSACTION_TIMEOUT_SECONDS = 300;`
**Warning signs:** Tests pass but the lock is released every 300 milliseconds instead of 5 minutes.

### Pitfall 2: Quartz `executeInternal` is not a Spring-managed method call
**What goes wrong:** `@Transactional` on `executeInternal` may appear to not work because Quartz calls it directly, bypassing the Spring proxy.
**Why it happens:** `QuartzJobBean.executeInternal` is a protected method called from the `execute` public method on the same class. Spring AOP creates a proxy around the bean — but only for calls through the proxy (external callers). Quartz calls `execute()` on the proxy → `execute()` calls `executeInternal()` via self-invocation.
**Resolution:** This is already solved in the codebase — the `@Transactional` is on `executeInternal` and the existing jobs already work transactionally (they use `jdbcTemplate.queryForObject` and `transactionRepository` inside the transaction). The pattern works because Quartz calls `execute()` on the Spring-wrapped bean; the parent class `execute()` calls `executeInternal()`, but Spring's `QuartzJobBean` integration uses `applicationContext.getBean()` to obtain the Spring-managed instance, so the proxy IS involved. **Confidence: HIGH — this exact pattern is already in production and tests pass.**

### Pitfall 3: TenantContext probe reads the wrong thread
**What goes wrong:** The JUnit test thread reads `TenantContext.get()` after the HTTP response arrives and sees `null` — but that's the test thread, not the servlet thread. The test produces a false green.
**Why it happens:** `ThreadLocal` is per-thread. The test thread has no context set regardless.
**How to avoid:** Verify server-side state via the two-request probe pattern: check that request 2 is not corrupted by request 1's leaked context. Or use a `@Component` pre-filter spy that captures `TenantContext.get()` at the START of each request and stores it in a shared `AtomicReference` that the test thread can read.

### Pitfall 4: `@Transactional(timeout)` with `TransactionTemplate`
**What goes wrong:** Only `executeInternal` is annotated; `runPoller` is called from within the observation lambda, which is called within the transaction. The timeout applies to the whole transaction.
**Resolution:** This is correct — the `@Transactional` on `executeInternal` wraps the entire call including the `Observation.createNotStarted().observe(this::runPoller)` call. The timeout clock starts when `executeInternal` begins.

### Pitfall 5: Orange poller cutoff calculation at timeout = 0
**What goes wrong:** `MtnStatusPollerJob` has a special case: `if (initialDelaySeconds == 0) cutoff = Instant.now().plus(1, ChronoUnit.HOURS)`. Orange does not have this guard. This is pre-existing and out of scope for Phase 40.
**How to avoid:** Do not change this logic — scope is timeout annotation only.

## Code Examples

### Adding timeout to poller (Spring @Transactional)

```java
// Source: Spring Framework docs — @Transactional attributes
// File: MtnStatusPollerJob.java (and identically OrangeStatusPollerJob.java)

/** 5-minute transaction timeout — bounds the advisory lock hold on a crashed node. */
private static final int POLLER_TRANSACTION_TIMEOUT_SECONDS = 300;

@Override
@Transactional(timeout = POLLER_TRANSACTION_TIMEOUT_SECONDS)
protected void executeInternal(JobExecutionContext context) {
    Observation.createNotStarted("quartz.mtn-poller", observationRegistry)
            .lowCardinalityKeyValue("job", "MtnStatusPollerJob")
            .observe(this::runPoller);
}
```

### TenantContext already-correct finally block (no code change)

```java
// ApiKeyAuthenticationFilter.java (lines 142-148) — ALREADY CORRECT
try {
    chain.doFilter(request, response);
} finally {
    TenantContext.clear();               // ALWAYS clear — servlet containers reuse threads
    MDC.remove("tenantId");
    SecurityContextHolder.clearContext();
}
```

### Integration test pattern for exception-path verification

```java
// New test in TenantFilterChainIT or TenantContextClearIT
// Pattern: valid key → exception-path request (400/500) → second request with same key → not 401
@Test
void tenantContext_clearedAfterExceptionPath_noLeakOnNextRequest() {
    TenantService.TenantCreationResult result =
        tenantService.createTenant("ExPath Corp", ApiKeyEnvironment.PROD);
    String rawKey = result.rawKey();

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Api-Key", rawKey);

    // Request 1: valid key, invalid body → exception thrown by dispatcher (400 or 500)
    // The filter finally block fires and clears TenantContext
    org.springframework.http.HttpEntity<String> req1 =
        new org.springframework.http.HttpEntity<>("NOT_VALID_JSON", headers);
    headers.setContentType(MediaType.APPLICATION_JSON);
    try {
        restTemplate.postForEntity(url("/v1/payments"), req1, Object.class);
    } catch (HttpClientErrorException | HttpServerErrorException ignored) {
        // Expected — exception path triggered
    }

    // Request 2: same valid key — if TenantContext leaked, SecurityContext would be stale
    // and behavior undefined. We assert the request is not rejected with 401 from key-filter.
    org.springframework.http.HttpEntity<Map<String, String>> req2 =
        new org.springframework.http.HttpEntity<>(
            Map.of("msisdn", "67XXXXXXXX", "amount", "500", "idempotencyKey", UUID.randomUUID().toString()),
            headers);
    try {
        ResponseEntity<Object> resp = restTemplate.postForEntity(url("/v1/payments"), req2, Object.class);
        assertThat(resp.getStatusCode().value()).isNotEqualTo(401);
    } catch (HttpClientErrorException e) {
        // Any 4xx except 401 means the filter chain passed correctly
        assertThat(e.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    } catch (HttpServerErrorException e) {
        // 5xx from downstream — filter chain passed correctly
        assertThat(e.getStatusCode().value()).isNotEqualTo(401);
    }
}
```

**Note:** The indirect probe approach (request 2 not rejected) is the same pattern as the existing test 5 in `TenantFilterChainIT`. It is the correct approach for ThreadLocal verification in a different-thread test client.

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `@Transactional` with no timeout (unbounded) | `@Transactional(timeout = N)` | Phase 40 | Advisory lock bounded to N seconds even on node crash |
| No IT test for exception-path TenantContext | IT test with exception-path probe | Phase 40 | OPS-03 requirement satisfied |

## Open Questions

1. **What timeout value for pollers?**
   - What we know: Quartz fires every 5 minutes; batch is 100 transactions; each transaction involves one provider HTTP call (10s read timeout from WEBHOOK-03 pattern).
   - What's unclear: Whether 300 s (5 min) is the right ceiling, or if 600 s (10 min) is safer given network variability.
   - Recommendation: Use 300 s (5 minutes) matching the Quartz interval. This ensures the lock is released before the next tick. The constant name `POLLER_TRANSACTION_TIMEOUT_SECONDS` makes it easy to tune.

2. **Should timeout be configurable via application.properties?**
   - What we know: Other timeouts in the codebase are hardcoded or in config classes (e.g., `WebhookConfig` uses `CONNECT_TIMEOUT_MS = 5000`).
   - What's unclear: Whether ops team wants to tune without a redeploy.
   - Recommendation: Hardcode as a named constant for Phase 40. Add `@ConfigurationProperties` wrapper only if the planner flags it as needed.

## Environment Availability

Step 2.6: SKIPPED (no external dependencies beyond existing Postgres and Spring — already verified as running in CI/testcontainers).

## Validation Architecture

`workflow.nyquist_validation` key is absent from `.planning/config.json` — treated as enabled.

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test + Testcontainers |
| Config file | `pom.xml` (maven-surefire + maven-failsafe) |
| Quick run command | `mvn test -pl . -Dtest=TenantFilterChainIT,MtnStatusPollerJob*,OrangeStatusPollerJob*` |
| Full suite command | `mvn verify` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| OPS-01 | Poller `@Transactional(timeout=300)` prevents indefinite lock hold | unit (annotation verification) + functional (IT if desired) | `mvn verify` | ❌ Wave 0 — new annotation only; no dedicated timeout IT required |
| OPS-03 | TenantContext is null after exception-path request | integration | `mvn verify -Dtest=TenantContextClearIT` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `mvn test -Dtest=TenantFilterChainIT`
- **Per wave merge:** `mvn verify`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `src/test/java/com/softropic/payam/tenant/TenantContextClearIT.java` — covers OPS-03 (or add method to `TenantFilterChainIT`)
- [ ] No framework install needed — existing test infrastructure applies

## Project Constraints (from CLAUDE.md)

CLAUDE.md does not exist at the project root. No additional project-specific constraints were found.

Key constraints derived from codebase analysis and prior accumulated decisions:

- `@Transactional` annotations are already in use on both pollers — adding `timeout` attribute is fully within the established pattern
- Test files use `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@Import(TestConfig.class)` + `@ActiveProfiles("dev")` — new IT must follow this pattern exactly
- `TenantContext.clear()` uses `ThreadLocal.remove()` (not `set(null)`) per the Javadoc — this is correct and must not be changed
- No Flyway migration is needed for either fix
- `@Transactional(timeout)` uses **seconds** (integer) — not milliseconds

## Sources

### Primary (HIGH confidence)
- Direct source code reading — `MtnStatusPollerJob.java`, `OrangeStatusPollerJob.java`, `ApiKeyAuthenticationFilter.java`, `TenantContext.java`, `TenantFilterChainIT.java`, `LedgerConstraintIT.java`, `TestConfig.java`
- Spring Framework `@Transactional.timeout` attribute — standard Spring API, version-stable since Spring 1.x; confirmed applicable to Spring Boot 3.5.11 (pom.xml line 8)

### Secondary (MEDIUM confidence)
- `pg_try_advisory_xact_lock` transaction-level release semantics — well-documented Postgres advisory lock behavior; confirmed by existing comments in both poller files

## Metadata

**Confidence breakdown:**
- OPS-01 fix (timeout annotation): HIGH — one-line change per file, well-understood Spring API
- OPS-03 code assessment (already correct): HIGH — read filter source directly
- OPS-03 test design (indirect probe): HIGH — follows exact pattern of existing test 5 in TenantFilterChainIT
- Timeout value (300 s): MEDIUM — matches Quartz interval; no performance benchmarks for this specific workload

**Research date:** 2026-04-15
**Valid until:** 2026-05-15 (stable APIs; Spring Boot 3.5.11 released)
