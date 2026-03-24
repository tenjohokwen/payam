# Phase 8: Admin Dashboard + Monitoring - Research

**Researched:** 2026-03-24
**Domain:** Spring Boot REST/SSE, Micrometer custom metrics, Quasar 2 SPA (Vue 3 + Pinia)
**Confidence:** HIGH — all findings verified against actual codebase files

---

## Summary

The codebase is well-prepared for Phase 8. Spring Boot Actuator with Micrometer Prometheus is
already on the classpath and configured. Quasar 2 (Vue 3, Pinia, vue-router 4, vue-i18n 11, axios)
is built via frontend-maven-plugin from `src/frontend/` and bundled as a SPA into
`target/classes/static/`. The admin dashboard pages will be new Quasar pages within the existing
SPA — the project structure, routing patterns, and API client conventions are all established.

The core data model for transaction investigation is complete: `Transaction` (with `tenantId`,
`transactionId`, `traceId`, `provider`, `txStatus`, `riskScore`), `PaymentEventLog`
(immutable hash-chained event log with `actor`, `metadata`, `eventType`, `statusFrom`,
`statusTo`), and `LedgerEntry`. All three live in the `main` schema and have indexed foreign keys.

No SSE endpoints exist yet — this is net-new. No Micrometer custom counters exist yet beyond
the tracing/OTEL setup. The actuator exposes Prometheus at `/manage/prometheus` (behind `ROLE_ADMIN`
for `/manage/**`), which feeds Grafana. The SSE metrics feed will be a new `/v1/admin/metrics/stream`
endpoint in the JWT filter chain (not the tenant API-key chain).

**Primary recommendation:** Admin REST endpoints go under `/v1/admin/**` using JWT + `ROLE_ADMIN`
(not API-key auth). `SseEmitter` is the correct Spring MVC tool for the live metrics feed — not
`Flux` (no reactive stack). Quasar pages follow the existing `src/pages/` layout with axios calls
through the established `api/` module pattern.

---

## Standard Stack

### Core (already in pom.xml — no new Maven dependencies needed)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `spring-boot-starter-actuator` | via BOM | Prometheus/Micrometer endpoint at `/manage/prometheus` | Already declared, Prometheus scraping active |
| `micrometer-registry-prometheus` | via BOM | Exposes metrics as Prometheus text format | Already declared as runtime dep |
| `micrometer-tracing-bridge-otel` | via BOM | Distributed tracing, `Tracer` already injected in `TransactionService` | Already declared |
| `spring-boot-starter-web` | via BOM | `SseEmitter`, `ResponseEntity`, `@RestController` | Already declared |
| Spring Data JPA | via BOM | `JpaRepository` for all query needs | Already declared |
| Quasar 2.16 | package.json | UI framework (components, layout, Notify plugin) | Already installed |
| axios 1.x | package.json | HTTP client for API calls | Already installed, interceptors configured |
| Pinia 3.x | package.json | Vue state management | Already installed |
| vue-i18n 11 | package.json | i18n for all UI strings | Already installed |

### No New Maven Dependencies Required

The `MeterRegistry` bean is auto-configured by `spring-boot-starter-actuator` + `micrometer-registry-prometheus`.
`SseEmitter` is in `spring-boot-starter-web`. `@PreAuthorize` with role checks is in
`spring-boot-starter-security`. Everything needed for Phase 8 is already on the classpath.

### Quasar npm Packages to Add (frontend only)

| Package | Purpose | Install |
|---------|---------|---------|
| None required | Chart.js or similar for live metrics charting is optional; Quasar's `QLinearProgress`, `QKnob`, and `QCard` can display numeric KPIs without a chart library | — |

For a live TPS/latency chart, consider `chart.js` + `vue-chartjs`. This is optional — the
requirement is to show live data, not specifically to show it as a chart.

---

## Architecture Patterns

### Security: Admin Endpoints Use JWT Chain, Not API-Key Chain

