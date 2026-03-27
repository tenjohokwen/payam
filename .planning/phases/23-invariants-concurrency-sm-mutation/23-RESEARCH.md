# Phase 23: Domain Invariants, Concurrency, State Machine, and Mutation Tests - Research

**Researched:** 2026-03-28
**Domain:** Payment domain invariants, concurrency testing, state machine parameterization, PITest mutation testing
**Confidence:** HIGH

---

## Summary

Phase 23 completes test coverage by proving domain invariants hold, races are handled correctly, the state machine is fully parametrized, and mutation testing kills the six critical mutations at 90% threshold. All infrastructure, builders, and verifiers are already in place from phases 19-22. The new tests build directly on top of `AbstractPayamE2ETest` and the verifier layer.

The domain model is well-understood: `TransactionStatus` is a self-guarding enum whose `transitionTo()` throws `IllegalStateTransitionException` for invalid moves. `PaymentEventLog.create()` computes SHA-256 hash chains canonically. `LedgerService.postEntry()` always inserts exactly two rows atomically. `IdempotencyService.checkAndReserve()` uses Redis `setIfAbsent` (NX+EX) for atomic reservation with a PostgreSQL fallback.

**Primary recommendation:** All 23-01, 23-02, and 23-03 test classes extend `AbstractPayamE2ETest` directly (not the template-method subclasses) because invariant, concurrency, and parameterized tests each define their own `@Test` methods. PITest is not currently in `pom.xml` and must be added as a Maven plugin.

---

## Standard Stack

### Core (already in pom.xml)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| JUnit Jupiter | via spring-boot-starter-test | `@ParameterizedTest`, `@MethodSource` | Standard test framework |
| AssertJ | 3.24.2 | Fluent assertions | Already used everywhere |
| Awaitility | 4.2.0 | Async assertions | Already used in existing tests |
| WireMock Spring Boot | 4.0.9 | Provider HTTP stubs | Already used |
| Testcontainers (Postgres + Redis) | via spring-boot-testcontainers | Test databases | Already used |
| commons-codec | 1.19.0 | `DigestUtils.sha256Hex` | Already used in `HashChainVerifier` |

### Must Add
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| PITest Maven Plugin | 1.15.x | Mutation testing | Industry standard Java mutation tool |
| PITest JUnit 5 Plugin | 1.2.x | JUnit 5 bridge for PITest | Required for JUnit Jupiter tests |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| PITest | Stryker, Descartes | PITest is the Java standard; Descartes is a PITest plugin variant only |

### PITest Installation (pom.xml addition)
```xml
<plugin>
    <groupId>org.pitest</groupId>
    <artifactId>pitest-maven</artifactId>
    <version>1.15.3</version>
    <dependencies>
        <dependency>
            <groupId>org.pitest</groupId>
            <artifactId>pitest-junit5-plugin</artifactId>
            <version>1.2.1</version>
        </dependency>
    </dependencies>
    <configuration>
        <targetClasses>
            <param>com.softropic.payam.payment.service.*</param>
            <param>com.softropic.payam.payment.domain.*</param>
            <param>com.softropic.payam.transaction.service.*</param>
            <param>com.softropic.payam.transaction.repo.*</param>
            <param>com.softropic.payam.fraud.service.*</param>
            <param>com.softropic.payam.fraud.infrastructure.*</param>
            <param>com.softropic.payam.webhook.service.*</param>
            <param>com.softropic.payam.reconciliation.service.*</param>
        </targetClasses>
        <targetTests>
            <param>com.softropic.payam.domain.*</param>
        </targetTests>
        <mutators>
            <mutator>STRONGER</mutator>
        </mutators>
        <mutationThreshold>90</mutationThreshold>
    </configuration>
</plugin>
```

---

## Architecture Patterns

