# PAYAM END-TO-END TESTING STANDARD

### For Spring Boot 3.5 + PostgreSQL — Multi-Tenant Mobile Money Payment Gateway

> This document extends the generic Spring Boot E2E standard with Payam-specific patterns:
> transaction state machine testing, concurrency harness for payment flows, idempotency verification,
> multi-tenant isolation proofs, hash chain integrity, fraud engine testing, and more.

---

# 1️⃣ PURPOSE

This document defines the **mandatory standard** for End-to-End (E2E) testing of the Payam payment gateway.

Our E2E tests must guarantee:

* Payment flow correctness (MTN MoMo + Orange Money)
* Transaction state machine integrity (no illegal transitions)
* Idempotency — no double charges under network retry
* Multi-tenant isolation — no cross-tenant data leakage
* Webhook pipeline correctness (inbound verification + outbound delivery)
* Fraud engine evaluation before every provider call
* Hash chain integrity — tamper-evident event log
* Ledger consistency — double-entry balances after every flow
* Concurrency safety — no race conditions under simultaneous webhook + polling
* Reconciliation accuracy — ledger matches provider reports

E2E tests are **flow-driven**, not controller-driven.

---

# 2️⃣ TESTING PHILOSOPHY

We do NOT test only:

* HTTP status codes ❌
* Service method return values ❌
* Provider mock responses alone ❌

We test:

* Final database state ✔
* Transaction state machine transitions ✔
* Hash chain integrity ✔
* Invariants (ledger balance, tenant isolation, idempotency) ✔
* Transaction boundaries ✔
* Spring Modulith event publication (outbox) ✔
* Redis idempotency cache + velocity counters ✔
* Provider adapter behavior (MTN PUT / Orange POST) ✔
* Concurrency behavior under simultaneous requests ✔
* Failure scenarios (provider timeout, mid-transaction exception) ✔

If a test passes, the system must be **provably consistent**.

---

# 3️⃣ E2E FLOW BLUEPRINT TEMPLATE (MANDATORY PER FLOW)

Each business flow must have one blueprint.

---

## 3.1 Flow Metadata

```yaml
Flow Name:
Flow ID:
Feature Area: (Payment / Webhook / Fraud / Reconciliation / Admin / Auth)
Priority: Critical | High | Medium | Low
Risk Level: High | Medium | Low
Provider: MTN_MOMO | ORANGE_MONEY | BOTH | N/A
Related Requirement IDs:
Author:
Date:
```

---

## 3.2 Flow Description

```yaml
Actors:
  - (e.g., Tenant API Client, MTN MoMo Provider, Payam Webhook Scheduler)
Trigger:
Business Goal:
Main Success Path:
Alternative Paths:
Failure Paths:
Provider Async Model: (sync response + async webhook | polling fallback)
```

---

## 3.3 Preconditions

```yaml
System State:
Required Database Records:
  - tenant with active API key
  - idempotency cache: empty for this key
  - velocity counters: within threshold
External System State:
  - MTN/Orange WireMock stub configured
Cache State:
  - Redis idempotency key: absent
  - Redis velocity counter: initial value
Feature Flags:
Time Assumptions: (WAT UTC+1 — relevant for Orange timestamps and reconciliation)
Environment Assumptions:
```

Preconditions must be deterministic and reproducible.

---

## 3.4 Supporting Components Involved

```yaml
Primary Components:
  - REST Controller (e.g., PaymentController, WebhookController)
  - PaymentOrchestrator
  - ProviderGateway (MtnMoMoGateway | OrangeMoMoGateway)
  - Repository (PaymentRepository, PaymentEventLogRepository)

Secondary Components:
  - FraudScoringService
  - IdempotencyStore (Redis)
  - PaymentEventLog (append-only, hash-chained)
  - InternalLedger
  - Spring Modulith ApplicationEventPublisher
  - Event Publication Registry (outbox)
  - WebhookDeliveryScheduler
  - Resilience4j CircuitBreaker
  - Quartz Scheduler (polling fallback, reconciliation)
  - ApiKeyAuthenticationFilter
```

All supporting components must be verified after execution.

---

## 3.5 Execution Steps

```yaml
Step 1:
  Action: POST /v1/payment/initiate
  Input:
    tenantId: (from API key header)
    msisdn: "6XXXXXXXX"
    amount: 5000
    currency: XAF
    idempotencyKey: "unique-key-001"
  Expected Immediate Response: 200 OK, transactionId returned, status=INITIATED

Step 2:
  Action: Simulate provider webhook (MTN PUT / Orange POST)
  Input: provider callback payload
  Expected: double-check re-query fires, transaction transitions to SUCCESS
```

---

## 3.6 Final State Verification Checklist (MANDATORY)

### A. Database Verification

```yaml
payments table:
  - status = SUCCESS (or expected terminal state)
  - providerId = (set by provider response)
  - tenantId = correct tenant only
  - amount/currency unchanged from initiation

payment_events (append-only):
  - 3–4 event rows for this transactionId
  - eventType sequence matches legal state machine path
  - each row: hashValue = SHA-256(previousHash + eventData)
  - No events for other tenants (tenant isolation)

idempotency_keys (Redis):
  - entry exists with transactionId
  - TTL not expired

ledger_entries:
  - debit row: customerId, amount XAF
  - credit row: providerClearing, amount XAF
  - debit.amount = credit.amount (double-entry balance)

No orphan records
No duplicate payment rows for same idempotencyKey
```

---

### B. Transaction Integrity

```yaml
No partial updates (INIT row committed before provider HTTP call)
Rollback verified on exception before provider call
State never stuck at INITIATED without terminal transition
Idempotency key reserved before provider call (NX atomic set)
```

---

### C. Invariant Verification

Payam-specific invariants — see Section 16 for full catalog.

```yaml
Hash chain: payment_event[n].hashValue == SHA256(event[n-1].hashValue + event[n].data)
Ledger balance: SUM(debit) == SUM(credit) for this transactionId
Tenant isolation: zero rows in any table with wrong tenantId
Legal state transition: no INITIATED→SUCCESS without passing through PROCESSING
No double charge: idempotency key maps to exactly one transactionId
```

---

### D. Event / Outbox Verification

```yaml
Spring Modulith event publication registry:
  - PaymentInitiatedEvent: 1 row
  - TransactionStateChangedEvent: 1 row per state transition
  - WebhookReceivedEvent: 1 row on inbound webhook
  - FraudEvaluatedEvent: 1 row (if fraud score computed)

Outbound webhook delivery:
  - WebhookDelivery row exists for tenant
  - HMAC-SHA256 signature header present
  - Payload matches final transaction state
  - Delivery status = DELIVERED | PENDING_RETRY
```

