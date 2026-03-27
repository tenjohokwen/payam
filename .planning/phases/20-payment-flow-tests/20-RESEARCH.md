# Phase 20: Payment Flow Tests - Research

**Researched:** 2026-03-27
**Domain:** E2E payment flow tests — MTN and Orange happy/unhappy paths through all verifiers
**Confidence:** HIGH

---

## Summary

Phase 20 implements seven end-to-end test classes (FLOWS-PAY-01 through FLOWS-PAY-07) that drive full payment lifecycles through the running application and assert using the verifier and builder infrastructure from Phase 19. Every production service, stub URL path, entity field, and verifier method was read directly from source — no inference required.

The test classes divide cleanly across two plans: 20-01 handles the four happy/quasi-happy paths (MTN webhook, Orange webhook, MTN polling fallback, Orange payToken expiry), and 20-02 handles the three failure/edge paths (idempotency rounds, fraud-blocked, provider timeout + circuit breaker). Each class extends either `AbstractPaymentFlowTest`, `AbstractWebhookFlowTest`, or `AbstractFailureFlowTest` based on its phase structure.

The key architectural insight is that webhook-driven flows require an Awaitility wait after dispatching the inbound callback because the double-check (`WebhookDoubleCheckHandler`) fires via `@TransactionalEventListener(AFTER_COMMIT)` — the transaction commits asynchronously after the callback HTTP response returns. The test cannot assert final state synchronously.

**Primary recommendation:** All seven test classes extend one of the three abstract bases. Use `AbstractWebhookFlowTest` for FLOWS-PAY-01 and FLOWS-PAY-02 (webhook paths). Use `AbstractPaymentFlowTest` for FLOWS-PAY-03 (polling fallback, where `simulateProviderCallback()` manually triggers the poller job). Use `AbstractFailureFlowTest` for FLOWS-PAY-04, FLOWS-PAY-06, and FLOWS-PAY-07. Use a standalone `@Test` pattern for FLOWS-PAY-05 (idempotency three-round scenario).

---

## Standard Stack

### Core (all in pom.xml — no new dependencies)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `spring-boot-test` | Spring Boot BOM | `@SpringBootTest`, `TestRestTemplate` | Full application context for E2E |
| `wiremock-spring-boot` | 4.0.9 | `@EnableWireMock`, `WireMockServer` stub control | Already used in `AbstractPayamE2ETest` |
| `awaitility` | 4.2.0 | Async wait for webhook delivery | `WebhookDeliveryVerifier.assertDelivered` uses it |
| `resilience4j-spring-boot3` | Spring Boot BOM | `CircuitBreakerRegistry.circuitBreaker("mtn").reset()` | Circuit state management in tests |
| `spring-jdbc` | Spring Boot BOM | `JdbcTemplate` for verifiers | Already wired in `AbstractPayamE2ETest` |
| `assertj-core` | 3.24.2 | Fluent assertions | Standard across all ITs |

### Supporting (Spring-managed beans needed in test `@BeforeEach`)

| Bean | Type | How Obtained | Purpose |
|------|------|--------------|---------|
| `TenantService` | Spring bean | `@Autowired` | `TenantBuilder.create(tenantService, tenantRepository)` |
| `TenantRepository` | Spring bean | `@Autowired` | `TenantBuilder.create()` webhook URL update |
| `JdbcTemplate` | Spring bean | `@Autowired` | Instantiate verifiers |
| `MtnStatusPollerJob` | Spring bean | `@Autowired` | Direct `executeInternal()` trigger for polling fallback |
| `OrangeStatusPollerJob` | Spring bean | `@Autowired` | Not needed for 20-01/20-02 but available |
| `FraudRuleCache` | Spring bean | `@Autowired` | `refreshRules()` after lowering velocity threshold |
| `CircuitBreakerRegistry` | Spring bean | `@Autowired` (in base) | `reset()` per test; `transitionToOpenState()` for FLOWS-PAY-07 |

**Installation:** No new dependencies. All artifacts are already declared in pom.xml.

---

## Architecture Patterns

### Recommended Package Structure

```
src/test/java/com/softropic/payam/
└── e2e/
    └── payment/
        ├── MtnPaymentInitiationE2ETest.java     # FLOWS-PAY-01
        ├── OrangePaymentInitiationE2ETest.java  # FLOWS-PAY-02
        ├── MtnPollingFallbackE2ETest.java       # FLOWS-PAY-03
        ├── OrangePayTokenExpiryE2ETest.java     # FLOWS-PAY-04
        ├── PaymentIdempotencyE2ETest.java       # FLOWS-PAY-05
        ├── FraudBlockedPaymentE2ETest.java      # FLOWS-PAY-06
        └── ProviderTimeoutCircuitBreakerE2ETest.java  # FLOWS-PAY-07
```

### Pattern 1: Webhook-driven flow (FLOWS-PAY-01, FLOWS-PAY-02)

**What:** Extends `AbstractWebhookFlowTest` — three sealed phases plus two new abstract methods.
**When to use:** MTN and Orange happy paths where provider notifies via inbound callback.