### Test Package Structure
New test classes go in:
```
src/test/java/com/softropic/payam/
├── e2e/
│   └── domain/                    # Phase 23 tests live here
│       ├── HashChainIntegrityTest.java
│       ├── LedgerDoubleEntryTest.java
│       ├── IdempotencyNoDoubleChargeTest.java
│       ├── TenantIsolationTest.java
│       ├── StateMachineLegalTransitionsTest.java
│       ├── WebhookDoubleCheckTest.java
│       ├── FraudBeforeProviderCallTest.java
│       ├── CallbackUrlSsrfGuardTest.java
│       ├── InitBeforeProviderCallTest.java
│       ├── OrangeTimestampWatTest.java
│       ├── ConcurrentIdempotencyRaceTest.java
│       ├── WebhookPollingRaceTest.java
│       ├── VelocityCounterFloodTest.java
│       ├── ApiKeyRotationGracePeriodTest.java
│       ├── MtnPathMatrixTest.java           # SM-03 parameterized
│       ├── OrangePathMatrixTest.java        # SM-04 parameterized
│       ├── TransactionBoundaryTest.java     # TXN-01 to TXN-04
│       └── MutationKillTest.java           # PITest target
```

### Pattern 1: INV Tests — Extend AbstractPayamE2ETest Directly
All INV tests use `AbstractPayamE2ETest` directly (not template-method subclasses). Each defines its own `@Test` methods. Setup mirrors `FraudVelocityBlockE2ETest` — seed tenant via `TenantBuilder`, run flow via HTTP, assert via verifiers.

```java
// Source: existing pattern from PaymentIdempotencyE2ETest
public class HashChainIntegrityTest extends AbstractPayamE2ETest {

    @Autowired
    private TenantService tenantService;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void hashChain_isValid_afterSuccessfulPayment() {
        // seed tenant, stub WireMock, POST payment, drive to SUCCESS
        // then:
        new HashChainVerifier(jdbcTemplate).assertChainValid(transactionId);
    }
}
```

### Pattern 2: CONC Tests — CyclicBarrier + ExecutorService
For 20-thread idempotency race (CONC-01):

```java
// Standard Java concurrency pattern
int THREAD_COUNT = 20;
CyclicBarrier barrier = new CyclicBarrier(THREAD_COUNT);
ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
List<Future<PaymentResponse>> futures = new ArrayList<>();

for (int i = 0; i < THREAD_COUNT; i++) {
    futures.add(pool.submit(() -> {
        barrier.await(); // all threads start simultaneously
        return postPayment(req, apiKey);
    }));
}

pool.shutdown();
pool.awaitTermination(30, TimeUnit.SECONDS);

// Collect all transactionIds — all must be the same
Set<String> txIds = futures.stream()
    .map(f -> f.get().transactionId())
    .collect(Collectors.toSet());
assertThat(txIds).hasSize(1);

// Exactly 1 payment row
jdbcTemplate.queryForObject("SELECT count(*) FROM main.transaction WHERE ...", Integer.class);
// Exactly 1 provider call
mtnServer.verify(1, postRequestedFor(urlPathEqualTo("/v1_0/requesttopay")));
```

### Pattern 3: SM Parameterized Tests
Use `@ParameterizedTest` + `@MethodSource` for full path matrix:

```java
// Source: JUnit 5 @ParameterizedTest pattern
@ParameterizedTest(name = "MTN path: {0}")
@MethodSource("mtnPathMatrix")
void mtnPath_drivesCorrectFinalState(String scenarioName,
                                      String wireMockResponse,
                                      String expectedFinalStatus,
                                      int expectedEventCount) {
    // setup stubs per scenario, drive flow, assert
}

static Stream<Arguments> mtnPathMatrix() {
    return Stream.of(
        Arguments.of("success", "SUCCESSFUL", "SUCCESS", 4),
        Arguments.of("fraud-blocked", null, "FAILED", 2),
        Arguments.of("provider-timeout", "TIMEOUT_STUB", "FAILED", 2),
        Arguments.of("webhook-failed", "FAILED", "FAILED", 3),
        Arguments.of("polling-fallback-success", "SUCCESSFUL_VIA_POLL", "SUCCESS", 4)
    );
}
```

### Pattern 4: TXN-01 WireMock Transformer for DB State Inspection
TXN-01 requires asserting that the INIT row exists in the DB at the exact moment WireMock receives the provider HTTP call. The pattern is a `ResponseDefinitionTransformer`:

```java
// WireMock extension that queries the DB when the stub is hit
public class DbStateCapturingTransformer extends ResponseDefinitionTransformer {

    private final JdbcTemplate jdbc;
    private volatile int rowCountAtProviderCall = -1;

    public DbStateCapturingTransformer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ResponseDefinition transform(Request request,
                                         ResponseDefinition responseDefinition,
                                         FileSource files,
                                         Parameters parameters) {
        rowCountAtProviderCall = jdbc.queryForObject(
            "SELECT count(*) FROM main.transaction WHERE tx_status = 'INITIATED'",
            Integer.class);
        return responseDefinition;
    }

    @Override
    public String getName() { return "db-state-capturing-transformer"; }

    public int getRowCountAtProviderCall() { return rowCountAtProviderCall; }
}
```

**IMPORTANT**: The WireMock Spring Boot integration (`wiremock-spring-boot 4.0.9`) uses `@EnableWireMock` / `@ConfigureWireMock`. To add a custom transformer, it must be registered via `WireMockConfigurationCustomizer` or `WireMockServerCustomizer`. The transformer must be registered before the WireMock server starts — this cannot be done per-test via `stubFor()`.

Simpler alternative for TXN-01: use a `WireMock.RequestListener` (WireMock 3.x) or a `stubFor()` with a `stateful` scenario pattern that sets a flag, then assert the DB row count in the test body using Awaitility around the provider call timing.

Simplest viable approach: register a `ServeEventListener` on the WireMock server in `@BeforeEach` that captures DB state into an `AtomicInteger`. WireMock Spring Boot provides `WireMockServer` directly via `@InjectWireMock`, and WireMockServer has `addMockServiceRequestListener(RequestListener)`.

### Pattern 5: PITest Mutation Targets
PITest runs as `mvn pitest:mutate`. The six critical mutations (MUT-02) need dedicated fast unit tests that exercise the exact guard paths:

| Mutation Target | Class | Method/Guard | Test Class |
|----------------|-------|--------------|-----------|
| INITIATED→SUCCESS guard | `TransactionStatus` | `transitionTo()` with INITIATED→SUCCESS | `StateMachineLegalTransitionsTest` |
| Ledger balance check | `LedgerVerifier` / `LedgerService` | `debitAmount == creditAmount` | `LedgerDoubleEntryTest` |
| Idempotency tenant scope | `IdempotencyService` / `DatabaseVerifier` | `tenant_id = ?` in WHERE clause | `IdempotencyNoDoubleChargeTest` |
| Fraud blocking >= threshold | `FraudScoringService` | `fraud.blocked()` check | `FraudBeforeProviderCallTest` |
| Hash chain previousHash inclusion | `PaymentEventLog.create()` | canonical string includes `previousHash` | `HashChainIntegrityTest` |
| Orange +01:00 timestamp offset | `OrangeTimeUtil.parseOrangeTimestamp()` | `WAT = ZoneId.of("Africa/Douala")` | `OrangeTimestampWatTest` |

PITest needs fast unit tests, not Spring Boot integration tests, to be practical. For the six critical mutations, pure unit tests (no `@SpringBootTest`) targeting the domain classes directly will work. The INV tests in 23-01 serve as the mutation kill tests since they exercise the exact same code paths.

**CRITICAL**: PITest cannot run against `@SpringBootTest` tests within the default timeout. The `targetTests` in the PITest config should point to a package of pure unit tests. Create `src/test/java/com/softropic/payam/domain/` with fast unit tests that instantiate domain classes directly (no Spring context).

