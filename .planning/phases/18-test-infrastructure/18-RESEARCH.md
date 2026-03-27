# Phase 18: Test Infrastructure - Research

**Researched:** 2026-03-27
**Domain:** Spring Boot integration test infrastructure — Testcontainers, WireMock, base test classes
**Confidence:** HIGH

---

## Summary

Phase 18 builds a shared test infrastructure layer consumed by all v3 E2E tests. The codebase already has a working integration test pattern used across 20+ `*IT.java` files. The new abstractions must lift that pattern into named, composable base classes that enforce the four-phase flow structure (`setupPreconditions / executeFlow / simulateProviderCallback / verifyFinalState`) from the E2E testing standard.

The existing `TestConfig.java` already handles PostgreSQL and Redis container wiring via `@ServiceConnection`. The new `PostgresContainerConfig` and `RedisContainerConfig` split that single class into dedicated, focused configuration beans. WireMock is already in use via `wiremock-spring-boot:4.0.9` with `@EnableWireMock/@ConfigureWireMock/@InjectWireMock` — the new `WireMockConfig` centralises the two-server (`mtn` + `orange`) wiring. Security seeding is currently repeated in every `@BeforeEach` by copying the same 5-line JDBC call; `E2ESecurityConfig` must extract this into a reusable bean. Data cleanup is done inline in `@AfterEach` by each test; `TestDataCleaner` centralises it into a declarative, FK-safe delete sequence.

**Primary recommendation:** Model the new abstractions directly on existing test code patterns rather than inventing new infrastructure. The implementation is a restructuring of patterns already proven in `PaymentOrchestratorIT`, `WebhookDoubleCheckIT`, and `MtnMoMoPortIT`.

---

## Standard Stack

### Core (all already in pom.xml — no new dependencies required)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `spring-boot-testcontainers` | Spring Boot BOM (3.5.11) | `@ServiceConnection` auto-wiring | Replaces manual JDBC URL overrides |
| `testcontainers:postgresql` | Spring Boot BOM | Real PostgreSQL container | V1-V16 Flyway migrations run on it |
| `testcontainers:junit-jupiter` | Spring Boot BOM | Container lifecycle management | JUnit 5 integration |
| `wiremock-spring-boot` | 4.0.9 (pom.xml) | WireMock server wiring | Already used in 6+ test classes |
| `spring-boot-starter-test` | Spring Boot BOM | JUnit 5, Mockito, AssertJ | Standard |
| `assertj-core` | 3.24.2 | Fluent assertions | Already used everywhere |
| `awaitility` | 4.2.0 | Async flow assertion | Required for webhook/event tests |

### Container Images (verified in existing tests)

| Image | Version | Used for |
|-------|---------|---------|
| `postgres` | 14.18 | PostgreSQL container |
| `redis` | 7-alpine | Redis container |

### Test Scope Only — No New Runtime Dependencies

All infrastructure is test-scoped. The pom.xml already declares every required artifact. Phase 18 adds no new `<dependency>` entries.

---

## Architecture Patterns

### Recommended Package Structure

```
src/test/java/com/softropic/payam/
├── e2e/
│   ├── AbstractPayamE2ETest.java          # INFRA-01: base for all E2E tests
│   ├── AbstractPaymentFlowTest.java       # INFRA-02: payment flow template
│   ├── AbstractWebhookFlowTest.java       # INFRA-03: webhook flow template
│   └── AbstractFailureFlowTest.java       # INFRA-04: failure injection template
└── config/
    ├── TestConfig.java                    # EXISTING — keep as-is
    ├── PostgresContainerConfig.java       # INFRA-05: extract from TestConfig
    ├── RedisContainerConfig.java          # INFRA-05: extract from TestConfig
    ├── WireMockConfig.java                # INFRA-06: centralise WireMock setup
    ├── TestClockConfig.java               # INFRA-07: fixed WAT clock
    ├── E2ESecurityConfig.java             # INFRA-08: JWT secret seeding
    └── TestDataCleaner.java               # INFRA-09: table wipe (new class)
```

Note: `TestDataCleaner` is a new class at `src/test/java/com/softropic/payam/config/TestDataCleaner.java` (not `utils/`). The existing `DbCleaner` in `utils/` handles only security tables and is incomplete for payment tables — do not extend it.

### Pattern 1: AbstractPayamE2ETest

The base class for all E2E tests. Provides container wiring, WireMock servers, JWT secret seeding, and Redis flush. Replicates the boilerplate present in every existing IT class.

