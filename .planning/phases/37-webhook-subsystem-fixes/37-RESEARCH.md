# Phase 37: Webhook Subsystem Fixes - Research

**Researched:** 2026-04-14
**Domain:** Spring Boot 3.5 / Quartz / Spring Data JPA — webhook delivery pipeline hardening
**Confidence:** HIGH

---

## Summary

Phase 37 fixes three independent but related bugs in the outbound webhook delivery pipeline. All three bugs exist in the current codebase and have been confirmed by direct code inspection.

**Bug 1 (WEBHOOK-01 — N+1 query):** `WebhookDeliveryJob` calls `deliveryService.attemptDelivery(delivery)` for each `WebhookDeliveryLog` in a loop. `attemptDelivery()` calls `tenantRepository.findById(delivery.getTenantId())` for every delivery. When N deliveries are pending, this produces N tenant queries per job tick. The fix is to bulk-load all distinct tenants once before the loop in `WebhookDeliveryJob.runDelivery()` and pass the already-loaded `Tenant` into the delivery service, or add a new overload.

**Bug 2 (WEBHOOK-02 — premature enqueue):** `webhookDeliveryService.enqueue(...)` is called directly inside `WebhookTransitionService.applyFinalTransition()`, which runs in a `REQUIRES_NEW` transaction. This means the enqueue (which creates the `WebhookDeliveryLog` row and immediately makes the first HTTP attempt) happens within the same state-transition transaction. If the transaction rolls back after enqueue, a delivery has already been attempted or the log row is gone. The correct fix is to decouple enqueue from the state-transition transaction by publishing a Spring application event and handling it with a second `@TransactionalEventListener(AFTER_COMMIT)` — or by registering a `TransactionSynchronization.afterCommit()` callback inside `applyFinalTransition`. Either approach ensures the row is created and delivery attempted only after the state-transition transaction commits. Enqueue failure must NOT roll back the state transition (decoupled exception handling required).

**Bug 3 (WEBHOOK-03 — no timeouts):** `WebhookConfig` creates the `noRetryRestTemplate` using `new RestTemplate(new SimpleClientHttpRequestFactory())` with no timeout configuration. `SimpleClientHttpRequestFactory` defaults to infinite connect/read timeout (0 ms = no timeout). A tenant endpoint that accepts the TCP connection but never responds can hold a Quartz thread indefinitely. The fix is to call `factory.setConnectTimeout(5_000)` and `factory.setReadTimeout(10_000)` (per the requirement: ≤5s connect, ≤10s read) on the `SimpleClientHttpRequestFactory` before handing it to the `RestTemplate`.

**Primary recommendation:** Implement all three fixes together in one phase. They are independent enough to be split into separate plan files but share the same test class (`WebhookDeliveryIT`). Add new IT tests for WEBHOOK-01 (query count assertion using existing `QueryCountVerifier`/datasource-proxy infrastructure) and WEBHOOK-02 (rollback isolation: rollback of state transition must not persist a delivery log row).

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| WEBHOOK-01 | Tenant data is loaded in one query per job tick (not per delivery) — N deliveries produce 1 query, not N+1 | Confirmed N+1 in `WebhookDeliveryJob.runDelivery()` → bulk-load distinct tenant IDs before the loop; existing `QueryCountVerifier` + datasource-proxy can assert this |
| WEBHOOK-02 | Webhook enqueue fires only after the status-transition transaction commits — uses `@TransactionalEventListener(phase = AFTER_COMMIT)`; enqueue failure does not roll back the state transition | `enqueue()` is called directly inside `applyFinalTransition()` REQUIRES_NEW transaction; fix by publishing a Spring event or using `TransactionSynchronization.afterCommit()`; enqueue wrapped in try/catch so failure is swallowed |
| WEBHOOK-03 | Webhook RestTemplate has explicit connect timeout (≤5s) and read timeout (≤10s) | `WebhookConfig` uses `SimpleClientHttpRequestFactory()` with no timeout set; fix: set `connectTimeout(5000)` and `readTimeout(10000)` on the factory |