### Anti-Patterns to Avoid
- **Parameterized tests as `@SpringBootTest`**: Too slow for PITest. Pure unit tests kill mutations faster.
- **Thread.sleep() in concurrency tests**: Use `CyclicBarrier` to coordinate, `Awaitility` for async assertions. Thread.sleep is fragile on CI.
- **WireMock transformer with static state**: Transformer instances are shared; use `AtomicInteger` not a plain `int` field.
- **Using `@EnableWireMock` without resetting in `@AfterEach`**: Already handled by `AbstractPayamE2ETest.baseTearDown()` which calls `mtnServer.resetAll()`.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| SHA-256 hash verification | Custom MessageDigest code | `DigestUtils.sha256Hex()` from commons-codec | Already in `HashChainVerifier`; identical to production `PaymentEventLog.create()` |
| Ledger balance assertion | Custom SQL aggregation | `LedgerVerifier.assertLedgerBalanced()` | Already built; tests accounts CUSTOMER_WALLET + PROVIDER_CLEARING |
| Tenant isolation checks | Custom per-table queries | `TenantIsolationVerifier.assertNoDataLeaksToOtherTenant()` | Already checks 4 tables + Redis |
| Hash chain validation | Manual row iteration | `HashChainVerifier.assertChainValid()` | Recomputes canonical string using identical logic as production |
| Provider call counting | Custom WireMock count query | `ProviderCallVerifier` methods | Already wraps `mtnServer.verify()` |
| Thread coordination | Custom lock/flag patterns | `CyclicBarrier` | Standard Java; predictable all-at-once release |
| Async assertions | `Thread.sleep()` | `Awaitility.await().atMost(5, SECONDS).untilAsserted()` | Already used in MtnWebhookDoubleCheckE2ETest |

**Key insight:** Nearly all assertion infrastructure already exists. Phase 23 tests are primarily *composition* of existing verifiers over new test scenarios, not new infrastructure.

---

## Common Pitfalls

### Pitfall 1: WireMock Transformer Registration Timing
**What goes wrong:** Registering a `ResponseDefinitionTransformer` in `@BeforeEach` via `stubFor()` will not work — transformers must be registered with the `WireMockServer` before stubs are added.
**Why it happens:** `WireMockServer.addStubMapping()` resolves transformers at stub-match time, but the transformer registry is set at server startup.
**How to avoid:** For TXN-01, use `mtnServer.addMockServiceRequestListener()` in `@BeforeEach` to attach a `RequestListener` that captures the DB state at request time. This is the correct hook for state capture during request handling in WireMock 3.x.
**Warning signs:** `IllegalArgumentException: transformer 'X' not configured` at test runtime.

### Pitfall 2: CyclicBarrier Exception Propagation
**What goes wrong:** If one thread in the barrier group throws before `barrier.await()`, the barrier is "broken" and all subsequent awaits throw `BrokenBarrierException`.
**Why it happens:** `CyclicBarrier` transitions to broken state on any exception.
**How to avoid:** Wrap barrier await in try-catch; use `Future.get()` to unwrap exceptions after all threads complete. Assert on futures only after `pool.awaitTermination()`.
**Warning signs:** Tests fail with `BrokenBarrierException` instead of the real assertion failure.

### Pitfall 3: PITest Cannot Run SpringBootTest Tests
**What goes wrong:** PITest times out or OOMs when `targetTests` includes `@SpringBootTest` tests because each mutation starts a new Spring context.
**Why it happens:** PITest runs each surviving mutant against the full test suite. 20 Spring contexts per mutant = unusable.
**How to avoid:** Put the six critical mutation kill tests in a separate fast-unit-test package (`com.softropic.payam.domain`). These tests instantiate domain classes directly. Keep INV E2E tests separate from PITest's `targetTests` scope.
**Warning signs:** `mvn pitest:mutate` runs for >10 minutes.

### Pitfall 4: VelocityCounterFloodTest — Bucket4j Counter vs Redis Counter
**What goes wrong:** Asserting `Redis velocity counter = 100` directly via `redis.opsForValue().get("fraud:velocity:IP_VELOCITY:x.x.x.x")` will find nothing — Bucket4j does not store a simple integer counter; it stores a bucket state blob.
**Why it happens:** `VelocityCheckService` uses `LettuceBasedProxyManager` which stores Bucket4j's internal CAS-based state, not a plain increment counter.
**How to avoid:** Assert the *effect* (N threads exceeded threshold = blocked = exactly N ≤ threshold provider calls made, rest return FRAUD_BLOCKED) rather than the Redis counter value directly. The `mtnServer.verify(atMost(threshold), postRequestedFor(...))` pattern is the correct assertion.
**Warning signs:** Test passes when it shouldn't because the Redis key assertion is trivially true (key doesn't exist).