---

### E. Cache Verification

```yaml
Redis idempotency key: present, maps to correct transactionId
Redis velocity counters: incremented by 1 for this userId/IP
Redis MTN OAuth2 token cache: unchanged (not evicted on successful flow)
No stale idempotency entries from prior test runs (test cleanup required)
```

---

### F. External Integration Verification

```yaml
MTN MoMo:
  - /collection/v1_0/requesttopay called exactly once
  - X-Reference-Id header matches transactionId
  - Callback URL = Payam's internal endpoint (NOT tenant-supplied)

Orange Money:
  - /mp/init called, followed by /mp/pay
  - notifUrl = Payam's internal endpoint
  - payToken extracted and used in /pay call

WireMock verify:
  - No unexpected calls to provider stubs
  - Retry count matches expected Resilience4j config
```

---

## 3.7 Concurrency Scenario Block (If Applicable)

```yaml
Scenario: Simultaneous webhook + polling for same transaction
Threads:
  - Thread A: inbound provider webhook (MTN PUT)
  - Thread B: Quartz polling job queries MTN status API
Collision Type: concurrent PENDING→SUCCESS write on same payment row
Execution Pattern: both threads read PROCESSING state, both attempt SUCCESS transition
Expected Final State:
  - Exactly one SUCCESS payment row
  - Exactly one hash-chained event for SUCCESS
  - payment_events count = expected (no duplicate SUCCESS event)
  - Ledger entries = 2 (one debit, one credit), not 4
Invariant Checks:
  - Hash chain integrity holds
  - Ledger balanced
  - No duplicate webhook delivery to tenant
```

---

## 3.8 Failure Injection Block

Payam-specific failure scenarios:

```yaml
P1.1 — Provider webhook arrives before INIT row commits:
  Inject: artificial delay between INIT insert and commit
  Verify: webhook handler retries with exponential backoff until row visible

P1.4 — MTN callback arrives as PUT (not POST):
  Inject: send webhook to /v1/webhooks/mtn with PUT method
  Verify: accepted and processed (not 405)

Provider timeout:
  Inject: WireMock delay > Resilience4j timeout
  Verify: circuit breaker OPEN, transaction → FAILED, no ledger entry

Orange payToken expiry:
  Inject: WireMock returns 401 on /mp/pay after token TTL
  Verify: correct error code, no partial state, idempotency key NOT consumed

DB failure mid-orchestration:
  Inject: exception between INIT row commit and provider HTTP call
  Verify: INIT row exists (already committed), no provider call made, operator can retry

Event publishing failure:
  Inject: Spring Modulith event publisher throws after payment commit
  Verify: payment state committed (not rolled back), outbox retry fires
```

---

## 3.9 Idempotency Block (MANDATORY for all payment endpoints)

```yaml
Round 1: POST /v1/payment/initiate with idempotencyKey=K
  Verify: new transactionId T1 created, Redis key set NX

Round 2: POST /v1/payment/initiate with same idempotencyKey=K (before TTL expiry)
  Verify:
    - HTTP 200 with same response body as Round 1
    - No new payment row (still only T1)
    - Provider called exactly once (not twice)
    - No new ledger entries

Round 3: POST with idempotencyKey=K from different tenant (same key string)
  Verify:
    - New transactionId T2 created (tenantId scopes the key)
    - T1 and T2 are distinct rows with different tenantId
    - No cross-tenant data in response
```

---

# 4️⃣ REUSABLE E2E FRAMEWORK STRUCTURE

All payment flows must use this structure.

```
src/test/java/
 └── e2e/
      ├── base/
      │     ├── AbstractPayamE2ETest         ← bootstraps containers, verifiers, WireMock
      │     ├── AbstractPaymentFlowTest      ← enforces setup/execute/verify structure
      │     ├── AbstractWebhookFlowTest      ← adds inboundWebhook() step
      │     └── AbstractFailureFlowTest      ← failure injection hooks
      │
      ├── verifiers/
      │     ├── DatabaseVerifier             ← payment/ledger/event record assertions
      │     ├── HashChainVerifier            ← verifies SHA-256 chain integrity
      │     ├── InvariantVerifier            ← Payam-specific invariants (see §16)
      │     ├── EventVerifier                ← Spring Modulith outbox assertions
      │     ├── CacheVerifier                ← Redis idempotency + velocity checks
      │     ├── ProviderCallVerifier         ← WireMock call count + payload checks
      │     ├── WebhookDeliveryVerifier      ← outbound webhook + HMAC assertions
      │     ├── LedgerVerifier               ← double-entry balance assertions
      │     ├── TenantIsolationVerifier      ← cross-tenant data leakage checks
      │     └── QueryCountVerifier           ← N+1 detection
      │
      ├── builders/                          ← see §19 Test Data Builder Pattern
      │     ├── TenantBuilder
      │     ├── ApiKeyBuilder
      │     ├── PaymentRequestBuilder
      │     ├── WebhookPayloadBuilder
      │     │     ├── MtnWebhookPayloadBuilder
      │     │     └── OrangeWebhookPayloadBuilder
      │     ├── FraudSignalBuilder
      │     └── ReconciliationReportBuilder
      │
      ├── support/
      │     ├── PayamConcurrencyHarness      ← see §18
      │     ├── StateMachineDriver           ← see §17 / §21
      │     ├── TransactionBoundaryProbe     ← see §20
      │     ├── MutationTestRunner           ← see §22
      │     └── TestDataCleaner
      │
      ├── config/
      │     ├── PostgresContainerConfig
      │     ├── RedisContainerConfig
      │     ├── WireMockConfig               ← MTN + Orange provider stubs
      │     ├── TestClockConfig              ← fixed clock, WAT-aware
      │     └── E2ESecurityConfig            ← test API key injection
      │
      └── flows/
            ├── payment/
            │     ├── MtnPaymentInitiationE2ETest
            │     ├── OrangePaymentInitiationE2ETest
            │     ├── PaymentIdempotencyE2ETest
            │     └── PaymentConcurrencyE2ETest
            ├── webhook/
            │     ├── MtnWebhookDoubleCheckE2ETest
            │     ├── OrangeWebhookDoubleCheckE2ETest
            │     └── OutboundWebhookDeliveryE2ETest
            ├── fraud/
            │     └── FraudVelocityBlockE2ETest
            ├── reconciliation/
            │     └── DailyReconciliationE2ETest
            └── admin/
                  └── TransactionInvestigationE2ETest
```

---

# 5️⃣ BASE TEST CLASSES (STANDARD)

---

## 5.1 AbstractPayamE2ETest

Responsible for:

