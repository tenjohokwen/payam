# Phase 21: Webhook Flow Tests - Research

**Researched:** 2026-03-27
**Domain:** E2E webhook flow tests — inbound MTN/Orange webhook processing, outbound delivery, replay protection, retry scheduling
**Confidence:** HIGH

---

## Summary

Phase 21 implements six end-to-end test classes (FLOWS-HOOK-01 through FLOWS-HOOK-06) verifying the complete inbound and outbound webhook pipelines. Every production service, entity field, URL path, status constant, and verifier method was read directly from source — no inference required.

The inbound pipeline has two providers with different HTTP methods and correlation keys: MTN uses `PUT /v1/callbacks/mtn` with `externalId` = our `transactionId` as correlation, while Orange uses `POST /v1/callbacks/orange` with `payToken` as correlation. Both implement Redis-based dedup before publishing a `WebhookReceivedEvent`, which `WebhookDoubleCheckHandler` consumes via `@TransactionalEventListener(AFTER_COMMIT)` to call the provider status API and apply the state transition through `WebhookTransitionService`.

The outbound pipeline lives in `WebhookDeliveryService`: after `WebhookTransitionService.applyFinalTransition()` commits, it calls `enqueue()`, which persists a `webhook_delivery_log` row and immediately attempts delivery. Failed attempts schedule exponential backoff retries (`2^attemptCount` minutes, capped at 60) with `MAX_ATTEMPTS = 5`. The Quartz `WebhookDeliveryJob` fires every 1 minute to pick up pending rows via `findPendingForRetry()`.

The E2E tests divide cleanly: 21-01 covers the four inbound scenarios (MTN double-check, Orange double-check, replay protection for both providers, MTN PUT method acceptance), and 21-02 covers the two outbound scenarios (HMAC-signed delivery, 5xx retry scheduling).

**Primary recommendation:** FLOWS-HOOK-01 and FLOWS-HOOK-02 extend `AbstractWebhookFlowTest`, following the same pattern as the existing `MtnPaymentInitiationE2ETest` and `OrangePaymentInitiationE2ETest`. FLOWS-HOOK-03 (replay protection) and FLOWS-HOOK-06 (MTN PUT) fit the `AbstractFailureFlowTest` or a standalone `@Test` pattern. FLOWS-HOOK-04 and FLOWS-HOOK-05 require a third `tenant-wh` WireMock server added to the test class — pattern already proven in `WebhookDeliveryIT`.

---

## Standard Stack

### Core (all in pom.xml — no new dependencies)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `spring-boot-test` | Spring Boot BOM | `@SpringBootTest`, `TestRestTemplate` | Full application context for E2E |
| `wiremock-spring-boot` | 4.0.9 | `@EnableWireMock`, `WireMockServer` | Already used in `AbstractPayamE2ETest`; tenant-wh server for outbound tests |
| `awaitility` | 4.2.0 | Async wait for double-check and outbound delivery | Both events fire `@TransactionalEventListener(AFTER_COMMIT)` |
| `resilience4j-spring-boot3` | Spring Boot BOM | `CircuitBreakerRegistry.reset()` | Inherited from base class |
| `spring-data-redis` | Spring Boot BOM | `StringRedisTemplate` for dedup key inspection | Inherited from base class |
| `assertj-core` | 3.24.2 | Fluent assertions | Standard across all ITs |

### Supporting Beans (needed in test classes)

| Bean | Type | How Obtained | Purpose |
|------|------|--------------|---------|
| `TenantService` | Spring bean | `@Autowired` | `TenantBuilder.create(tenantService, tenantRepository)` |
| `TenantRepository` | Spring bean | `@Autowired` | `TenantBuilder.withWebhookUrl()` to set webhook URL + secret |
| `JdbcTemplate` | Spring bean | `@Autowired` | Instantiate `InvariantVerifier`, `WebhookDeliveryVerifier` |
| `WebhookDeliveryLogRepository` | Spring bean | `@Autowired` | Direct log row inspection for retry state assertions |
| `WebhookDeliveryService` | Spring bean | `@Autowired` | Direct `attemptDelivery()` call for retry E2E test |