### Pitfall 5: WebhookPollingRaceTest — AFTER_COMMIT Timing
**What goes wrong:** Thread A (webhook) and Thread B (Quartz poller) both racing to PROCESSING→SUCCESS. The webhook path fires `@TransactionalEventListener(AFTER_COMMIT)` for outbound delivery. Without Awaitility, assertions run before the event fires.
**Why it happens:** `@TransactionalEventListener(AFTER_COMMIT)` executes in a new thread after the transaction commit. The HTTP response returns before that thread completes.
**How to avoid:** Wrap all assertions in `Awaitility.await().atMost(5, SECONDS).untilAsserted(() -> {...})`. This pattern is already established in `MtnWebhookDoubleCheckE2ETest`.
**Warning signs:** Ledger or webhook delivery log assertions fail intermittently.

### Pitfall 6: StateMachine Illegal Transition Tests — DB Must Be Unchanged
**What goes wrong:** Testing that `IllegalStateTransitionException` is thrown is not enough. INV-05 requires the DB row be unchanged after the attempt.
**Why it happens:** If the state machine call is inside a `@Transactional` method that rolls back on `RuntimeException`, the DB *is* unchanged by the rollback, but the test needs to verify this explicitly.
**How to avoid:** After asserting the exception, re-query the DB and assert the status is still the pre-transition value. Use `TransactionStateMachineIT` as a model — it already does this pattern.
**Warning signs:** Test passes on exception assertion but the invariant assertion is missing.

### Pitfall 7: Orange SUCCESSFULL vs MTN SUCCESSFUL Spelling
**What goes wrong:** Using "SUCCESSFUL" (single-L) in Orange WireMock stubs causes `OrangeStatusMapper` to return PROCESSING instead of SUCCESS.
**Why it happens:** Orange API uses "SUCCESSFULL" (double-L). This is documented in prior decision [21-01].
**How to avoid:** Orange stubs must use `"status":"SUCCESSFULL"`. MTN stubs must use `"status":"SUCCESSFUL"`.
**Warning signs:** Orange path matrix tests never reach SUCCESS state.

### Pitfall 8: OrangeTimestampWatTest — UTC vs WAT Comparison
**What goes wrong:** The mutation test for Orange timestamp parsing must prove that UTC parse produces a *different* Instant than WAT parse, not just that WAT parse returns a non-null value.
**Why it happens:** The mutation (changing `ZoneId.of("Africa/Douala")` to `UTC`) would produce an Instant 1 hour earlier. The test must catch this specific difference.
**How to avoid:** Parse the same createtime string with both WAT (`OrangeTimeUtil.parseOrangeTimestamp()`) and with `LocalDateTime.parse(t).toInstant(ZoneOffset.UTC)`. Assert they differ by exactly 3600 seconds.
**Warning signs:** Test passes even when WAT constant is replaced with UTC.

### Pitfall 9: ConcurrentIdempotencyRaceTest — False Success from Redis RESERVED State
**What goes wrong:** Multiple threads may get back "PAYMENT_ALREADY_PROCESSING" (RESERVED state) from `IdempotencyService` rather than the true cached transactionId. The test must assert all 20 threads eventually see the *same* transactionId, not just that no exception occurred.
**Why it happens:** The first successful thread sets the Redis key to the full response JSON only after the provider call completes. Other threads see RESERVED during in-flight processing.
**How to avoid:** Collect all 20 futures' transactionIds. For threads that got RESERVED, their response will have a null transactionId. The assertion should be: all non-null transactionIds are equal AND at most 1 provider call occurred.

---

## Code Examples

### INV-01-TEST: HashChainIntegrityTest skeleton

```java
// Source: HashChainVerifier.java + AbstractPayamE2ETest pattern
public class HashChainIntegrityTest extends AbstractPayamE2ETest {

    @Autowired TenantService tenantService;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void genesisEvent_hasPreviousHashGENESIS() {
        // seed, drive to INITIATED, assert first row previous_hash = "GENESIS"
        TenantBuilder.CreatedTenant tenant = new TenantBuilder()
            .withName("HashChain-Test")
            .create(tenantService, tenantRepository);
        // stub, POST payment, get transactionId
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT previous_hash FROM main.payment_event_log " +
            "WHERE transaction_id = ? ORDER BY id ASC LIMIT 1", transactionId);
        assertThat(rows.get(0).get("previous_hash")).isEqualTo("GENESIS");
    }

    @Test
    void fullChain_isValid_afterSuccessTransition() {
        // drive full flow to SUCCESS
        new HashChainVerifier(jdbcTemplate).assertChainValid(transactionId);
    }
}
```

