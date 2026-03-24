# Phase 10: Operational Hardening - Research

**Researched:** 2026-03-25
**Domain:** Fee engine, alert rules, TLS startup assertion, provider health, circuit breaker status, hash chain audit, MSISDN prefix table
**Confidence:** HIGH (all findings verified against codebase or JAR contents)

---

## Summary

Phase 10 adds operational controls on top of the fully-working payment system established in Phases 1-9. Five sub-domains need planning: (1) fee rules, (2) alert rules, (3) TLS startup assertion + provider health Actuator, (4) circuit breaker status endpoint, and (5) SHA-256 hash chain audit tool. An additional cross-cutting concern is making MsisdnRouter config-driven.

The standard pattern for all five domains is already established in this codebase: DB-backed entity extending `AbstractAuditingEntity`, `BaseEntity` TSID primary key, Flyway migration, `@Transactional` service, `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)` REST resource under `/v1/admin/**`. The planner should replicate this pattern for fee rules and alert rules exactly.

The Resilience4j circuit breaker status endpoint is available for free via `CircuitBreakerRegistry` which is auto-registered as a Spring Bean by `spring-cloud-starter-circuitbreaker-resilience4j`. No new dependencies are needed. `CircuitBreakersHealthIndicator` also auto-wires into `/manage/health`. The custom `/providers/status` endpoint is a thin controller calling `CircuitBreakerRegistry`.

TLS startup assertion is cleanest as an `ApplicationListener<ApplicationReadyEvent>` that inspects `TcpConfiguration.isCheckCertificate()` on the mtn and orange client configs. The existing `AppSetupException` is the right failure mechanism.

**Primary recommendation:** Model FeeRule after FraudRule (same DB pattern, same hot-reload via scheduled cache). Model AlertRule as a separate entity with threshold fields. Wire circuit breaker status to an admin controller via `CircuitBreakerRegistry`. TLS assertion via `ApplicationReadyEvent` checks `TcpConfiguration.checkCertificate`. Hash chain audit endpoint exposes existing `EventLogService.verifyChain()` via admin REST.

---

## Standard Stack

All of the following are already on the classpath — no new dependencies needed.

### Core (already present)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `spring-cloud-starter-circuitbreaker-resilience4j` | 3.3.1 | Circuit breaker + health + actuator endpoints | Already used for mtn/orange; includes `CircuitBreakerRegistry`, `CircuitBreakersHealthIndicator`, `CircuitBreakerEndpoint` |
| `resilience4j-spring-boot3` | 2.2.0 | Auto-configures CB registry as Spring bean | Transitive, verified in jar |
| `resilience4j-micrometer` | 2.2.0 | CB metrics to Micrometer | Transitive |
| `spring-boot-starter-actuator` | 3.5.11 | `/manage/health`, `/manage/circuitbreakers` | Declared in pom.xml |
| `spring-boot-starter-mail` | 3.5.11 | Email notification channel (already configured) | Declared in pom.xml |
| `commons-codec` | 1.19.0 | `DigestUtils.sha256Hex` (already used in PaymentEventLog) | Declared in pom.xml |

### No New Dependencies Required
Everything for Phase 10 is on the classpath. The planner must NOT add new Maven dependencies.

---

## Architecture Patterns

### Pattern 1: FeeRule Entity (modeled after FraudRule)

`FraudRule` is the direct template for `FeeRule`. The fraud rule entity demonstrates:
- `extends AbstractAuditingEntity` (provides TSID id, created_by, created_date, status, etc.)
- Table in `schema = "main"`
- Separate `FraudRuleCache` service doing scheduled refresh from DB
- No config file — all configuration is in the DB

**FeeRule design:**
```java
// src/main/java/com/softropic/payam/fee/repo/FeeRule.java
@Entity
@Table(name = "fee_rule", schema = "main")
@SuperBuilder @NoArgsConstructor @AllArgsConstructor @Getter
public class FeeRule extends AbstractAuditingEntity {

    /** NULL = global rule applies to all tenants; non-null = per-tenant override */
    @Column(name = "tenant_id")
    private Long tenantId;

    /** FEE_FIXED or FEE_PERCENTAGE */
    @Enumerated(EnumType.STRING)
    @Column(name = "fee_type", nullable = false)
    private FeeType feeType;

    /** Fixed amount in base currency units (e.g., 50.00 XAF) */
    @Column(name = "fixed_amount", precision = 20, scale = 2)
    private BigDecimal fixedAmount;

    /** Percentage rate (e.g., 1.50 means 1.5%) — used when feeType = FEE_PERCENTAGE */
    @Column(name = "percentage_rate", precision = 5, scale = 4)
    private BigDecimal percentageRate;

    /** Currency code for fixedAmount (e.g., "XAF") */
    @Column(name = "currency", length = 3)
    private String currency;

    /** Human-readable rule name (e.g., "MTN fixed fee", "Global 1.5%") */
    @Column(name = "rule_name", nullable = false)
    private String ruleName;

    @Column(nullable = false)
    private boolean enabled;
}
```