```java
// Source: derived from PaymentOrchestratorIT, WebhookDoubleCheckIT patterns
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import({PostgresContainerConfig.class, RedisContainerConfig.class,
         WireMockConfig.class, E2ESecurityConfig.class, TestClockConfig.class})
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "mtn.callback-ip-whitelist=",
    "orange.callback-ip-whitelist=",
    "orange.callback-hmac-secret="
})
@EnableWireMock({
    @ConfigureWireMock(name = "mtn",    baseUrlProperties = {"mtn.collection-base-url"}),
    @ConfigureWireMock(name = "orange", baseUrlProperties = {"orange.base-url", "orange.pay-url"})
})
public abstract class AbstractPayamE2ETest {

    @InjectWireMock("mtn")
    protected WireMockServer mtnServer;

    @InjectWireMock("orange")
    protected WireMockServer orangeServer;

    @Autowired
    protected TestDataCleaner testDataCleaner;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void baseSetUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        circuitBreakerRegistry.circuitBreaker("mtn").reset();
        circuitBreakerRegistry.circuitBreaker("orange").reset();
        stubTokenEndpoints();
    }

    @AfterEach
    void baseTearDown() {
        mtnServer.resetAll();
        orangeServer.resetAll();
        testDataCleaner.wipeAll();
    }

    // Default token stubs — subclasses can override
    protected void stubTokenEndpoints() {
        mtnServer.stubFor(post(urlPathEqualTo("/token/"))
            .willReturn(okJson("{\"access_token\":\"test-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
        orangeServer.stubFor(post(urlPathEqualTo("/token"))
            .willReturn(okJson("{\"access_token\":\"orange-token\",\"token_type\":\"Bearer\",\"expires_in\":7200}")));
    }
}
```

### Pattern 2: AbstractPaymentFlowTest

Enforces the four-phase template method pattern. Subclasses implement each phase; the `runFlow()` orchestrator calls them in order.

```java
// Source: derived from e2e-test-standard.md section 3.5 + PaymentOrchestratorIT structure
public abstract class AbstractPaymentFlowTest extends AbstractPayamE2ETest {

    @Test
    final void runFlow() {
        setupPreconditions();
        executeFlow();
        simulateProviderCallback();
        verifyFinalState();
    }

    protected abstract void setupPreconditions();
    protected abstract void executeFlow();
    protected abstract void simulateProviderCallback();
    protected abstract void verifyFinalState();
}
```

### Pattern 3: AbstractWebhookFlowTest

Adds inbound webhook dispatch and double-check verification steps on top of the payment flow template.

```java
// Source: derived from WebhookDoubleCheckIT pattern
public abstract class AbstractWebhookFlowTest extends AbstractPaymentFlowTest {

    protected abstract void dispatchInboundWebhook();
    protected abstract void verifyDoubleCheckTriggered();

    @Override
    protected void simulateProviderCallback() {
        dispatchInboundWebhook();
    }

    @Override
    protected void verifyFinalState() {
        verifyDoubleCheckTriggered();
        verifyTransactionState();
    }

    protected abstract void verifyTransactionState();
}
```

### Pattern 4: AbstractFailureFlowTest

Adds fault injection hook points. Subclasses choose which fault to inject before the flow executes.

```java
// Source: derived from circuit-breaker test in PaymentOrchestratorIT
public abstract class AbstractFailureFlowTest extends AbstractPayamE2ETest {

    @Test
    final void runFailureScenario() {
        setupPreconditions();
        injectFault();
        executeFlow();
        verifyFailureHandled();
    }

    protected abstract void setupPreconditions();
    protected abstract void injectFault();
    protected abstract void executeFlow();
    protected abstract void verifyFailureHandled();
}
```

### Pattern 5: PostgresContainerConfig

Split from `TestConfig`. Uses `CustomPostgresContainer` to pin UTC timezone.

```java
// Source: TestConfig.java lines 47-54 and CustomPostgresContainer.java
@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainerConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer(@Value("${spring.application.name}") String dbName) {
        return new CustomPostgresContainer(DockerImageName.parse("postgres:14.18"))
                .withDatabaseName(dbName)
                .withPassword("postgres")
                .withUsername("postgres")
                .withInitScript("sql/createSchema.sql");
    }
}
```

Key point: `withInitScript("sql/createSchema.sql")` only creates the `main` schema. Flyway applies all migrations (V1-V16) when the application context starts. Do NOT seed data in init script — use `TestDataCleaner` or test-level JDBC calls.

### Pattern 6: RedisContainerConfig