**Installation:** No new dependencies. All artifacts already declared in pom.xml.

---

## Architecture Patterns

### Recommended Package Structure

```
src/test/java/com/softropic/payam/
└── e2e/
    └── webhook/
        ├── MtnWebhookDoubleCheckE2ETest.java         # FLOWS-HOOK-01
        ├── OrangeWebhookDoubleCheckE2ETest.java      # FLOWS-HOOK-02
        ├── WebhookReplayProtectionE2ETest.java       # FLOWS-HOOK-03
        ├── OutboundWebhookDeliveryE2ETest.java       # FLOWS-HOOK-04 + FLOWS-HOOK-05
        └── MtnPutCallbackAcceptanceE2ETest.java      # FLOWS-HOOK-06
```

### Pattern 1: Double-check webhook flow (FLOWS-HOOK-01, FLOWS-HOOK-02)

**What:** Extends `AbstractWebhookFlowTest`. Seeds a `PROCESSING` transaction directly via JDBC, dispatches the inbound callback, waits for double-check via Awaitility, asserts `SUCCESS` state and outbound delivery.
**When to use:** MTN and Orange happy-path webhook flows confirming the double-check fires.

```java
// Source: MtnPaymentInitiationE2ETest.java — established pattern for AbstractWebhookFlowTest
public class MtnWebhookDoubleCheckE2ETest extends AbstractWebhookFlowTest {

    @Autowired TenantService tenantService;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private TenantBuilder.CreatedTenant tenant;
    private String transactionId;
    private InvariantVerifier invariant;

    @Override
    protected void setupPreconditions() {
        // MTN double-check stub — SUCCESSFUL (single-L per MtnStatusMapper)
        mtnServer.stubFor(get(urlPathMatching("/v1_0/requesttopay/.*"))
            .willReturn(okJson("{\"status\":\"SUCCESSFUL\",\"financialTransactionId\":\"fin-001\"}")));
        tenant = new TenantBuilder().withName("MTN-Hook-Tenant")
            .create(tenantService, tenantRepository);
        invariant = new InvariantVerifier(jdbcTemplate, redis, mtnServer, orangeServer);
    }

    @Override
    protected void executeFlow() {
        // Insert PROCESSING MTN transaction directly (skip payment initiation)
        // Sets provider_ref = referenceId UUID (needed by double-check for getTransactionStatus call)
        transactionId = insertMtnProcessingTransaction(referenceId, tenant.tenantId());
    }

    @Override
    protected void dispatchInboundWebhook() {
        // MTN uses PUT — a POST returns 405 (FLOWS-HOOK-06 pitfall)
        MtnCallbackPayload payload = new MtnWebhookPayloadBuilder()
            .forTransaction(transactionId)
            .asSuccessful()
            .build();
        new RestTemplate().exchange(
            "http://localhost:" + serverPort + "/v1/callbacks/mtn",
            HttpMethod.PUT, new HttpEntity<>(payload), Void.class);
    }

    @Override
    protected void verifyDoubleCheckTriggered() {
        invariant.assertWebhookDoubleCheckFired(transactionId);
    }

    @Override
    protected void verifyTransactionState() {
        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            invariant.assertLegalStateTransition(transactionId, "SUCCESS");
            invariant.assertLedgerBalanced(transactionId);
            invariant.events().assertEventPresent(transactionId, "PROVIDER_SUCCESS");
        });
    }
}
```

### Pattern 2: Replay protection (FLOWS-HOOK-03)

**What:** Extends `AbstractFailureFlowTest` or uses a two-call standalone `@Test`. Sends the identical webhook payload twice. The second call must be silently accepted (200 OK) but must not create a duplicate outbox event, and transaction state must remain unchanged.
**When to use:** Verifying Redis-based dedup on both Orange and MTN callback paths.