* Bootstrapping Spring Boot with Testcontainers (PostgreSQL + Redis)
* Starting WireMock stubs for MTN MoMo and Orange Money
* Injecting all Payam verifiers
* Cleaning database + Redis before each test
* Providing a fixed test clock (WAT-aware for Orange reconciliation)

```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
@ActiveProfiles("e2e")
public abstract class AbstractPayamE2ETest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static WireMockServer mtnWireMock;
    static WireMockServer orangeWireMock;

    @Autowired protected TestRestTemplate restTemplate;
    @Autowired protected JdbcTemplate jdbcTemplate;
    @Autowired protected RedisTemplate<String, String> redisTemplate;

    @Autowired protected DatabaseVerifier databaseVerifier;
    @Autowired protected HashChainVerifier hashChainVerifier;
    @Autowired protected InvariantVerifier invariantVerifier;
    @Autowired protected EventVerifier eventVerifier;
    @Autowired protected LedgerVerifier ledgerVerifier;
    @Autowired protected ProviderCallVerifier providerCallVerifier;
    @Autowired protected TenantIsolationVerifier tenantIsolationVerifier;
    @Autowired protected WebhookDeliveryVerifier webhookDeliveryVerifier;

    @BeforeEach
    void cleanState() {
        TestDataCleaner.cleanAll(jdbcTemplate, redisTemplate);
    }
}
```

---

## 5.2 AbstractPaymentFlowTest

Enforces disciplined structure for all payment flows:

```java
public abstract class AbstractPaymentFlowTest extends AbstractPayamE2ETest {

    protected abstract void setupPreconditions();     // tenant, API key, WireMock stubs
    protected abstract void executeFlow();            // HTTP call(s)
    protected abstract void simulateProviderCallback(); // webhook or polling
    protected abstract void verifyFinalState();       // all verifiers

    @Test
    void runPaymentFlowE2E() {
        setupPreconditions();
        executeFlow();
        simulateProviderCallback();
        verifyFinalState();
    }
}
```

---

## 5.3 AbstractWebhookFlowTest

Adds inbound webhook dispatch step and double-check verification:

```java
public abstract class AbstractWebhookFlowTest extends AbstractPayamE2ETest {

    protected abstract void setupPreconditions();
    protected abstract void deliverProviderWebhook();       // simulate MTN PUT or Orange POST
    protected abstract void verifyDoubleCheckFired();       // provider status re-queried
    protected abstract void verifyTransactionStateChanged();
    protected abstract void verifyOutboundWebhookDelivered();

    @Test
    void runWebhookFlowE2E() {
        setupPreconditions();
        deliverProviderWebhook();
        awaitEventProcessing();
        verifyDoubleCheckFired();
        verifyTransactionStateChanged();
        verifyOutboundWebhookDelivered();
    }
}
```

---

## 5.4 AbstractFailureFlowTest

Used for failure injection tests. Provides hook points for fault injection:

```java
public abstract class AbstractFailureFlowTest extends AbstractPayamE2ETest {

    protected abstract void setupPreconditions();
    protected abstract void injectFault();                  // WireMock error / @SpyBean throw
    protected abstract void executeFlow();
    protected abstract void verifyConsistencyAfterFailure();

    @Test
    void runFailureE2E() {
        setupPreconditions();
        injectFault();
        executeFlow();
        verifyConsistencyAfterFailure();
    }
}
```

---

# 6️⃣ VERIFIER COMPONENTS (MANDATORY USAGE)

All state validation must go through verifiers.

Never embed raw SQL assertions inside tests.

---

## 6.1 DatabaseVerifier

Payam-specific responsibilities:

* Payment row field validation (status, tenantId, providerId, amount, currency)
* `payment_events` row count for a transactionId
* Orphan detection (events without parent payment)
* No duplicate payment rows for same idempotencyKey within a tenant

---

## 6.2 HashChainVerifier

Validates the SHA-256 hash chain on `payment_events`:

```java
// For every event in order, verify:
// event[n].hashValue == SHA256(event[n-1].hashValue + event[n].eventData)
void assertHashChainIntact(UUID transactionId);

// Verify no events inserted out of sequence
void assertNoInsertionBetweenEvents(UUID transactionId);

// Verify chain is unbroken from genesis event
void assertGenesisEventHasValidSeed(UUID transactionId);
```

Every payment flow test MUST call `hashChainVerifier.assertHashChainIntact(transactionId)`.

---

## 6.3 InvariantVerifier

Encodes Payam's domain invariants (see full catalog in Section 16):

```java
void assertLedgerBalanced(UUID transactionId);
void assertNoDoubleCharge(String tenantId, String idempotencyKey);
void assertTenantIsolation(UUID transactionId, String expectedTenantId);
void assertLegalStateTransition(UUID transactionId, TransactionStatus expectedFinalState);
void assertNoNegativeBalance(UUID tenantId);
void assertWebhookDoubleCheckFired(UUID transactionId);
void assertFraudEvaluatedBeforeProviderCall(UUID transactionId);
```

Every flow test MUST call invariant checks.

---

## 6.4 EventVerifier

Validates Spring Modulith event publication registry:

```java
void assertEventPublished(String eventType, UUID transactionId);
void assertEventPublishedExactlyOnce(String eventType, UUID transactionId);
void assertEventSequence(UUID transactionId, String... expectedEventTypes);
void assertNoEventPublished(String eventType, UUID transactionId);
```

---

## 6.5 LedgerVerifier

Validates double-entry ledger integrity:

```java
void assertDebitCreditBalance(UUID transactionId);           // debit.amount == credit.amount
void assertLedgerEntryCount(UUID transactionId, int expected); // exactly 2 entries
void assertLedgerEntryFields(UUID transactionId, LedgerSide side, BigDecimal amount, String currency);
void assertNoLedgerEntry(UUID transactionId);                // for failed/rolled-back flows
```

---

## 6.6 ProviderCallVerifier

Validates WireMock interactions:

```java
void assertMtnRequestToPayCalledOnce(UUID transactionId);
void assertOrangeInitCalledOnce();
void assertOrangePayCalledOnce(String payToken);
void assertProviderStatusPolledOnce(UUID transactionId);     // double-check
void assertCallbackUrlIsPayamOwned(String callbackUrl);      // not tenant-supplied (SSRF guard)
void assertNoProviderCallMade();                              // for fraud-blocked flows
```

---

## 6.7 WebhookDeliveryVerifier

Validates outbound webhook delivery to tenant:

```java
void assertWebhookDelivered(UUID transactionId, String tenantCallbackUrl);
void assertHmacSignaturePresent(UUID deliveryId, String expectedSecretKey);
void assertWebhookPayloadMatchesTransaction(UUID deliveryId, UUID transactionId);
void assertWebhookRetryScheduled(UUID deliveryId, int expectedAttempt);
void assertWebhookDeliveryStatus(UUID deliveryId, DeliveryStatus expected);
```

---

## 6.8 TenantIsolationVerifier

Validates multi-tenant data boundaries:

```java
void assertNoRowsForOtherTenant(String tableName, UUID transactionId, String ownerTenantId);
void assertIdempotencyKeyNotSharedAcrossTenants(String key, String tenantA, String tenantB);
void assertApiKeyCannotAccessOtherTenantData(String apiKey, String otherTenantId);
```

---

## 6.9 CacheVerifier

Validates Redis state:

```java
void assertIdempotencyKeyExists(String tenantId, String idempotencyKey);
void assertIdempotencyKeyAbsent(String tenantId, String idempotencyKey);
void assertVelocityCounterValue(String counterKey, int expectedCount);
void assertMtnTokenCached(String tenantId);
void assertNoCrossContextCachePollution();
```

---

## 6.10 QueryCountVerifier (Recommended)

Prevents N+1 regressions in payment listing and admin dashboard queries.

---

# 7️⃣ CONCURRENCY TEST EXECUTION STANDARD

High-risk flows MUST include concurrency tests. See Section 18 for the full harness.

Mandatory for all Payam flows involving:

* Payment initiation (idempotency key race)
* Webhook processing (webhook + polling race for same transaction)
* Velocity counter increments (fraud engine)
* API key rotation (old key + new key grace period)
* Ledger entry writes

Thread counts:
* Idempotency race: 20–50 threads, same idempotency key
* Webhook+polling race: 2 threads, coordinated timing
* Velocity counters: 100 threads, expected counter value asserted

---

# 8️⃣ TEST DATA STRATEGY

See Section 19 for the full Test Data Builder Pattern.

All test data must:

* Be created via the builder pattern (TenantBuilder, PaymentRequestBuilder, etc.)
* Be deterministic — fixed UUIDs seeded per test
* Not rely on existing DB state
* Not depend on test order
* Include WAT-aware timestamps for Orange Money flows

Use:

* Testcontainers PostgreSQL (real schema via Flyway)
* Testcontainers Redis (real idempotency + velocity behavior)
* Fixed Clock (WAT UTC+1 for Orange reconciliation accuracy)
* WireMock for MTN MoMo and Orange Money provider responses
* Clean database + Redis before each test

---

# 9️⃣ FAILURE TESTING STANDARD

For every critical payment flow, tests must include at minimum:

* Provider HTTP timeout (Resilience4j circuit breaker fires)
* Exception after INIT row commit but before provider call
* Orange payToken expiry between /init and /pay
* Webhook arrives before INIT row committed (P1.1 — retry until visible)
* Event publishing failure after payment commit (outbox retry)
* MTN callback via PUT (not POST) — correct routing
* Duplicate webhook delivery (replay protection)

System must remain consistent after every failure.

---

# 🔟 IDEMPOTENCY STANDARD

Every Payam write endpoint is externally callable.

Every write endpoint MUST:

* Accept `X-Idempotency-Key` header
* Scope the key to `(tenantId, idempotencyKey)` — NOT key alone
* Have an E2E idempotency test covering all three rounds (see §3.9)
* Verify provider is called exactly once across all retry attempts
* Verify identical response returned on second call

---

# 1️⃣1️⃣ CODE STRUCTURE RULES

Flow tests must look like:

```java
class MtnPaymentSuccessFlowE2ETest extends AbstractPaymentFlowTest {

    private UUID transactionId;
    private String tenantId;

    @Override
    protected void setupPreconditions() {
        tenantId = new TenantBuilder().active().create(jdbcTemplate);
        String apiKey = new ApiKeyBuilder().forTenant(tenantId).create(jdbcTemplate);
        mtnWireMock.stubFor(requestToPaySuccess());
        mtnWireMock.stubFor(getTransactionStatusSuccess());
        restTemplate.getRestTemplate().setInterceptors(List.of(apiKeyHeader(apiKey)));
    }

    @Override
    protected void executeFlow() {
        var request = new PaymentRequestBuilder()
                .mtn().msisdn("677000001").amount(5000).currency("XAF")
                .idempotencyKey("idem-001").build();
        var response = restTemplate.postForEntity("/v1/payment/initiate", request, PaymentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        transactionId = response.getBody().transactionId();
    }

    @Override
    protected void simulateProviderCallback() {
        var webhook = new MtnWebhookPayloadBuilder().success(transactionId).build();
        restTemplate.exchange("/v1/webhooks/mtn", HttpMethod.PUT, new HttpEntity<>(webhook), Void.class);
        awaitEventProcessing();
    }

    @Override
    protected void verifyFinalState() {
        databaseVerifier.assertPaymentStatus(transactionId, TransactionStatus.SUCCESS);
        hashChainVerifier.assertHashChainIntact(transactionId);
        ledgerVerifier.assertDebitCreditBalance(transactionId);
        invariantVerifier.assertLedgerBalanced(transactionId);
        invariantVerifier.assertNoDoubleCharge(tenantId, "idem-001");
        invariantVerifier.assertTenantIsolation(transactionId, tenantId);
        invariantVerifier.assertWebhookDoubleCheckFired(transactionId);
        invariantVerifier.assertFraudEvaluatedBeforeProviderCall(transactionId);
        eventVerifier.assertEventPublishedExactlyOnce("TransactionStateChangedEvent", transactionId);
        webhookDeliveryVerifier.assertWebhookDelivered(transactionId, tenantCallbackUrl(tenantId));
        webhookDeliveryVerifier.assertHmacSignaturePresent(/* deliveryId */ null, tenantSecret(tenantId));
        providerCallVerifier.assertMtnRequestToPayCalledOnce(transactionId);
        providerCallVerifier.assertProviderStatusPolledOnce(transactionId);
    }
}
```

Never mix setup, execution, and verification logic.

---

# 1️⃣2️⃣ COVERAGE REQUIREMENTS

Minimum standards for Payam:

| Area                             | Requirement                                         |
|----------------------------------|-----------------------------------------------------|
| Payment initiation (MTN)         | Happy path + idempotency + timeout + fraud-blocked  |
| Payment initiation (Orange)      | Happy path + payToken expiry + webhook race         |
| Webhook pipeline (inbound)       | MTN PUT + Orange POST + double-check + replay guard |
| Webhook pipeline (outbound)      | Delivered + HMAC + retry on 5xx                     |
| Hash chain integrity             | Verified on every payment flow test                 |
| Ledger balance                   | Verified on every payment flow test                 |
| Tenant isolation                 | Verified on every payment flow test                 |
| Idempotency                      | All 3 rounds tested for every write endpoint        |
| Concurrency (webhook+polling)    | Mandatory concurrency test                          |
| Fraud evaluation                 | Blocked path + allowed path + velocity limit        |
| Reconciliation                   | Matched + missing + mismatched cases                |
| Circuit breaker                  | Provider down → OPEN → fallback error propagated    |

