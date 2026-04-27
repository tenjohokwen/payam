# Phase 53: E2E Test Suite - Research

**Researched:** 2026-04-27
**Domain:** Spring Boot integration testing — disbursement E2E flows, WireMock multi-server topology, concurrency tests, LedgerVerifier, Awaitility
**Confidence:** HIGH

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| TEST-01 | MTN disbursement E2E: happy path (PROCESSING → callback SUCCESS → SUCCESS + ledger balanced), callback FAILED + balance released, callback replay ignored | MtnDisbursementCallbackControllerIT already exists (Phase 52). E2E class needs full lifecycle from `POST /v1/disbursements` through callback via HTTP. |
| TEST-02 | Orange disbursement E2E: happy path, insufficient balance (422, no provider call), callback replay | OrangeDisbursementCallbackControllerIT already exists (Phase 52). E2E needs full HTTP lifecycle path. |
| TEST-03 | Step-up confirmation E2E: `PENDING_CONFIRMATION` → confirm → `PROCESSING`; expiry → `EXPIRED` after 15-min Quartz tick; no provider call on expiry | DisbursementExpiryJobIT and DisbursementOrchestratorIT cover parts. E2E needs single test class covering all three sub-flows via HTTP. |
| TEST-04 | Concurrency race: 20 simultaneous POST /v1/disbursements against same wallet, balance covers 1 — exactly 1 PROCESSING, 19 get 422 INSUFFICIENT_BALANCE, no overdraft | WalletBalanceConcurrencyIT covers the service layer. Phase 53 needs HTTP-level proof via DisbursementResource. |

</phase_requirements>

---

## Summary

Phase 53 is a pure test-writing phase. All production code shipped in Phases 50–52 is already running correctly (Phases 50–52 green, 38 plans complete across v10). The objective is to assemble a machine-verifiable proof suite that covers the full disbursement lifecycle end-to-end — from HTTP request through provider stubs to database and ledger assertions.

The project already has comprehensive disbursement unit and integration tests (DisbursementOrchestratorIT, DisbursementExpiryJobIT, WalletBalanceConcurrencyIT, MtnDisbursementCallbackControllerIT, OrangeDisbursementCallbackControllerIT, DisbursementWebhookDeliveryIT) and a mature E2E infrastructure (`AbstractPayamE2ETest`, `LedgerVerifier`, `ConcurrentIdempotencyRaceTest` pattern). Phase 53 stitches these into four cohesive E2E test classes under `src/test/java/com/softropic/payam/e2e/disbursement/`.

The critical pattern decision: Phase 53 E2E tests should use **standalone IT topology** (mirrors Phase 52 ITs) rather than `AbstractPayamE2ETest`. The base class only configures `mtn.collection-base-url` and not `mtn.disbursement-base-url` — using it would require modifying the shared base, which risks breaking the existing 30+ collection-era E2E tests. Instead, each Phase 53 E2E class configures its own `@EnableWireMock` block exactly as `DisbursementOrchestratorIT` does.

**Primary recommendation:** Four standalone E2E test classes (no base-class extension), each self-contained with TestConfig import, direct HTTP via TestRestTemplate, and Awaitility waits for AFTER_COMMIT async transitions.

---

## Standard Stack

### Core (already in project — no new dependencies needed)
| Library | Purpose | Pattern Reference |
|---------|---------|-------------------|
| Spring Boot Test (`@SpringBootTest(RANDOM_PORT)`) | Full application context with real HTTP | All existing ITs |
| WireMock Spring (`wiremock-spring-boot`) | Provider simulation — mtn-disbursement, mtn-collection, orange servers | DisbursementOrchestratorIT, MtnDisbursementCallbackControllerIT |
| Testcontainers PostgreSQL | Real DB for ledger, wallet, disbursement rows | TestConfig (via @ServiceConnection) |
| Testcontainers Redis | Real Redis for idempotency, dedup keys | TestConfig (via @ServiceConnection) |
| AssertJ | Fluent assertions | All tests |
| Awaitility | Async assertion polling (AFTER_COMMIT listener delay) | DisbursementWebhookDeliveryIT, MtnCallbackControllerIT |
| JUnit 5 | Test runner | All tests |
| `LedgerVerifier` | Reusable 3-entry disbursement ledger assertion | Already exists at `e2e/verify/LedgerVerifier.java` |
| `TestDataCleaner` | Full DB wipe including disbursement + wallet tables | `wipeAll()` covers disbursement, merchant_wallet_balance |

### No new dependencies are required.

---

## Architecture Patterns

### Recommended Test Structure
```
src/test/java/com/softropic/payam/e2e/disbursement/
├── MtnDisbursementE2ETest.java       # TEST-01
├── OrangeDisbursementE2ETest.java    # TEST-02
├── StepUpConfirmationE2ETest.java    # TEST-03
└── ConcurrentDisbursementRaceTest.java  # TEST-04 + fraud/idempotency race
```