The dedup key structures differ by provider:
- **MTN:** `"webhook:mtn:" + externalId + ":" + status` (in `MtnMoMoPort.processCallback`)
- **Orange:** `"webhook:orange:" + payToken + ":" + createtime` (in `OrangeCallbackController`)

After the first call succeeds, the Redis key prevents the second call from re-publishing `WebhookReceivedEvent`. Verification:
1. `redis.hasKey(dedupKey)` returns true after first delivery
2. Event log count stays at 1 (not 2) — use `EventVerifier.assertEventPresent()` then count check
3. `webhook_delivery_log` row count stays at 1 (if tenant has webhook URL) or 0

### Pattern 3: Outbound delivery + HMAC (FLOWS-HOOK-04, FLOWS-HOOK-05)

**What:** Adds a `tenant-wh` WireMock server to the class-level `@EnableWireMock`. Provisions tenant with `TenantBuilder.withWebhookUrl(tenantWebhookServer.baseUrl() + "/webhook", "test-secret")`. Asserts the delivery log row and captured request headers.
**When to use:** All outbound delivery tests.

```java
// Source: WebhookDeliveryIT.java — proven pattern
@EnableWireMock({
    @ConfigureWireMock(name = "mtn",       baseUrlProperties = {"mtn.collection-base-url"}),
    @ConfigureWireMock(name = "orange",    baseUrlProperties = {"orange.base-url", "orange.pay-url"}),
    @ConfigureWireMock(name = "tenant-wh") // no baseUrlProperties — URL assigned dynamically to Tenant entity
})
public class OutboundWebhookDeliveryE2ETest extends AbstractPayamE2ETest {

    @InjectWireMock("tenant-wh")
    WireMockServer tenantCallbackServer;

    @Autowired WebhookDeliveryLogRepository deliveryLogRepo;
    @Autowired WebhookDeliveryService webhookDeliveryService;
    @Autowired TenantService tenantService;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    // tenant provisioned with webhookUrl = tenantCallbackServer.baseUrl() + "/webhook"
    // and webhookSecret = "test-secret"
}
```

Note: `AbstractPayamE2ETest` already declares `mtn` and `orange` WireMock servers. A subclass declaring `@EnableWireMock` at the class level adds to those. However, `AbstractPayamE2ETest` uses `@EnableWireMock` which means the tenant-wh server must be declared where it can be `@InjectWireMock`-ed. The safest approach is a separate standalone test class (like `WebhookDeliveryIT`) rather than extending `AbstractWebhookFlowTest`, because `AbstractPayamE2ETest`'s `@EnableWireMock` only declares mtn and orange.

**Proven pattern:** Declare `OutboundWebhookDeliveryE2ETest` as a standalone `@SpringBootTest` (not extending AbstractPayamE2ETest) with all three `@ConfigureWireMock` entries, mirroring `WebhookDeliveryIT`. Use `TestDataCleaner` directly for teardown rather than inheriting it.

### Pattern 4: Retry scheduling with 5xx (FLOWS-HOOK-05)

**What:** Stub the tenant callback to return 503. After inbound webhook fires and double-check commits, delivery attempt sets `delivered=false`, `attemptCount >= 1`, `httpStatus = 503`, `nextRetryAt != null`. Use `WebhookDeliveryVerifier.assertRetryScheduled()`.

```java
// Source: WebhookDeliveryService.scheduleRetry() — delay formula
long delayMinutes = Math.min((long) Math.pow(2, delivery.getAttemptCount()), 60);
// attemptCount=1 → delay 2 min; =2 → 4 min; =3 → 8 min; =4 → 16 min; =5 (max) → no more retries
```

To verify minimum 3 attempts, the test must directly invoke `webhookDeliveryService.attemptDelivery(delivery)` twice more after the initial inline attempt. The inline first attempt happens synchronously inside `enqueue()`. Use `Thread.sleep(500)` for the first async delivery then read the delivery row and call `attemptDelivery()` directly for attempts 2 and 3.