```java
// Source: AbstractWebhookFlowTest.java — final sealed phase pair
public class MtnPaymentInitiationE2ETest extends AbstractWebhookFlowTest {

    @Autowired TenantService tenantService;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private TenantBuilder.CreatedTenant tenant;
    private String transactionId;
    private InvariantVerifier invariant;

    @Override
    protected void setupPreconditions() {
        // Stub MTN provider endpoints
        mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
            .willReturn(okJson("{}")));
        mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
            .willReturn(aResponse().withStatus(202)));
        // Stub double-check GET call with SUCCESSFUL status
        mtnServer.stubFor(get(urlPathMatching("/v1_0/requesttopay/.*"))
            .willReturn(okJson("{\"status\":\"SUCCESSFUL\",\"financialTransactionId\":\"fin-123\"}")));

        tenant = new TenantBuilder()
            .withName("MTN-E2E-Tenant")
            .create(tenantService, tenantRepository);
        invariant = new InvariantVerifier(jdbcTemplate, redis, mtnServer, orangeServer);
    }

    @Override
    protected void executeFlow() {
        PaymentRequest req = new PaymentRequestBuilder()
            .withDeterministicIdempotencyKey(new DeterministicUuidFactory(0xABCDEL))
            .build(); // MTN MSISDN "237690000001" by default
        // POST /v1/payments — capture transactionId from 202 response
    }

    @Override
    protected void dispatchInboundWebhook() {
        MtnCallbackPayload payload = new MtnWebhookPayloadBuilder()
            .forTransaction(transactionId)
            .asSuccessful()
            .build();
        // PUT /v1/callbacks/mtn
    }

    @Override
    protected void verifyDoubleCheckTriggered() {
        invariant.assertWebhookDoubleCheckFired(transactionId);
    }

    @Override
    protected void verifyTransactionState() {
        // Wait for AFTER_COMMIT async double-check to complete
        Awaitility.await().atMost(5, SECONDS).untilAsserted(() ->
            invariant.assertAll(transactionId, tenant.tenantId(), idempotencyKey, "SUCCESS"));
    }
}
```

### Pattern 2: Polling fallback flow (FLOWS-PAY-03)

**What:** Extends `AbstractPaymentFlowTest`. `simulateProviderCallback()` does nothing (no webhook). `verifyFinalState()` manually invokes the poller job via direct bean call.
**When to use:** FLOWS-PAY-03 — no webhook arrives, Quartz polling drives SUCCESS.

```java
// Source: MtnStatusPollerJob.java — QuartzJobBean with @Autowired beans
// Direct call pattern from MtnMoMoPortIT.java
@Autowired MtnStatusPollerJob mtnStatusPollerJob;

@Override
protected void simulateProviderCallback() {
    // No callback — polling path under test
}

@Override
protected void verifyFinalState() {
    // Stub the GET status endpoint with SUCCESSFUL before triggering the poller
    mtnServer.stubFor(get(urlPathMatching("/v1_0/requesttopay/.*"))
        .willReturn(okJson("{\"status\":\"SUCCESSFUL\",\"financialTransactionId\":\"fin-poll\"}")));

    // Directly invoke the Quartz job (bypasses scheduler timing)
    mtnStatusPollerJob.executeInternal(null);

    // Poller sets status synchronously — no Awaitility needed
    invariant.assertAll(transactionId, tenant.tenantId(), idempotencyKey, "SUCCESS");
}
```

### Pattern 3: Failure flow (FLOWS-PAY-06, FLOWS-PAY-07)

**What:** Extends `AbstractFailureFlowTest`. `injectFault()` configures the fault before the flow runs.
**When to use:** Fraud-blocked path, circuit breaker path.

```java
// Source: AbstractFailureFlowTest.java — 4 sealed phases
public class FraudBlockedPaymentE2ETest extends AbstractFailureFlowTest {

    @Autowired FraudRuleCache fraudRuleCache;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;

    @Override
    protected void setupPreconditions() {
        tenant = new TenantBuilder().withName("Fraud-Test").create(tenantService, tenantRepository);
        invariant = new InvariantVerifier(jdbcTemplate, redis, mtnServer, orangeServer);
    }

    @Override
    protected void injectFault() {
        // Lower MSISDN_VELOCITY to 1 so the FIRST request is blocked (pre-seeded bucket = 0)
        // OR: use AppVelocity threshold = 0 to block without consuming velocity tokens first
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "UPDATE main.fraud_rule SET threshold = 0 WHERE signal_name = 'APP_VELOCITY'");
            return null;
        });
        fraudRuleCache.refreshRules();
    }

    @Override
    protected void executeFlow() {
        // POST /v1/payments — expect 422 FRAUD_BLOCKED
    }

    @Override
    protected void verifyFailureHandled() {
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().errorCode()).isEqualTo("FRAUD_BLOCKED");
        invariant.provider().assertNoProviderCalls();
        invariant.ledger().assertNoLedgerEntries(transactionId);
    }
}
```

### Pattern 4: Three-round idempotency test (FLOWS-PAY-05)

**What:** NOT a template-method pattern. Standalone test class with `@Test` method containing all three rounds in sequence.
**Why:** The three-round scenario (new creation → duplicate same tenant → cross-tenant creates separate) cannot be cleanly decomposed into the four-phase template. A single `@Test` method is cleaner and aligns with how `PaymentOrchestratorIT` handles idempotency.