### INV-05-TEST: Illegal Transition leaves DB unchanged

```java
// Source: TransactionStateMachineIT pattern
@Test
void illegalTransition_throwsAndLeavesDbUnchanged() {
    Transaction tx = buildInitiatedTransaction(); // persist via JDBC or transactionTemplate

    assertThatThrownBy(() ->
        transactionTemplate.execute(status -> {
            Transaction locked = repo.findByTransactionIdForUpdate(txId).orElseThrow();
            locked.applyTransition(TransactionStatus.SUCCESS); // ILLEGAL: INITIATED->SUCCESS
            return null;
        })
    ).isInstanceOf(IllegalStateTransitionException.class);

    // DB must be unchanged
    String actualStatus = jdbcTemplate.queryForObject(
        "SELECT tx_status FROM main.transaction WHERE transaction_id = ?",
        String.class, txId);
    assertThat(actualStatus).isEqualTo("INITIATED");
}
```

### CONC-01: ConcurrentIdempotencyRaceTest skeleton

```java
// Standard Java CyclicBarrier concurrency pattern
@Test
void twentyThreads_sameIdempotencyKey_producesExactlyOneRow() throws Exception {
    int THREADS = 20;
    CyclicBarrier barrier = new CyclicBarrier(THREADS);
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    String sharedKey = UUID.randomUUID().toString();
    PaymentRequest req = new PaymentRequestBuilder()
        .withIdempotencyKey(sharedKey).build();

    List<Future<ResponseEntity<PaymentResponse>>> futures = new ArrayList<>();
    for (int i = 0; i < THREADS; i++) {
        futures.add(pool.submit(() -> {
            barrier.await(); // all threads start simultaneously
            return postPayment(req, tenant.rawApiKey());
        }));
    }

    pool.shutdown();
    assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

    // All 20 responses must return the same transactionId (or RESERVED for in-flight)
    List<String> txIds = futures.stream()
        .map(f -> { try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); } })
        .map(r -> r.getBody() != null ? r.getBody().transactionId() : null)
        .filter(Objects::nonNull)
        .distinct().collect(Collectors.toList());
    assertThat(txIds).hasSize(1); // exactly one unique transactionId

    // Exactly 1 payment row in DB
    Integer rowCount = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM main.transaction WHERE ...", Integer.class);
    assertThat(rowCount).isEqualTo(1);

    // Exactly 1 provider call
    mtnServer.verify(1, postRequestedFor(urlPathEqualTo("/v1_0/requesttopay")));
}
```

### INV-10-TEST: OrangeTimestampWatTest

```java
// Source: OrangeTimeUtil.java
@Test
void parseOrangeTimestamp_returnsWatInstant_notUtcInstant() {
    String createtime = "2024-01-15T10:30:00";

    Instant watInstant = OrangeTimeUtil.parseOrangeTimestamp(createtime);
    Instant utcInstant = LocalDateTime.parse(createtime,
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
        .toInstant(ZoneOffset.UTC);

    // WAT = UTC+1, so WAT 10:30 = UTC 09:30. WAT parse produces UTC 09:30:00.
    // UTC parse would produce UTC 10:30:00. Difference = 3600 seconds.
    assertThat(Duration.between(watInstant, utcInstant).getSeconds())
        .as("WAT parse must produce UTC-1h vs UTC parse (mutation catches this)")
        .isEqualTo(3600L);
}
```

### TXN-01: Init row exists before provider call

```java
// WireMock RequestListener approach
@Test
void initRowExists_inDb_whenProviderCallArrives() throws Exception {
    AtomicInteger rowCountAtProviderCall = new AtomicInteger(-1);
    mtnServer.addMockServiceRequestListener((request, response) -> {
        if (request.getUrl().contains("/v1_0/requesttopay")) {
            rowCountAtProviderCall.set(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM main.transaction WHERE tx_status = 'INITIATED'",
                    Integer.class));
        }
    });
    // stub, POST payment
    // after flow completes:
    assertThat(rowCountAtProviderCall.get())
        .as("INIT row must exist in DB when provider HTTP call arrives")
        .isGreaterThanOrEqualTo(1);
}
```