### Anti-Patterns to Avoid

- **Using HttpMethod.POST for MTN callbacks:** `MtnCallbackController` only registers `@PutMapping`. A POST returns 405 Method Not Allowed — the callback is silently dropped. Use `HttpMethod.PUT`.
- **Correlating Orange callbacks by transactionId:** Orange `OrangeMoneyPort.processWebhook()` calls `transactionRepository.findByPayToken()`. The test must supply `payToken` in the webhook payload, not `transactionId`.
- **Sending Orange createtime in wrong format:** `OrangeTimeUtil.parseOrangeTimestamp()` expects `yyyy-MM-dd'T'HH:mm:ss` (T separator, no timezone offset). Use `OrangeWebhookPayloadBuilder` which applies `WAT_FORMAT` correctly.
- **Asserting state synchronously after callback dispatch:** `WebhookDoubleCheckHandler` fires via `@TransactionalEventListener(AFTER_COMMIT)`. Always wrap final state assertions in `Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(...)`.
- **Extending AbstractWebhookFlowTest for outbound-only tests:** The parent `AbstractPayamE2ETest` only declares mtn and orange WireMock servers. Tests needing `tenant-wh` must declare all three in a standalone class.
- **Extending AbstractPayamE2ETest for replay protection in a two-test scenario:** The `TestDataCleaner.wipeAll()` in `@AfterEach` also clears Redis via `redis.flushDb()` in `baseSetUp()`. The dedup key test must not re-flush Redis between the first and second call — keep both calls in a single `@Test` method.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Webhook payload construction | Custom JSON strings per test | `MtnWebhookPayloadBuilder` / `OrangeWebhookPayloadBuilder` | Correct field names, WAT time format, Jackson `convertValue` |
| HMAC-SHA256 recomputation | DigestUtils.sha256Hex | `javax.crypto.Mac.getInstance("HmacSHA256")` + `Hex.encodeHexString` | Must match `WebhookDeliveryService` algorithm exactly — plain SHA-256 is wrong |
| Delivery log assertions | Hand-written JDBC queries | `WebhookDeliveryVerifier` | `assertDelivered()`, `assertRetryScheduled()`, `assertHmacHeaderPresent()` already implemented |
| Async delivery wait | `Thread.sleep()` fixed delay | `Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(...)` | Prevents false negatives from timing variance |
| Tenant webhook URL provisioning | Raw JDBC UPDATE on tenant table | `TenantBuilder.withWebhookUrl(url, secret).create(tenantService, tenantRepository)` | Production key hashing + repository save already handled |
| Invariant assertions | Per-test JDBC queries | `InvariantVerifier.assertAll()` / `assertWebhookDoubleCheckFired()` | Composite assertion covering ledger, state, hash chain |

**Key insight:** `WebhookDeliveryVerifier` already computes the expected HMAC using the identical algorithm as production code. Use it — do not recompute inline.

---

## Common Pitfalls

### Pitfall 1: MTN callback via POST returns 405
**What goes wrong:** Test sends `HttpMethod.POST` to `/v1/callbacks/mtn`. Spring returns 405 Method Not Allowed. The callback is silently dropped and the double-check never fires.
**Why it happens:** `MtnCallbackController` is annotated `@PutMapping("/v1/callbacks/mtn")`. No `@PostMapping` exists.
**How to avoid:** Always use `HttpMethod.PUT` for MTN callbacks. This is FLOWS-HOOK-06's explicit test case.
**Warning signs:** Double-check never fires; `assertWebhookDoubleCheckFired()` times out.