**Fee evaluation at transaction time (idempotency-safe):**
- Fee is evaluated AFTER idempotency check, BEFORE provider dispatch — same location as fraud check in `PaymentOrchestrator.initiate()`
- The computed fee is stored on the `Transaction` (new column `fee_amount`) so replayed idempotent responses return the same fee
- Rule priority: tenant-specific rule beats global rule (lookup by tenantId first, fall back to tenantId IS NULL)
- Store fee on `LedgerEntry` (new column) so the ledger captures the actual fee charged

**Fee cache pattern** (modeled after `FraudRuleCache`):
```java
@Service
public class FeeRuleCache {
    private volatile List<FeeRule> cachedRules;
    private final FeeRuleRepository repository;

    @Scheduled(fixedDelayString = "${fee.rule-cache.refresh-interval-ms:60000}")
    public void refresh() {
        cachedRules = repository.findAllByEnabledTrue();
    }

    public Optional<FeeRule> findForTenant(Long tenantId) {
        // Prefer tenant-specific over global
        return cachedRules.stream()
            .filter(r -> tenantId.equals(r.getTenantId()))
            .findFirst()
            .or(() -> cachedRules.stream()
                .filter(r -> r.getTenantId() == null)
                .findFirst());
    }
}
```

### Pattern 2: AlertRule Entity

`AlertRule` follows the same entity pattern as `FraudRule` and `FeeRule`:

```java
// src/main/java/com/softropic/payam/alert/repo/AlertRule.java
@Entity
@Table(name = "alert_rule", schema = "main")
public class AlertRule extends AbstractAuditingEntity {

    @Column(name = "metric_name", nullable = false)
    private String metricName;      // FAILURE_RATE, FRAUD_SPIKE_RATE, CALLBACK_ANOMALY

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal threshold;   // e.g., 0.10 = 10% failure rate

    @Column(name = "window_seconds", nullable = false)
    private int windowSeconds;      // evaluation window

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "notification_channel", nullable = false)
    private String notificationChannel;  // LOG, EMAIL (expandable later)

    @Column(length = 500)
    private String description;
}
```

**Alert wiring via `ApplicationEventPublisher`:**

The existing pattern (MtnMoMoPort, OrangeMoneyPort) publishes Spring events. Alert rules fire when `PaymentMetricsService` metrics cross thresholds. The clean approach is:

1. `AlertEvaluationService` runs on `@Scheduled` (reuse `@EnableScheduling` from `email.config.AsyncConfig`)
2. Queries Micrometer counters via `MeterRegistry` (already injected in `PaymentMetricsService`)
3. On threshold breach, publishes `AlertFiredEvent` via `ApplicationEventPublisher`
4. `AlertNotificationListener` handles the event: logs at WARN level + optionally sends email via existing `MailManager`

```java
// The evaluator queries existing Micrometer counters
double failureRate = meterRegistry.counter("payment.failed.total").count()
    / Math.max(1.0, meterRegistry.counter("payment.success.total").count()
               + meterRegistry.counter("payment.failed.total").count());
```

**Notification channel choices:**
- LOG (always): `log.warn("ALERT: {} threshold breached: actual={} threshold={}", ...)`
- EMAIL: use existing `MailManager` bean (already in context, configured for GMX/Gmail)
- Webhook: NOT in Phase 10 scope (skip for now; add channel enum extensibility only)

### Pattern 3: TLS Startup Assertion

`TcpConfiguration.checkCertificate` is the existing boolean that controls SSL verification. In `application.yaml`, `checkCertificate: false` is the current default (`*DEFAULT_TCP` YAML anchor). The startup assertion must fail fast if this is `false` in a production-like environment.