```java
// Round 1: New creation → 202 + PROCESSING
// Round 2: Same tenant, same key → 202 + identical transactionId
// Round 3: Different tenant, same key → 202 + different transactionId
//          + TenantIsolationVerifier.assertNoDataLeaksToOtherTenant
```

### Pattern 5: Circuit breaker forced OPEN (FLOWS-PAY-07)

**What:** Use `circuitBreakerRegistry.circuitBreaker("mtn").transitionToOpenState()` after tripping the CB with failures, then assert the next request returns 503.
**Key:** The base class resets both circuit breakers in `@BeforeEach` via `circuitBreakerRegistry.circuitBreaker("mtn").reset()`. For FLOWS-PAY-07 the test must use `@TestPropertySource` to override the CB window to a small size (e.g., `slidingWindowSize=10, failureRateThreshold=50`), send enough failures to trip it, then call `transitionToOpenState()` explicitly.
**Alternatively:** Call `transitionToOpenState()` directly without tripping it naturally — simpler and more deterministic.

### Anti-Patterns to Avoid

- **Asserting final state synchronously after PUT /v1/callbacks/mtn**: The double-check fires after `AFTER_COMMIT`. Always wrap in `Awaitility.await().atMost(5, SECONDS)`.
- **Using TestRestTemplate for circuit breaker tests**: `TestRestTemplate` uses Apache HTTP Client which auto-retries 503 responses, masking the circuit-open state. Use a `RestTemplate` with `SimpleClientHttpRequestFactory` and a no-op error handler instead (exact pattern from `PaymentOrchestratorIT.noRetryRestTemplate`).
- **Calling `mtnStatusPollerJob.executeInternal(null)` before stubbing the status endpoint**: The poller requires the GET endpoint to return SUCCESSFUL. Stub it before triggering the job.
- **Directly setting fraud_rule threshold without `fraudRuleCache.refreshRules()`**: `FraudScoringService` reads from the cache, not the DB directly. DB update alone has no effect until cache is refreshed.
- **Using `@BeforeEach` to lower fraud velocity threshold**: This leaks state into the superclass `baseSetUp()` which calls `stubTokenEndpoints()`. The fraud threshold adjustment must happen in `setupPreconditions()` or `injectFault()`.
- **Expecting Orange payToken expiry (FLOWS-PAY-04) to consume the idempotency key**: Per requirement FLOWS-PAY-04, the idempotency key is NOT consumed on payToken expiry. Assert with `CacheVerifier.assertIdempotencyKeyAbsent`.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| HTTP client for POST /v1/payments | Custom RestTemplate config | `TestRestTemplate` (normal flows), `noRetryRestTemplate` (circuit breaker) | TestRestTemplate has 5xx retry behavior that breaks CB tests |
| Callback dispatch (PUT/POST) | RestTemplate | `TestRestTemplate.exchange(PUT, ...)` or `MockMvc` | Inbound callback endpoints are public; no API key needed |
| Poller execution timing | `Thread.sleep()` | `mtnStatusPollerJob.executeInternal(null)` direct call | Avoids flaky test timing; Quartz job is a Spring bean |
| Final state wait after webhook | Thread.sleep | `Awaitility.await().atMost(5, SECONDS).untilAsserted(...)` | Deterministic async wait |
| Fraud rule injection | Manual SQL insert | `transactionTemplate.execute` + `jdbcTemplate.update` + `fraudRuleCache.refreshRules()` | Same pattern as `FraudEngineIT` |
| Verifier instantiation per test | Spring `@Component` verifiers | POJO construction in `setupPreconditions()` | Verifiers are POJOs per Phase 19 design |

---

## Common Pitfalls

### Pitfall 1: Webhook assertion before double-check completes

**What goes wrong:** Test asserts `tx_status = SUCCESS` immediately after `PUT /v1/callbacks/mtn` returns 200, but the row is still `PROCESSING`.
**Why it happens:** `WebhookDoubleCheckHandler.handleWebhookReceived` is a `@TransactionalEventListener(AFTER_COMMIT)`. The PUT response returns before the commit+listener cycle completes.
**How to avoid:** Always wrap final state assertions in `Awaitility.await().atMost(5, SECONDS).untilAsserted(...)` for webhook-driven flows.
**Warning signs:** Test passes when `Thread.sleep(200)` is added but fails without it.

### Pitfall 2: TestRestTemplate retries 503 and returns 202

**What goes wrong:** FLOWS-PAY-07 circuit-open test sees 202 instead of 503.
**Why it happens:** `TestRestTemplate` uses Apache HTTP Client which retries on 503. The second attempt finds the idempotency key RESERVED and returns 202 (PAYMENT_ALREADY_PROCESSING).
**How to avoid:** Use a `RestTemplate` with `SimpleClientHttpRequestFactory` and a no-op error handler for FLOWS-PAY-07, exactly as `PaymentOrchestratorIT.noRetryRestTemplate` does. The base class provides `serverPort` to construct the URL.
**Warning signs:** Test asserts 503 but sees 202 with `errorCode=PAYMENT_ALREADY_PROCESSING`.

### Pitfall 3: Circuit breaker default window too large to trip