### Pitfall 2: Orange webhook correlated by transactionId instead of payToken
**What goes wrong:** Test builds Orange payload with `payToken = transactionId`. `OrangeMoneyPort.processWebhook()` calls `transactionRepository.findByPayToken()` and finds nothing — event is never published.
**Why it happens:** Orange uses payToken as the session token (correlation key), not transactionId.
**How to avoid:** The transaction row must have `pay_token` set. Use `OrangeWebhookPayloadBuilder.forPayToken(payToken)` where `payToken` is the value stored on the transaction. When inserting directly via JDBC, set both `provider_ref` and `pay_token` to the same value.
**Warning signs:** `@TransactionalEventListener` never fires; transaction stays in `PROCESSING`.

### Pitfall 3: Orange createtime format breaks dedup key
**What goes wrong:** `createtime` is sent as `"2026-03-27 10:00:00"` (space separator). `OrangeTimeUtil.parseOrangeTimestamp()` throws; controller returns 200 but double-check path may produce unexpected behavior.
**Why it happens:** `OrangeTimeUtil.ORANGE_FMT` expects `"yyyy-MM-dd'T'HH:mm:ss"` (T separator).
**How to avoid:** Use `OrangeWebhookPayloadBuilder.withCreatetime(Instant.now())` which applies `WAT_FORMAT` correctly. For raw JSON strings (as in existing ITs), use `"2026-03-27T10:00:00"`.

### Pitfall 4: Duplicate dedup key in replay protection test clears between calls
**What goes wrong:** Redis is flushed between the first and second webhook delivery, so the second call is processed as a new webhook — replay protection never fires.
**Why it happens:** `AbstractPayamE2ETest.baseSetUp()` calls `redis.flushDb()` in `@BeforeEach`. If the two-call test uses a sub-test or resets state between calls, the dedup key is lost.
**How to avoid:** Both calls must be in a single `@Test` method body with no Redis flush between them.

### Pitfall 5: Asserting final state synchronously after callback dispatch
**What goes wrong:** Test asserts `txStatus == SUCCESS` immediately after the HTTP response from `PUT /v1/callbacks/mtn`. State is still `PROCESSING` because the `@TransactionalEventListener(AFTER_COMMIT)` fires asynchronously.
**Why it happens:** Spring's `TransactionalEventListener(AFTER_COMMIT)` fires after the current transaction commits, which may be in a separate thread from the servlet response.
**How to avoid:** Always wrap state assertions in `Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(...)`.

### Pitfall 6: MTN double-check uses wrong status string
**What goes wrong:** MTN double-check stub returns `{"status":"SUCCESSFULL"}` (double-L Orange spelling). `MtnStatusMapper.toInternal()` doesn't recognize it — transaction stays `PROCESSING`.
**Why it happens:** MTN uses `"SUCCESSFUL"` (single-L) while Orange uses `"SUCCESSFULL"` (double-L). They are different providers.
**How to avoid:**
- MTN stubs: `{"status":"SUCCESSFUL"}`
- Orange stubs: `{"status":"SUCCESSFULL"}`

### Pitfall 7: tenant-wh WireMock server not declared when extending AbstractPayamE2ETest
**What goes wrong:** `@InjectWireMock("tenant-wh")` fails because the server isn't registered in the `@EnableWireMock` inherited from `AbstractPayamE2ETest`.
**Why it happens:** `AbstractPayamE2ETest` only declares mtn and orange servers. Subclasses cannot add to the parent's `@EnableWireMock`.
**How to avoid:** Outbound delivery tests (FLOWS-HOOK-04, FLOWS-HOOK-05) must be standalone classes declaring all three WireMock servers, mirroring `WebhookDeliveryIT` rather than extending `AbstractPayamE2ETest`.

### Pitfall 8: HMAC computed with plain SHA-256 instead of HmacSHA256
**What goes wrong:** Signature verification fails because `DigestUtils.sha256Hex(payload)` produces a plain hash, not a keyed HMAC.
**Why it happens:** Production code uses `javax.crypto.Mac.getInstance("HmacSHA256")` with the `webhookSecret` as key. Plain SHA-256 ignores the key entirely.
**How to avoid:** Use `WebhookDeliveryVerifier.assertHmacSignatureCorrect()` which mirrors the production algorithm exactly. Never use `DigestUtils`.