```java
@Component
public class TlsStartupAssertion implements ApplicationListener<ApplicationReadyEvent> {

    private final OrangeMoneyConfig orangeConfig;
    private final MtnMoMoConfig mtnConfig;
    private final Environment env;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        // Skip assertion in dev/test profiles
        if (Arrays.asList(env.getActiveProfiles()).contains("dev")) return;

        boolean orangeTls = orangeConfig.getTcpConfig() != null
            && orangeConfig.getTcpConfig().isCheckCertificate();
        boolean mtnTls = mtnConfig.getTcpConfig() != null
            && mtnConfig.getTcpConfig().isCheckCertificate();

        if (!orangeTls || !mtnTls) {
            throw new AppSetupException(
                "TLS certificate verification is disabled for provider(s). " +
                "Set checkCertificate: true for all providers in production.");
        }
    }
}
```

**Key observations from codebase:**
- `OrangeMoneyConfig` and `MtnMoMoConfig` both reference `TcpConfiguration` via `ClientConfiguration`
- `AppSetupException extends ApplicationException` — already used for fatal startup errors
- `email.config.AsyncConfig` has `@EnableScheduling` — that class already has it project-wide; no new config class needed for scheduling-related features

### Pattern 4: Circuit Breaker Status Endpoint

`CircuitBreakerRegistry` is auto-registered as a Spring bean by `resilience4j-spring-boot3` autoconfiguration. Inject it directly into a controller.

Resilience4j 2.2.0 `CircuitBreaker.State` has values: `CLOSED`, `OPEN`, `HALF_OPEN`, `DISABLED`, `METRICS_ONLY`, `FORCED_OPEN`.

```java
// src/main/java/com/softropic/payam/admin/api/ProviderStatusResource.java
@RestController
@RequestMapping("/v1/admin/providers")
@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
@RequiredArgsConstructor
public class ProviderStatusResource {

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @GetMapping("/status")
    public ResponseEntity<Map<String, ProviderStatusDto>> status() {
        Map<String, ProviderStatusDto> result = new LinkedHashMap<>();
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(cb -> {
            CircuitBreaker.State state = cb.getState();
            CircuitBreaker.Metrics metrics = cb.getMetrics();
            result.put(cb.getName(), new ProviderStatusDto(
                state.name(),
                metrics.getFailureRate(),
                metrics.getNumberOfBufferedCalls(),
                metrics.getNumberOfFailedCalls()
            ));
        });
        return ResponseEntity.ok(result);
    }
}
```

**Built-in actuator endpoints (already auto-configured):**
- `GET /manage/circuitbreakers` — lists all CB names and states (from `CircuitBreakerEndpoint`)
- `GET /manage/health` — includes `CircuitBreakersHealthIndicator` (reports DOWN if any CB is OPEN)

The custom `/v1/admin/providers/status` endpoint adds domain-friendly naming and is JWT-protected (consistent with other admin endpoints). The actuator endpoints at `/manage/**` require ROLE_ADMIN per `AppEndpoints.SECURED_MAPPINGS`.

### Pattern 5: SHA-256 Hash Chain Audit Tool

`EventLogService.verifyChain(String transactionId)` already exists and works correctly. The audit tool is a thin admin endpoint that exposes it. Two modes:

1. **Single transaction:** `GET /v1/admin/audit/hash-chain/{transactionId}` — calls `verifyChain(transactionId)`, returns `{valid: true/false, transactionId: "..."}`
2. **Full audit:** `GET /v1/admin/audit/hash-chain` — iterates all distinct `transactionId` values in `payment_event_log`, calls `verifyChain` for each, returns summary `{total: N, valid: N, violations: [...]}`

The full audit should be paged or async since the full event log can be large. Use `PaymentEventLogRepository` distinct query + streaming.

```java
// New query in PaymentEventLogRepository
@Query("SELECT DISTINCT e.transactionId FROM PaymentEventLog e")
List<String> findAllDistinctTransactionIds();
```

### Pattern 6: MSISDN Prefix Table (Config-Driven MsisdnRouter)

Current state: hardcoded in `MsisdnRouter.resolve()` with a comment "Phase 10 hardening concern."

**Approach: DB-backed prefix table (same pattern as FraudRule):**

```java
@Entity
@Table(name = "msisdn_prefix_route", schema = "main")
public class MsisdnPrefixRoute extends AbstractAuditingEntity {

    @Column(name = "prefix", nullable = false, unique = true)
    private String prefix;  // e.g., "65", "69", "67", "66"

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MobilePaymentProvider provider;

    @Column(nullable = false)
    private boolean enabled;
}
```

`MsisdnRouter` becomes a thin delegator to `MsisdnPrefixRouteCache` (same scheduled-refresh pattern). The hardcoded logic can be preserved as a fallback if the table is empty.