This is the most important architectural decision.

- `TenantSecurityConfig` (`@Order(1)`) intercepts `GET/POST /v1/**` BUT NOT `/v1/account/**`
  and requires `X-Api-Key` header (tenant auth).
- `SecurityConfiguration` (no `@Order`, lowest priority) handles everything else, including
  `/v1/admin/**`, requiring JWT cookie + authority check.
- `AppEndpoints.SECURED_MAPPINGS` maps `/v1/**` to `ROLE_ADMIN`, `ROLE_LTD_ADMIN`, `ROLE_USER`.
- **Existing pattern:** `AdminLoginResource` at `/v1/admin/...` with `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)`.
  `TenantAdminResource` at `/v1/admin/tenants` uses no explicit `@PreAuthorize` but falls through to
  the JWT chain.

**Decision:** New admin transaction/metrics endpoints go at `/v1/admin/transactions/**` and
`/v1/admin/metrics/**`. They should use `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)` on the
controller class.

The Quasar SPA uses JWT cookie auth (set by `JWTAuthenticationFilter`). The admin views use the
same cookie-based session as the existing Dashboard/Profile pages. No new auth mechanism is needed.

### Recommended Project Structure (new files only)

```
src/main/java/com/softropic/payam/
├── admin/
│   ├── api/
│   │   ├── AdminTransactionResource.java    # 08-01: search + detail REST endpoints
│   │   └── AdminMetricsResource.java        # 08-02/08-03: SSE metrics stream endpoint
│   ├── contract/
│   │   ├── TransactionSearchRequest.java    # query params record
│   │   ├── TransactionSummaryDto.java       # list view row
│   │   └── TransactionDetailDto.java        # full detail with event timeline
│   └── service/
│       ├── AdminTransactionQueryService.java # queries Transaction + PaymentEventLog
│       └── PaymentMetricsService.java        # Micrometer counters + SSE publisher

src/frontend/src/
├── pages/admin/
│   ├── AdminDashboardPage.vue              # live metrics: TPS, rates, latency
│   ├── TransactionSearchPage.vue           # search form + results table
│   └── TransactionDetailPage.vue           # full event timeline view
├── api/
│   └── admin.api.js                        # admin-specific API calls
└── stores/
    └── admin-metrics.store.js              # Pinia store for SSE state
```

### Pattern 1: Admin REST Endpoints for Transaction Search

**What:** JPQL queries against `Transaction` with optional filters, returning paginated results.
**When to use:** ADMIN-02 requirement — search by `transactionId`, phone (stored as `externalReference`), or `traceId`.

The `TransactionRepository` currently has only:
- `findByTransactionId(String)` — exact match
- `findByTenantIdOrderByCreatedDateDesc(Long)` — all for a tenant
- `findByPayToken(String)` — internal

New queries needed for admin search:
```java
// Source: verified against TransactionRepository.java and Transaction entity fields
@Query("SELECT t FROM Transaction t WHERE " +
       "(:transactionId IS NULL OR t.transactionId = :transactionId) AND " +
       "(:traceId IS NULL OR t.traceId = :traceId) AND " +
       "(:externalReference IS NULL OR t.externalReference = :externalReference) AND " +
       "(:tenantId IS NULL OR t.tenantId = :tenantId) " +
       "ORDER BY t.createdDate DESC")
Page<Transaction> adminSearch(
    @Param("transactionId") String transactionId,
    @Param("traceId") String traceId,
    @Param("externalReference") String externalReference,
    @Param("tenantId") Long tenantId,
    Pageable pageable);
```

Note: Phone number is stored as `externalReference` on `Transaction`. There is no dedicated phone
column — the client passes it as `externalReference` in `PaymentRequest`. Confirmed in `V3__transaction_schema.sql`.

**Pagination:** Use Spring Data `Pageable` with `PageRequest.of(page, size, Sort.by("createdDate").descending())`.
Default page size: 20.

### Pattern 2: Transaction Detail — Event Timeline