### Pattern 1: Standalone IT with Dual-WireMock topology
**What:** Each test class is fully self-contained — imports TestConfig, configures its own `@EnableWireMock` block with both `mtn-collection` and `mtn-disbursement` servers (and orange as needed), seeds its own tenant and wallet, cleans up in `@AfterEach`.

**When to use:** Any disbursement E2E test. MANDATORY because `AbstractPayamE2ETest` only wires `mtn.collection-base-url` — the disbursement port needs `mtn.disbursement-base-url` for provider token fetch and disbursement API calls.

**Critical WireMock configuration (confirmed from DisbursementOrchestratorIT):**
```java
@EnableWireMock({
    @ConfigureWireMock(name = "mtn",
        baseUrlProperties = {"mtn.collection-base-url", "mtn.disbursement-base-url"},
        portProperties    = {"wiremock.mtn.port"}),
    @ConfigureWireMock(name = "orange",
        baseUrlProperties = {"orange.base-url", "orange.pay-url"},
        portProperties    = {"wiremock.orange.port"})
})
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "mtn.callback-ip-whitelist=",
    "orange.callback-ip-whitelist=",
    "mtn.collection-token-url=http://localhost:${wiremock.mtn.port}/token/collection",
    "mtn.disbursement-token-url=http://localhost:${wiremock.mtn.port}/token/disbursement"
})
```

**Token stub pattern (confirmed from DisbursementOrchestratorIT):**
```java
mtnServer.stubFor(post(urlPathEqualTo("/token/collection"))
    .willReturn(okJson("{\"access_token\":\"mtn-coll-bearer\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
mtnServer.stubFor(post(urlPathEqualTo("/token/disbursement"))
    .willReturn(okJson("{\"access_token\":\"mtn-disb-bearer\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
orangeServer.stubFor(post(urlPathEqualTo("/token"))
    .withHeader("Authorization", containing("Basic"))
    .willReturn(okJson("{\"access_token\":\"orange-bearer\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
```