---

# 1️⃣3️⃣ DEFINITION OF DONE (FLOW LEVEL)

A payment flow is not complete until:

* Blueprint documented
* E2E test implemented
* Final DB state verified
* Hash chain integrity verified
* Ledger balance verified
* Tenant isolation verified
* Invariants asserted
* Idempotency tested (all 3 rounds)
* Concurrency tested (if applicable)
* Failure scenario tested
* Provider call count verified

---

# 1️⃣4️⃣ TEAM ENFORCEMENT RULES

Code review checklist:

* ❓ Does test verify final DB state including `payment_events`?
* ❓ Is hash chain integrity asserted?
* ❓ Is ledger balance asserted?
* ❓ Is tenant isolation asserted?
* ❓ Are invariants asserted via InvariantVerifier?
* ❓ Is provider call count verified (WireMock)?
* ❓ Is webhook double-check verified?
* ❓ Is idempotency tested (all 3 rounds)?
* ❓ Is concurrency covered where applicable?
* ❓ Is failure scenario included?
* ❓ Is test deterministic (no clock.now(), fixed UUIDs, no shared state)?

If any answer is NO → PR cannot be approved.

---

# 1️⃣5️⃣ WHAT THIS STANDARD GUARANTEES

If followed strictly:

* No double charges (idempotency enforced end-to-end)
* No tampered transaction logs (hash chain verified)
* No ledger imbalance (double-entry verified)
* No cross-tenant data leakage (tenant isolation verified)
* No unverified webhooks changing state (double-check verified)
* No fraud bypass (fraud evaluated before every provider call)
* No duplicate events (outbox verified for single publication)
* No silent circuit breaker failures (provider call count verified)
* No Orange timestamp drift (WAT clock used in all reconciliation tests)

This elevates the system from:

> "Payments work in our sandbox"

to

> "Payments are provably correct, fraud-resistant, and tamper-evident under production load"

---

# 1️⃣6️⃣ PAYAM DOMAIN INVARIANTS CATALOG

These are the non-negotiable system invariants that every flow test MUST assert via `InvariantVerifier`.

---

## INV-01: Hash Chain Integrity

```
For every transactionId T:
  ∀ event[n] in payment_events where transactionId = T:
    event[n].hashValue == SHA256(event[n-1].hashValue + event[n].eventData)

  event[0].hashValue == SHA256("GENESIS" + event[0].eventData)
```

Violation means tampered or corrupted event log.

---

## INV-02: Ledger Double-Entry Balance

```
For every transactionId T in terminal state (SUCCESS):
  SUM(amount WHERE transactionId = T AND side = DEBIT) ==
  SUM(amount WHERE transactionId = T AND side = CREDIT)

  COUNT(ledger_entries WHERE transactionId = T) == 2
```

Violation means money was created or destroyed in the ledger.

---

## INV-03: Idempotency — No Double Charge

```
For every (tenantId, idempotencyKey) pair:
  COUNT(payments WHERE tenantId = T AND idempotencyKey = K) <= 1

  COUNT(provider_calls WHERE transactionId maps to (T, K)) == 1
```

Violation means a customer was charged twice.

---

## INV-04: Tenant Isolation

```
For every table T with tenantId column:
  For every row R: no query via tenant A's API key returns R where R.tenantId != A

  For idempotency_keys in Redis:
    key namespace includes tenantId prefix
```

Violation means cross-tenant data leakage (security incident).

---

## INV-05: Legal State Machine Transitions

```
Legal transition graph:
  INITIATED → AUTH_PENDING → AUTHORIZED → PROCESSING → SUCCESS
  INITIATED → PROCESSING → FAILED
  INITIATED → FAILED
  PROCESSING → REVERSED

Illegal:
  INITIATED → SUCCESS (skipping PROCESSING)
  SUCCESS → any state
  FAILED → any state (terminal)
  Any backward transition
```

Violation means corrupted payment lifecycle.

---

## INV-06: Webhook Double-Check Enforced

```
For every inbound provider webhook W:
  provider_status_requery_count(W.transactionId) >= 1

  transaction state changes ONLY AFTER requery confirms outcome
  (never on webhook payload alone)
```

Violation means forged webhook could change transaction state.

---

## INV-07: Fraud Evaluated Before Provider Call

```
For every payment initiation:
  fraud_evaluation_timestamp < provider_call_timestamp

  IF fraud_score >= blocking_threshold:
    provider_call_count == 0
    transaction.status == FAILED
    failure_reason == "FRAUD_BLOCKED"
```

Violation means high-risk transactions reach the provider.

---

## INV-08: Callback URL Is Payam-Owned

```
For every outbound provider HTTP call:
  request.callbackUrl MATCHES internal Payam webhook endpoint pattern
  request.callbackUrl NOT IN tenant-supplied URLs
```

Violation means SSRF vulnerability (P3.1 from PITFALLS.md).

---

## INV-09: INIT Row Committed Before Provider Call

```
For every payment initiation:
  payment.createdAt < provider_http_call_timestamp

  (verifiable via DB row existence check before WireMock receives call)
```

Violation means webhook can arrive before the row exists (P1.1 from PITFALLS.md).

---

## INV-10: Orange Timestamps in WAT (UTC+1)

```
For every Orange Money reconciliation entry:
  parsedTimestamp == parse(orangeCreatetime, WAT offset +01:00)
  NOT parse(orangeCreatetime, UTC)
```

Violation means 1-hour reconciliation drift every day (P5.1 from PITFALLS.md).

---

# 1️⃣7️⃣ TRANSACTION STATE MACHINE TESTING

All legal and illegal transitions of `INITIATED → AUTH_PENDING → AUTHORIZED → PROCESSING → SUCCESS | FAILED | REVERSED` must be explicitly tested.

---

## 17.1 State Machine Test Matrix

Each cell defines whether the transition is expected to succeed (✓) or fail (✗):