```java
// Source: TestConfig.java lines 40-44
@TestConfiguration(proxyBeanMethods = false)
public class RedisContainerConfig {

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
    }
}
```

### Pattern 7: E2ESecurityConfig

The JWT secret (`main.sec` row) is required for `SecurityAdviceFilter.addSecretToThread()`. Currently every test class duplicates a 10-line JDBC INSERT in `@BeforeEach`. `E2ESecurityConfig` should seed it once at context startup.

```java
// Source: the repeated INSERT block in PaymentOrchestratorIT, MtnMoMoPortIT, etc.
@TestConfiguration
public class E2ESecurityConfig implements ApplicationListener<ContextRefreshedEvent> {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute(
                "INSERT INTO main.sec (id, ..., bus_id, value, version) " +
                "VALUES ('659287191260154475', ..., 'jot', '<base64-key>', 'v1') " +
                "ON CONFLICT DO NOTHING");
            return null;
        });
    }
}
```

The literal secret value is the same hardcoded value used across all existing test `@BeforeEach` methods — it is a test-only constant, not a production secret.

### Pattern 8: TestDataCleaner

FK-safe DELETE order derived from all existing `@AfterEach` tearDown implementations. The full order covers all tables touched by E2E flows.

```java
// Source: reconciled from WebhookDoubleCheckIT, PaymentOrchestratorIT, MtnMoMoPortIT tearDowns
@Component
public class TestDataCleaner {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;

    public void wipeAll() {
        transactionTemplate.execute(status -> {
            // Child tables first (FK order is critical)
            jdbcTemplate.execute("DELETE FROM main.ledger_entry");
            jdbcTemplate.execute("DELETE FROM main.payment_event_log");
            jdbcTemplate.execute("DELETE FROM main.idempotency_key");
            jdbcTemplate.execute("DELETE FROM main.webhook_delivery_log");
            jdbcTemplate.execute("DELETE FROM main.reconciliation_discrepancy");
            jdbcTemplate.execute("DELETE FROM main.reconciliation_report");
            jdbcTemplate.execute("DELETE FROM main.transaction");
            jdbcTemplate.execute("DELETE FROM main.fee_rule WHERE id NOT IN (1)");  // keep seed row
            jdbcTemplate.execute("DELETE FROM main.fraud_rule WHERE id NOT IN (1,2,3,4,5)");  // keep seeds
            jdbcTemplate.execute("DELETE FROM main.tenant_api_key");
            jdbcTemplate.execute("DELETE FROM main.tenant");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }
}
```

### Pattern 9: TestClockConfig

Provides a fixed WAT (`Africa/Douala`, UTC+1) clock as a Spring bean. Used for deterministic Orange timestamp tests.

```java
// Source: TestClockProvider.java + OrangeTimeUtil.WAT constant
@TestConfiguration
public class TestClockConfig {

    public static final ZoneId WAT = ZoneId.of("Africa/Douala");

    // Fixed instant: 2026-01-01T09:00:00Z = 2026-01-01T10:00:00 WAT
    public static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T09:00:00Z");

    @Bean
    @Primary
    public Clock testClock() {
        return Clock.fixed(FIXED_INSTANT, WAT);
    }
}
```

Note: `ClockProvider` uses `ThreadLocal` — it is not a Spring bean. `TestClockProvider.setClock()` must be called in `@BeforeEach` when test isolation of the clock is needed. `TestClockConfig` provides a fixed `Clock` bean that can be injected where services accept `Clock` via constructor injection.

### WireMockConfig

The `wiremock-spring-boot` library (`org.wiremock.integrations:wiremock-spring-boot:4.0.9`) uses annotation-driven configuration. `WireMockConfig` is an annotation aggregator rather than a `@TestConfiguration`:

```java
// The @EnableWireMock annotation belongs on the test class or meta-annotation, not in a @TestConfiguration.
// WireMockConfig centralises the property name constants and common stub snippets.
public final class WireMockConfig {

    // MTN property names (match what @ConfigureWireMock.baseUrlProperties expects)
    public static final String MTN_BASE_URL_PROPERTY = "mtn.collection-base-url";

    // Orange property names
    public static final String ORANGE_BASE_URL_PROPERTY = "orange.base-url";
    public static final String ORANGE_PAY_URL_PROPERTY  = "orange.pay-url";

    // Common stub response bodies
    public static final String MTN_TOKEN_RESPONSE =
        "{\"access_token\":\"mtn-test-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}";

    public static final String ORANGE_TOKEN_RESPONSE =
        "{\"access_token\":\"orange-test-token\",\"token_type\":\"Bearer\",\"expires_in\":7200}";

    private WireMockConfig() {}
}
```