**What goes wrong:** FLOWS-PAY-07 sends 100 failures but the circuit never opens.
**Why it happens:** `application.properties` in test resources sets `slidingWindowSize=100, failureRateThreshold=90` to prevent accidental CB trips across ITs in the shared context. The E2E test class does not override these.
**How to avoid:** Add `@TestPropertySource(properties = {"resilience4j.circuitbreaker.instances.mtn.slidingWindowSize=10", "resilience4j.circuitbreaker.instances.mtn.failureRateThreshold=50"})` on the test class. Alternatively, call `circuitBreakerRegistry.circuitBreaker("mtn").transitionToOpenState()` directly — this is simpler and avoids needing to send N failures.
**Warning signs:** `CircuitBreaker.getState()` is still `CLOSED` after 100 failures.

### Pitfall 4: MTN callback is PUT, not POST

**What goes wrong:** Test dispatches inbound MTN callback with `RestTemplate.postForEntity(...)` and gets 405.
**Why it happens:** `MtnCallbackController` uses `@PutMapping("/v1/callbacks/mtn")`. A POST returns 405.
**How to avoid:** Use `testRestTemplate.exchange(url, HttpMethod.PUT, ...)`.
**Warning signs:** 405 Method Not Allowed when dispatching the MTN callback.

### Pitfall 5: Orange payToken expiry path (FLOWS-PAY-04)

**What goes wrong:** The test expects the idempotency key to be absent after a payToken expiry, but finds it present.
**Why it happens:** Misunderstanding the requirement. FLOWS-PAY-04 states the idempotency key is NOT consumed on payToken expiry — the payment fails before the idempotency store step. However, if the test sends the request and the idempotency check fires first (reserve), the key IS set as RESERVED. Need to differentiate RESERVED (in-flight) vs. committed response.
**How to avoid:** payToken expiry is caught by `OrangeStatusPollerJob.pollTransaction()` — `assertPayTokenFresh()` throws `PayTokenExpiredException`. The orchestrator itself is not responsible for payToken TTL; the poller is. For the E2E test: POST /v1/payments succeeds (202, payment is PROCESSING); then the poller fires and marks the transaction FAILED due to expired payToken (TTL is configurable, default from `OrangeMoneyConfig.payTokenExpiryThresholdMinutes`). The idempotency key WAS committed (for the 202 response). The requirement says the idempotency key was "NOT consumed" in the sense that the FAILED outcome is NOT cached — the stored response still shows the original 202/PROCESSING. Verify by asserting tx_status=FAILED and that idempotency replay still returns the original PROCESSING response.
**Warning signs:** Confusion between the "idempotency key reservation" step and the "idempotency response caching" step.

### Pitfall 6: Polling fallback requires the transaction to be in PROCESSING state AND older than 2 minutes

**What goes wrong:** `MtnStatusPollerJob.executeInternal(null)` is called but no transactions are polled.
**Why it happens:** The poller queries `findByTxStatusAndProviderAndLastModifiedDateBefore(PROCESSING, MTN, cutoff)` where `cutoff = now - 2 minutes`. A freshly created PROCESSING transaction has `lastModifiedDate = now`, which is NOT before the cutoff.
**How to avoid:** After `executeFlow()` creates the transaction and it reaches PROCESSING, manually backdate `last_modified_date` via a JDBC update before calling the poller: `jdbcTemplate.update("UPDATE main.transaction SET last_modified_date = NOW() - INTERVAL '3 minutes' WHERE transaction_id = ?", transactionId)`. This forces the transaction past the 2-minute poller delay.
**Warning signs:** Poller runs but logs "stuckCount=0"; transaction stays in PROCESSING indefinitely.

### Pitfall 7: Orange MSISDN uses the `forOrange()` builder method

**What goes wrong:** FLOWS-PAY-02 uses the default MTN MSISDN (`237690000001`) and routes to MTN, not Orange.
**Why it happens:** `PaymentRequestBuilder` defaults to `MTN_MSISDN = "237690000001"`. Orange requires `forOrange()` to switch to `ORANGE_MSISDN = "237690000002"`.
**How to avoid:** Always call `.forOrange()` on `PaymentRequestBuilder` for Orange flow tests.
**Warning signs:** Orange WireMock stubs get zero calls; MTN stubs get unexpected calls.

### Pitfall 8: Orange inbound callback uses `payToken` for correlation, not `transactionId`

**What goes wrong:** `OrangeWebhookPayloadBuilder.forPayToken(transactionId)` is called with the wrong ID.
**Why it happens:** `OrangeMoneyPort.processWebhook()` looks up the transaction via `transactionRepository.findByPayToken(payload.getPayToken())`. The payToken is stored in `main.transaction.pay_token` by `persistPayToken()`. The transactionId is NOT the payToken.
**How to avoid:** After `executeFlow()` captures the 202 response with `providerRef`, use that value as the payToken: `new OrangeWebhookPayloadBuilder().forPayToken(response.getBody().providerRef())`. The `providerRef` in the `PaymentResponse` for Orange is the payToken (returned by `getMerchantInfo()`).
**Warning signs:** `OrangeMoneyPort.processWebhook()` logs "no transaction found"; webhook is silently ignored.

### Pitfall 9: Fraud-blocked path — event log has only PROVIDER_FAILED, not FRAUD_CHECK_BLOCKED