| From \ To     | AUTH_PENDING | AUTHORIZED | PROCESSING | SUCCESS | FAILED | REVERSED |
|---------------|:---:|:---:|:---:|:---:|:---:|:---:|
| INITIATED     | ✓   | ✗   | ✓   | ✗   | ✓   | ✗   |
| AUTH_PENDING  | ✗   | ✓   | ✗   | ✗   | ✓   | ✗   |
| AUTHORIZED    | ✗   | ✗   | ✓   | ✗   | ✓   | ✗   |
| PROCESSING    | ✗   | ✗   | ✗   | ✓   | ✓   | ✓   |
| SUCCESS       | ✗   | ✗   | ✗   | ✗   | ✗   | ✗   |
| FAILED        | ✗   | ✗   | ✗   | ✗   | ✗   | ✗   |
| REVERSED      | ✗   | ✗   | ✗   | ✗   | ✗   | ✗   |

✗ transitions must throw `IllegalStateTransitionException` and leave the database unchanged.

---

## 17.2 StateMachineDriver

`StateMachineDriver` drives a transaction through a sequence of transitions and asserts state at each step:

```java
StateMachineDriver driver = new StateMachineDriver(restTemplate, jdbcTemplate, hashChainVerifier);

// Positive path
driver.initiate(transactionId)
      .assertState(INITIATED)
      .transition(PROCESSING, providerWebhook)
      .assertState(PROCESSING)
      .assertHashChainGrew(1)
      .transition(SUCCESS, providerConfirmation)
      .assertState(SUCCESS)
      .assertHashChainGrew(1)
      .assertTerminal();

// Negative path — illegal transition
driver.initiate(transactionId)
      .assertState(INITIATED)
      .attemptIllegalTransition(SUCCESS)
      .assertStateUnchanged(INITIATED)
      .assertHashChainUnchanged()
      .assertNoLedgerEntry();
```

---

## 17.3 State Machine E2E Test Structure

```java
@ParameterizedTest
@MethodSource("illegalTransitions")
void illegalTransitionIsRejected(TransactionStatus from, TransactionStatus to) {
    UUID txId = driver.createInState(from);
    assertThatThrownBy(() -> driver.transitionTo(txId, to))
        .isInstanceOf(IllegalStateTransitionException.class);
    databaseVerifier.assertPaymentStatus(txId, from);   // unchanged
    hashChainVerifier.assertHashChainIntact(txId);       // no new event appended
}

static Stream<Arguments> illegalTransitions() {
    return Stream.of(
        Arguments.of(INITIATED, SUCCESS),
        Arguments.of(SUCCESS, FAILED),
        Arguments.of(FAILED, PROCESSING),
        Arguments.of(SUCCESS, SUCCESS)
        // ... full matrix
    );
}
```

---

# 1️⃣8️⃣ CONCURRENCY TESTING HARNESS

Payam has several high-risk concurrent access patterns unique to mobile money gateways. Each requires a dedicated concurrency harness scenario.

---

## 18.1 PayamConcurrencyHarness

```java
public class PayamConcurrencyHarness {

    /**
     * Launches N threads simultaneously. Uses CyclicBarrier to coordinate
     * all threads to start at exactly the same moment.
     */
    public ConcurrencyResult execute(int threads, Callable<HttpStatus> action) { ... }

    public void assertExactlyOneSuccess(ConcurrencyResult result) { ... }
    public void assertAllSameResponse(ConcurrencyResult result, HttpStatus expected) { ... }
}
```

---

## 18.2 Scenario: Idempotency Key Race (Duplicate Submit)

Simulates a client submitting the same payment 20 times simultaneously (network retry storm).

```yaml
Scenario: 20 threads, same (tenantId, idempotencyKey), same payload
Expected:
  - 1 payment row created
  - 1 provider call made
  - 20 HTTP responses all return same transactionId
  - 0 duplicate ledger entries
  - Hash chain has exactly N events (not 20×N)
Thread count: 20
Barrier: CyclicBarrier(20) — all threads release together
```

---

## 18.3 Scenario: Webhook + Polling Race

Simulates the real scenario where the provider webhook arrives at the same millisecond as the Quartz polling job checks the provider status.

```yaml
Scenario: 2 threads
  Thread A: delivers provider webhook (MTN PUT)
  Thread B: polls provider status API (Quartz)
  Both threads: attempt PROCESSING→SUCCESS transition on same row

Expected:
  - Exactly 1 SUCCESS payment row
  - Exactly 1 SUCCESS event in payment_events
  - Exactly 2 ledger entries (not 4)
  - Hash chain intact (no duplicate SUCCESS event)
  - OutboundWebhookDelivery = 1 (not 2)

Database lock strategy: PESSIMISTIC_WRITE on payment row
```

---

## 18.4 Scenario: Velocity Counter Flood

Simulates 100 concurrent requests from the same IP (bot attack attempt).

```yaml
Scenario: 100 threads, same sourceIp, different idempotency keys
Expected:
  - Redis velocity counter = 100 after all threads complete
  - Threads exceeding threshold (e.g., > 10/min) get FRAUD_BLOCKED
  - No provider calls for blocked threads
  - Exactly N non-blocked payments created (N = threshold)
Thread count: 100
```

---

## 18.5 Scenario: API Key Rotation Grace Period

Simulates in-flight requests during key rotation — old key and new key both valid.

```yaml
Scenario:
  Thread A: uses old API key (being rotated)
  Thread B: uses new API key (just created)
  Both: initiate payments simultaneously during grace period

Expected:
  - Both requests succeed
  - Each transaction correctly attributed to same tenant
  - No authentication failure during rotation window
```

---

# 1️⃣9️⃣ TEST DATA BUILDER PATTERN STRATEGY

All test data is created through typed builders. No raw SQL inserts in test code.

---

## 19.1 TenantBuilder

```java
String tenantId = new TenantBuilder()
    .active()
    .withCallbackUrl("https://test.example.com/webhook")
    .withFeeRule(FeeRule.fixed(100))
    .create(jdbcTemplate);
```

---

## 19.2 ApiKeyBuilder

```java
String apiKey = new ApiKeyBuilder()
    .forTenant(tenantId)
    .production()                   // or .sandbox()
    .withScope(Scope.PAYMENT_WRITE)
    .create(jdbcTemplate);
```

---

## 19.3 PaymentRequestBuilder

```java
PaymentInitiateRequest request = new PaymentRequestBuilder()
    .mtn()                          // or .orange()
    .msisdn("677000001")
    .amount(5000)
    .currency("XAF")
    .idempotencyKey("idem-001")
    .withFraudSignal(DeviceFingerprint.of("device-abc"))
    .build();
```

---

## 19.4 WebhookPayloadBuilder

Provider-specific builders encapsulate the different payload formats:

```java
// MTN MoMo — PUT callback
MtnCallbackPayload mtnWebhook = new MtnWebhookPayloadBuilder()
    .success()
    .forTransaction(transactionId)
    .withFinancialTransactionId("FT-1234")
    .build();

// Orange Money — POST notifUrl callback
OrangeCallbackPayload orangeWebhook = new OrangeWebhookPayloadBuilder()
    .success()
    .forTransaction(transactionId)
    .withCreatetime("2026-03-27T10:00:00")   // WAT — no timezone in Orange payload
    .build();
```

---

## 19.5 FraudSignalBuilder

```java
FraudContext fraudContext = new FraudSignalBuilder()
    .sourceIp("192.168.1.100")
    .deviceFingerprint("device-abc")
    .velocityCountOverride(5)                // pre-seed velocity counter
    .build();
```

---

## 19.6 ReconciliationReportBuilder

```java
ProviderReport mtnReport = new ReconciliationReportBuilder()
    .provider(MTN)
    .date(LocalDate.of(2026, 3, 27))
    .addMatched(transactionId1, "SUCCESS", 5000)
    .addMissing(transactionId2)               // in Payam ledger, not in MTN report
    .addMismatched(transactionId3, "SUCCESS_PAYAM", "FAILED_MTN")
    .build();
```

---

## 19.7 Builder Principles

* All builders use fixed, deterministic UUIDs seeded per test class
* Builders do not write to DB — `create(jdbcTemplate)` is the explicit commit step
* Builders are composable — `new TenantBuilder().active()` returns the same builder for chaining
* WireMock stubs and DB state are created together in `setupPreconditions()`
* No builder reads from the database — they are write-only at test setup time

---

# 2️⃣0️⃣ TRANSACTIONAL BOUNDARY TESTING STRATEGY

Payam has several deliberate transaction boundary decisions (see PROJECT.md Key Decisions). Each must be tested explicitly.

---

## 20.1 INIT-Before-Provider Boundary (INV-09)

Tests that the INIT row is committed to PostgreSQL BEFORE the provider HTTP call is made.

```java
@Test
void initRowCommittedBeforeProviderCall() {
    // Use TransactionBoundaryProbe to intercept at the exact boundary
    AtomicBoolean rowExistsWhenProviderCalled = new AtomicBoolean(false);

    mtnWireMock.stubFor(post(urlEqualTo("/collection/v1_0/requesttopay"))
        .willReturn(aResponse()
            .withTransformerParameter("probe", () -> {
                // At the moment MTN WireMock receives the call, check DB
                rowExistsWhenProviderCalled.set(
                    jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM payments WHERE id = ?",
                        Integer.class, transactionId
                    ) == 1
                );
                return ResponseDefinition.ok();
            })));

    executeInitiate();

    assertThat(rowExistsWhenProviderCalled.get()).isTrue();
}
```

---

## 20.2 Rollback Scope Verification

Tests that an exception after INIT commit does NOT roll back the INIT row (different transaction).

```java
@Test
void exceptionAfterInitCommitDoesNotRollbackInitRow() {
    fraudService.throwOnNextEvaluation(new RuntimeException("Simulated fraud crash"));

    assertThatThrownBy(() -> restTemplate.postForEntity("/v1/payment/initiate", request, Void.class));

    // INIT row still exists
    databaseVerifier.assertPaymentExists(transactionId);
    databaseVerifier.assertPaymentStatus(transactionId, INITIATED);

    // But no provider call made
    providerCallVerifier.assertNoProviderCallMade();

    // And no ledger entry
    ledgerVerifier.assertNoLedgerEntry(transactionId);
}
```

---

## 20.3 @TransactionalEventListener(AFTER_COMMIT) Boundary

Tests that Spring Modulith events fire AFTER the outer transaction commits, not during it.

```java
@Test
void eventFiresAfterTransactionCommitsNotDuring() {
    AtomicBoolean paymentRowCommittedWhenEventFired = new AtomicBoolean(false);

    // Spy on event listener
    eventListenerSpy.onEvent(e -> {
        paymentRowCommittedWhenEventFired.set(
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payments WHERE id = ?",
                Integer.class, e.transactionId()) == 1
        );
    });

    executeInitiate();
    awaitEventProcessing();

    assertThat(paymentRowCommittedWhenEventFired.get()).isTrue();
}
```

---

## 20.4 Redis Idempotency Key NX Atomicity

Tests that the Redis NX+EX atomic reservation prevents two concurrent requests from both getting "key not found".

```java
@Test
void concurrentIdempotencyKeyReservation_onlyOneSucceeds() {
    // See §18.2 for the full scenario
    ConcurrencyResult result = harness.execute(20, () ->
        restTemplate.postForEntity("/v1/payment/initiate", sameKeyRequest, PaymentResponse.class)
                    .getStatusCode()
    );

    harness.assertExactlyOneSuccess(result);
    databaseVerifier.assertPaymentCount(tenantId, "idem-001", 1);
}
```

---

# 2️⃣1️⃣ FLOW STATE MACHINE TESTING TEMPLATE

For each business flow, document the expected state transitions and provide a parameterized test that walks every legal path.

---

## 21.1 MTN MoMo Payment — State Transition Map

```
INITIATED
  ├─[fraud check passes, provider call succeeds]──► PROCESSING
  │     ├─[MTN webhook SUCCESS + double-check confirms]──► SUCCESS
  │     ├─[MTN webhook FAILED + double-check confirms]───► FAILED
  │     └─[no webhook, polling confirms SUCCESS]──────────► SUCCESS
  └─[fraud check fails]──────────────────────────────────► FAILED
  └─[provider HTTP timeout/circuit open]─────────────────► FAILED
```

---

## 21.2 Orange Money Payment — State Transition Map

```
INITIATED
  ├─[/init succeeds, payToken extracted]──► AUTH_PENDING
  │     ├─[/pay with payToken succeeds]──► PROCESSING
  │     │     ├─[Orange webhook received + double-check]──► SUCCESS | FAILED
  │     │     └─[polling fallback after timeout]──────────► SUCCESS | FAILED
  │     └─[/pay fails — payToken expired]────────────────► FAILED
  └─[/init fails]────────────────────────────────────────► FAILED
```

---

## 21.3 Flow State Machine Test Template