Important: `@EnableWireMock` must be placed on `AbstractPayamE2ETest` (or a meta-annotation). It cannot be placed inside a `@TestConfiguration` class.

### Anti-Patterns to Avoid

- **Anti-pattern: Container per test class.** Current `TestConfig` shares one container across the entire test run via Spring context cache. Do not add `@DirtiesContext` — it forces container restart and adds ~10 seconds per test class.
- **Anti-pattern: Flyway init script mixing.** The `withInitScript("sql/createSchema.sql")` only creates the `main` schema. All table creation is done by Flyway migrations. Do not add table DDL to init scripts.
- **Anti-pattern: Shared state via Redis without flush.** Velocity counters (Bucket4j), idempotency keys, and OAuth tokens all survive between tests unless `flushDb()` is called. `AbstractPayamE2ETest.baseSetUp()` must call `flushDb()` unconditionally.
- **Anti-pattern: Circuit breaker state bleed.** Circuit breakers survive test boundaries unless explicitly reset. `baseSetUp()` must reset both `mtn` and `orange` circuit breakers.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Container lifecycle | Custom Docker wrapper | `@ServiceConnection` + `@Bean` | Spring Boot 3.x auto-wires host/port |
| HTTP stubbing | Custom HTTP server | `wiremock-spring-boot` | Already in pom.xml, proven in 6+ tests |
| JWT secret injection | Random key generator | Hardcoded test constant with `ON CONFLICT DO NOTHING` | Test keys don't need real entropy |
| DB cleanup ordering | Custom topology sort | Hardcoded FK-safe DELETE sequence | Schema is stable, FK graph is known |
| Async waiting | `Thread.sleep(n)` | `Awaitility.await()` | Sleep is non-deterministic in CI |
| Clock control | System.currentTimeMillis override | `TestClockProvider.setClock()` | Existing `ClockProvider` is test-aware |

**Key insight:** The existing codebase already solved container wiring, WireMock, and clock control. Phase 18 restructures proven patterns into base classes — it does not introduce new solutions.

---

## Common Pitfalls

### Pitfall 1: Fee rule and fraud rule seed data wiped by TestDataCleaner

**What goes wrong:** `DELETE FROM main.fee_rule` removes the Flyway-seeded row (id=1, "Default (no fee)"). Tests that expect zero-fee behavior then fail with NPE or wrong fee amounts.

**Why it happens:** Flyway seeds `fee_rule` row `id=1` in V14. `TestDataCleaner.wipeAll()` must preserve seed rows.

**How to avoid:** Use `DELETE FROM main.fee_rule WHERE id NOT IN (1)` and `DELETE FROM main.fraud_rule WHERE id NOT IN (1,2,3,4,5)`.

**Warning signs:** Tests that pass in isolation fail in sequence because seed rows are absent.

### Pitfall 2: Orange token Redis key inconsistency

**What goes wrong:** `OrangeTokenService` caches at key `orange:token:cm` but `PaymentOrchestratorIT.tearDown()` deletes `orange:token`. The flush-based approach (`flushDb()`) avoids this bug.

**Why it happens:** The Redis key was renamed between phases — some tests use the old key name.

**How to avoid:** Use `redis.getConnectionFactory().getConnection().serverCommands().flushDb()` instead of deleting named keys. This is already the pattern in `WebhookDoubleCheckIT`.

### Pitfall 3: `@EnableWireMock` on base class vs annotation inheritance

**What goes wrong:** `@EnableWireMock` is processed by a Spring Boot test extension. Placing it on an abstract class may not be processed if JUnit 5 resolves annotations at the concrete class level.

**Why it happens:** JUnit 5 annotation discovery traversal rules differ from Spring's `@Inherited` semantics.

**How to avoid:** Verify annotation is inherited at runtime. If not, use a meta-annotation (`@PayamE2ETest`) that includes `@EnableWireMock` + `@SpringBootTest` + `@ActiveProfiles`, applied to the abstract base class. Test with at least one concrete subclass before shipping.

**Warning signs:** `@InjectWireMock` fields are null in subclass tests.

### Pitfall 4: Clock ThreadLocal leaks between tests

**What goes wrong:** `ClockProvider` uses `ThreadLocal`. If a test calls `TestClockProvider.setClock(fixed)` and does not call `TestClockProvider.unsetClock()` in `@AfterEach`, subsequent tests on the same thread observe the stale fixed clock.