---

## Code Examples

### Insert PROCESSING MTN Transaction Directly via JDBC

```java
// Source: WebhookDoubleCheckIT.java — createOrangeProcessingTransaction pattern adapted for MTN
// MTN requires provider_ref (referenceId UUID); Orange requires pay_token
private String insertMtnProcessingTransaction(String referenceId, Long tenantId) {
    String txId = UUID.randomUUID().toString();
    String traceId = UUID.randomUUID().toString();
    long id = System.nanoTime() & Long.MAX_VALUE;
    transactionTemplate.execute(status -> {
        jdbcTemplate.update(
            "INSERT INTO main.transaction " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, " +
            "transaction_id, trace_id, tenant_id, tx_status, status, provider, amount, currency, provider_ref) " +
            "VALUES (?, 'TEST', NOW(), 'TEST', NOW(), ?, ?, ?, 'PROCESSING', 'ACTIVE', 'MTN', 100, 'XAF', ?)",
            id, txId, traceId, tenantId, referenceId);
        return null;
    });
    return txId;
}
```

```java
// Source: WebhookDoubleCheckIT.java — Orange version (pay_token is correlation key)
private String insertOrangeProcessingTransaction(String payToken, Long tenantId) {
    String txId = UUID.randomUUID().toString();
    String traceId = UUID.randomUUID().toString();
    long id = System.nanoTime() & Long.MAX_VALUE;
    transactionTemplate.execute(status -> {
        jdbcTemplate.update(
            "INSERT INTO main.transaction " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, " +
            "transaction_id, trace_id, tenant_id, tx_status, status, provider, amount, currency, provider_ref, pay_token) " +
            "VALUES (?, 'TEST', NOW(), 'TEST', NOW(), ?, ?, ?, 'PROCESSING', 'ACTIVE', 'ORANGE', 100, 'XAF', ?, ?)",
            id, txId, traceId, tenantId, payToken, payToken);
        return null;
    });
    return txId;
}
```

### Dispatch MTN Inbound Webhook (PUT)

```java
// Source: MtnPaymentInitiationE2ETest.dispatchInboundWebhook()
MtnCallbackPayload payload = new MtnWebhookPayloadBuilder()
    .forTransaction(transactionId) // sets externalId = transactionId (MTN correlation key)
    .asSuccessful()                // status=SUCCESSFUL, random financialTransactionId
    .build();
new RestTemplate().exchange(
    "http://localhost:" + serverPort + "/v1/callbacks/mtn",
    HttpMethod.PUT,               // CRITICAL: MTN is PUT not POST
    new HttpEntity<>(payload),
    Void.class);
```

### Dispatch Orange Inbound Webhook (POST)

```java
// Source: OrangePaymentInitiationE2ETest.dispatchInboundWebhook()
// Orange correlation is by payToken, NOT transactionId
String body = String.format(
    "{\"payToken\":\"%s\",\"notif_token\":\"%s\",\"status\":\"SUCCESS\",\"txnid\":\"%s\"," +
    "\"msisdn\":\"237653000001\",\"amount\":\"1000\",\"createtime\":\"%s\"}",
    payToken,                     // must match pay_token on the Transaction row
    UUID.randomUUID(),
    UUID.randomUUID(),
    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")));
HttpHeaders headers = new HttpHeaders();
headers.setContentType(MediaType.APPLICATION_JSON);
new RestTemplate().exchange(
    "http://localhost:" + serverPort + "/v1/callbacks/orange",
    HttpMethod.POST,              // Orange is POST
    new HttpEntity<>(body, headers), Void.class);
```

### Verify Outbound Delivery with HMAC