```java
@ParameterizedTest
@MethodSource("mtnPaymentPaths")
void mtnPaymentFlowPath(PaymentScenario scenario) {
    // Setup stubs for this scenario
    scenario.configureMtnWireMock(mtnWireMock);

    // Execute
    UUID txId = initiatePayment(scenario.getRequest());
    scenario.deliverWebhookIfAny(restTemplate, txId);
    awaitEventProcessing();

    // Assert expected final state
    databaseVerifier.assertPaymentStatus(txId, scenario.getExpectedFinalState());
    hashChainVerifier.assertHashChainIntact(txId);

    if (scenario.getExpectedFinalState() == SUCCESS) {
        ledgerVerifier.assertDebitCreditBalance(txId);
        invariantVerifier.assertWebhookDoubleCheckFired(txId);
    } else {
        ledgerVerifier.assertNoLedgerEntry(txId);
    }
}

static Stream<PaymentScenario> mtnPaymentPaths() {
    return Stream.of(
        PaymentScenario.mtnSuccess(),
        PaymentScenario.mtnFraudBlocked(),
        PaymentScenario.mtnProviderTimeout(),
        PaymentScenario.mtnWebhookFailed(),
        PaymentScenario.mtnPollingFallbackSuccess()
    );
}
```

---

# 2️⃣2️⃣ MUTATION TESTING STRATEGY FOR BUSINESS LOGIC

Mutation testing validates that tests catch business rule violations. Payam's high-risk business logic targets:

---

## 22.1 Priority Targets for PITest

Run PITest (PIT Mutation Testing) against these packages with `mutationThreshold=90`:

| Package | Business Logic Under Test |
|---------|--------------------------|
| `payment.service` | Orchestration, state transitions, fee calculation |
| `payment.infrastructure.fraud` | Velocity checks, risk score computation |
| `payment.infrastructure.webhook` | Double-check logic, HMAC verification |
| `payment.infrastructure.reconciliation` | Discrepancy detection, WAT timestamp parsing |
| `payment.domain` | State machine guard conditions |

---

## 22.2 Critical Mutation Classes

These mutations MUST be killed by existing tests:

```yaml
INITIATED→SUCCESS direct transition guard:
  Mutation: remove guard that blocks INITIATED→SUCCESS
  Must be killed by: StateMachineIllegalTransitionTest

Ledger balance check:
  Mutation: change == to >= in balance assertion
  Must be killed by: LedgerBalanceVerificationTest

Idempotency tenant scope:
  Mutation: remove tenantId from Redis key
  Must be killed by: IdempotencyCrossTenantTest

Fraud blocking threshold:
  Mutation: change >= to > in fraud block condition
  Must be killed by: FraudThresholdBoundaryTest

Hash chain computation:
  Mutation: remove previousHash from SHA-256 input
  Must be killed by: HashChainVerificationTest

Orange timestamp offset:
  Mutation: change +01:00 to UTC in Orange time parser
  Must be killed by: OrangeTimestampParsingTest
```

---

## 22.3 Mutation Test Execution

```xml
<!-- pom.xml — PITest configuration -->
<plugin>
    <groupId>org.pitest</groupId>
    <artifactId>pitest-maven</artifactId>
    <configuration>
        <targetClasses>
            <param>com.payam.payment.service.*</param>
            <param>com.payam.payment.domain.*</param>
            <param>com.payam.payment.infrastructure.fraud.*</param>
            <param>com.payam.payment.infrastructure.webhook.*</param>
            <param>com.payam.payment.infrastructure.reconciliation.*</param>
        </targetClasses>
        <mutationThreshold>90</mutationThreshold>
        <mutators>STRONGER</mutators>
        <excludedMethods>
            <param>toString</param>
            <param>hashCode</param>
            <param>equals</param>
        </excludedMethods>
    </configuration>
</plugin>
```

---

# 2️⃣3️⃣ DOMAIN-INVARIANT DRIVEN TESTING MODEL

Rather than organizing tests by feature (payment, webhook, fraud), Payam's most critical tests are organized by invariant. This guarantees each invariant is directly verified in isolation.

---

## 23.1 Invariant → Test Mapping

| Invariant | Primary Test Class | Concurrency Test |
|-----------|-------------------|-----------------|
| INV-01: Hash chain | `HashChainIntegrityTest` | `ConcurrentEventAppendTest` |
| INV-02: Ledger balance | `LedgerDoubleEntryTest` | `ConcurrentWebhookLedgerTest` |
| INV-03: No double charge | `IdempotencyNoDoubleChargeTest` | `ConcurrentIdempotencyRaceTest` |
| INV-04: Tenant isolation | `TenantIsolationTest` | `ConcurrentMultiTenantTest` |
| INV-05: Legal transitions | `StateMachineLegalTransitionsTest` | `ConcurrentStateTransitionTest` |
| INV-06: Webhook double-check | `WebhookDoubleCheckTest` | N/A (sequential by design) |
| INV-07: Fraud before provider | `FraudBeforeProviderCallTest` | N/A |
| INV-08: Callback URL owned | `CallbackUrlSsrfGuardTest` | N/A |
| INV-09: INIT before provider | `InitBeforeProviderCallTest` | N/A |
| INV-10: Orange WAT timestamp | `OrangeTimestampWatTest` | N/A |

---

## 23.2 Invariant-First Test Structure

```java
// Tests are named after the invariant they protect
class INV02_LedgerDoubleEntryTest extends AbstractPayamE2ETest {

    @Test
    void successfulPaymentCreatesBalancedLedger() { ... }

    @Test
    void failedPaymentCreatesNoLedgerEntry() { ... }

    @Test
    void reversedPaymentLedgerRemainsBalanced() { ... }

    @Test
    @DisplayName("INV-02: concurrent SUCCESS updates do not create duplicate ledger entries")
    void concurrentWebhookAndPollingDoNotDuplicateLedger() { ... }
}
```

---

## 23.3 Invariant Assertion in Every Flow Test

Every `AbstractPaymentFlowTest.verifyFinalState()` MUST end with:

```java
// Mandatory invariant assertions — cannot be skipped
invariantVerifier.assertAll(transactionId);
```

Where `assertAll` calls the full invariant catalog:

```java
public void assertAll(UUID transactionId) {
    assertHashChainIntact(transactionId);
    assertLedgerBalanced(transactionId);
    assertNoDoubleCharge(tenantId, idempotencyKey);
    assertTenantIsolation(transactionId, tenantId);
    assertLegalStateTransition(transactionId, expectedFinalStatus);
    assertFraudEvaluatedBeforeProviderCall(transactionId);
}
```

---

# 🎯 FINAL PRINCIPLE

E2E testing is not about coverage numbers.

It is about **payment integrity guarantees**.

If a Payam flow test passes, we must be confident that:

✔ The payment was processed exactly once — no double charge
✔ The event log is tamper-evident — hash chain intact
✔ The ledger is balanced — money was not created or destroyed
✔ No other tenant saw this transaction — isolation verified
✔ The provider webhook was verified, not blindly trusted — double-check fired
✔ The fraud engine evaluated before the provider was called
✔ The system recovers from network instability without inconsistency

---