</phase_requirements>

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Boot | 3.5.11 | Framework base | Established — no change |
| Spring Data JPA | (Boot-managed) | Repository queries for bulk tenant load | Standard JPA `findAllById()` |
| Spring Tx | (Boot-managed) | `@TransactionalEventListener`, `TransactionSynchronization` | Spring's built-in post-commit hooks |
| Quartz | (Boot-managed) | `WebhookDeliveryJob` Quartz JDBC job | Already in use |
| datasource-proxy (`net.ttddyy:datasource-proxy`) | see pom.xml | Query count assertions in tests | Already in classpath and wired in `TestConfig` |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `SimpleClientHttpRequestFactory` | Spring Web (Boot-managed) | Timeout-configurable, no-retry RestTemplate | Already the factory in use; just needs `setConnectTimeout` / `setReadTimeout` |
| `QueryCountVerifier` | test-scope (project class) | Assert 1 SELECT per tick | WEBHOOK-01 test |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Bulk `findAllById` | `JOIN FETCH` in JPQL | `findAllById` is simpler; no JPA associations to eager-load on `Tenant` in this path |
| `TransactionSynchronization.afterCommit()` | Spring ApplicationEvent + `@TransactionalEventListener` | Both work; `@TransactionalEventListener` is established project pattern (email, Phase 32 decisions) — use that |

---

## Architecture Patterns

### Pattern 1: Bulk tenant load before delivery loop (WEBHOOK-01)

**What:** Collect distinct `tenantId` values from the pending delivery list, load all tenants in one `findAllById()` call, build a `Map<Long, Tenant>`, then loop delivering with tenant looked up from the map.

**When to use:** Whenever a Quartz job or batch processor iterates a list of rows that each require a parent-entity lookup.

**Example:**
```java
// In WebhookDeliveryJob.runDelivery()
List<WebhookDeliveryLog> pending = deliveryService.findPendingDeliveries();
Set<Long> tenantIds = pending.stream()
    .map(WebhookDeliveryLog::getTenantId)
    .collect(Collectors.toSet());
Map<Long, Tenant> tenantMap = deliveryService.loadTenants(tenantIds);
for (WebhookDeliveryLog delivery : pending) {
    Tenant tenant = tenantMap.get(delivery.getTenantId());
    deliveryService.attemptDelivery(delivery, tenant);
}
```

The new `loadTenants(Set<Long>)` method in `WebhookDeliveryService` wraps `tenantRepository.findAllById(ids)` and builds the map. The `attemptDelivery` signature gains an overload that accepts a pre-loaded `Tenant` (or tenant is passed directly) to avoid the per-call `findById`.

**Anti-pattern:** Calling `tenantRepository.findById()` inside the loop — this is the current bug.

---

### Pattern 2: Post-commit enqueue via TransactionalEventListener (WEBHOOK-02)

**What:** Replace the direct `webhookDeliveryService.enqueue(...)` call in `WebhookTransitionService.applyFinalTransition()` with publishing an internal Spring application event (e.g. `WebhookEnqueueRequestedEvent`), and add a `@TransactionalEventListener(phase = AFTER_COMMIT)` handler in `WebhookDeliveryService` (or a new `WebhookEnqueueListener`) that calls `enqueue()`.

**Established project pattern:** Phase 32-02 decision: "Email pattern: `@EventListener` on listener, `Envelope -> MailManager @TransactionalEventListener(AFTER_COMMIT)`." This is verbatim from STATE.md. Use the same pattern.

**Isolation requirement:** The `@TransactionalEventListener(AFTER_COMMIT)` handler for enqueue must run in `REQUIRES_NEW` (so it creates its own transaction for the log row INSERT) and must catch all exceptions — enqueue failure must not propagate to roll back anything (there is no enclosing transaction after AFTER_COMMIT, but the exception can still prevent other listeners from running and produce noisy stack traces).