**What:** Fetch `Transaction` + all `PaymentEventLog` events ordered by `createdDate ASC`.
**Source of truth:** `PaymentEventLogRepository.findByTransactionIdOrderByCreatedDateAsc(String)`.

The `PaymentEventLog` entity contains: `eventType`, `statusFrom`, `statusTo`, `actor`,
`metadata` (JSONB), `createdDate`, `previousHash`, `eventHash`.

`metadata` is stored as a JSON string. The detail DTO should parse and return it as-is or typed.

### Pattern 3: SSE for Live Metrics Feed

**What:** Spring MVC `SseEmitter` held per connected admin client, pushed to on a fixed schedule.
**When to use:** ADMIN-01 — live TPS, success/failure rates, fraud rate, provider latency.

Spring Boot's `SseEmitter` (synchronous web stack) is correct for this project because it uses
`spring-boot-starter-web` (Tomcat), not `spring-boot-starter-webflux`. Using `Flux`/`ServerSentEvent`
requires webflux and is incompatible with the current stack.

```java
// Source: Spring Boot 3.x SseEmitter pattern — verified against spring-boot-starter-web on classpath
@GetMapping(value = "/v1/admin/metrics/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter streamMetrics() {
    SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
    // register emitter with PaymentMetricsService for periodic push
    metricsService.addEmitter(emitter);
    return emitter;
}
```

The `PaymentMetricsService` maintains a `CopyOnWriteArrayList<SseEmitter>` and uses a
`@Scheduled` method (Quartz is already on classpath but Spring's `@Scheduled` is simpler for
in-process push — no job persistence needed) to push metric snapshots every 5 seconds.

**Cleanup:** Register `onCompletion`, `onTimeout`, and `onError` callbacks on each `SseEmitter`
to remove it from the list when the client disconnects.

### Pattern 4: Micrometer Custom Counters/Timers

**What:** Register named `Counter`, `Timer`, and `Gauge` objects via `MeterRegistry`.
**Where:** `PaymentMetricsService` constructor receives `MeterRegistry` (auto-injected by Spring Boot).

```java
// Source: Micrometer API — verified against io.micrometer in pom.xml
private final Counter paymentSuccessCounter;
private final Counter paymentFailedCounter;
private final Counter fraudBlockedCounter;
private final Timer providerLatencyTimer;  // per provider, tagged

public PaymentMetricsService(MeterRegistry registry) {
    this.paymentSuccessCounter = Counter.builder("payment.success.total")
        .description("Total successful payments")
        .register(registry);
    this.paymentFailedCounter = Counter.builder("payment.failed.total")
        .description("Total failed payments")
        .register(registry);
    this.fraudBlockedCounter = Counter.builder("payment.fraud.blocked.total")
        .description("Total fraud-blocked payments")
        .register(registry);
    // Per-provider timer uses tags
    this.providerLatencyTimer = null; // use Timer.builder(...).tag("provider", name).register(registry)
}
```

The counters are incremented by `PaymentOrchestrator` (or via Spring events) when payments
succeed, fail, or are fraud-blocked. A `Gauge` for "current in-flight PROCESSING transactions"
can query `TransactionRepository.countByTxStatus(TransactionStatus.PROCESSING)`.

The SSE snapshot DTO wraps the current Micrometer counter values read via `registry.find(...)`.

### Pattern 5: Quasar Page for SSE

**What:** Vue 3 Composition API page consuming an `EventSource` for the SSE stream.
**Framework:** Quasar 2.16, Vue 3, Pinia store for reactive state.

```javascript
// Source: verified against src/frontend/src/boot/axios.js and existing page patterns
import { ref, onMounted, onUnmounted } from 'vue'
import { useAdminMetricsStore } from 'src/stores/admin-metrics.store.js'

const store = useAdminMetricsStore()
let eventSource = null

onMounted(() => {
  eventSource = new EventSource('/v1/admin/metrics/stream', { withCredentials: true })
  eventSource.onmessage = (event) => {
    store.updateMetrics(JSON.parse(event.data))
  }
  eventSource.onerror = () => {
    // reconnect handled by browser EventSource spec automatically
  }
})

onUnmounted(() => {
  eventSource?.close()
})
```