---

## Domain Model Reference

### TransactionStatus State Machine
```
INITIATED → AUTH_PENDING → AUTHORIZED → PROCESSING → SUCCESS (terminal)
                                      ↘              ↘
                                       FAILED (terminal from any state)
PROCESSING → REVERSED (terminal)
```

All illegal transitions (e.g., INITIATED→SUCCESS, SUCCESS→FAILED) throw `IllegalStateTransitionException` from `TransactionStatus.transitionTo()`.

### Full Legal Transition Matrix
| From | To | Legal? |
|------|----|--------|
| INITIATED | AUTH_PENDING | YES |
| INITIATED | FAILED | YES |
| INITIATED | SUCCESS | **NO** |
| AUTH_PENDING | AUTHORIZED | YES |
| AUTH_PENDING | FAILED | YES |
| AUTHORIZED | PROCESSING | YES |
| AUTHORIZED | FAILED | YES |
| PROCESSING | SUCCESS | YES |
| PROCESSING | FAILED | YES |
| PROCESSING | REVERSED | YES |
| SUCCESS | anything | **NO** (terminal) |
| FAILED | anything | **NO** (terminal) |
| REVERSED | anything | **NO** (terminal) |

### PaymentEventLog Hash Chain Formula
```
canonical = txId + "|" + eventType + "|" + (statusFrom != null ? statusFrom : "null")
            + "|" + statusTo + "|" + actor + "|" + previousHash
eventHash = SHA-256(canonical)
```
Genesis row: `previousHash = "GENESIS"`

### LedgerService Double-Entry
- `postEntry()` is `@Transactional`
- Always inserts exactly 2 rows: DEBIT on `CUSTOMER_WALLET`, CREDIT on `PROVIDER_CLEARING`
- Both share same `entryGroupId` (UUID) and have identical `amount`

### IdempotencyService Redis Pattern
- Key: `"idempotency:" + tenantId + ":" + idempotencyKey`
- `setIfAbsent(key, "RESERVED", TTL)` — atomic NX+EX
- `Boolean.TRUE` or `null` = key was absent = proceed
- `Boolean.FALSE` = key existed = return cached response
- PostgreSQL fallback: `repo.reserve(tsid, tenantId, idempotencyKey, expiresAt, now)` with unique constraint

### OrangeTimeUtil
- `ZoneId.of("Africa/Douala")` = WAT = UTC+1, no DST
- Orange timestamps: `"yyyy-MM-dd'T'HH:mm:ss"` with no offset, WAT assumed
- Mutation catch: changing to `ZoneOffset.UTC` produces Instant 3600s later

### ApiKeyService Grace Period
- `GRACE_PERIOD = Duration.ofHours(24)`
- After rotation: old key valid for 24h via `findValidKeyByHash(hash, graceDeadline)` where `graceDeadline = now - 24h`
- `TenantApiKey.keyStatus` = ACTIVE | ROTATED | REVOKED
- CONC-04: Old key (ROTATED, rotatedAt within 24h window) and new key (ACTIVE) both authenticate to same tenant

### VelocityCheckService
- Uses Bucket4j `LettuceBasedProxyManager` with Redis
- Redis key: `"fraud:velocity:" + signal.getSignalName() + ":" + identifier`
- NOT a plain integer counter — stores Bucket4j CAS state
- Assert *effect* (provider call count ≤ threshold), not raw Redis value

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Hand-rolled hash verification | `HashChainVerifier.assertChainValid()` | Phase 19 | Full verifier exists |
| Per-test DB cleanup in tearDown | `TestDataCleaner.wipeAll()` | Phase 18 | Inherited from base class |
| Direct `@Transactional` on orchestrator | Non-transactional orchestrator with per-step `TransactionTemplate` | Phase 5 | Requires `findByTransactionIdForUpdate()` in test state manipulation |
| WireMock manual server setup | `@EnableWireMock` / `@ConfigureWireMock` | Phase 18 | Injected via `@InjectWireMock` |

**No PITest plugin currently in pom.xml.** Must be added as part of MUT-01.

---

## Open Questions