**What goes wrong:** `EventVerifier.assertEventSequence` fails because the expected event type is wrong.
**Why it happens:** `PaymentOrchestrator.applyFailed()` appends `TransactionEventType.PROVIDER_FAILED` with metadata `"FRAUD_BLOCKED"` — not a separate `FRAUD_CHECK_BLOCKED` event type. The `TransactionEventType.FRAUD_CHECK_BLOCKED` event is written by `FraudScoringService.evaluate()` only in the non-orchestrator path (not present in the orchestrator integration).
**Confirmed from source:** `PaymentOrchestrator.java:392` — `eventLogService.append(..., TransactionEventType.PROVIDER_FAILED, ..., "ORCHESTRATOR", "\"FRAUD_BLOCKED\"")`.
**How to avoid:** For FLOWS-PAY-06, assert event type `PROVIDER_FAILED` with metadata containing `"FRAUD_BLOCKED"`, not `FRAUD_CHECK_BLOCKED`. Use `EventVerifier.assertEventPresent(transactionId, "PROVIDER_FAILED")`.
**Warning signs:** Assertion on event type sequence fails; `FRAUD_CHECK_BLOCKED` is not found.

### Pitfall 10: Cross-tenant idempotency test (FLOWS-PAY-05 Round 3)

**What goes wrong:** Round 3 asserts a separate transaction was created for tenant B with the same idempotency key but finds the same transaction ID as tenant A.
**Why it happens:** Idempotency key uniqueness is scoped to `(tenant_id, idempotency_key)`. Two different tenants CAN use the same key. The isolation must be asserted explicitly.
**How to avoid:** Create a second tenant (`TenantBuilder`) with its own API key. Send the same payment request body with the same `idempotencyKey` but authenticated with tenant B's API key. Assert: different `transactionId` in the response, `TenantIsolationVerifier.assertNoDataLeaksToOtherTenant`.

---

## Code Examples

Verified patterns from production source:

### Complete WireMock stub set for MTN happy path

```java
// Source: MtnMoMoClient.java, MtnMoMoPort.java
// 1. Token (already stubbed by stubTokenEndpoints() in AbstractPayamE2ETest)
// mtnServer.stubFor(post(urlPathEqualTo("/token/")).willReturn(okJson(WireMockConfig.MTN_TOKEN_RESPONSE)));

// 2. Validate account holder — returns 200 with empty JSON body (active account)
mtnServer.stubFor(get(urlPathMatching("/v1_0/accountholder/MSISDN/.*/basicuserinfo"))
    .willReturn(okJson("{}")));

// 3. requestToPay — returns 202 Accepted (no body)
mtnServer.stubFor(post(urlPathEqualTo("/v1_0/requesttopay"))
    .willReturn(aResponse().withStatus(202)));

// 4. Double-check GET status — called by WebhookDoubleCheckHandler after PUT callback
mtnServer.stubFor(get(urlPathMatching("/v1_0/requesttopay/.*"))
    .willReturn(okJson("{\"status\":\"SUCCESSFUL\",\"financialTransactionId\":\"fin-tx-001\"}")));
```

### Complete WireMock stub set for Orange happy path

```java
// Source: OrangeMoneyClient.java, OrangeMoneyPort.java
// 1. Token (stubbed by stubTokenEndpoints())

// 2. Subscriber info — active subscriber
orangeServer.stubFor(get(urlPathEqualTo("/infos/subscriber"))
    .willReturn(okJson("{\"status\":\"ACTIF\",\"message\":\"OK\"}")));

// 3. Merchant info — returns payToken
orangeServer.stubFor(get(urlPathEqualTo("/infos/merchant"))
    .willReturn(okJson("{\"payToken\":\"tok-orange-test-001\",\"message\":\"OK\"}")));

// 4. Pay — returns SUCCESS status
orangeServer.stubFor(post(urlPathMatching("/mp/pay"))
    .willReturn(okJson("{\"status\":\"SUCCESS\",\"message\":\"OK\"}")));

// 5. Double-check GET payment status — called by WebhookDoubleCheckHandler
orangeServer.stubFor(get(urlPathMatching("/mp/paymentstatus/.*"))
    .willReturn(okJson("{\"status\":\"SUCCESSFULL\",\"message\":\"OK\"}")));
```

### POST /v1/payments with X-Api-Key header

```java
// Source: ApiKeyAuthenticationFilter.java, PaymentOrchestratorIT.headersWithKey()
HttpHeaders headers = new HttpHeaders();
headers.setContentType(MediaType.APPLICATION_JSON);
headers.set("X-Api-Key", tenant.rawApiKey());

PaymentRequest req = new PaymentRequestBuilder()
    .withDeterministicIdempotencyKey(uuidFactory)
    .build();
// Serialize to JSON via ObjectMapper or Jackson auto-convert

ResponseEntity<PaymentResponse> response = testRestTemplate.exchange(
    "http://localhost:" + serverPort + "/v1/payments",
    HttpMethod.POST,
    new HttpEntity<>(requestJson, headers),
    PaymentResponse.class);
```

### PUT /v1/callbacks/mtn — dispatch inbound MTN callback