**Why it happens:** JUnit reuses threads within the same test class.

**How to avoid:** If per-test clock control is needed, call `TestClockProvider.unsetClock()` in `@AfterEach`. The Spring-bean `TestClockConfig.testClock()` approach is safer for tests that just need a consistent fixed clock.

### Pitfall 5: `sec` table vs `E2ESecurityConfig` timing

**What goes wrong:** `E2ESecurityConfig` seeds the JWT secret at `ContextRefreshedEvent`. If `TestDataCleaner.wipeAll()` is called in `@AfterEach` and deletes `main.sec`, the next test starts without the secret. The `SecurityAdviceFilter` then fails to find the JWT secret.

**Why it happens:** `ContextRefreshedEvent` fires only once per context startup, not before each test.

**How to avoid:** Do not include `main.sec` in the per-test wipe if `E2ESecurityConfig` seeds it at context startup. Instead, use `ON CONFLICT DO NOTHING` in the insert and call the insert again at the start of each test via an `@BeforeEach` in the base class — exactly as existing tests do.

### Pitfall 6: `msisdn_prefix_route` seed data

**What goes wrong:** Flyway V16 seeds the prefix routing table. `TestDataCleaner` must preserve these rows or all MSISDN routing will fail (returning `UNKNOWN_MSISDN_PREFIX`).

**How to avoid:** Do not delete from `main.msisdn_prefix_route` in `TestDataCleaner`.

---

## Code Examples

### WireMock with wiremock-spring-boot 4.0.9

```java
// Source: verified in MtnMoMoPortIT and OrangeMoneyPortIT
@EnableWireMock(@ConfigureWireMock(name = "mtn", baseUrlProperties = {"mtn.collection-base-url"}))
class SomeTest extends AbstractPayamE2ETest {

    @InjectWireMock("mtn")
    WireMockServer mtnServer;

    @Test
    void example() {
        mtnServer.stubFor(post(urlPathEqualTo("/token/"))
            .willReturn(okJson("{\"access_token\":\"test\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));

        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{}")));

        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));
    }
}
```

### Complete MTN endpoint stubs required per test

```java
// Source: PaymentOrchestratorIT
// 1. Token acquisition
mtnServer.stubFor(post(urlPathEqualTo("/token/"))
    .willReturn(okJson("{\"access_token\":\"...\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));

// 2. Account validation
mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
    .willReturn(okJson("{}")));  // 200 = active; 404 = inactive

// 3. Request to pay initiation
mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
    .willReturn(aResponse().withStatus(202)));  // 202 = accepted, no body

// 4. Status polling
mtnServer.stubFor(get(urlPathMatching("/v1_0/requesttopay/.*"))
    .willReturn(okJson("{\"status\":\"SUCCESSFUL\",\"financialTransactionId\":\"FIN-001\"}")));
```

### Complete Orange endpoint stubs required per test

```java
// Source: OrangeMoneyPortIT and PaymentOrchestratorIT
// Note: orange.token-url=${orange.base-url}/token (from test application.properties)
// Note: orange.pay-url=${orange.base-url} in tests (both point to same WireMock server)

// 1. Token acquisition
orangeServer.stubFor(post(urlPathEqualTo("/token"))
    .willReturn(okJson("{\"access_token\":\"...\",\"token_type\":\"Bearer\",\"expires_in\":7200}")));

// 2. Subscriber validation
orangeServer.stubFor(get(urlPathEqualTo("/infos/subscriber"))
    .withQueryParam("msisdn", equalTo("692954629"))
    .willReturn(okJson("{\"status\":\"ACTIF\",\"message\":\"OK\"}")));

// 3. Merchant info (payToken acquisition)
orangeServer.stubFor(get(urlPathEqualTo("/infos/merchant"))
    .willReturn(okJson("{\"payToken\":\"tok-abc-123\",\"message\":\"OK\"}")));

// 4. Payment initiation
orangeServer.stubFor(post(urlPathMatching("/mp/pay"))
    .willReturn(okJson("{\"payToken\":\"tok-abc-123\",\"status\":\"PENDING\",\"txnid\":\"TXN001\"}")));

// 5. Status polling
orangeServer.stubFor(get(urlPathMatching("/mp/paymentstatus/.*"))
    .willReturn(okJson("{\"status\":\"SUCCESSFULL\",\"payToken\":\"tok-abc-123\"}")));
// Note: "SUCCESSFULL" double-L is the correct Orange spelling
```

### Redis flushDb pattern