**Flyway migration inserts existing rules:**
```sql
INSERT INTO main.msisdn_prefix_route (id, prefix, provider, enabled, ...)
VALUES
  (tsid(), '65', 'ORANGE', true, ...),
  (tsid(), '69', 'ORANGE', true, ...),
  (tsid(), '60', 'MTN', true, ...),
  ...
```

### Recommended Project Structure for New Code

```
src/main/java/com/softropic/payam/
├── fee/
│   ├── repo/
│   │   ├── FeeRule.java              # entity
│   │   └── FeeRuleRepository.java
│   ├── service/
│   │   ├── FeeRuleCache.java         # @Scheduled hot-reload
│   │   └── FeeEvaluationService.java
│   └── api/
│       └── FeeRuleAdminResource.java # CRUD under /v1/admin/fees
├── alert/
│   ├── repo/
│   │   ├── AlertRule.java
│   │   └── AlertRuleRepository.java
│   ├── service/
│   │   ├── AlertEvaluationService.java  # @Scheduled, checks metrics
│   │   └── AlertNotificationListener.java  # @EventListener
│   └── contract/
│       └── AlertFiredEvent.java
├── ops/
│   └── TlsStartupAssertion.java      # ApplicationReadyEvent listener
├── admin/api/
│   ├── ProviderStatusResource.java   # GET /v1/admin/providers/status
│   └── AuditResource.java            # GET /v1/admin/audit/hash-chain[/{txId}]
└── payment/service/
    └── MsisdnRouter.java             # refactor to use DB prefix table
```

### Anti-Patterns to Avoid

- **Computing fee AFTER idempotency store:** The fee amount must be stored on the transaction row and included in the idempotency cache response. Otherwise, a fee change between the original call and a replay changes the charged amount.
- **Holding DB connection during alert evaluation:** AlertEvaluationService reads counters from Micrometer (in-memory), NOT from the DB. No `@Transactional` needed.
- **Creating a new `@EnableScheduling` config class:** `email.config.AsyncConfig` already has it. Adding it again causes a warning but not an error. Prefer adding the `@Scheduled` annotation to the service bean directly.
- **Registering TLS assertion as `@PostConstruct`:** `@PostConstruct` runs before the context is fully ready. Use `ApplicationReadyEvent` so the check runs after all beans are wired and the application is ready to serve requests — this is the correct fail-fast point for operational assertions.
- **Querying Micrometer `Counter.count()` from a `@Transactional` method:** Counters are in-memory; no DB needed. The alert evaluator must NOT be `@Transactional`.
- **Using `CircuitBreakerRegistry.circuitBreaker("name")` without checking existence:** Call `.getAllCircuitBreakers()` to iterate; calling `.circuitBreaker("unknown")` creates a new CB instance with default config rather than throwing.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Circuit breaker state query | Custom state tracker | `CircuitBreakerRegistry.getAllCircuitBreakers()` | Auto-configured by resilience4j-spring-boot3; already on classpath |
| CB health in `/manage/health` | Custom HealthIndicator | `CircuitBreakersHealthIndicator` (auto-configured) | Already wires into Actuator health endpoint from the spring-boot3 jar |
| SHA-256 hash computation | Re-implement | `DigestUtils.sha256Hex()` from commons-codec | Already used in `PaymentEventLog.create()` and `EventLogService.verifyChain()` |
| Hash chain verification | Re-implement | `EventLogService.verifyChain()` | Already implemented and correct in existing code |
| Fee/rule TSID primary key | Manual sequence | `@Tsid` from hypersistence-utils (already used by `BaseEntity`) | Consistent with all other entities in the project |
| Email notification | Build new mail client | Inject existing `MailManager` bean | Already has retry, multi-provider fallback, Testcontainer support |
| Scheduled rule cache refresh | `@PostConstruct` + timer | `@Scheduled(fixedDelayString=...)` | `@EnableScheduling` already active project-wide |

---

## Common Pitfalls

### Pitfall 1: TLS Assertion Breaks Tests
**What goes wrong:** `TlsStartupAssertion` fires on `ApplicationReadyEvent` during IT tests that use the `dev` profile. Even though `checkCertificate: false` is set in `application.yaml` (via `*DEFAULT_TCP`), in tests this becomes a startup failure.
**How to avoid:** Guard with `Arrays.asList(env.getActiveProfiles()).contains("dev")` check before the assertion. The `dev` profile is used in all existing ITs (confirmed: `@ActiveProfiles("dev")` in `ReconciliationApiIT`, `FraudEngineIT`, etc.).
**Warning signs:** Tests fail with `AppSetupException` on startup.