```java
// Source: MtnCallbackController.java — @PutMapping("/v1/callbacks/mtn")
// No auth header required — public endpoint; IP whitelist disabled via @TestPropertySource
MtnCallbackPayload payload = new MtnWebhookPayloadBuilder()
    .forTransaction(transactionId)
    .asSuccessful()
    .build();

testRestTemplate.exchange(
    "http://localhost:" + serverPort + "/v1/callbacks/mtn",
    HttpMethod.PUT,
    new HttpEntity<>(payload),
    Void.class);
```

### POST /v1/callbacks/orange — dispatch inbound Orange callback

```java
// Source: OrangeCallbackController.java — @PostMapping("/v1/callbacks/orange")
// No auth required. orange.callback-hmac-secret="" in @TestPropertySource disables HMAC check.
// Orange dedup key: "webhook:orange:{payToken}:{createtime}" — different createtime allows re-dispatch.
OrangeWebhookPayload payload = new OrangeWebhookPayloadBuilder()
    .forPayToken(providerRef)   // providerRef from PaymentResponse is the payToken
    .asSuccessful()
    .build();

testRestTemplate.exchange(
    "http://localhost:" + serverPort + "/v1/callbacks/orange",
    HttpMethod.POST,
    new HttpEntity<>(payload),
    Void.class);
```

### Polling fallback — backdate lastModifiedDate and invoke poller

```java
// Source: MtnStatusPollerJob.java — cutoff = now - 2 minutes
// Backdate to make the poller see the transaction as "stuck"
jdbcTemplate.update(
    "UPDATE main.transaction SET last_modified_date = NOW() - INTERVAL '3 minutes' " +
    "WHERE transaction_id = ?", transactionId);

// Stub the GET status before triggering poller
mtnServer.stubFor(get(urlPathMatching("/v1_0/requesttopay/.*"))
    .willReturn(okJson("{\"status\":\"SUCCESSFUL\",\"financialTransactionId\":\"fin-poll\"}")));

// Direct poller invocation — bypasses Quartz scheduler
mtnStatusPollerJob.executeInternal(null);
```

### Circuit breaker OPEN — FLOWS-PAY-07 approach

```java
// Source: PaymentOrchestratorIT.circuit_breaker_trips_after_repeated_failures_returns_503
// Approach A: Trip via failures (requires @TestPropertySource window override)
// @TestPropertySource(properties = {
//     "resilience4j.circuitbreaker.instances.mtn.slidingWindowSize=10",
//     "resilience4j.circuitbreaker.instances.mtn.failureRateThreshold=50"
// })
// ...then send 10 failures to trip it.

// Approach B: Direct transition (simpler, recommended for E2E tests)
CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("mtn");
cb.transitionToOpenState();
// Flush Redis to clear RESERVED idempotency keys from prior calls
redis.getConnectionFactory().getConnection().serverCommands().flushDb();
// Re-stub token endpoint (flushed with Redis)
mtnServer.stubFor(post(urlPathEqualTo("/token/"))
    .willReturn(okJson(WireMockConfig.MTN_TOKEN_RESPONSE)));

// Now POST /v1/payments → 503 PROVIDER_UNAVAILABLE
// Use noRetryRestTemplate to avoid Apache HC retry behavior
```

### Fraud-blocked flow — APP_VELOCITY threshold = 0

```java
// Source: FraudEngineIT.velocityBlockReturns422 — uses MSISDN_VELOCITY with threshold=1
// For E2E, setting APP_VELOCITY threshold=0 blocks the FIRST request without needing
// a prior successful request to exhaust the bucket.
transactionTemplate.execute(status -> {
    jdbcTemplate.update(
        "UPDATE main.fraud_rule SET threshold = 0 WHERE signal_name = 'APP_VELOCITY'");
    return null;
});
fraudRuleCache.refreshRules();
// POST /v1/payments → 422 FRAUD_BLOCKED
// mtnServer.verify(0, postRequestedFor(urlPathEqualTo("/v1_0/requesttopay")));
```

### Full verifier chain for SUCCESS flow

```java
// After Awaitility confirms SUCCESS state:
invariant.assertAll(transactionId, tenant.tenantId(), idempotencyKey, "SUCCESS");
// assertAll = assertLedgerBalanced + assertNoDoubleCharge + assertLegalStateTransition + assertChainValid

// Additional per-flow assertions:
invariant.provider().assertMtnCallCount("/v1_0/requesttopay", 1);
invariant.events().assertEventPresent(transactionId, "PROVIDER_SUCCESS");
invariant.assertFraudEvaluatedBeforeProviderCall(transactionId);
new CacheVerifier(redis).assertIdempotencyKeyPresent(tenant.tenantId(), idempotencyKey);
new CacheVerifier(redis).assertMtnTokenCached();
```

---

## Full Flow Lifecycle Reference

### MTN Happy Path (FLOWS-PAY-01) — complete event sequence

```
PAYMENT_INITIATED   (INITIATED -> PROCESSING)   actor: MTN_ADAPTER
PROVIDER_SUCCESS    (PROCESSING -> SUCCESS)      actor: WEBHOOK_DOUBLE_CHECK
```

Event count: 2 rows in `main.payment_event_log`.

State sequence on `main.transaction.tx_status`:
`INITIATED` → `AUTH_PENDING` → `AUTHORIZED` → `PROCESSING` → `SUCCESS`