```java
// Source: WebhookDoubleCheckIT.setUp() and PaymentOrchestratorIT.setUp()
// Always use flushDb() — not named key deletes — for reliable test isolation
redis.getConnectionFactory().getConnection().serverCommands().flushDb();
```

### Tenant + API key creation

```java
// Source: PaymentOrchestratorIT.setUp()
// TenantService.createTenant() generates a real API key via SecureRandom — this is the
// primary overhead E2ESecurityConfig.injectTestApiKey() should bypass for performance.
var provision = tenantService.createTenant("e2e-test-tenant", "LIVE");
Long tenantId = provision.tenant().getId();
String rawApiKey = provision.rawKey();  // use as X-Api-Key header value
```

### FK-safe DELETE order (complete sequence)

```java
// Source: reconciled from WebhookDoubleCheckIT, PaymentOrchestratorIT, MtnMoMoPortIT @AfterEach
// Order matters — FK violations will occur if out of sequence.
// ledger_entry -> payment_event_log -> idempotency_key -> webhook_delivery_log
//   -> reconciliation_discrepancy -> reconciliation_report
//   -> transaction -> fee_rule (non-seed) -> fraud_rule (non-seed)
//   -> tenant_api_key -> tenant -> sec
// DO NOT delete: msisdn_prefix_route, alert_rule, fraud_rule seeds (ids 1-5), fee_rule seed (id 1)
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Manual JDBC URL override in test properties | `@ServiceConnection` | Spring Boot 3.1 | Container host/port auto-wired |
| WireMock via standalone server | `wiremock-spring-boot` + `@EnableWireMock` | wiremock-spring-boot 2.x+ | Server lifecycle managed by Spring |
| Postgres container per test class | Shared via Spring context cache | Spring Boot 3.x | ~10s startup amortised across suite |

**Deprecated/outdated:**

- `@AutoConfigureWireMock` (Spring Cloud Contract): The project uses `wiremock-spring-boot` directly, NOT Spring Cloud Contract's WireMock integration. These are different libraries. Do not mix them.
- `org.wiremock:wiremock` standalone: The pom.xml uses `org.wiremock.integrations:wiremock-spring-boot:4.0.9` which bundles WireMock. Do not add a separate `wiremock` dependency.

---

## Key Questions Answered

### Q1: What is the existing test structure?

20+ `*IT.java` integration test files exist. All follow the same pattern: `@ActiveProfiles("dev")`, `@SpringBootTest(RANDOM_PORT)`, `@Import(TestConfig.class)`, manual `@BeforeEach` seeding, manual `@AfterEach` teardown. There are no existing abstract base classes — Phase 18 creates them.

### Q2: What existing entities/services must test infrastructure support?

- `TenantService.createTenant(name, environment)` — creates tenant + API key, returns `TenantCreationResult(tenant, key, rawKey)`
- `TransactionService.initiate(tenantId, provider, amount, currency, externalRef)` — creates INITIATED transaction
- `ApiKeyService` — sha256-hashes raw key, looks up in `tenant_api_key`
- `IdempotencyService` — Redis NX + PostgreSQL fallback, key format `idempotency:{tenantId}:{key}`

### Q3: MTN WireMock endpoints

1. `POST /token/` — OAuth2 token acquisition (note trailing slash)
2. `GET /v1_0/accountholder/MSISDN/{msisdn}/basicuserinfo` — subscriber validation
3. `POST /v1_0/requesttopay` — payment initiation (returns 202, no body)
4. `GET /v1_0/requesttopay/{providerRef}` — status polling
5. `PUT /v1/callbacks/mtn` — inbound callback (handled by Payam, not stubbed in WireMock)

### Q4: Orange WireMock endpoints

1. `POST /token` — OAuth2 token acquisition (no trailing slash)
2. `GET /infos/subscriber?msisdn={msisdn}` — subscriber validation (status: "ACTIF" / "INACTIF")
3. `GET /infos/merchant` — payToken acquisition
4. `POST /mp/pay` — payment initiation
5. `GET /mp/paymentstatus/{payToken}` — status polling (Orange spells "SUCCESSFULL" with double L)
6. `POST /v1/callbacks/orange` — inbound callback (handled by Payam, not stubbed in WireMock)

### Q5: Security/API key authentication

Authentication is via `X-Api-Key` header. `ApiKeyAuthenticationFilter` reads the header, SHA-256 hashes it, and looks up `tenant_api_key.key_hash`. `TenantService.createTenant()` returns the raw key from `rawKey()`. No mocking needed — the real key generation is used and takes ~1ms.

INFRA-08 (`E2ESecurityConfig`) targets the JWT secret in `main.sec` (required by `SecurityAdviceFilter`), not the API key. The two are separate concerns.

### Q6: Flyway migrations and schema

- 16 migrations (V1-V16), all in `src/main/resources/db/migration/`
- The `main` schema is created by `sql/createSchema.sql` (init script)
- Flyway runs automatically when Spring context starts against the container
- Dev profile uses `ddl-auto: create-drop` — BUT the container setup uses Flyway (`flyway.enabled: true`), so the final schema is Flyway-managed, not JPA DDL

Tables that `TestDataCleaner` must NOT wipe:
- `main.msisdn_prefix_route` (seed data from V16 — routing rules)
- `main.fraud_rule` ids 1-5 (seed data from V10)
- `main.alert_rule` ids 1-2 (seed data from V15)
- `main.fee_rule` id 1 (seed data from V14 — default no-fee rule)

### Q7: Redis usage patterns

| Key Pattern | Service | Content | TTL |
|-------------|---------|---------|-----|
| `mtn:token:cm` | `MtnTokenService` | Bearer token string | 55 min |
| `mtn:token:lock` | `MtnTokenService` | Lock flag "1" | 10 sec |
| `orange:token:cm` | `OrangeTokenService` | Bearer token string | 55 min |
| `orange:token:lock` | `OrangeTokenService` | Lock flag "1" | 10 sec |
| `idempotency:{tenantId}:{key}` | `IdempotencyService` | "RESERVED" or JSON | 24 hr |
| `fraud:velocity:{signal}:{identifier}` | `VelocityCheckService` | Bucket4j token bucket | Dynamic |

Note: `VelocityCheckService` uses `LettuceBasedProxyManager` (not `StringRedisTemplate`) — it creates its own `RedisClient` from the connection factory host/port. `flushDb()` wipes all keys including velocity counters.

### Q8: Clock/time abstraction

`ClockProvider` is a `ThreadLocal`-based static utility (not a Spring bean). Test code uses `TestClockProvider.setClock(fixed)` and `TestClockProvider.unsetClock()`.

For Orange WAT timestamp tests: `OrangeTimeUtil.WAT = ZoneId.of("Africa/Douala")` (UTC+1, no DST). Orange timestamps arrive as `"yyyy-MM-dd'T'HH:mm:ss"` strings without timezone offset — `OrangeTimeUtil.parseOrangeTimestamp()` interprets them as WAT and converts to UTC `Instant`.

`TestClockConfig.testClock()` provides a fixed `Clock` bean for services that accept `Clock` via constructor. The fixed instant `2026-01-01T09:00:00Z` = `2026-01-01T10:00:00` WAT is a safe test anchor.

### Q9: Payment table names for TestDataCleaner

Complete list from V1-V16 migrations:

| Table | FK Dependencies |
|-------|----------------|
| `main.ledger_entry` | transaction_id (logical) |
| `main.payment_event_log` | transaction_id (logical) |
| `main.idempotency_key` | tenant_id |
| `main.webhook_delivery_log` | tenant_id |
| `main.reconciliation_discrepancy` | report_id |
| `main.reconciliation_report` | (none) |
| `main.transaction` | tenant_id, fee_rule_id |
| `main.fee_rule` | tenant_id (nullable) |
| `main.fraud_rule` | (none, seed data) |
| `main.alert_rule` | (none, seed data) |
| `main.msisdn_prefix_route` | (none, seed data — DO NOT DELETE) |
| `main.tenant_api_key` | tenant_id |
| `main.tenant` | (none) |
| `main.sec` | (none) |
| `main.user_authority` | user_id, authority_id |
| `main.user` | (none) |
| `main.authority` | (none) |
| `main.persistent_token` | (security module) |
| `main.audit_log` | (security module) |
| `main.user_addresses` | user_id |
| `main.envelope_entity_recipients` | envelope_entity_id |
| `main.envelope_entity` | (none) |

### Q10: Test package structure

```
src/test/java/com/softropic/payam/
├── TestPayamApplication.java      # main entry for dev run
├── config/                        # @TestConfiguration beans
├── common/                        # shared utilities (TestClockProvider, AdminLogin, etc.)
├── utils/                         # DbCleaner, TestMailManager, sql/
├── e2e/                           # NEW in Phase 18 — base classes
├── mtn/                           # MtnMoMoPortIT, MtnTokenServiceIT
├── orange/                        # OrangeMoneyPortIT, OrangeTokenServiceIT, OrangeTimeUtilTest
├── payment/                       # PaymentOrchestratorIT
├── webhook/                       # WebhookDoubleCheckIT, WebhookDeliveryIT, OrangeCallbackControllerIT
├── transaction/                   # TransactionStateMachineIT, LedgerServiceIT, etc.
├── fraud/                         # FraudEngineIT, FraudScoringServiceIT
├── reconciliation/                # ReconciliationApiIT, ReconciliationJobIT
├── tenant/                        # TenantFilterChainIT, TenantProvisioningIT, etc.
├── fee/                           # FeeEngineIT
├── alert/                         # AlertRuleIT
├── security/                      # SecurityIT, SecurityFilterChainIT, etc.
├── ops/                           # OperationalIT
└── email/                         # MailManagerIT, etc.
```

New E2E tests (Phases 19+) should be placed in `e2e/` sub-packages (e.g., `e2e/payment/`, `e2e/webhook/`).

---

## Open Questions

1. **`@EnableWireMock` on abstract base class — annotation inheritance**
   - What we know: `wiremock-spring-boot` processes `@EnableWireMock` as a JUnit 5 extension annotation
   - What's unclear: Whether JUnit 5 picks up the annotation from the abstract superclass or requires it on the concrete test class
   - Recommendation: Implement and validate with one concrete test. If not inherited, use a composed meta-annotation `@PayamE2ETest` annotated with `@EnableWireMock(...)` and place it on `AbstractPayamE2ETest`

2. **`E2ESecurityConfig` timing — context refresh vs BeforeEach**
   - What we know: `ContextRefreshedEvent` fires once; `TestDataCleaner.wipeAll()` deletes `main.sec`
   - What's unclear: Whether the sec row should be seeded at context startup or per-test in `@BeforeEach`
   - Recommendation: Seed in `AbstractPayamE2ETest.baseSetUp()` (i.e., per-test `@BeforeEach`) using `ON CONFLICT DO NOTHING`, identical to the existing pattern. This is safe and avoids timing issues.

---

## Sources

### Primary (HIGH confidence)

- `src/test/java/com/softropic/payam/config/TestConfig.java` — existing container wiring
- `src/test/java/com/softropic/payam/config/CustomPostgresContainer.java` — UTC container pattern
- `src/test/java/com/softropic/payam/payment/PaymentOrchestratorIT.java` — complete flow test pattern
- `src/test/java/com/softropic/payam/webhook/WebhookDoubleCheckIT.java` — webhook + double-check pattern
- `src/test/java/com/softropic/payam/mtn/MtnMoMoPortIT.java` — MTN WireMock stub inventory
- `src/test/java/com/softropic/payam/orange/OrangeMoneyPortIT.java` — Orange WireMock stub inventory
- `pom.xml` — all dependency versions (Spring Boot 3.5.11, wiremock-spring-boot 4.0.9)
- `src/main/resources/db/migration/V1-V16` — complete table/FK inventory
- `src/main/java/com/softropic/payam/transaction/service/IdempotencyService.java` — Redis key patterns
- `src/main/java/com/softropic/payam/mtn/service/MtnTokenService.java` — Redis token keys
- `src/main/java/com/softropic/payam/orange/service/OrangeTokenService.java` — Redis token keys
- `src/main/java/com/softropic/payam/fraud/service/VelocityCheckService.java` — Bucket4j Redis keys
- `src/main/java/com/softropic/payam/orange/service/OrangeTimeUtil.java` — WAT zone constant
- `src/main/java/com/softropic/payam/common/ClockProvider.java` — ThreadLocal clock abstraction
- `src/test/java/com/softropic/payam/common/TestClockProvider.java` — test clock control
- `.planning/codebase/TESTING.md` — testing patterns analysis (2026-03-21)
- `requirements/e2e-test-standard.md` — four-phase flow structure

### Secondary (MEDIUM confidence)

- `src/main/resources/application-dev.yaml` — `flyway.enabled: true`, Quartz config, circuit breaker config

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all versions read directly from pom.xml
- Architecture: HIGH — derived from 6+ existing IT test files in the codebase
- Pitfalls: HIGH — derived from actual tearDown patterns and known FK constraints
- WireMock endpoints: HIGH — read directly from MtnMoMoPortIT and OrangeMoneyPortIT stub calls
- Redis key names: HIGH — read directly from MtnTokenService, OrangeTokenService, IdempotencyService

**Research date:** 2026-03-27
**Valid until:** 2026-05-01 (stable schema; valid until next Flyway migration)