### Pitfall 2: Fee Computed Outside Transaction — Not Idempotency-Safe
**What goes wrong:** Fee is computed at evaluation time but not stored. When an idempotent replay happens (second call with same idempotency key), a different fee rule might now apply, returning a different amount than the original.
**How to avoid:** Store `fee_amount` and `fee_rule_id` on the `Transaction` row in the same `TransactionTemplate.execute()` block that stores `riskScore` and `deviceFingerprint`. Include fee in the `PaymentResponse` and in the idempotency cache JSON.

### Pitfall 3: AlertRule Uses Micrometer Rate vs. Absolute Count
**What goes wrong:** Micrometer `Counter.count()` returns cumulative totals, not rates. A failure rate threshold of 10% requires computing `failed / (success + failed)`, not comparing an absolute count to a threshold.
**How to avoid:** The alert evaluator must compute the ratio from the two counters. Alternatively, read from `TransactionRepository.countByTxStatus()` for a DB-backed count (already exists).

### Pitfall 4: MsisdnRouter DB Table Is Empty on First Deploy
**What goes wrong:** If the Flyway migration inserts prefix rules but the `MsisdnPrefixRouteCache` hasn't loaded yet (or the table is accidentally empty), all payments fail with `UnknownMsisdnPrefixException`.
**How to avoid:** Keep the hardcoded fallback logic in `MsisdnRouter.resolve()` as the last resort. Log a WARN when the fallback fires.

### Pitfall 5: `CircuitBreakerRegistry.getAllCircuitBreakers()` Returns Lazy Set
**What goes wrong:** Circuit breakers are created lazily on first use. If neither `orange` nor `mtn` circuit breaker has been triggered yet (fresh startup), `getAllCircuitBreakers()` may return an empty set.
**How to avoid:** In `ProviderStatusResource`, also check the two known names explicitly: `circuitBreakerRegistry.circuitBreaker("orange")` and `circuitBreakerRegistry.circuitBreaker("mtn")`. This forces creation.

### Pitfall 6: `fee_amount` Column on Transaction Must Be Nullable
**What goes wrong:** Adding a NOT NULL `fee_amount` column to an existing `transaction` table fails on Flyway migration if existing rows have no fee data.
**How to avoid:** Add `fee_amount NUMERIC(20,2)` as nullable in the migration. The application-layer semantics: null = no fee rule matched (global fallback applies). A separate `fee_rule_id` FK can be nullable for the same reason.

### Pitfall 7: Hash Chain Audit Full-Table Scan Timeout
**What goes wrong:** `GET /v1/admin/audit/hash-chain` iterates ALL transaction IDs in `payment_event_log`. Under production load, this can take minutes.
**How to avoid:** The full-audit endpoint should accept optional `from` and `to` date parameters. Alternatively, page the result by date range. Add a note in the response body that the audit is approximate for large date ranges.

### Pitfall 8: `@Scheduled` Alert Evaluator Fires Before Metrics Are Populated
**What goes wrong:** At startup, all Micrometer counters start at 0. If the alert threshold is low, the first scheduled evaluation triggers a false alarm (0 success / 0 failure = 0% failure rate — no issue; but 0/0 division).
**How to avoid:** Guard `failureRate` computation with `totalCount > minimumSampleSize` check (e.g., require at least 10 calls in the window before evaluating).

---

## Code Examples

### Circuit Breaker Status Query (from Resilience4j 2.2.0 API)
```java
// Source: io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry (verified from jar)
// CircuitBreaker.State values: CLOSED, OPEN, HALF_OPEN, DISABLED, METRICS_ONLY, FORCED_OPEN
@Autowired
CircuitBreakerRegistry circuitBreakerRegistry;

// Force-create known CBs so they appear even before first use
CircuitBreaker orange = circuitBreakerRegistry.circuitBreaker("orange");
CircuitBreaker mtn    = circuitBreakerRegistry.circuitBreaker("mtn");
String state = orange.getState().name();   // "CLOSED", "OPEN", or "HALF_OPEN"
float failureRate = orange.getMetrics().getFailureRate(); // -1.0 if no calls yet
```

### ApplicationReadyEvent Listener Pattern
```java
// Source: Spring Boot 3 ApplicationReadyEvent — standard Spring application event
@Component
public class TlsStartupAssertion implements ApplicationListener<ApplicationReadyEvent> {
    // ApplicationReadyEvent fires after context refresh + SmartLifecycle.start()
    // Throwing any RuntimeException here DOES abort startup (context is closed)
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        // ...
    }
}
```