`withCredentials: true` is required because the SPA uses cookie-based JWT auth and the browser
must send cookies with the SSE connection. Confirmed from `axios.create({ withCredentials: true })`
in `boot/axios.js`.

### Anti-Patterns to Avoid

- **Using the Tenant API-Key chain for admin endpoints:** Admin routes `/v1/admin/**` are matched
  by `TenantSecurityConfig` (`@Order(1)`) since they match `/v1/**` (but NOT `/v1/account/**`).
  This means admin endpoints will require an API key, not a JWT, unless the matcher is updated.
  **Solution:** Extend the `NegatedRequestMatcher` in `TenantSecurityConfig` to also exclude
  `/v1/admin/**`, or add `/v1/admin/**` to `AppEndpoints.PUBLIC_ENDPOINTS` with a subsequent
  `@PreAuthorize` guard. The cleanest approach is to exclude `/v1/admin/**` from the tenant
  API-key chain so it falls through to the JWT chain.

- **Using `Page<Transaction>` directly as response body:** The `Transaction` entity contains
  internal fields (`payToken`, `pollAttempts`, `deviceFingerprint`) that should not be exposed.
  Always map to a DTO before returning.

- **Querying `PaymentEventLog` by phone number:** Phone is not stored on `PaymentEventLog`.
  Only `Transaction` has `externalReference` (phone). The admin search must query `Transaction`
  first, then return the event timeline for the found transaction.

- **Using `@Component` on any new filter:** The existing codebase explicitly avoids this for
  `ApiKeyAuthenticationFilter`. For admin endpoints there is no new filter needed — just
  `@PreAuthorize` on the controller.

- **Blocking async thread in SSE emitter push:** `SseEmitter.send()` must be called from a
  non-request thread. The `@Scheduled` method runs in the scheduler thread pool — this is correct.
  Do NOT call `send()` inside a request handler.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Prometheus metrics exposure | Custom `/metrics` endpoint | `spring-boot-actuator` at `/manage/prometheus` | Already configured, Grafana already scrapes it |
| TPS calculation | Time-windowed counter | Micrometer `Counter` + compute rate in SSE snapshot | Micrometer handles thread-safe incrementing |
| Transaction pagination | Cursor-based custom impl | Spring Data `Pageable` + `@Query` | Already pattern used elsewhere in project |
| SSE reconnection | Client-side retry loop | Browser `EventSource` spec | Automatic reconnect per SSE spec; just close/reopen emitter |
| Role-based access control | Custom auth filter | `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)` | Already in use on `AdminLoginResource` |
| Event timeline ordering | Manual sort | `findByTransactionIdOrderByCreatedDateAsc(String)` | Already in `PaymentEventLogRepository` |

**Key insight:** Micrometer's `MeterRegistry` is auto-configured. `Counter.increment()` is
thread-safe. The Prometheus scrape endpoint is already accessible to Grafana. Phase 8 only needs
to register named metrics and wire them into the payment flow.

---

## Common Pitfalls

### Pitfall 1: TenantSecurityConfig Intercepts /v1/admin/**

**What goes wrong:** New `@RestController` at `/v1/admin/transactions/**` requires JWT but
gets rejected with 401 because `TenantSecurityConfig` (`@Order(1)`) intercepts it first
and requires an `X-Api-Key` header.

**Why it happens:** `TenantSecurityConfig.tenantApiKeyFilterChain()` matches ALL of `/v1/**`
except `/v1/account/**`. Admin endpoints are under `/v1/admin/**`, which is a subset of `/v1/**`.