1. **WireMock RequestListener thread safety**
   - What we know: `WireMockServer.addMockServiceRequestListener()` adds a listener that fires in the WireMock request-handling thread. `AtomicInteger` is safe.
   - What's unclear: Whether the listener is cleared by `mtnServer.resetAll()` in `baseTearDown()`.
   - Recommendation: Call `mtnServer.removeMockServiceRequestListener()` in `@AfterEach`, or re-add in `@BeforeEach` (it's idempotent to add the same instance multiple times — verify by checking WireMock 3.x API).

2. **PITest skip on regular `mvn test` vs dedicated profile**
   - What we know: PITest runs via `mvn pitest:mutate` or via a Maven profile.
   - What's unclear: Whether the team wants PITest to gate CI builds or just be a manually triggered report.
   - Recommendation: Configure PITest in a `mutation` Maven profile so `mvn test` is not affected. Bind to `verify` phase only when the profile is active.

3. **WireMock Quartz poller triggering for CONC-02**
   - What we know: `WebhookPollingRaceTest` requires Thread B to be the Quartz `OrangeStatusPollerJob`. Quartz is auto-configured with a `@Scheduled` or `@QuartzScheduler` trigger.
   - What's unclear: Whether the Quartz scheduler can be triggered programmatically in tests (past tests in Phase 20 did this — check `MtnPollingFallbackE2ETest`).
   - Recommendation: Look at `MtnPollingFallbackE2ETest` for the pattern of manually triggering the poller job.

---

## Sources

### Primary (HIGH confidence)
- Codebase: `TransactionStatus.java` — full state machine with `transitionTo()` guard
- Codebase: `PaymentEventLog.java` — canonical hash formula
- Codebase: `LedgerService.java` — double-entry posting
- Codebase: `IdempotencyService.java` — Redis NX+EX atomic reservation
- Codebase: `OrangeTimeUtil.java` — WAT timezone constant and `parseOrangeTimestamp()`
- Codebase: `HashChainVerifier.java` — existing assertChainValid() verifier
- Codebase: `LedgerVerifier.java` — existing assertLedgerBalanced() verifier
- Codebase: `InvariantVerifier.java` — composite verifier, all delegate accessors
- Codebase: `TenantIsolationVerifier.java` — 4-table + Redis isolation check
- Codebase: `DatabaseVerifier.java` — payment row + idempotency assertions
- Codebase: `ApiKeyService.java` — 24h grace period rotation
- Codebase: `VelocityCheckService.java` — Bucket4j Redis token bucket
- Codebase: `AbstractPayamE2ETest.java` — base class, WireMock injection pattern
- Codebase: `PaymentIdempotencyE2ETest.java` — extends base directly, noRetryRestTemplate pattern
- Codebase: `FraudVelocityBlockE2ETest.java` — local seedFraudRule() helper, concurrent fraud pattern
- Codebase: `TransactionStateMachineIT.java` — illegal transition + DB-unchanged pattern
- Codebase: `pom.xml` — no PITest plugin present; must be added
- Codebase: `TestDataCleaner.java` — wipeAll() preserves Flyway seed rows (fraud_rule ids 1-5, fee_rule id 1)

### Secondary (MEDIUM confidence)
- PITest Maven plugin documentation — version 1.15.x is current stable; `pitest-junit5-plugin` 1.2.x required for JUnit Jupiter
- WireMock 3.x `addMockServiceRequestListener()` API — available on `WireMockServer` for request capture

### Tertiary (LOW confidence)
- WireMock `ResponseDefinitionTransformer` registration approach for TXN-01 — documented pattern but server startup timing requires verification with wiremock-spring-boot 4.0.9 specifically

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all dependencies verified in pom.xml; PITest absence confirmed
- Architecture: HIGH — all base classes and verifiers verified by reading source
- Domain model: HIGH — `TransactionStatus`, `PaymentEventLog`, `LedgerService`, `IdempotencyService` all read from source
- Pitfalls: HIGH for Bucket4j/Redis (source read), MEDIUM for WireMock transformer timing (pattern known, wiremock-spring-boot 4.0.9 specifics not verified against docs)
- PITest config: MEDIUM — version numbers from general knowledge; pom.xml addition pattern is standard

**Research date:** 2026-03-28
**Valid until:** 2026-04-28 (stable codebase, 30-day validity)