```java
// Source: WebhookDeliveryIT.shouldDeliverWebhookWithCorrectHmacSignatureOnSuccessTransition()
// Stub the tenant callback endpoint
tenantCallbackServer.stubFor(post(urlEqualTo("/webhook"))
    .willReturn(aResponse().withStatus(200).withBody("ok")));

// After webhook fires and double-check commits:
Thread.sleep(500); // wait for @TransactionalEventListener delivery

// Verify delivery log
WebhookDeliveryVerifier verifier = new WebhookDeliveryVerifier(jdbcTemplate, tenantCallbackServer);
verifier.assertDelivered(transactionId);
verifier.assertHmacHeaderPresent(tenantCallbackServer, transactionId);

// Optionally recompute HMAC from captured request
LoggedRequest logged = tenantCallbackServer.findAll(
    postRequestedFor(urlEqualTo("/webhook"))).get(0);
verifier.assertHmacSignatureCorrect(
    logged.getBodyAsString(), "test-secret", logged.getHeader("X-Payam-Signature"));
```

### Verify Retry Scheduled on 5xx

```java
// Source: WebhookDeliveryIT.shouldScheduleRetryWhenTenantEndpointReturns503()
tenantCallbackServer.stubFor(post(urlEqualTo("/webhook"))
    .willReturn(aResponse().withStatus(503).withBody("Service Unavailable")));

// After double-check fires:
Thread.sleep(500);

List<WebhookDeliveryLog> logs = deliveryLogRepo
    .findByTransactionIdOrderByCreatedDateAsc(transactionId);
WebhookDeliveryLog log = logs.get(0);
assertThat(log.getDelivered()).isFalse();
assertThat(log.getAttemptCount()).isGreaterThanOrEqualTo(1);
assertThat(log.getHttpStatus()).isEqualTo(503);
assertThat(log.getNextRetryAt()).isNotNull(); // retry scheduled

// To verify minimum 3 attempts, call attemptDelivery() directly 2 more times
webhookDeliveryService.attemptDelivery(logs.get(0));
webhookDeliveryService.attemptDelivery(deliveryLogRepo.findById(logs.get(0).getId()).get());
assertThat(deliveryLogRepo.findById(logs.get(0).getId()).get().getAttemptCount())
    .isGreaterThanOrEqualTo(3);
```

### Verify Replay Protection (Redis dedup key assertion)