**How to avoid:** In `TenantSecurityConfig.tenantApiKeyFilterChain()`, extend the `securityMatcher`
to also exclude `/v1/admin/**`:
```java
RequestMatcher tenantPaths = new AndRequestMatcher(
    new AntPathRequestMatcher("/v1/**"),
    new NegatedRequestMatcher(new OrRequestMatcher(
        new AntPathRequestMatcher("/v1/account/**"),
        new AntPathRequestMatcher("/v1/admin/**")
    ))
);
```

**Warning signs:** POST /v1/payments returns 202 but GET /v1/admin/transactions returns 401
even with a valid JWT cookie.

### Pitfall 2: SSE Connection Dropped by Tomcat Idle Timeout

**What goes wrong:** Long-lived SSE connections are closed by Tomcat after the default async
request timeout (10 seconds in some configurations).

**Why it happens:** `SseEmitter` sets `timeout` in milliseconds. Default Tomcat async timeout
may be 10000ms.

**How to avoid:** Set `SseEmitter` timeout to `Long.MAX_VALUE` (effectively infinite) and rely
on client reconnection:
```java
SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
```
Also set `spring.mvc.async.request-timeout=-1` in `application.yaml` if needed.

**Warning signs:** SSE stream disconnects ~10 seconds after opening.

### Pitfall 3: Cross-Tenant Data Leak

**What goes wrong:** An admin authenticated as `ROLE_LTD_ADMIN` (limited admin) queries
transactions for all tenants.

**Why it happens:** The admin search endpoint accepts `tenantId` as an optional filter.
If `ROLE_LTD_ADMIN` is not scoped to a specific tenant, they could pass any `tenantId`.

**How to avoid:** For `ROLE_ADMIN` (superadmin), allow unscoped queries. For `ROLE_LTD_ADMIN`,
enforce that the `tenantId` filter matches the tenant associated with their account. The
`AuthoritiesConstants` already distinguishes `ADMIN` from `LTD_ADMIN`.

**Warning signs:** Requirements say "Per-client transaction history is scoped to the authenticated
tenant — cross-tenant data never appears." This applies to tenant-facing endpoints (via API-key
chain). Admin endpoints are different — `ROLE_ADMIN` legitimately sees all tenants.
Clarify scope before implementing.

### Pitfall 4: SseEmitter Memory Leak on Client Disconnect

**What goes wrong:** `SseEmitter` objects accumulate in the emitter list because disconnected
clients are never removed.

**Why it happens:** When a client closes the browser tab, `SseEmitter.send()` throws
`IOException`. If the exception is caught silently, the emitter stays in the list.

**How to avoid:** Register completion/error/timeout callbacks:
```java
emitter.onCompletion(() -> emitters.remove(emitter));
emitter.onTimeout(() -> emitters.remove(emitter));
emitter.onError(ex -> emitters.remove(emitter));
```
Use `CopyOnWriteArrayList<SseEmitter>` for thread safety.

### Pitfall 5: Micrometer Counter Values Not Readable for SSE Snapshot

**What goes wrong:** Trying to read `Counter.count()` returns a `double` representing the
total-since-start, not a per-interval rate (TPS).

**Why it happens:** Micrometer `Counter` is cumulative. TPS requires measuring the delta over
an interval.

**How to avoid:** Maintain an `AtomicLong previousCount` snapshot. On each SSE push interval
(e.g., 5 seconds), compute `delta = currentCount - previousCount` then `tps = delta / intervalSeconds`.
Or use Micrometer's `FunctionCounter` pattern to expose a supplier.

Alternatively, expose TPS via a Prometheus `rate()` query in Grafana and only show the raw counter
on the SSE feed. The SSE feed can expose cumulative totals + computed rates.

---

## Code Examples

### Existing Security Pattern to Follow

```java
// Source: src/main/java/com/softropic/payam/security/api/AdminLoginResource.java
@RestController
@RequestMapping("/v1/admin")
@RequiredArgsConstructor
public class AdminLoginResource {

    @DeleteMapping("/users/{username}/login-lock")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    public ResponseEntity<Void> unlockUserLoginAttempts(...) { ... }
}
```