### Pattern 2: HTTP-level disbursement initiation
**What:** Post to `/v1/disbursements` with `X-Api-Key` header (tenant's raw API key) and `Idempotency-Key` header. Use `TestRestTemplate` (no error handler override needed for 202 flows; use `RestTemplate` with `DefaultResponseErrorHandler` override for 4xx flow assertions).

**Confirmed API contract (from DisbursementResource):**
- POST `/v1/disbursements` — body: `DisbursementRequest`; headers: `X-Api-Key`, `Idempotency-Key`
- Returns 202 with `DisbursementResponse` containing `disbursementId`, `status`, optional `errorCode`
- POST `/v1/disbursements/{id}/confirm` — same auth headers, no body

**Tenant seeding (confirmed from DisbursementOrchestratorIT):**
```java
tenantId = tenantService.createTenant("dsb-e2e-" + UUID.randomUUID(), ApiKeyEnvironment.PROD)
    .tenant().getId();
// then get rawApiKey from the result for X-Api-Key header
```

**JWT secret seeding (confirmed from DisbursementOrchestratorIT, DisbursementExpiryJobIT — mandatory):**
```java
transactionTemplate.execute(status -> {
    jdbcTemplate.execute(
        "INSERT INTO main.sec (...) VALUES ('659287191260154475',...) ON CONFLICT DO NOTHING");
    return null;
});
```

**Wallet seeding (confirmed from DisbursementOrchestratorIT):**
```java
jdbcTemplate.update(
    "INSERT INTO main.merchant_wallet_balance " +
    "(id, created_by, created_date, last_modified_by, last_modified_date, " +
    "request_id, status, tenant_id, balance, reserved_amount, currency, version) " +
    "VALUES (?, 'TEST', NOW(), 'TEST', NOW(), gen_random_uuid()::text, 'ACTIVE', ?, ?, 0, 'XAF', 0)",
    System.currentTimeMillis(), tenantId, initialBalance);
```

### Pattern 3: Callback dispatch within E2E tests
**What:** For MTN disbursement callbacks, use `PUT /v1/callbacks/mtn/disbursement/{providerRef}` with JSON body matching the controller's expected payload. For Orange, use `POST /v1/callbacks/orange/disbursement`.

**Awaitility for async transitions (AFTER_COMMIT listener):**
```java
await().atMost(Duration.ofSeconds(10)).until(() ->
    disbursementRepository.findByDisbursementId(disbId).orElseThrow()
        .getDisbursementStatus() == DisbursementStatus.SUCCESS);
```

**Note:** The callback transition fires via `@TransactionalEventListener(AFTER_COMMIT)` inside a `REQUIRES_NEW` transaction. The callback HTTP response returns before the transition completes. Awaitility is mandatory.

### Pattern 4: LedgerVerifier usage for TEST-01 happy path
**What:** After MTN callback SUCCESS transition, assert the disbursement ledger entries (3 rows: DEBIT MERCHANT_WALLET gross, CREDIT CUSTOMER_WALLET principal, CREDIT PROVIDER_FEE fee).

**Existing `assertDisbursementLedgerBalanced` signature (confirmed from LedgerVerifier.java):**
```java
LedgerVerifier ledger = new LedgerVerifier(jdbcTemplate);
ledger.assertDisbursementLedgerBalanced(disbursementId, principal, fee);
```

**Important:** The ledger is written during the callback SUCCESS transition (in `DisbursementCallbackTransitionService`). It will only be present AFTER the Awaitility poll confirms `DisbursementStatus.SUCCESS`. Call `assertDisbursementLedgerBalanced` AFTER the Awaitility wait.

### Pattern 5: MTN provider stubs for disbursement flows
**What:** Stub MTN account validation, transfer, and disbursement status endpoints.

**Confirmed endpoints (from DisbursementOrchestratorIT and MtnDisbursementCallbackControllerIT):**
```java
// Account validation (validateSubscriber)
mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*"))
    .willReturn(okJson("{}")));
// Transfer initiation
mtnServer.stubFor(post(urlPathEqualTo("/v1_0/transfer"))
    .willReturn(aResponse().withStatus(202)));
// Disbursement status (for callback double-check)
mtnServer.stubFor(get(urlPathMatching(".*/transfer/" + providerRef))
    .willReturn(okJson("{\"status\":\"SUCCESSFUL\",\"amount\":\"5000\",\"currency\":\"XAF\",...}")));
```

**MSISDN prefix for MTN:** `+237671234567` (MTN prefix 67x confirmed in DisbursementOrchestratorIT).
**MSISDN prefix for Orange:** `+237691234567` (Orange prefix 69x).

### Pattern 6: Orange provider stubs for disbursement flows
**What:** Orange uses cashout endpoint (not collection endpoint). Confirmed from DisbursementOrchestratorIT.

**Confirmed endpoints:**
```java
// Orange subscriber info (validateSubscriber)
orangeServer.stubFor(post(urlPathMatching("/infos/subscriber/customer/.*"))
    .willReturn(okJson("{\"data\":{\"firstname\":\"Jean\"},\"message\":\"OK\"}")));
// Cashout (ic2cDisbursement)
orangeServer.stubFor(post(urlPathEqualTo("/cashout"))
    .willReturn(okJson("{\"status\":\"SUCCESS\"}")));
```

**Note:** Orange callback double-check uses a different status endpoint. Confirm exact path from `OrangeMoneyClient.getPaymentStatus` before writing the stub (Medium confidence — see Open Questions).

### Pattern 7: Concurrency race test (TEST-04)
**What:** 20 threads simultaneously POST to `/v1/disbursements` with wallet balance covering exactly 1 request. Uses `CyclicBarrier` for synchronized blast, `ExecutorService`, collects responses.

**Reference implementation:** `ConcurrentIdempotencyRaceTest` and `WalletBalanceConcurrencyIT` provide the exact patterns. Phase 53 adapts at the HTTP level (not service layer).

**Wallet seeding for race:** seed with `balance = PER_REQUEST_AMOUNT` (enough for exactly 1), `reserved_amount = 0`.

**Error handler override needed** (to capture 422 without exception):
```java
RestTemplate rt = new RestTemplate(new SimpleClientHttpRequestFactory());
rt.setErrorHandler(new DefaultResponseErrorHandler() {
    @Override public boolean hasError(HttpStatusCode statusCode) { return false; }
    @Override public boolean hasError(ClientHttpResponse response) throws IOException { return false; }
});
```

**Expected assertions:**
- Exactly 1 response with `status=PROCESSING`
- 19 responses with HTTP 422 and `errorCode=INSUFFICIENT_BALANCE`
- Wallet balance query: `balance = 0`, `reserved_amount = PER_REQUEST_AMOUNT` (no overdraft)
- 1 provider call to MTN transfer endpoint

### Pattern 8: Fraud block assertion (TEST-01 sub-test)
**What:** Seed known-fraud MSISDN in Redis/fraud rules, POST disbursement, assert `FRAUD_BLOCK` errorCode returned and zero provider calls.

**Confirmed fraud scoring signals (from REQUIREMENTS.md SEC-03):**
- Known-fraud MSISDN: +80 score → score > 80 → FRAUD_BLOCK

**WireMock request count assertion:**
```java
mtnServer.verify(0, postRequestedFor(urlPathEqualTo("/v1_0/transfer")));
```

**Fraud rule seeding:** The Flyway-seeded fraud rules use `BLOCK_THRESHOLD=80` for disbursements (SEC-03 spec: score > 80 blocks). To trigger fraud block via known-fraud MSISDN, seed a fraud rule or use a pre-configured known-fraud MSISDN. Read `DisbursementFraudEvaluationService` to confirm the exact mechanism before writing — confirmed needed as an Open Question below.

### Pattern 9: Idempotency race (TEST-01 sub-test)
**What:** 20 concurrent threads with same `Idempotency-Key` — exactly 1 disbursement row in DB.

**Reference:** `ConcurrentIdempotencyRaceTest` (collection) is the direct analog. Adaptation: use `idempotency:dsb:` namespace, POST to `/v1/disbursements` with same `Idempotency-Key` header.

**Expected assertion:** exactly 1 row in `main.disbursement` for the given idempotency key.

### Pattern 10: Step-up expiry via DisbursementExpiryJob
**What:** The 15-minute Quartz expiry test must bypass the Quartz scheduler and invoke `DisbursementExpiryJob.executeInternal(null)` directly, with `spring.quartz.auto-startup=false`.

**Confirmed from DisbursementExpiryJobIT:**
```java
@SpringBootTest(properties = {"enable.test.mail=true", "spring.quartz.auto-startup=false"})
// Inject DisbursementExpiryJob expiryJob;
// Insert aged disbursement (NOW() - INTERVAL '16 minutes') via jdbcTemplate
expiryJob.executeInternal(null);
```

**Aging pattern (avoids JVM/DB timezone skew — confirmed from DisbursementExpiryJobIT):**
```java
jdbcTemplate.update(
    "INSERT INTO main.disbursement (..., created_date, ...) " +
    "VALUES (..., NOW() - INTERVAL '16 minutes', ...)", ...);
```

### Anti-Patterns to Avoid

- **Extending `AbstractPayamE2ETest` for disbursement tests:** The base class only configures `mtn.collection-base-url`, not `mtn.disbursement-base-url`. Using it will cause `MtnMoMoPort.initiateDisbursement` to call the wrong endpoint. Every E2E test in Phase 53 MUST use standalone `@EnableWireMock` topology.
- **Using `@Transactional` on test methods:** Spring test `@Transactional` rolls back automatically — the AFTER_COMMIT listener never fires. Use `transactionTemplate.execute()` for seeding; never annotate tests with `@Transactional`.
- **Calling provider count assertions before Awaitility resolves:** The callback transition is async. Always Awaitility-wait for the terminal status before querying the ledger or wallet.
- **Missing Idempotency-Key header on POST /v1/disbursements:** The controller requires this header; missing it returns 400, not 422.
- **Reusing tenant across tests without cleanup:** `TestDataCleaner.wipeAll()` covers all disbursement tables. Call it in `@AfterEach`.
- **Using `walletRepo.findById(tenantId)`:** The base entity `id` is TSID-generated Long, not the business `tenantId`. Use `walletRepo.findByTenantId(tenantId)` — confirmed as a Phase 52 decision.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Disbursement ledger assertions | Custom SQL ledger check | `LedgerVerifier.assertDisbursementLedgerBalanced()` | Already implemented in Phase 48; handles 3-entry MERCHANT_WALLET/CUSTOMER_WALLET/PROVIDER_FEE logic |
| DB wipe between tests | Manual DELETE statements | `TestDataCleaner.wipeAll()` | Handles FK order, preserves Flyway-seeded fraud/fee rules, covers disbursement tables |
| Concurrent thread orchestration | Ad-hoc Thread.sleep patterns | `CyclicBarrier` + `ExecutorService` + `AtomicInteger` | Proven pattern from WalletBalanceConcurrencyIT and ConcurrentIdempotencyRaceTest |
| Provider simulation | Real provider calls | WireMock | Already wired; all stubs verified against real provider API shapes in existing ITs |
| Async assertion polling | Thread.sleep loops | Awaitility `await().atMost(Duration.ofSeconds(10))` | Proven in MtnDisbursementCallbackControllerIT; avoids flaky fixed sleeps |
| Test DB/Redis containers | Manual container mgmt | TestConfig (auto via @ServiceConnection) | Testcontainers already managed by TestConfig |

---

## Common Pitfalls

### Pitfall 1: mtn.disbursement-base-url missing from WireMock config
**What goes wrong:** `MtnMoMoPort.initiateDisbursement` (or `getDisbursementTransactionStatus`) resolves `mtn.disbursement-base-url` from config. If this property points to a non-existent server, calls fail with `ConnectionRefused`.
**Why it happens:** `AbstractPayamE2ETest` (and naive copies of it) only configure `mtn.collection-base-url`.
**How to avoid:** Always include both `"mtn.collection-base-url"` and `"mtn.disbursement-base-url"` in `baseUrlProperties` for the `mtn` WireMock server, plus both token URL properties.
**Warning signs:** `ConnectException` or `SocketException` in test logs during disbursement initiation.

### Pitfall 2: Forgetting the JWT secret SQL seed (`main.sec`)
**What goes wrong:** `SecurityAdviceFilter` (or equivalent) rejects all API requests with 401 when no security row exists.
**Why it happens:** The security row seeding is not automatic — it's injected by `E2ESecurityConfig.seedSecurityRow()` in `AbstractPayamE2ETest.baseSetUp()`. Standalone ITs must replicate this manually.
**How to avoid:** Copy the exact `INSERT INTO main.sec` block from `DisbursementOrchestratorIT.setUp()` (line 96–106) into every `@BeforeEach`. The ID `659287191260154475` and the value are hardcoded — use them verbatim.
**Warning signs:** All HTTP calls returning 401 or 403.

### Pitfall 3: Async callback transition not yet complete
**What goes wrong:** Test asserts `DisbursementStatus.SUCCESS` immediately after the callback HTTP call returns — but the transition fires via `@TransactionalEventListener(AFTER_COMMIT)` in a `REQUIRES_NEW` bean, meaning it completes slightly after the callback response.
**Why it happens:** Spring's `@TransactionalEventListener(AFTER_COMMIT)` is inherently async from the HTTP request perspective.
**How to avoid:** Always use `await().atMost(Duration.ofSeconds(10))` before any status assertion following a callback dispatch.
**Warning signs:** Intermittent `AssertionError: expected SUCCESS but was PROCESSING`.

### Pitfall 4: Orange cashout endpoint path uncertainty
**What goes wrong:** `OrangeMoneyPort.ic2cDisbursement()` may call `/cashout` (legacy) or `/ic2c/pay` (v10 path). If the stub path doesn't match the actual call, the call returns a 404 from WireMock and the port throws a provider error.
**Why it happens:** The Orange cashout path changed during v10 — the v9 path was `/cashout`, but Phase 51 introduced `ic2cDisbursement()` potentially calling `/ic2c/pay`.
**How to avoid:** Read `OrangeMoneyPort.ic2cDisbursement()` source before writing any Orange E2E stubs. Confirm exact endpoint path and body structure. This is flagged in STATE.md blockers.
**Warning signs:** WireMock returning "No stub found" for POST to Orange server.

### Pitfall 5: WalletRepo lookup by wrong key
**What goes wrong:** `walletRepo.findById(tenantId)` returns empty — the base entity ID is a TSID-generated Long, not the business tenantId.
**Why it happens:** JPA's `findById()` uses the `@Id` field. `MerchantWalletBalance.id` is a TSID-generated primary key; `tenantId` is a separate business-key field.
**How to avoid:** Always use `walletRepo.findByTenantId(tenantId)` — confirmed in Phase 52 decisions.

### Pitfall 6: Orange subscriber validation endpoint
**What goes wrong:** In `DisbursementOrchestratorIT`, the Orange subscriber validation stub is `POST /infos/subscriber/customer/.*` — but the actual Orange port for ic2cDisbursement might call a different endpoint.
**Why it happens:** The subscriber validation path for disbursement (ic2c) vs collection (cashout) might differ.
**How to avoid:** Read `OrangeMoneyPort.validateAccountHolder()` (or `ic2cDisbursement()`) before writing subscriber stubs. Confirm whether the `69x` national MSISDN stripping matches what the port produces.

### Pitfall 7: Fraud block seeding mechanism
**What goes wrong:** Sending a request to a "known-fraud" MSISDN doesn't trigger FRAUD_BLOCK because the fraud seeding is wrong.
**Why it happens:** `DisbursementFraudEvaluationService` applies signals differently than the collection `FraudScoringService`. The +80 "known-fraud MSISDN" signal likely requires a Redis entry or a seeded blocklist.
**How to avoid:** Read `DisbursementFraudEvaluationService` source to confirm how "known-fraud MSISDN" is represented (Redis key? DB row?). Seed the correct store before asserting FRAUD_BLOCK.

### Pitfall 8: Idempotency race uses distinct `Idempotency-Key` header, not body field
**What goes wrong:** Concurrent requests with different `Idempotency-Key` header values don't exercise the race condition — they're all treated as distinct disbursements.
**Why it happens:** The controller reads the key from the `Idempotency-Key` HTTP header, not from the JSON body. All 20 concurrent threads must share the exact same header value.
**How to avoid:** In the idempotency race helper, set `headers.set("Idempotency-Key", sharedKey)` where `sharedKey` is a constant defined once before all threads launch.

---

## Code Examples

### E2E test class skeleton (TEST-01: MTN happy path)
```java
// Source: confirmed topology from DisbursementOrchestratorIT + MtnDisbursementCallbackControllerIT
@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "mtn.callback-ip-whitelist=",
    "orange.callback-ip-whitelist=",
    "mtn.collection-token-url=http://localhost:${wiremock.mtn.port}/token/collection",
    "mtn.disbursement-token-url=http://localhost:${wiremock.mtn.port}/token/disbursement"
})
@EnableWireMock({
    @ConfigureWireMock(name = "mtn",
        baseUrlProperties = {"mtn.collection-base-url", "mtn.disbursement-base-url"},
        portProperties    = {"wiremock.mtn.port"}),
    @ConfigureWireMock(name = "orange",
        baseUrlProperties = {"orange.base-url", "orange.pay-url"},
        portProperties    = {"wiremock.orange.port"})
})
class MtnDisbursementE2ETest {
    @InjectWireMock("mtn") WireMockServer mtnServer;
    @Autowired TestRestTemplate testRestTemplate;
    @Autowired TenantService tenantService;
    @Autowired DisbursementRepository disbursementRepository;
    @Autowired MerchantWalletBalanceRepository walletRepo;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired StringRedisTemplate redis;
    @Autowired TestDataCleaner testDataCleaner;
    @LocalServerPort int serverPort;

    private Long tenantId;
    private String rawApiKey;

    @BeforeEach
    void setUp() {
        testDataCleaner.wipeAll();
        seedJwtSecret();
        var created = tenantService.createTenant("mtn-e2e-" + UUID.randomUUID(), ApiKeyEnvironment.PROD);
        tenantId = created.tenant().getId();
        rawApiKey = created.rawKey(); // or however the raw key is exposed
        seedWallet(tenantId, new BigDecimal("100000"));
        stubTokenEndpoints();
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @AfterEach
    void tearDown() {
        mtnServer.resetAll();
        testDataCleaner.wipeAll();
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }
}
```

### Disbursement initiation helper
```java
// Source: confirmed from DisbursementResource contract + DisbursementRequest record
private ResponseEntity<String> postDisbursement(String msisdn, BigDecimal amount,
                                                  String reference, String idempotencyKey) {
    String body = """
        {"recipientMsisdn":"%s","amount":%s,"currency":"XAF","reference":"%s"}
        """.formatted(msisdn, amount.toPlainString(), reference);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Api-Key", rawApiKey);
    headers.set("Idempotency-Key", idempotencyKey);
    return testRestTemplate.exchange(
        "http://localhost:" + serverPort + "/v1/disbursements",
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        String.class);
}
```

### MTN callback dispatch helper
```java
// Source: confirmed from MtnDisbursementCallbackControllerIT
private ResponseEntity<Void> putMtnCallback(String providerRef, String disbursementId,
                                              String status) {
    String body = "{\"externalId\":\"" + disbursementId + "\",\"status\":\"" + status +
                  "\",\"financialTransactionId\":\"FT-1\"}";
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return testRestTemplate.exchange(
        "http://localhost:" + serverPort + "/v1/callbacks/mtn/disbursement/" + providerRef,
        HttpMethod.PUT, new HttpEntity<>(body, headers), Void.class);
}
```

### LedgerVerifier usage after SUCCESS callback
```java
// Source: LedgerVerifier.assertDisbursementLedgerBalanced — confirmed signature
// Wait for async transition first:
await().atMost(Duration.ofSeconds(10)).until(() ->
    disbursementRepository.findByDisbursementId(disbId).orElseThrow()
        .getDisbursementStatus() == DisbursementStatus.SUCCESS);

LedgerVerifier ledger = new LedgerVerifier(jdbcTemplate);
ledger.assertDisbursementLedgerBalanced(disbId, new BigDecimal("5000"), BigDecimal.ZERO);
```

### Concurrency race pattern (TEST-04)
```java
// Source: WalletBalanceConcurrencyIT + ConcurrentIdempotencyRaceTest
int THREADS = 20;
CyclicBarrier barrier = new CyclicBarrier(THREADS);
ExecutorService pool = Executors.newFixedThreadPool(THREADS);
AtomicInteger successes = new AtomicInteger(0);
AtomicInteger insufficients = new AtomicInteger(0);

for (int i = 0; i < THREADS; i++) {
    pool.submit(() -> {
        try { barrier.await(10, TimeUnit.SECONDS); } catch (Exception ignored) {}
        var response = postDisbursement(MTN_MSISDN, BALANCE_AMOUNT, "REF-" + i,
                                         UUID.randomUUID().toString());
        if (response.getStatusCode().value() == 202) successes.incrementAndGet();
        else if (response.getStatusCode().value() == 422) insufficients.incrementAndGet();
    });
}
pool.shutdown();
assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
assertThat(successes.get()).isEqualTo(1);
assertThat(insufficients.get()).isEqualTo(THREADS - 1);

// DB: no overdraft
MerchantWalletBalance wallet = walletRepo.findByTenantId(tenantId).orElseThrow();
assertThat(wallet.getBalance()).isEqualByComparingTo("0");
assertThat(wallet.getReservedAmount()).isEqualByComparingTo(BALANCE_AMOUNT);
```

---

## Coverage Map — What Existing Tests Already Prove vs. What Phase 53 Adds

| Scenario | Existing Test | Phase 53 Adds |
|----------|---------------|---------------|
| Wallet balance reserve/release service-layer | `WalletBalanceConcurrencyIT` | HTTP-level 20-thread race via POST /v1/disbursements |
| MTN callback → SUCCESS transition | `MtnDisbursementCallbackControllerIT.shouldTransitionToSuccessOnSuccessfulCallback` | Full lifecycle (initiate → callback) + LedgerVerifier.assertDisbursementLedgerBalanced |
| MTN callback → FAILED + wallet release | `MtnDisbursementCallbackControllerIT.shouldTransitionToFailedAndReleaseWallet` | Full lifecycle from HTTP initiation |
| MTN callback replay | `MtnDisbursementCallbackControllerIT.shouldDeduplicateReplayedCallback` | Confirms within full E2E context |
| Orange callback → SUCCESS | `OrangeDisbursementCallbackControllerIT` | Full lifecycle from HTTP initiation |
| Orange insufficient balance | `DisbursementOrchestratorIT.insufficient_balance_returns_failed_no_provider_call` | HTTP-level REST assertion (422 + errorCode in body) |
| Step-up → PENDING_CONFIRMATION | `DisbursementOrchestratorIT.step_up_amount_returns_pending_confirmation_no_provider_call` | HTTP-level + confirm endpoint + expiry in one test class |
| Step-up confirm → PROCESSING | `DisbursementOrchestratorIT.confirm_pending_disbursement_dispatches_to_provider` | HTTP-level |
| Expiry → EXPIRED, no wallet release | `DisbursementExpiryJobIT.expiryJob_agedPendingConfirmation_transitionsToExpired` | HTTP-initiated then direct `executeInternal()` |
| Fraud block → no provider call | `DisbursementFraudEvaluationServiceTest` (unit) | HTTP-level with WireMock provider call count assertion |
| Idempotency race → 1 row | `DisbursementIdempotencyIT` (single-threaded) | 20-thread concurrent race over same Idempotency-Key header |

Phase 53 adds the **HTTP-level closure** for scenarios already proven at the service layer. This is the "machine-verified correct" bar set by the phase goal.

---

## Open Questions

1. **Orange ic2c cashout endpoint path**
   - What we know: `DisbursementOrchestratorIT` stubs `POST /cashout`. Phase 51 introduced `OrangeMoneyPort.ic2cDisbursement()`.
   - What's unclear: Whether `ic2cDisbursement()` calls `/cashout` (legacy path) or `/ic2c/pay` (new path per PROV-02 spec). STATE.md blocks mention: "Read `OrangeMoneyClient.cashout()` HTTP path before writing any disbursement port code".
   - Recommendation: Executor MUST read `OrangeMoneyPort.ic2cDisbursement()` and `OrangeMoneyClient` source as first task action. Do not assume `/cashout` — stub the actual path.

2. **Orange callback double-check status endpoint**
   - What we know: The Orange callback `double-check` calls a provider status API. For collection, it calls `GET /orange-money-webpay/dev/v1/transactionstatus?orderId=...`.
   - What's unclear: Whether the disbursement path uses the same endpoint or a separate ic2c status endpoint.
   - Recommendation: Executor must read `OrangeMoneyPort.getDisbursementTransactionStatus()` before writing Orange E2E callback stubs.

3. **Fraud FRAUD_BLOCK seeding mechanism for disbursement**
   - What we know: `DisbursementFraudEvaluationService` applies known-fraud MSISDN signal (+80). The collection path uses a Redis-keyed blocklist or a seeded DB rule.
   - What's unclear: Whether the disbursement service reads from the same Redis blocklist or a different store. The exact Redis key or DB mechanism to seed a "known-fraud MSISDN".
   - Recommendation: Executor must read `DisbursementFraudEvaluationService` source before writing the fraud block E2E test. Use the simplest seeding mechanism the service exposes.

4. **Raw API key extraction from `TenantService.createTenant()`**
   - What we know: `DisbursementOrchestratorIT` calls `tenantService.createTenant(...).tenant().getId()`. The raw API key for HTTP headers comes from the create result.
   - What's unclear: Whether the return type exposes `rawKey()` directly or requires a second lookup. Pattern established in v6 (`TenantBuilder.CreatedTenant.rawApiKey()`).
   - Recommendation: Executor uses `TenantService.createTenant()` return value — check if result has `rawKey()` or equivalent. If not, use `TenantBuilder` pattern from `ConcurrentIdempotencyRaceTest`.

5. **DisbursementResource response body shape for 422**
   - What we know: The controller returns `DisbursementResponse` with `errorCode` field. 422 responses use `ResponseEntity<DisbursementResponse>`.
   - What's unclear: Whether the 422 body is `DisbursementResponse` (with errorCode) or a generic `ApiError` format.
   - Recommendation: Executor reads `DisbursementResource` exception handling and `ApiAdvice` to confirm 422 body structure before writing assertions.

---

## Environment Availability

Step 2.6: SKIPPED — Phase 53 is pure test code. All dependencies (Testcontainers, WireMock, Redis, PostgreSQL) are already declared and available in the project's test classpath. No new external dependencies are introduced.

---

## Validation Architecture

`workflow.nyquist_validation` is not set in `.planning/config.json` — treat as enabled.

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test |
| Config file | None — Spring Boot auto-configuration via `@SpringBootTest` |
| Quick run command | `mvn test -pl . -Dtest=MtnDisbursementE2ETest -q` |
| Full suite command | `mvn verify -q` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| TEST-01 | MTN happy path + FAILED + replay + fraud + idempotency race | integration (E2E) | `mvn verify -Dit.test=MtnDisbursementE2ETest -q` | ❌ Wave 0 |
| TEST-02 | Orange happy path + insufficient balance + replay | integration (E2E) | `mvn verify -Dit.test=OrangeDisbursementE2ETest -q` | ❌ Wave 0 |
| TEST-03 | Step-up: PENDING_CONFIRMATION + confirm + expiry | integration (E2E) | `mvn verify -Dit.test=StepUpConfirmationE2ETest -q` | ❌ Wave 0 |
| TEST-04 | 20-thread concurrency race via HTTP | integration (E2E) | `mvn verify -Dit.test=ConcurrentDisbursementRaceTest -q` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `mvn verify -Dit.test={ClassUnderTask} -q`
- **Per wave merge:** `mvn verify -q`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `src/test/java/com/softropic/payam/e2e/disbursement/MtnDisbursementE2ETest.java` — covers TEST-01
- [ ] `src/test/java/com/softropic/payam/e2e/disbursement/OrangeDisbursementE2ETest.java` — covers TEST-02
- [ ] `src/test/java/com/softropic/payam/e2e/disbursement/StepUpConfirmationE2ETest.java` — covers TEST-03
- [ ] `src/test/java/com/softropic/payam/e2e/disbursement/ConcurrentDisbursementRaceTest.java` — covers TEST-04

---

## Sources

### Primary (HIGH confidence)
- `src/test/java/com/softropic/payam/disbursement/service/DisbursementOrchestratorIT.java` — WireMock topology, token stubs, wallet seeding, JWT secret, all confirmed
- `src/test/java/com/softropic/payam/disbursement/api/MtnDisbursementCallbackControllerIT.java` — callback dispatch, Awaitility pattern, wallet release assertion
- `src/test/java/com/softropic/payam/disbursement/api/OrangeDisbursementCallbackControllerIT.java` — Orange callback, replay dedup
- `src/test/java/com/softropic/payam/disbursement/service/WalletBalanceConcurrencyIT.java` — CyclicBarrier race pattern, 20-thread assertion
- `src/test/java/com/softropic/payam/disbursement/service/DisbursementExpiryJobIT.java` — step-up expiry direct invocation pattern, BAL-03 assertion
- `src/test/java/com/softropic/payam/e2e/verify/LedgerVerifier.java` — `assertDisbursementLedgerBalanced` confirmed signature and account codes
- `src/test/java/com/softropic/payam/e2e/domain/ConcurrentIdempotencyRaceTest.java` — HTTP-level concurrent race pattern, no-retry RestTemplate builder
- `src/test/java/com/softropic/payam/e2e/AbstractPayamE2ETest.java` — base class topology gap confirmed (no mtn.disbursement-base-url)
- `src/test/java/com/softropic/payam/config/TestDataCleaner.java` — wipeAll() covers disbursement and wallet tables confirmed
- `.planning/REQUIREMENTS.md` — TEST-01 through TEST-04 requirement definitions confirmed

### Secondary (MEDIUM confidence)
- `src/main/java/com/softropic/payam/disbursement/api/DisbursementResource.java` — HTTP contract (paths, headers, response shape) — read to line 80; full error mapping confirmed from Javadoc
- `.planning/STATE.md` — key decisions from Phases 50–52, blockers, and established patterns

---

## Metadata

**Confidence breakdown:**
- Test topology: HIGH — all WireMock patterns confirmed from existing ITs
- LedgerVerifier usage: HIGH — existing implementation fully read
- Concurrency patterns: HIGH — direct reference implementations exist
- Orange E2E endpoint paths: MEDIUM — open question remains on ic2c vs cashout path
- Fraud seeding mechanism: MEDIUM — implementation not yet read

**Research date:** 2026-04-27
**Valid until:** 2026-05-27 (stable tech — no breaking changes expected within 30 days)