**Example structure:**
```java
// In WebhookTransitionService.applyFinalTransition() — after the state save:
// Replace direct enqueue() call with:
eventPublisher.publishEvent(new WebhookEnqueueRequestedEvent(
    tx.getTransactionId(), tx.getTenantId(), eventType.name(),
    target, tx.getExternalReference(), tx.getFeeAmount()));

// New listener class (or method on WebhookDeliveryService):
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void onEnqueueRequested(WebhookEnqueueRequestedEvent event) {
    try {
        enqueue(event.transactionId(), event.tenantId(), event.eventType(),
                event.status(), event.externalReference(), event.feeAmount());
    } catch (Exception e) {
        log.error("Enqueue failed — delivery will not be attempted", kv(...), e);
        // swallow: enqueue failure must not affect already-committed state transition
    }
}
```

**Important:** The existing `WebhookDeliveryIT` tests post a callback and then `Thread.sleep(500)` waiting for the async delivery. This pattern already accounts for async delivery and will work with the post-commit listener approach. No test timing changes needed.

---

### Pattern 3: RestTemplate connect/read timeouts (WEBHOOK-03)

**What:** Configure `SimpleClientHttpRequestFactory` with explicit timeouts before creating the `RestTemplate` bean.

**Example (the complete fix in WebhookConfig):**
```java
@Bean
public RestTemplate noRetryRestTemplate() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(5_000);   // ≤5s per WEBHOOK-03
    factory.setReadTimeout(10_000);     // ≤10s per WEBHOOK-03
    return new RestTemplate(factory);
}
```

Note: `SimpleClientHttpRequestFactory.setConnectTimeout(int millis)` and `setReadTimeout(int millis)` — both accept milliseconds (not `Duration` in this class, unlike `HttpComponentsClientHttpRequestFactory`). Source: Spring Web docs for `SimpleClientHttpRequestFactory`.

---

### Anti-Patterns to Avoid

- **N+1 in job loop:** Never call `repository.findById()` inside a `for` loop over batch results. Load parent entities in bulk before the loop.
- **Enqueue inside state-transition transaction:** Calling `enqueue()` directly inside `applyFinalTransition()` means delivery attempt is part of the state-transition transaction scope. A rollback discards the log row; a slow delivery extends the transaction open time.
- **Zero timeouts on RestTemplate:** `new SimpleClientHttpRequestFactory()` has no timeouts by default. Any outbound HTTP call without explicit timeouts can block threads indefinitely.
- **`@TransactionalEventListener` without REQUIRES_NEW:** After an `AFTER_COMMIT` fires, there is no active transaction. Without `REQUIRES_NEW`, any JPA writes in the handler will fail with `TransactionRequiredException`.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Post-commit callback | Custom thread or `@Async` | `@TransactionalEventListener(AFTER_COMMIT)` + Spring ApplicationEvent | Spring Modulith event pattern already chosen (STATE.md); thread-based approaches lose transaction ordering |
| Query count assertion | Custom JDBC spy | `QueryCountVerifier` + datasource-proxy (already in classpath) | Infrastructure already built in Phase 19; just enable `log.database.spy=true` in the test |
| Bulk entity lookup | Loop `findById()` | `findAllById(Collection<ID>)` (JpaRepository) | Single SQL `WHERE id IN (...)` |

---

## Common Pitfalls

### Pitfall 1: `@TransactionalEventListener` without `REQUIRES_NEW` causes TransactionRequiredException

**What goes wrong:** If the enqueue handler is annotated `@Transactional` without `propagation = REQUIRES_NEW`, Spring tries to join the current transaction — but after `AFTER_COMMIT` fires there is no active transaction. JPA operations fail with `javax.persistence.TransactionRequiredException: No EntityManager with actual transaction available for current thread`.

**Why it happens:** `AFTER_COMMIT` fires during the transaction synchronization phase, after `commit()` has been called. The `ThreadLocal` transaction context has been cleared.