New admin controllers follow this exact pattern: `@RequestMapping("/v1/admin/transactions")`,
`@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)` on the class.

### Existing Repository Query Pattern

```java
// Source: src/main/java/com/softropic/payam/transaction/repo/TransactionRepository.java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT t FROM Transaction t WHERE t.transactionId = :transactionId")
Optional<Transaction> findByTransactionIdForUpdate(@Param("transactionId") String transactionId);
```

Admin search follows the same `@Query` + `@Param` convention. No `@Lock` needed for read-only
admin queries.

### Quasar Page Pattern (Composition API)

```javascript
// Source: src/frontend/src/pages/DashboardPage.vue (existing pattern)
<script setup>
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { authApi } from 'src/api/auth.api';
// ...
</script>
```

New admin pages follow the same `<script setup>` Composition API pattern. API calls go through
dedicated `admin.api.js` module (mirrors existing `account.api.js`, `session.api.js`).

### Quasar Router Registration Pattern

```javascript
// Source: src/frontend/src/router/routes.js
{
  path: 'admin',
  meta: { requiresAuth: true, requiresAdmin: true },
  children: [
    { path: 'dashboard', component: () => import('pages/admin/AdminDashboardPage.vue') },
    { path: 'transactions', component: () => import('pages/admin/TransactionSearchPage.vue') },
    { path: 'transactions/:transactionId', component: () => import('pages/admin/TransactionDetailPage.vue') },
  ],
},
```

Add a navigation guard in `src/router/index.js` to check `requiresAdmin` meta and verify
the user has admin role (from cookie or a `/v1/account/` response that includes roles).

---

## Existing Codebase Inventory

### Admin-Related Controllers (existing)

| File | Path | Purpose |
|------|------|---------|
| `AdminLoginResource.java` | `/v1/admin/users/{username}/login-lock` | Unlock user login attempts — model for new admin controllers |
| `TenantAdminResource.java` | `/v1/admin/tenants` | Tenant CRUD — shares same URL prefix |
| `WebhookDeliveryResource.java` | `/v1/webhooks/deliveries/{transactionId}` | Delivery log by transactionId — read pattern |

### Micrometer Usage (existing)

| File | What It Uses |
|------|-------------|
| `TransactionService.java` | `io.micrometer.tracing.Tracer` — injected to read `traceId` from current span |
| `ObservabilityConfig.java` | `ObservationRegistry`, `ObservedAspect` — enables `@Observed` annotation |
| `AccountResource.java`, `MailService.java` | `ObservationRegistry` for `@Observed` or `Observation.start()` |

No payment-domain `Counter`/`Timer`/`Gauge` metrics exist yet. Phase 8 creates them.

### Actuator Configuration (application.yaml)

```yaml
management:
  endpoints:
    web:
      exposure:
        include: "*"
      base-path: /manage   # Prometheus is at /manage/prometheus
  endpoint:
    health:
      show-details: when-authorized
      roles: ROLE_ADMIN
```

`AppEndpoints.ACTUATOR` = `/manage/**` is mapped to `ROLE_ADMIN` in `SECURED_MAPPINGS`.
The Prometheus scrape endpoint is already secured and working.

### Transaction Entity Fields Available for Admin Search

| Field | Column | Type | Searchable |
|-------|--------|------|-----------|
| `transactionId` | `transaction_id` | `VARCHAR(36)` | Exact match |
| `traceId` | `trace_id` | `VARCHAR(255)` | Exact match |
| `externalReference` | `external_reference` | `VARCHAR(255)` | Exact match (phone number) |
| `tenantId` | `tenant_id` | `BIGINT` | Filter |
| `txStatus` | `tx_status` | enum | Filter |
| `provider` | `provider` | enum | Filter |
| `riskScore` | `risk_score` | `INTEGER` | Range filter |
| `createdDate` | `created_date` | `TIMESTAMP` | Date range |