```java
// After first webhook call:
// MTN dedup key: "webhook:mtn:" + externalId + ":" + status
// Orange dedup key: "webhook:orange:" + payToken + ":" + createtime
String mtnDedupKey = "webhook:mtn:" + transactionId + ":SUCCESSFUL";
assertThat(redis.hasKey(mtnDedupKey)).isTrue();

// Send second identical call — must return 200 silently
// Then verify event count in payment_event_log has NOT increased
Integer eventCount = jdbcTemplate.queryForObject(
    "SELECT count(*) FROM main.payment_event_log WHERE transaction_id = ? " +
    "AND event_type IN ('PROVIDER_SUCCESS','PROVIDER_FAILED')",
    Integer.class, transactionId);
assertThat(eventCount).isEqualTo(1); // only one double-check fired
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Orange status check with wrong URL path | `GET /mp/paymentstatus/{payToken}` | Phase 6 | Double-check stub must use `urlPathMatching("/mp/paymentstatus/.*")` |
| Inline dedup in OrangeMoneyPort | Dedup in OrangeCallbackController (before delegation) | Phase 6 | Redis key is set before `processWebhook()` is called |
| MTN dedup in MtnCallbackController | MTN dedup inside `MtnMoMoPort.processCallback()` | Phase 6 | The dedup key for MTN uses `(externalId, status)` pair, not inside the controller |

---

## Open Questions

1. **Replay protection test structure for FLOWS-HOOK-03**
   - What we know: The requirement says "duplicate webhook delivery rejected; transaction state unchanged; no duplicate outbox event"
   - What's unclear: Whether FLOWS-HOOK-03 should be a single class with two `@Test` methods (one for MTN, one for Orange) or two separate test classes
   - Recommendation: Single class with two `@Test` methods since they share the setup pattern. Use `AbstractPayamE2ETest` directly (not `AbstractWebhookFlowTest`) because the test does not follow the standard 4-phase template.

2. **Minimum 3 attempts verification for FLOWS-HOOK-05**
   - What we know: `WebhookDeliveryService.MAX_ATTEMPTS = 5`; first attempt is synchronous in `enqueue()`; Quartz fires every 1 minute
   - What's unclear: Whether the test should trigger retries by calling `webhookDeliveryService.attemptDelivery()` directly (fast, deterministic) or by waiting for Quartz (slow, flaky)
   - Recommendation: Call `webhookDeliveryService.attemptDelivery()` directly for attempts 2 and 3. This is the approach used in `WebhookDeliveryIT` for analogous delivery testing.

3. **MtnPutCallbackAcceptanceE2ETest structure for FLOWS-HOOK-06**
   - What we know: `MtnCallbackController` only has `@PutMapping`. A POST returns 405.
   - What's unclear: Whether FLOWS-HOOK-06 is a positive-path test (verify PUT returns 200 and processes correctly) or also includes a negative assertion that POST returns 405
   - Recommendation: Include both assertions in the same test: PUT returns 200 and triggers double-check; POST returns 405.

---

## Sources

### Primary (HIGH confidence)
- Direct source read: `MtnCallbackController.java` — `@PutMapping("/v1/callbacks/mtn")`, dedup delegated to `MtnMoMoPort`
- Direct source read: `OrangeCallbackController.java` — `@PostMapping("/v1/callbacks/orange")`, Redis dedup key `"webhook:orange:" + payToken + ":" + createtime`
- Direct source read: `MtnMoMoPort.processCallback()` — MTN dedup key `"webhook:mtn:" + externalId + ":" + status`
- Direct source read: `WebhookDoubleCheckHandler.java` — `@TransactionalEventListener(AFTER_COMMIT)`, delegates to `WebhookTransitionService`
- Direct source read: `WebhookTransitionService.applyFinalTransition()` — `REQUIRES_NEW`, PESSIMISTIC_WRITE lock, calls `webhookDeliveryService.enqueue()`
- Direct source read: `WebhookDeliveryService.java` — `MAX_ATTEMPTS=5`, `scheduleRetry()` formula `2^attemptCount` capped at 60, `noRetryRestTemplate`, HMAC header `X-Payam-Signature`
- Direct source read: `WebhookDeliveryJob.java` — Quartz `QuartzJobBean`, fires every 1 minute
- Direct source read: `WebhookDeliveryVerifier.java` — `assertDelivered()`, `assertRetryScheduled()`, `assertHmacHeaderPresent()`, `assertHmacSignatureCorrect()`
- Direct source read: `AbstractWebhookFlowTest.java`, `AbstractPayamE2ETest.java`, `AbstractPaymentFlowTest.java`, `AbstractFailureFlowTest.java`
- Direct source read: `MtnWebhookPayloadBuilder.java`, `OrangeWebhookPayloadBuilder.java`, `TenantBuilder.java`
- Direct source read: `WebhookDoubleCheckIT.java` — `createOrangeProcessingTransaction()` pattern, mtn/orange WireMock setup, teardown order
- Direct source read: `WebhookDeliveryIT.java` — `tenant-wh` WireMock pattern, HMAC recomputation, 503 retry test
- Direct source read: `MtnStatusMapper.java` — `"SUCCESSFUL"` (single-L)
- Direct source read: `OrangeStatusMapper.java` — `"SUCCESSFULL"` (double-L)

### Secondary (MEDIUM confidence)
- None required — all findings are HIGH confidence from direct source reads.

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all library imports read from existing test files
- Architecture: HIGH — all patterns read from existing test implementations
- Pitfalls: HIGH — all pitfalls sourced from production code comments (marked P1.x) and existing test doc strings
- Code examples: HIGH — all examples adapted directly from existing IT tests

**Research date:** 2026-03-27
**Valid until:** 2026-04-27 (stable — no fast-moving external dependencies)