**How to avoid:** Always pair `@TransactionalEventListener(phase = AFTER_COMMIT)` with `@Transactional(propagation = Propagation.REQUIRES_NEW)` when the handler needs DB access.

**Established in project:** `WebhookTransitionService.applyFinalTransition()` uses REQUIRES_NEW for exactly this reason (comment in current code: "REQUIRES_NEW: The @TransactionalEventListener(AFTER_COMMIT) fires after the commit synchronization phase where no transaction is active").

---

### Pitfall 2: N+1 fix moves from `attemptDelivery()` to `findPendingDeliveries()` Hibernate lazy load

**What goes wrong:** If the `WebhookDeliveryLog` entity had a `@ManyToOne Tenant` association (it does NOT), Hibernate would lazy-load each tenant when accessing `delivery.getTenant()`. In this codebase `WebhookDeliveryLog` stores `tenantId` as a plain `Long` (no JPA association). The N+1 comes only from the explicit `tenantRepository.findById()` call in `attemptDelivery()`.

**How to avoid:** The correct fix is bulk-loading via `findAllById(tenantIds)` in the job before the loop. No lazy-load risk here — no associations.

---

### Pitfall 3: Existing `WebhookDeliveryIT` Test 1 asserts `log.getDelivered() == true`

**What goes wrong:** After the WEBHOOK-02 fix, enqueue fires after the state-transition commit (via `@TransactionalEventListener(AFTER_COMMIT)`). The existing `Thread.sleep(500)` in Test 1 should still be sufficient because the AFTER_COMMIT listener was already firing asynchronously. However: the WEBHOOK-02 fix adds a SECOND async hop (AFTER_COMMIT of the enqueue-requested event, which fires after AFTER_COMMIT of the state-transition). This may require verifying the sleep is long enough in tests.

**How to avoid:** Keep the `Thread.sleep(500)` pattern from the existing tests. If flakiness appears, increase to 1000ms. Since `WebhookDeliveryIT` already uses this pattern and it passes, a second AFTER_COMMIT hop should still complete within 500ms on the Testcontainers JVM.

---

### Pitfall 4: `SimpleClientHttpRequestFactory` timeout units

**What goes wrong:** `setConnectTimeout(Duration)` does NOT exist on `SimpleClientHttpRequestFactory`. The method signature is `setConnectTimeout(int millis)` — plain `int`, in milliseconds. Passing `Duration.ofSeconds(5)` will not compile.

**How to avoid:** Use integer literals: `factory.setConnectTimeout(5_000)` and `factory.setReadTimeout(10_000)`.

---

### Pitfall 5: Enqueue failure swallowing vs. propagating

**What goes wrong:** If the `@TransactionalEventListener(AFTER_COMMIT)` for enqueue throws, Spring will log the exception but the state-transition commit has already happened — no rollback is possible. However, throwing from an AFTER_COMMIT listener can suppress subsequent synchronization callbacks. This is acceptable here (no other callbacks expected) but the exception must be caught and logged inside the handler to avoid noise.

**How to avoid:** Wrap the `enqueue()` call inside the listener with try/catch, log the failure as ERROR, and return normally.

---

## Code Examples

### WEBHOOK-01: Bulk tenant load in WebhookDeliveryService
```java
// New method in WebhookDeliveryService
public Map<Long, Tenant> loadTenants(Set<Long> tenantIds) {
    return tenantRepository.findAllById(tenantIds).stream()
        .collect(Collectors.toMap(Tenant::getId, Function.identity()));
}

// New overload in WebhookDeliveryService — tenant pre-loaded by job
@Transactional
public void attemptDelivery(WebhookDeliveryLog delivery, Tenant tenant) {
    if (tenant == null) {
        log.warn("Tenant not found for webhook delivery", ...);
        return;
    }
    attemptDeliveryInternal(delivery, tenant);
    repo.save(delivery);
}
```