Indexes: `idx_transaction_tenant_id`, `idx_transaction_external_ref`, `idx_transaction_provider_ref`.
Missing: no index on `transaction_id` search — it has `UNIQUE` constraint which serves as index.
Missing: no index on `trace_id` — will need `CREATE INDEX` in V11 migration for admin search.

### PaymentEventLog Fields for Timeline

| Field | Purpose in Admin Detail |
|-------|------------------------|
| `eventType` | Display as step label (PAYMENT_INITIATED, FRAUD_CHECK_PASSED, etc.) |
| `statusFrom` | Previous state |
| `statusTo` | New state |
| `actor` | Who triggered (system, provider, orchestrator) |
| `metadata` | JSONB — raw event payload for investigation |
| `createdDate` | Timestamp of transition |
| `eventHash` | Chain integrity proof |

### Quasar Frontend Structure

```
src/frontend/src/
├── api/              # Axios-based API modules (account.api.js, session.api.js, auth.api.js)
├── boot/
│   ├── axios.js      # Axios instance with withCredentials:true, loading state, session management
│   └── i18n.js       # vue-i18n setup
├── composables/      # useErrorHandler.js, useLoading.js, useSession.js
├── layouts/
│   └── MainLayout.vue  # Header + drawer navigation — must add admin menu items here
├── pages/
│   ├── auth/         # Login, Register, OTP, ForgotPassword, ResetPassword, Activate
│   ├── DashboardPage.vue  # Currently a stub — will need admin-specific dashboard logic
│   └── ProfilePage.vue
├── plugins/
│   └── sessionManager.js  # Session inactivity / refresh logic
├── router/
│   ├── index.js       # Navigation guards (requiresAuth, requiresGuest)
│   └── routes.js      # Route definitions
└── stores/
    └── index.js       # Pinia root (no domain stores yet — add admin-metrics.store.js)
```

**Build:** `quasar build` outputs to `src/frontend/dist/spa/`. Maven `copy-resources` plugin
copies this to `target/classes/static/`. Spring Boot serves it as static resources.
The devServer proxy in `quasar.config.js` forwards `/v1/account`, `/api`, `/authenticate` to
`localhost:9990` — the new `/v1/admin/**` proxy rule must be added for local dev.

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `WebFlux Flux<ServerSentEvent>` | `SseEmitter` (servlet stack) | N/A — project uses Tomcat, not Netty | Must use `SseEmitter`, not `Flux` |
| Micrometer 1.x `.builder()` pattern | Same API in Micrometer 1.13 (via Spring Boot 3.5.11) | Stable | No change needed |
| Quasar 1.x (Vue 2) | Quasar 2.x (Vue 3) | Project uses Quasar 2.16 | Composition API (`<script setup>`), Pinia (not Vuex) |

**Deprecated/outdated:**
- Vuex: Project uses Pinia. Do not add Vuex stores.
- Vue Options API: All existing pages use `<script setup>`. Follow this pattern.

---

## Open Questions

1. **TenantSecurityConfig exclusion scope**
   - What we know: `/v1/admin/**` is intercepted by the API-key chain today
   - What's unclear: Whether admin endpoints should also accept tenant API-key auth (unlikely) or exclusively JWT
   - Recommendation: Exclude `/v1/admin/**` from `TenantSecurityConfig` matcher. This is a one-line change.

2. **LTD_ADMIN scope for transaction search**
   - What we know: `SecurityConstants.HAS_ADMIN_ROLE` allows both `ROLE_ADMIN` and `ROLE_LTD_ADMIN`
   - What's unclear: Whether `ROLE_LTD_ADMIN` should see all tenants' transactions or only their own
   - Recommendation: Implement full-access for `ROLE_ADMIN`, tenant-scoped for `ROLE_LTD_ADMIN`. Note that
     `LTD_ADMIN` is a user account role (JWT chain), not a tenant API key — so the "their own" scope
     would need a mapping from user account to tenant, which doesn't exist yet.