(The orchestrator applies 3 transitions — INITIATED→AUTH_PENDING, AUTH_PENDING→AUTHORIZED, AUTHORIZED→PROCESSING — before returning 202. The double-check applies PROCESSING→SUCCESS.)

### Orange Happy Path (FLOWS-PAY-02) — complete event sequence

```
PAYMENT_INITIATED   (INITIATED -> PROCESSING)   actor: ORANGE_ADAPTER
PROVIDER_SUCCESS    (PROCESSING -> SUCCESS)      actor: WEBHOOK_DOUBLE_CHECK
```

Same event count and final state as MTN. The difference is WAT timestamp handling in `OrangeWebhookPayloadBuilder` — already handled by the builder.

### Fraud-Blocked (FLOWS-PAY-06) — complete event sequence

```
PROVIDER_FAILED     (INITIATED -> FAILED)        actor: ORCHESTRATOR  metadata: "FRAUD_BLOCKED"
```

Event count: 1 row. No ledger entries. No provider calls.

### Provider Timeout/Circuit Open (FLOWS-PAY-07) — complete event sequence

```
PROVIDER_FAILED     (INITIATED -> FAILED)        actor: ORCHESTRATOR  metadata: "PROVIDER_UNAVAILABLE"
```

Event count: 1 row. No ledger entries.

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Inline test assertions in `@AfterEach` | Verifier objects (`InvariantVerifier`, etc.) | Phase 19 | Composable, consistent checks across all flow tests |
| Ad-hoc tenant creation per IT | `TenantBuilder.create(tenantService, tenantRepository)` | Phase 19 | Production key hashing; reproducible |
| Manual MSISDN string in request body | `PaymentRequestBuilder.forOrange()` / default MTN | Phase 19 | Seeded V16 prefixes always match routing table |
| Direct `Thread.sleep()` for async | `Awaitility.await().atMost(5, SECONDS)` | Phase 18+ | Deterministic, no flakiness |

**Deprecated/outdated:**
- Inline `tenantService.createTenant(...)` in `@BeforeEach`: replaced by `TenantBuilder`.
- `DbCleaner` in `utils/`: handles only security tables. Use `TestDataCleaner.wipeAll()` from the base class `@AfterEach` — already called by `baseTearDown()`.

---

## Open Questions

1. **Orange payToken expiry exact behavior (FLOWS-PAY-04)**
   - What we know: `OrangeStatusPollerJob.pollTransaction()` calls `orangeMoneyPort.assertPayTokenFresh()`. If the payToken TTL is exceeded, `PayTokenExpiredException` is thrown and the poller skips the transaction (increments `pollAttempts`, does NOT transition to FAILED immediately).
   - What's unclear: Does FLOWS-PAY-04 require the transaction to eventually be FAILED (after max attempts), or does it only require verifying the 401 behavior from the `/pay` endpoint? The requirement says "/pay returns 401 after TTL" — this implies a scenario where the poller calls `/mp/paymentstatus/{payToken}` and Orange returns 401 because the token expired.
   - Recommendation: FLOWS-PAY-04 should be structured as a `AbstractFailureFlowTest`. In `injectFault()`, set `orange.pay-token-expiry-threshold-minutes=0` (via `@TestPropertySource`) so that `assertPayTokenFresh` throws immediately. Then in `executeFlow()`, trigger `OrangeStatusPollerJob` after backdating `last_modified_date`. Verify: `tx_status=FAILED` is NOT set by the poller (it only increments attempts on expiry). This may need further investigation of the exact expiry-to-FAILED path.
   - Alternative approach: Stub the Orange `/mp/paymentstatus/*` endpoint to return 401 and confirm the poller increments `pollAttempts` and eventually transitions to FAILED on max attempts. This is cleaner but requires advancing time.

2. **`executeInternal(null)` Quartz job execution context**
   - What we know: `MtnStatusPollerJob extends QuartzJobBean`. The `executeInternal` method signature takes `JobExecutionContext context`. In `executeInternal`, `context` is not used.
   - What's unclear: Whether passing `null` for `context` causes any NPE in the actual `QuartzJobBean` superclass.
   - Recommendation: Check if `QuartzJobBean.executeInternal(null)` is safe by reviewing the existing `MtnMoMoPortIT` and `WebhookDoubleCheckIT` tests that already invoke pollers or jobs directly. If they use null, it's confirmed safe.

3. **Orange dedup key collision in FLOWS-PAY-02**
   - What we know: `OrangeCallbackController` sets Redis dedup key `"webhook:orange:{payToken}:{createtime}"` with 24h TTL. If the test tries to dispatch the callback twice, the second is suppressed.
   - What's unclear: Whether the test needs to dispatch the callback more than once (it doesn't for a standard happy path).
   - Recommendation: Use a single callback dispatch with `OrangeWebhookPayloadBuilder.withCreatetime(Instant.now())` — the default builder already sets `createtime` from `Instant.now()`, so each test run gets a fresh timestamp and avoids dedup collision with stale Redis keys from previous test contexts (though `redis.flushDb()` in `baseSetUp()` clears all keys anyway).

---

## Sources

### Primary (HIGH confidence)