### Micrometer Counter Read
```java
// Source: existing PaymentMetricsService.java — verified pattern
double successCount = successCounter.count();     // cumulative since startup
double failedCount  = failedCounter.count();      // cumulative since startup
double total        = successCount + failedCount;
double failureRate  = total > 0 ? failedCount / total : 0.0;
```

### EventLogService.verifyChain (already implemented, confirmed)
```java
// Source: EventLogService.java line 66 — existing, verified
// Returns true if entire hash chain for transactionId is valid
boolean valid = eventLogService.verifyChain(transactionId);
```

### Fraud Rule Cache Pattern (template for FeeRuleCache and AlertRuleCache)
```java
// Source: fraud/service/FraudRuleCache.java (existing, verified pattern)
@Service
public class FraudRuleCache {
    private volatile List<FraudRule> cachedRules = List.of();
    private final FraudRuleRepository repository;

    @PostConstruct
    public void init() { refresh(); }

    @Scheduled(fixedDelayString = "${fraud.rule-cache.refresh-interval-ms:60000}")
    public void refresh() {
        cachedRules = repository.findAllByStatusAndEnabled("ACTIVE", true);
    }
    // ...
}
```

### Admin Resource Pattern (template for FeeRuleAdminResource, AuditResource, ProviderStatusResource)
```java
// Source: AdminTransactionResource.java and ReconciliationResource.java (verified)
@RestController
@RequestMapping("/v1/admin/...")
@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)  // JWT chain, no API-key
@RequiredArgsConstructor
public class XxxResource {
    // Endpoints auto-protected by TenantSecurityConfig NegatedRequestMatcher for /v1/admin/**
}
```

---

## Flyway Migrations Needed

The next available migration version is V14. Phase 10 needs at minimum two:

**V14__fee_rule_schema.sql**
```sql
CREATE TABLE main.fee_rule (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_by          VARCHAR(50),
    created_date        TIMESTAMP,
    last_modified_by    VARCHAR(50),
    last_modified_date  TIMESTAMP,
    request_id          VARCHAR(255),
    session_id          TEXT,
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    tenant_id           BIGINT       REFERENCES main.tenant(id),  -- NULL = global
    fee_type            VARCHAR(20)  NOT NULL,  -- FEE_FIXED, FEE_PERCENTAGE
    fixed_amount        NUMERIC(20,2),
    percentage_rate     NUMERIC(5,4),
    currency            CHAR(3),
    rule_name           VARCHAR(200) NOT NULL,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    description         VARCHAR(500)
);

ALTER TABLE main.transaction
    ADD COLUMN IF NOT EXISTS fee_amount  NUMERIC(20,2),
    ADD COLUMN IF NOT EXISTS fee_rule_id BIGINT REFERENCES main.fee_rule(id);

-- Seed one global fixed-fee rule as the default
INSERT INTO main.fee_rule (id, fee_type, fixed_amount, currency, rule_name, enabled, status)
VALUES (659287191260154476, 'FEE_FIXED', 0.00, 'XAF', 'Default (no fee)', true, 'ACTIVE');
```

**V15__alert_rule_schema.sql**
```sql
CREATE TABLE main.alert_rule (
    id                      BIGINT       NOT NULL PRIMARY KEY,
    version                 BIGINT       NOT NULL DEFAULT 0,
    created_by              VARCHAR(50),
    created_date            TIMESTAMP,
    last_modified_by        VARCHAR(50),
    last_modified_date      TIMESTAMP,
    request_id              VARCHAR(255),
    session_id              TEXT,
    status                  VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    metric_name             VARCHAR(100) NOT NULL,  -- FAILURE_RATE, FRAUD_SPIKE_RATE, CALLBACK_ANOMALY
    threshold               NUMERIC(10,4) NOT NULL,
    window_seconds          INTEGER      NOT NULL DEFAULT 300,
    notification_channel    VARCHAR(50)  NOT NULL DEFAULT 'LOG',  -- LOG, EMAIL
    enabled                 BOOLEAN      NOT NULL DEFAULT TRUE,
    description             VARCHAR(500)
);

INSERT INTO main.alert_rule (id, metric_name, threshold, window_seconds, notification_channel, enabled, status)
VALUES
    (659287191260154477, 'FAILURE_RATE',   0.20, 300, 'LOG', true, 'ACTIVE'),
    (659287191260154478, 'FRAUD_SPIKE_RATE', 0.05, 300, 'LOG', true, 'ACTIVE');
```