### WEBHOOK-01: Updated WebhookDeliveryJob
```java
private void runDelivery() {
    List<WebhookDeliveryLog> pending = deliveryService.findPendingDeliveries();
    if (pending.isEmpty()) return;
    Set<Long> tenantIds = pending.stream()
        .map(WebhookDeliveryLog::getTenantId)
        .collect(Collectors.toSet());
    Map<Long, Tenant> tenantMap = deliveryService.loadTenants(tenantIds);  // 1 query
    for (WebhookDeliveryLog delivery : pending) {
        try {
            deliveryService.attemptDelivery(delivery, tenantMap.get(delivery.getTenantId()));
        } catch (Exception e) { ... }
    }
}
```

### WEBHOOK-02: Event record + listener pattern
```java
// Immutable event record (same style as WebhookReceivedEvent)
public record WebhookEnqueueRequestedEvent(
    String transactionId, Long tenantId, String eventType,
    TransactionStatus status, String externalReference, BigDecimal feeAmount) {}

// In WebhookTransitionService — inject ApplicationEventPublisher
// Replace: webhookDeliveryService.enqueue(...)
// With:
eventPublisher.publishEvent(new WebhookEnqueueRequestedEvent(...));

// In WebhookDeliveryService (or separate listener bean)
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void onEnqueueRequested(WebhookEnqueueRequestedEvent event) {
    try {
        enqueue(event.transactionId(), event.tenantId(), event.eventType(),
                event.status(), event.externalReference(), event.feeAmount());
    } catch (Exception e) {
        log.error("Webhook enqueue failed after commit — delivery skipped",
            kv("transactionId", event.transactionId()), e);
    }
}
```

### WEBHOOK-03: Timeout configuration
```java
@Bean
public RestTemplate noRetryRestTemplate() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(5_000);
    factory.setReadTimeout(10_000);
    return new RestTemplate(factory);
}
```