- Source code read directly: `AbstractPayamE2ETest.java` — base class: `@BeforeEach`, `@AfterEach`, injected fields, `stubTokenEndpoints()`, circuit breaker reset
- Source code read directly: `AbstractPaymentFlowTest.java` — four-phase template contract
- Source code read directly: `AbstractWebhookFlowTest.java` — webhook refinement: `dispatchInboundWebhook()`, `verifyDoubleCheckTriggered()`, `verifyTransactionState()`
- Source code read directly: `AbstractFailureFlowTest.java` — failure injection pattern: `injectFault()` before `executeFlow()`
- Source code read directly: `PaymentOrchestrator.java` — complete orchestration sequence, FRAUD_BLOCKED path, circuit breaker catch, idempotency logic
- Source code read directly: `PaymentResource.java` — HTTP status mapping: 202, 503, 422, 502
- Source code read directly: `MtnCallbackController.java` — `@PutMapping("/v1/callbacks/mtn")` (PUT, not POST)
- Source code read directly: `OrangeCallbackController.java` — `@PostMapping("/v1/callbacks/orange")`, dedup key format, HMAC disabled when secret blank
- Source code read directly: `MtnMoMoPort.java` — `initiateMerchantPayment()` flow, `processCallback()` dedup key format
- Source code read directly: `OrangeMoneyPort.java` — `initiateMerchantPayment()` flow, `processWebhook()` payToken correlation, `assertPayTokenFresh()`
- Source code read directly: `MtnMoMoClient.java` — all WireMock stub URLs: `/v1_0/requesttopay`, `/v1_0/accountholder/MSISDN/.*/basicuserinfo`, `/v1_0/requesttopay/{referenceId}`
- Source code read directly: `OrangeMoneyClient.java` — all WireMock stub URLs: `/infos/subscriber`, `/infos/merchant`, `/mp/pay`, `/mp/paymentstatus/{payToken}`
- Source code read directly: `MtnStatusPollerJob.java` — 2-minute cutoff, poll condition, SUCCESS/FAILED branch, `executeInternal` pattern
- Source code read directly: `OrangeStatusPollerJob.java` — payToken expiry guard, same poll logic
- Source code read directly: `WebhookDoubleCheckHandler.java` — `@TransactionalEventListener(AFTER_COMMIT)`, provider status call, async nature
- Source code read directly: `WebhookTransitionService.java` — REQUIRES_NEW propagation, ledger post on SUCCESS, `webhookDeliveryService.enqueue()` call
- Source code read directly: `FraudScoringService.java` — scoring logic, BLOCK_THRESHOLD, `FraudDecision.block()` path
- Source code read directly: `OrchestratorError.java` — error codes: `FRAUD_BLOCKED`, `PROVIDER_UNAVAILABLE`, `PROVIDER_ERROR`, `PAYMENT_ALREADY_PROCESSING`
- Source code read directly: `PaymentResponse.java` — fields: `transactionId`, `status`, `providerRef`, `errorCode`, `errorMessage`, `feeAmount`, `feeRuleId`
- Source code read directly: all Phase 19 verifiers: `DatabaseVerifier`, `HashChainVerifier`, `EventVerifier`, `LedgerVerifier`, `ProviderCallVerifier`, `WebhookDeliveryVerifier`, `TenantIsolationVerifier`, `CacheVerifier`, `InvariantVerifier`
- Source code read directly: all Phase 19 builders: `TenantBuilder`, `PaymentRequestBuilder`, `MtnWebhookPayloadBuilder`, `OrangeWebhookPayloadBuilder`, `FraudSignalBuilder`, `DeterministicUuidFactory`
- Source code read directly: `WireMockConfig.java` — `MTN_TOKEN_RESPONSE`, `ORANGE_TOKEN_RESPONSE` constants
- Source code read directly: `E2ESecurityConfig.java` — `seedSecurityRow()` pattern
- Source code read directly: `TestDataCleaner.java` — `wipeAll()` FK-safe order, preserved seed rows
- Source code read directly: `application.properties` (test resources) — CB window=100, threshold=90; MTN token URL pattern; Orange token URL pattern
- Source code read directly: `PaymentOrchestratorIT.java` — reference implementation for all test patterns (headers, stubs, circuit breaker, no-retry RestTemplate)
- Source code read directly: `FraudEngineIT.java` — fraud threshold override + `refreshRules()` pattern

---

## Metadata

**Confidence breakdown:**

- Standard stack: HIGH — all imports and dependencies verified from source
- WireMock stub URL paths: HIGH — read directly from `MtnMoMoClient` and `OrangeMoneyClient`
- HTTP status codes: HIGH — read directly from `PaymentResource` and `OrchestratorError`
- Event sequences: HIGH — read directly from `PaymentOrchestrator`, `WebhookTransitionService`, `MtnMoMoPort`
- Async boundary (Awaitility need): HIGH — `@TransactionalEventListener(AFTER_COMMIT)` confirmed in `WebhookDoubleCheckHandler`
- Circuit breaker test pattern: HIGH — exact pattern from `PaymentOrchestratorIT`
- Orange payToken expiry exact path (FLOWS-PAY-04): MEDIUM — flow is partially clear but exact FAILED transition path needs tracing to `OrangeStatusPollerJob` behavior

**Research date:** 2026-03-27
**Valid until:** 2026-05-27 (stable domain; production flows unlikely to change)