**V16__msisdn_prefix_route_schema.sql** (if MSISDN config-driven is in scope for this phase)
```sql
CREATE TABLE main.msisdn_prefix_route (
    id          BIGINT       NOT NULL PRIMARY KEY,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_by  VARCHAR(50),
    created_date TIMESTAMP,
    last_modified_by VARCHAR(50),
    last_modified_date TIMESTAMP,
    request_id  VARCHAR(255),
    session_id  TEXT,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    prefix      VARCHAR(10)  UNIQUE NOT NULL,
    provider    VARCHAR(20)  NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE
);

INSERT INTO main.msisdn_prefix_route (id, prefix, provider, enabled, status)
VALUES
    (659287191260154479, '65', 'ORANGE', true, 'ACTIVE'),
    (659287191260154480, '69', 'ORANGE', true, 'ACTIVE'),
    (659287191260154481, '60', 'MTN', true, 'ACTIVE'),
    (659287191260154482, '61', 'MTN', true, 'ACTIVE'),
    (659287191260154483, '62', 'MTN', true, 'ACTIVE'),
    (659287191260154484, '63', 'MTN', true, 'ACTIVE'),
    (659287191260154485, '64', 'MTN', true, 'ACTIVE'),
    (659287191260154486, '66', 'MTN', true, 'ACTIVE'),
    (659287191260154487, '67', 'MTN', true, 'ACTIVE'),
    (659287191260154488, '68', 'MTN', true, 'ACTIVE');
```

**NOTE:** TSID IDs used as examples above are fictitious; the actual inserts should use `@Tsid`-generated values or use explicit BIGINT literals from a TSID generator utility. Look at how V10__fraud_schema.sql seeds `fraud_rule` — it uses sequential small integers (1, 2, 3, 4, 5) which is fine for seed data since TSID is only for application-generated rows. Use the same approach.

---

## IT Test Patterns

### Template: Follow ReconciliationApiIT

`ReconciliationApiIT` is the canonical pattern for Phase 10 ITs. Key characteristics:

1. `@ActiveProfiles("dev")` — required for all ITs
2. `@SpringBootTest(webEnvironment = RANDOM_PORT)`
3. `@Import(TestConfig.class)`
4. `@TestPropertySource(properties = {"spring.cloud.compatibility-verifier.enabled=false", "mtn.callback-ip-whitelist="})` — standard suppressions
5. Seed JWT secret in `main.sec` table in `@BeforeEach` (exact SQL copied from `ReconciliationApiIT` lines 113-118)
6. Seed authority + user + user_authority rows for admin login
7. Authenticate via `POST /authenticate` to get real JWT cookies
8. Use `new RestTemplateBuilder().requestFactory(SimpleClientHttpRequestFactory.class).build()` — the `noRetryRestTemplate` pattern
9. `@AfterEach` tearDown deletes all seeded data

### Test Coverage Per Sub-Plan

**10-01 (Fee Engine):**
- Fee rule CRUD via admin API: `POST /v1/admin/fees` creates rule, `GET` lists it
- Payment with global fee: verify `fee_amount` on Transaction after initiation
- Payment with tenant-specific fee: seed per-tenant and global rules, verify tenant rule wins
- Idempotency replay: same fee amount returned on second call with same idempotency key
- No fee rule: `fee_amount` is null (or 0.00 from default seed)

**10-02 (Alert Rules):**
- Alert rule CRUD via admin API
- Alert fires when failure rate exceeds threshold: inject failures into metrics, trigger evaluation, verify log entry (or mock notification)
- Alert does NOT fire when below threshold

**10-03 (TLS assertion + CB status + hash chain audit):**
- `GET /v1/admin/providers/status` with JWT → 200, body contains "orange" and "mtn" keys
- `GET /v1/admin/providers/status` without JWT → 401/403
- `GET /v1/admin/audit/hash-chain/{transactionId}` with valid chain → `{valid: true}`
- `GET /v1/admin/audit/hash-chain/{transactionId}` with tampered chain → `{valid: false}`
- TLS assertion: test that the listener does NOT throw when `dev` profile is active

---

## State of the Art

| Old Approach | Current Approach | Status |
|--------------|------------------|--------|
| Hardcoded MSISDN prefix rules | DB-backed `msisdn_prefix_route` table | Phase 10 target |
| No fee calculation | `FeeRule` entity, per-tenant or global | Phase 10 target |
| No alert thresholds | `AlertRule` entity + `@Scheduled` evaluator | Phase 10 target |
| `checkCertificate: false` silently ignored | Startup assertion fails fast in non-dev | Phase 10 target |
| CB state only via `/manage/circuitbreakers` (actuator) | Custom `/v1/admin/providers/status` (JWT, domain-friendly) | Phase 10 target |
| Hash chain verification is a service method only | Exposed via admin REST endpoint | Phase 10 target |