### WEBHOOK-01 test: Query count assertion
```java
// In WebhookDeliveryIT or a new WebhookDeliveryJobIT
// Requires @TestPropertySource(properties = {"log.database.spy=true", "datasource.container=true"})
@Autowired QueryCountVerifier queryCountVerifier;

// Seed N distinct tenants, each with a FAILED pending delivery row
// Direct-invoke deliveryService.findPendingDeliveries() + job logic
queryCountVerifier.reset();
job.executeForTest();  // or invoke the job's runDelivery logic
queryCountVerifier.assertSelectCountAtMost(3); // 1 pending fetch + 1 tenant bulk load + 1 save
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Per-delivery `findById` | Bulk `findAllById` before loop | Phase 37 | Eliminates N+1 |
| Direct `enqueue()` in state-transition tx | AFTER_COMMIT event listener | Phase 37 | Rollback-safe delivery scheduling |
| No timeouts on `SimpleClientHttpRequestFactory` | Explicit 5s/10s timeouts | Phase 37 | Bounded Quartz thread hold time |

---

## Open Questions

1. **Should `attemptDelivery(WebhookDeliveryLog)` (the original single-delivery overload) be retained?**
   - What we know: It is currently called by the job loop. After the fix, the job uses the tenant-aware overload.
   - What's unclear: Whether any other caller uses the original signature.
   - Recommendation: Scan callers. If none remain after the job fix, remove the old overload to prevent future N+1 regression. If the original signature is needed (e.g., the IT test creates delivery logs and calls `attemptDelivery` directly), keep it as a private or package-private method.

2. **Does the WEBHOOK-02 fix require a Flyway migration?**
   - What we know: `WebhookEnqueueRequestedEvent` is a plain Java record in memory — no persistence. No schema change needed.
   - Recommendation: No migration required.

3. **Can `WebhookDeliveryIT` test WEBHOOK-02 rollback isolation?**
   - What we know: The existing IT tests verify happy path delivery. A rollback isolation test would need to force a rollback of `applyFinalTransition` and assert no `WebhookDeliveryLog` row was created.
   - What's unclear: How to force a partial rollback in a Spring Boot IT while the rest of the flow completes.
   - Recommendation: Write a unit-level test that publishes the event directly, rolls back the publishing transaction, and asserts the listener does not fire. Alternatively, document the rollback guarantee as "structurally enforced by AFTER_COMMIT" and test only the positive path in ITs.

---

## Environment Availability

Step 2.6: SKIPPED — phase is purely code/config changes to existing Spring Boot service with no new external dependencies.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test + Testcontainers (PostgreSQL 14.18 + Redis) |
| Config file | `src/test/resources/application.properties` |
| Quick run command | `mvn test -pl . -Dtest=WebhookDeliveryIT -Dsurefire.failIfNoSpecifiedTests=false` |
| Full suite command | `mvn verify` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| WEBHOOK-01 | N deliveries → 1 tenant query per tick | integration | `mvn verify -Dit.test=WebhookDeliveryIT` | ❌ new test needed |
| WEBHOOK-02 | Enqueue fires only after commit; rollback leaves no delivery row | integration | `mvn verify -Dit.test=WebhookDeliveryIT` | ❌ new test needed |
| WEBHOOK-03 | RestTemplate has ≤5s connect, ≤10s read timeout | unit/config | assertion in `WebhookConfig` bean wiring test or checked via `RestTemplate` request factory inspection | ❌ new test needed |

### Sampling Rate
- **Per task commit:** `mvn test -Dtest=WebhookDeliveryIT`
- **Per wave merge:** `mvn verify`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] New test method in `WebhookDeliveryIT` — covers WEBHOOK-01 (N+1 query count)
- [ ] New test method in `WebhookDeliveryIT` — covers WEBHOOK-02 (post-commit enqueue isolation)
- [ ] New test method or assertion in `WebhookDeliveryIT` — covers WEBHOOK-03 (timeout configured on `noRetryRestTemplate` bean)

*(Existing test infrastructure covers the framework setup; no new framework install needed.)*

---

## Sources

### Primary (HIGH confidence)
- Direct code inspection: `WebhookDeliveryService.java`, `WebhookDeliveryJob.java`, `WebhookTransitionService.java`, `WebhookConfig.java`, `WebhookDeliveryLog.java`, `TenantRepository.java`, `WebhookDeliveryLogRepository.java`
- Direct code inspection: `WebhookDeliveryIT.java`, `QueryCountVerifier.java`, `TestConfig.java`
- STATE.md: Phase 32-02 decision ("Email pattern: `@EventListener` on listener, Envelope -> MailManager `@TransactionalEventListener(AFTER_COMMIT)`")
- STATE.md: Established `REQUIRES_NEW` rationale for AFTER_COMMIT handlers (already in `WebhookTransitionService` comments)
- REQUIREMENTS.md: WEBHOOK-01, WEBHOOK-02, WEBHOOK-03 requirement text

### Secondary (MEDIUM confidence)
- Spring Framework docs: `SimpleClientHttpRequestFactory.setConnectTimeout(int)` / `setReadTimeout(int)` accept milliseconds (verified by API signature — no `Duration` overload exists on this class)
- Spring Framework docs: `@TransactionalEventListener(phase = AFTER_COMMIT)` fires after outer transaction commits; must pair with `REQUIRES_NEW` for JPA operations

### Tertiary (LOW confidence)
- None.

---

## Metadata

**Confidence breakdown:**
- Bug identification: HIGH — confirmed by reading source files; bugs are unambiguous
- Fix patterns: HIGH — patterns established in project (AFTER_COMMIT + REQUIRES_NEW) and standard JPA (findAllById)
- Test approach: HIGH — datasource-proxy infrastructure already in classpath and wired
- Spring API details (SimpleClientHttpRequestFactory timeout units): HIGH — confirmed by class API

**Research date:** 2026-04-14
**Valid until:** 2026-05-14 (stable Spring Boot 3.5 API; no fast-moving dependencies)