3. **trace_id index for admin search**
   - What we know: `trace_id` has no standalone index (`NOT NULL` but no `CREATE INDEX`)
   - What's unclear: Query volume — could cause slow scans on large tables
   - Recommendation: Add `CREATE INDEX idx_transaction_trace_id ON main.transaction(trace_id)` in V11 migration.

4. **SSE authentication — cookie forwarding**
   - What we know: Browser `EventSource` sends cookies automatically for same-origin requests
   - What's unclear: Whether the deployed app uses HTTPS + Secure cookie flags that could block SSE
   - Recommendation: Test SSE connection in dev with `withCredentials: true` on `EventSource` constructor
     (already the correct approach per existing axios config).

---

## Sources

### Primary (HIGH confidence — verified against codebase)

- `pom.xml` — confirmed `micrometer-registry-prometheus`, `spring-boot-starter-actuator`,
  `spring-boot-starter-web`, `spring-boot-starter-security` on classpath; Spring Boot 3.5.11
- `src/main/java/com/softropic/payam/transaction/repo/Transaction.java` — entity field inventory
- `src/main/java/com/softropic/payam/transaction/repo/PaymentEventLog.java` — event log field inventory
- `src/main/java/com/softropic/payam/transaction/repo/TransactionRepository.java` — existing queries
- `src/main/java/com/softropic/payam/transaction/repo/PaymentEventLogRepository.java` — existing queries
- `src/main/java/com/softropic/payam/tenant/config/TenantSecurityConfig.java` — API-key chain scope (CRITICAL)
- `src/main/java/com/softropic/payam/security/config/SecurityConfiguration.java` — JWT chain, role mappings
- `src/main/java/com/softropic/payam/security/config/AppEndpoints.java` — URL patterns and authority mappings
- `src/main/java/com/softropic/payam/security/api/AdminLoginResource.java` — admin endpoint pattern
- `src/main/java/com/softropic/payam/security/common/util/SecurityConstants.java` — `HAS_ADMIN_ROLE`
- `src/main/java/com/softropic/payam/security/contract/util/AuthoritiesConstants.java` — role constants
- `src/main/java/com/softropic/payam/config/ObservabilityConfig.java` — MeterRegistry setup
- `src/main/resources/application.yaml` — actuator config, management base-path
- `src/main/resources/db/migration/V3__transaction_schema.sql` — confirmed DB schema
- `src/main/resources/db/migration/V10__fraud_schema.sql` — risk_score column
- `src/frontend/src/router/routes.js` — existing route structure
- `src/frontend/src/layouts/MainLayout.vue` — existing layout, navigation drawer
- `src/frontend/src/pages/DashboardPage.vue` — existing page pattern (stub)
- `src/frontend/src/boot/axios.js` — `withCredentials: true`, API patterns
- `src/frontend/package.json` — Quasar 2.16, Vue 3, Pinia 3, vue-i18n 11, axios 1.x
- `src/frontend/quasar.config.js` — build config, dev proxy rules, Notify plugin registered

### Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries verified in pom.xml and package.json
- Architecture (security chain interaction): HIGH — read TenantSecurityConfig and SecurityConfiguration source
- Transaction data model: HIGH — read entity classes and migrations
- SSE pattern: HIGH — spring-boot-starter-web confirmed on classpath; SseEmitter is the correct API
- Quasar SPA structure: HIGH — read all source files in src/frontend/src/
- Micrometer counters: HIGH — micrometer-registry-prometheus confirmed; Counter/Timer API verified via pom.xml
- Pitfalls: HIGH for pitfall 1 (TenantSecurityConfig) — directly verified from source; MEDIUM for pitfall 2 (timeout) — documented Spring behavior

**Research date:** 2026-03-24
**Valid until:** 2026-04-24 (stable dependencies; no fast-moving external APIs)