**Already in place (no change needed):**
- `CircuitBreakersHealthIndicator` — auto-configured by `resilience4j-spring-boot3`, visible at `GET /manage/health`
- `GET /manage/circuitbreakers` — auto-configured Actuator endpoint listing CB states
- `EventLogService.verifyChain()` — already implemented and verified
- Micrometer counters `payment.success.total`, `payment.failed.total`, `payment.fraud.blocked.total` — registered in `PaymentMetricsService`

---

## Open Questions

1. **Fee storage on LedgerEntry**
   - What we know: `LedgerEntry` exists in `main.ledger_entry` (V4 migration). Fee should ideally appear in the ledger.
   - What's unclear: Whether a separate fee ledger entry should be created (DEBIT against a fee account) or whether `fee_amount` on the transaction row is sufficient.
   - Recommendation: For Phase 10, store `fee_amount` on `Transaction` only. A separate ledger debit can be a Phase 11 concern.

2. **Alert notification channel expansion**
   - What we know: `MailManager` is in context. The `AlertRule.notificationChannel` column stores a string enum.
   - What's unclear: Whether Slack/webhook notification is needed in Phase 10.
   - Recommendation: Implement LOG and EMAIL only. Leave `notificationChannel` as a string (not a Java enum) to avoid Flyway migration if new channels are added later.

3. **MSISDN prefix table scope**
   - What we know: `MsisdnRouter` has a TODO "Phase 10 hardening concern."
   - What's unclear: Whether the 10-01 plan explicitly covers MSISDN or whether it stays hardcoded with only a refactoring note.
   - Recommendation: Implement the DB-backed prefix table in 10-01 (alongside FeeRule) since both are DB-backed rule tables following the same pattern. Doing both in 10-01 keeps the pattern consistent.

4. **TLS config access path for MtnMoMoConfig**
   - What we know: `TcpConfiguration.checkCertificate` exists; `application.yaml` `*DEFAULT_TCP` anchor sets it to `false` globally.
   - What's unclear: The exact field path from `MtnMoMoConfig` to reach `TcpConfiguration` (not verified from MtnMoMoConfig source).
   - Recommendation: Read `MtnMoMoConfig` source before implementing `TlsStartupAssertion`. The `ClientConfiguration` → `TcpConfiguration` path is established.

---

## Sources

### Primary (HIGH confidence)
- Codebase — `PaymentEventLog.java`, `EventLogService.java`: verified hash chain implementation
- Codebase — `FraudRule.java`, `FraudRuleCache.java`, `FraudScoringService.java`: verified DB-backed rule pattern
- Codebase — `PaymentMetricsService.java`: verified Micrometer counter registration
- Codebase — `MsisdnRouter.java`: verified hardcoded prefix logic with Phase 10 TODO comment
- Codebase — `TcpConfiguration.java`: verified `checkCertificate` field
- Codebase — `AppEndpoints.java` + `TenantSecurityConfig.java`: verified `/v1/admin/**` JWT-only chain
- Codebase — `SecurityConstants.HAS_ADMIN_ROLE`: verified admin auth expression
- Codebase — `ReconciliationApiIT.java`: verified IT test pattern including sec/auth seeding
- Jar inspection — `resilience4j-spring-boot3-2.2.0.jar`: verified `CircuitBreakerEndpoint`, `CircuitBreakersHealthIndicator`, `CircuitBreakerRegistry` classes exist
- Jar inspection — `resilience4j-circuitbreaker-2.2.0.jar`: verified `CircuitBreaker.State`, `CircuitBreakerRegistry` API
- `pom.xml`: verified no new dependencies needed; Spring Boot 3.5.11, Spring Cloud 2025.0.1

### Secondary (MEDIUM confidence)
- `application.yaml` Resilience4j config: verified instance names "orange" and "mtn" used as CB names

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — verified from pom.xml and jar inspection
- Architecture: HIGH — patterns directly derived from existing production code (FraudRule, ReconciliationApiIT)
- FeeRule design: HIGH — direct model from FraudRule; no external assumptions
- Resilience4j CB API: HIGH — verified class names and methods from local Maven jar
- Pitfalls: HIGH — derived from actual code decisions documented in existing classes
- Alert thresholds: MEDIUM — reasonable defaults for a payment system; actual values depend on production traffic patterns

**Research date:** 2026-03-25
**Valid until:** 2026-06-25 (stable stack — Spring Boot, Resilience4j versions locked in pom.xml)
