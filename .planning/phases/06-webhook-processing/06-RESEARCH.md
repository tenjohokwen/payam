# Phase 6: Webhook Processing - Research

**Researched:** 2026-03-24
**Domain:** Inbound webhook reception, IP whitelist, HMAC verification, Redis dedup, Spring events, outbound tenant webhook delivery with retry
**Confidence:** HIGH — all findings verified from direct codebase inspection; no external research required

---

## Summary

Phase 6 adds the inbound webhook path (Orange POST, MTN PUT) and the outbound delivery path (tenant webhook
notification). The codebase already contains most of the building blocks: `MtnCallbackController` and
`MtnIpWhitelistInterceptor` are live; `OrangeMoneyPort.processWebhook()` is stubbed; `IdempotencyService`
shows the exact `StringRedisTemplate.setIfAbsent()` pattern for Redis dedup; `EventLogService` and
`PaymentEventLog` handle event persistence.

Spring Modulith is **not present** in pom.xml or anywhere in the codebase. The prior-decision reference to
"Spring Modulith durable events via PostgreSQL Event Publication Registry" is aspirational — this feature was
planned but never added. The codebase uses plain Spring `ApplicationEventPublisher` + `@EventListener` for
all internal events (verified in `AccountChangeEmailListener`, `AccountChangeEventListener`). Phase 6 must
use the same in-process event pattern, or introduce Spring Modulith as a new dependency. Because this is a
significant new dependency, the plan should default to plain Spring `@TransactionalEventListener` (which
provides durability within the same DB transaction) unless the planner decides Spring Modulith is worth
adding.

The `Tenant` entity has **no `webhookUrl` field** — this must be added via a new Flyway migration (V8) and
entity change. Next available Flyway version is V8 (`V7__transaction_mtn_fields.sql` is the last). The
`tenant` schema DDL in V1 is confirmed: no webhook URL column.

The MTN callback skeleton is fully present (controller, interceptor, config). The Orange side has no
controller yet — only `OrangeMoneyPort.processWebhook()`. Phase 6 must add `OrangeCallbackController` and a
corresponding `OrangeIpWhitelistInterceptor`/config, following the MTN pattern exactly.

**Primary recommendation:** Follow the MTN inbound pattern for Orange (separate interceptor, separate
WebMvcConfigurer). Use `StringRedisTemplate.setIfAbsent()` for dedup (same pattern as IdempotencyService).
Deliver outbound tenant webhooks with a dedicated `WebhookDeliveryService` using Spring `@Async` or a Quartz
job for retry — do not block the inbound HTTP thread waiting for tenant delivery.

---

## Findings — Answers to Research Questions

### RQ1: Does Tenant have webhookUrl? What DDL is needed?

**Answer: NO — webhookUrl field is absent from the Tenant entity.**

`Tenant.java` contains only: `tenantRef`, `name`, `tenantStatus`, `apiKeys` (relationship).
`V1__tenant_schema.sql` confirms no `webhook_url` column.

Required additions:
- New Flyway migration **V8__tenant_webhook_url.sql**
- Add `webhook_url VARCHAR(2048)` and `webhook_secret VARCHAR(255)` columns to `main.tenant`
- `webhook_url` is nullable (not all tenants need webhooks)
- `webhook_secret` stores the HMAC signing key for outbound signatures

Entity changes:
- Add `webhookUrl` and `webhookSecret` fields to `Tenant.java`
- Add corresponding getter/setter

**Confidence: HIGH** — verified from Tenant.java and V1 DDL.

---

### RQ2: OrangeWebhookPayload fields and HMAC verification

**Answer: OrangeWebhookPayload has: `payToken`, `notifToken`, `status`, `txnid`, `msisdn`, `amount`, `createtime`.**

No HMAC-related field (like `X-Orange-Signature`) is currently modeled in `OrangeWebhookPayload`.
The payload itself contains `notifToken` which is used for correlation (passed as a query param or header
by Orange, not a cryptographic signature). Orange HMAC header existence is **unconfirmed** (documented as
a blocker in the phase context).

Key fields for Phase 6:
- `payToken` → lookup key for finding the Transaction (via `pay_token` column on Transaction)
- `notifToken` → identity/correlation check (OrangeMoneyPort.processWebhook already validates it)
- `status` → trigger for double-check (do NOT apply state transition from this; call `getTransactionStatus()`)
- `createtime` → **always consume via `getCreatetimeAsInstant()`** (P5.1 WAT issue)
- `txnid` → Orange's own reference, stored for reconciliation

**Confidence: HIGH** — verified from OrangeWebhookPayload.java source.

---

### RQ3: MTN PUT callback body

**Answer: `MtnCallbackPayload` exists and is fully modeled.**

Fields: `financialTransactionId` (null on FAILED), `externalId` (= our `transactionId`), `status`, `reason`
(present on FAILED only).

Correlation key: `payload.getExternalId()` = our `transactionId`. This is the correct lookup key for the
Transaction table (not `providerRef`).

`MtnCallbackController` at `/v1/callbacks/mtn` (HTTP PUT) already calls `mtnMoMoPort.processCallback(payload)`
which logs + stores `financialTransactionId`. Phase 6 extends this with dedup + double-check event.

**Confidence: HIGH** — verified from MtnCallbackPayload.java and MtnCallbackController.java.

---

### RQ4: Spring Modulith durable events — is it configured?

**Answer: Spring Modulith is NOT present in this codebase.**

`pom.xml` has no `spring-modulith-*` dependency. No `@ApplicationModuleListener` annotation appears anywhere.
No `ApplicationModuleEvent` type exists. The `EventPublication` concept (PostgreSQL-backed durable events) is
not configured.

What IS present: plain Spring `ApplicationEventPublisher` + `@EventListener`. Example: `AccountChangeEmailListener`
uses `@Transactional @EventListener` for handling `AccountChangeEvent`. `AccountChangeEventListener` uses
`@EventListener` without `@Transactional`.

**Phase 6 implementation options for WebhookReceivedEvent:**

Option A (no new dependencies): Use `ApplicationEventPublisher.publishEvent()` + `@TransactionalEventListener(phase = AFTER_COMMIT)` on the double-check handler. This is synchronous within the same thread but fires after transaction commit. No durability guarantee if the JVM crashes between publish and handling — acceptable for MVP since polling provides a safety net.

Option B (full durability): Add Spring Modulith dependency (`spring-modulith-starter-jpa`) to enable PostgreSQL Event Publication Registry. This is a meaningful new dependency but provides at-least-once delivery semantics. It requires Spring Boot 3.x (confirmed: pom.xml uses 3.5.11).

**Recommendation:** Use `@TransactionalEventListener(phase = AFTER_COMMIT)` (Option A) unless durability at the event publication layer is a hard requirement. Document as a conscious trade-off. Option B can be a follow-on.

**Confidence: HIGH** — pom.xml fully read; no modulith dependency found.

---

### RQ5: MTN IP whitelist pattern and reusability for Orange

**Answer: The MTN IP whitelist pattern is fully reusable. Orange needs a parallel implementation.**

MTN pattern:
1. `MtnMoMoConfig.callbackIpWhitelist` — `List<String>` from `mtn.callback-ip-whitelist` in application.yaml
2. `MtnIpWhitelistInterceptor` — `@Component`, implements `HandlerInterceptor.preHandle()`
3. `MtnWebConfig implements WebMvcConfigurer` — registers interceptor for `/v1/callbacks/mtn` only
4. CIDR support: octet-boundary CIDR only (`/8`, `/16`, `/24`, `/32`) — not full RFC 4632
5. Empty whitelist = sandbox mode (accept all, log warning)
6. `X-Forwarded-For` respected (`server.forward-headers-strategy=native` is set in application.yaml)

For Orange: add `orange.callback-ip-whitelist` to `OrangeMoneyConfig`, create `OrangeIpWhitelistInterceptor`
and `OrangeWebConfig` mirroring the MTN structure exactly.

Current application.yaml has no `orange.callback-ip-whitelist` key — must be added.

**Confidence: HIGH** — verified from MtnIpWhitelistInterceptor.java and MtnWebConfig.java.

---

### RQ6: PaymentEventLog structure — can delivery attempts be added?

**Answer: PaymentEventLog is @Immutable — it cannot be extended for mutable delivery tracking.**

`PaymentEventLog` is annotated `@Immutable` (Hibernate), has no setters, and uses a factory method `create()`.
It is a hash-chained append-only log — not suitable for tracking mutable delivery attempt state (attempts,
last status, next retry time).

**Recommendation:** A **separate** `WebhookDeliveryLog` entity is needed for outbound delivery tracking.

Fields needed for outbound delivery log:
- `transactionId VARCHAR(36)` — link to transaction
- `tenantId BIGINT` — which tenant
- `webhookUrl VARCHAR(2048)` — where we tried to deliver
- `eventType VARCHAR(50)` — the event type dispatched
- `httpStatus INTEGER` — last HTTP response code (null if never attempted)
- `attemptCount INTEGER` — number of attempts so far
- `nextRetryAt TIMESTAMP WITH TIME ZONE` — for exponential backoff scheduling
- `delivered BOOLEAN` — final delivery flag
- `lastAttemptAt TIMESTAMP WITH TIME ZONE`
- `createdDate TIMESTAMP WITH TIME ZONE`

This needs a new Flyway migration (V9 after the V8 tenant webhook_url migration).

**Confidence: HIGH** — verified from PaymentEventLog.java (confirmed @Immutable, no setters, factory method only).

---

### RQ7: Next Flyway version

**Answer: V8 is the next available version.**

Existing migrations:
- V1__tenant_schema.sql
- V2__idempotency_key_schema.sql
- V3__transaction_schema.sql
- V4__ledger_schema.sql
- V5__quartz_schema.sql
- V6__transaction_orange_fields.sql
- V7__transaction_mtn_fields.sql

Phase 6 needs:
- **V8__tenant_webhook_url.sql** — adds `webhook_url` and `webhook_secret` to `main.tenant`
- **V9__webhook_delivery_log.sql** — creates `main.webhook_delivery_log` table

**Confidence: HIGH** — verified from resources/db/migration/ directory listing.

---

### RQ8: Resilience4j circuit breaker configuration

**Answer: Two instances configured — "orange" and "mtn". Both use same parameters.**

From application.yaml:
```yaml
resilience4j:
  circuitbreaker:
    instances:
      orange:
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        slidingWindowSize: 10
        permittedNumberOfCallsInHalfOpenState: 3
        ignoreExceptions:
          - SubscriberInactiveException
          - PayTokenExpiredException
      mtn:
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        slidingWindowSize: 10
        permittedNumberOfCallsInHalfOpenState: 3
        ignoreExceptions:
          - MtnAccountInactiveException
  retry:
    instances:
      orange: maxAttempts=3, waitDuration=1s, exponential x2 (SocketTimeoutException, ResourceAccessException)
      mtn:    maxAttempts=3, waitDuration=1s, exponential x2 (SocketTimeoutException, ResourceAccessException)
```

The double-check call (`OrangeMoneyPort.getTransactionStatus()` / `MtnMoMoPort.getTransactionStatus()`) is
already annotated `@CircuitBreaker(name = "orange"/"mtn")`. Phase 6 calls these methods — the existing
circuit breakers apply automatically.

If the circuit is open during double-check, `CallNotPermittedException` is thrown. The webhook handler must
catch this and NOT apply a state transition — leave transaction in PROCESSING.

**Confidence: HIGH** — verified from application.yaml and OrangeMoneyPort.getTransactionStatus() source.

---

### RQ9: OrangeTimeUtil / getCreatetimeAsInstant() implementation

**Answer: Fully implemented. One call site rule (P5.1): always use `getCreatetimeAsInstant()`, never `getCreatetime()`.**

`OrangeTimeUtil.parseOrangeTimestamp(String)`:
- Format: `"yyyy-MM-dd'T'HH:mm:ss"` (no timezone offset in string)
- Zone: `Africa/Douala` (WAT = UTC+1, no DST)
- Converts `LocalDateTime.parse()` → `atZone(WAT)` → `toInstant()`

`OrangeWebhookPayload.getCreatetimeAsInstant()` — delegates to `OrangeTimeUtil.parseOrangeTimestamp()`,
returns null if `createtime` is null or blank.

Phase 6 consumers MUST call `payload.getCreatetimeAsInstant()` when any timestamp field is needed.
The raw `getCreatetime()` returns a WAT string with no offset — treating it as UTC gives a 1-hour error.

**Confidence: HIGH** — verified from OrangeTimeUtil.java and OrangeWebhookPayload.java source.

---

### RQ10: OrangeMoneyPort — does it have a fetchStatus() / double-check method?

**Answer: Yes — `OrangeMoneyPort.getTransactionStatus(String providerRef)` is the double-check method.**

Signature: `public ProviderResult getTransactionStatus(String providerRef)` — implements `MobileMoneyPort`.
- `providerRef` for Orange = the `payToken` stored on the Transaction
- Decorated with `@CircuitBreaker(name = "orange")` and `@Transactional`
- Calls `orangeMoneyClient.getPaymentStatus(token, providerRef)` → `OrangeStatusMapper.toInternal(rawStatus)`
- Returns `ProviderResult.pending(providerRef, rawStatus)` if PROCESSING, else `ProviderResult.success()`

**MTN equivalent:** `MtnMoMoPort.getTransactionStatus(String providerRef)` uses the merchant-generated `referenceId`
stored in `transaction.provider_ref`.

**Double-check sequence for Phase 6:**
1. Inbound webhook arrives → look up Transaction by correlation key
2. Publish `WebhookReceivedEvent` (or call handler directly)
3. Call `port.getTransactionStatus(transaction.getProviderRef())` — never skip this
4. Only if result is final (SUCCESS/FAILED) → apply `tx.applyTransition()` + `EventLogService.append()`
5. If result is still PROCESSING → do nothing (poller will catch it later)

**Confidence: HIGH** — verified from OrangeMoneyPort.java and MtnMoMoPort.java source.

---

## Pitfalls

### Pitfall 1: Applying State Transition from Webhook Payload Without Double-Check

**What goes wrong:** Inbound webhook body says `"status": "SUCCESS"` — code calls `tx.applyTransition(SUCCESS)`.
This is P1.4. Orange/MTN webhook delivery can be delayed, reordered, or replayed. Provider APIs are the
source of truth.

**Why it happens:** Looks simpler — webhook says SUCCESS so mark SUCCESS. Tempting optimization.

**How to avoid:** The webhook is only a trigger. After receiving it, ALWAYS call `port.getTransactionStatus()`
before any state change. `OrangeMoneyPort.processWebhook()` already has a comment confirming this.

**Warning signs:** Transaction transitions to SUCCESS from a webhook but status poll later returns PENDING.

---

### Pitfall 2: Duplicate Webhook Processing (Race Between Poller and Webhook)

**What goes wrong:** Orange webhook arrives at the same time the Quartz poller fires for the same transaction.
Both call `getTransactionStatus()`, both get SUCCESS, both try to call `tx.applyTransition(SUCCESS)`. The
second call throws `IllegalStateTransitionException` (SUCCESS → SUCCESS is not allowed).

**Why it happens:** Concurrent processing without locking.

**How to avoid:**
1. Redis dedup by webhook ID (payToken + createtime hash for Orange; externalId for MTN) within a TTL window
   prevents duplicate webhook handling.
2. Use `transactionRepository.findByTransactionIdForUpdate()` (PESSIMISTIC_WRITE) before any state transition
   — same pattern as PaymentOrchestrator and OrangeStatusPollerJob.

**Warning signs:** `IllegalStateTransitionException: Invalid state transition: SUCCESS -> SUCCESS` in logs.

---

### Pitfall 3: Orange Correlation Key Confusion

**What goes wrong:** Code looks up Transaction by `txnid` (OrangeWebhookPayload) instead of `payToken`.
The `txnid` is Orange's internal transaction ID — it is NOT stored in the Transaction table. The `payToken`
is stored in `transaction.pay_token`.

**How to avoid:** Use `transactionRepository.findByPayToken(payToken)` (needs a new `@Query` on
TransactionRepository). The `txnid` can be stored for reconciliation (as metadata in EventLogService) but
should not be used for lookup.

**Warning signs:** Transaction lookup by `txnid` always returns empty.

---

### Pitfall 4: Orange HMAC Verification Without Confirmed Header Name

**What goes wrong:** Implementing HMAC verification for Orange using an assumed header name (e.g.,
`X-Orange-Signature`) that turns out to differ from what Orange actually sends. All Orange webhooks are
rejected in production.

**Why it happens:** Orange docs do not clearly specify the HMAC header in the available materials
(documented as a blocker in phase context).

**How to avoid:** Design the inbound Orange controller to make HMAC verification optional/configurable.
Add a property `orange.callback-hmac-secret` that, if empty, skips HMAC check (sandbox mode pattern from
MtnIpWhitelistInterceptor). IP whitelist alone provides security until HMAC header is confirmed.

**Warning signs:** All Orange webhooks return 403 in sandbox.

---

### Pitfall 5: Blocking HTTP Thread During Outbound Tenant Webhook Delivery

**What goes wrong:** After processing an inbound callback and completing the double-check, code synchronously
calls the tenant's webhook URL before returning 200 to the provider. Provider (Orange/MTN) receives a timeout
on the callback response, retries, creating duplicate callbacks.

**Why it happens:** "Complete everything before responding" instinct.

**How to avoid:** Return 200 to the provider IMMEDIATELY on receiving the callback. Enqueue outbound delivery
asynchronously (via `@Async`, Quartz job, or Spring event). The outbound delivery is not on the critical path
of the inbound response.

**Warning signs:** Provider (Orange/MTN) re-sends webhooks because our endpoint takes > 5s to respond.

---

### Pitfall 6: Outbound HMAC Signing Key Missing From Tenant

**What goes wrong:** `WebhookDeliveryService` tries to sign outbound payload with tenant's HMAC secret but
`Tenant.getWebhookSecret()` returns null (secret was never set, or webhook was registered without a secret).

**How to avoid:** Require `webhookSecret` to be non-null when registering a webhook URL. Or skip signing when
null and document that unsigned webhooks are not recommended. The `TenantAdminResource` provisioning flow
should enforce this constraint.

**Warning signs:** NPE in HMAC computation for newly registered tenants.

---

### Pitfall 7: `@Transactional` on Inbound Webhook Controller Method

**What goes wrong:** Marking the callback controller endpoint `@Transactional` holds a DB connection open
during the optional double-check HTTP call to the provider (P1.1/P8.1 pattern — identical to orchestrator).

**How to avoid:** Same P1.1 pattern used in PaymentOrchestrator: commit the "webhook received, dedup stored"
record first (in its own @Transactional boundary), then call `port.getTransactionStatus()` outside any
@Transactional, then apply state transition in a new transaction.

---

### Pitfall 8: Webhook Dedup Key Design

**What goes wrong:** Using only `payToken` as the Redis dedup key for Orange webhooks. Orange can re-send
the same webhook with the same payToken for a different notification (e.g., multiple status updates on the
same payment during processing). Using payToken alone deduplicates across ALL notifications for that payment.

**How to avoid:** Include the webhook timestamp in the dedup key:
`"webhook:orange:" + payToken + ":" + createtime`
For MTN, use `externalId` + `status` as the dedup key since MTN sends one callback per terminal state.

---

## Patterns to Follow (from Prior Phases)

### Pattern 1: IP Whitelist via HandlerInterceptor (MTN)

The complete, tested pattern is in:
- `MtnIpWhitelistInterceptor` — interceptor logic with CIDR support and sandbox mode
- `MtnWebConfig` — registration for a single path only
- `MtnMoMoConfig.callbackIpWhitelist` — `List<String>` from config

Orange needs: `OrangeIpWhitelistInterceptor`, `OrangeWebConfig`, and `orange.callback-ip-whitelist` in
`OrangeMoneyConfig` (analogous to `callbackIpWhitelist` in `MtnMoMoConfig`).

### Pattern 2: Redis setIfAbsent for Dedup (IdempotencyService)

```java
// Source: IdempotencyService.java — exact pattern to copy for webhook dedup
Boolean wasAbsent = redis.opsForValue().setIfAbsent(redisKey, PLACEHOLDER, TTL);
if (Boolean.FALSE.equals(wasAbsent)) {
    // Duplicate — reject
    return;
}
// First time — proceed with processing
```

Key namespacing convention: `"webhook:orange:" + payToken + ":" + createtime` or `"webhook:mtn:" + externalId + ":" + status`.

### Pattern 3: EventLogService.append() — DO NOT bypass

```java
// Source: OrangeMoneyPort, MtnMoMoPort, PaymentOrchestrator
// ALL state transition records go through EventLogService.append() — never directly to PaymentEventLogRepository
eventLogService.append(
    transactionId, traceId, externalReference,
    TransactionEventType.PROVIDER_SUCCESS,     // or PROVIDER_FAILED
    TransactionStatus.PROCESSING,
    TransactionStatus.SUCCESS,
    "WEBHOOK_HANDLER",
    metadataJson
);
```

### Pattern 4: PESSIMISTIC_WRITE Lock Before State Transition

```java
// Source: PaymentOrchestrator.applyFailed(), OrangeStatusPollerJob
Transaction locked = transactionRepository
    .findByTransactionIdForUpdate(transactionId)
    .orElseThrow(() -> new IllegalStateException("Transaction not found: " + transactionId));
locked.applyTransition(TransactionStatus.SUCCESS);
```

### Pattern 5: PUBLIC_ENDPOINTS Registration for Unauthenticated Endpoints

Orange callback endpoint must be added to `AppEndpoints.PUBLIC_ENDPOINTS` (same as MTN):
```java
// Source: AppEndpoints.java — current list includes "/v1/callbacks/mtn"
// Add: "/v1/callbacks/orange"
public static final List<String> PUBLIC_ENDPOINTS = List.of(
    ...,
    "/v1/callbacks/mtn",
    "/v1/callbacks/orange"  // ADD THIS
);
```

The `TenantSecurityConfig` builds its permit-all list from `PUBLIC_ENDPOINTS` — adding to the constant is sufficient.

### Pattern 6: @TransactionalEventListener for Post-Commit Events

```java
// Source: AccountChangeEmailListener.java — @Transactional @EventListener pattern used in codebase
// For Phase 6, the webhook double-check must happen AFTER the dedup record is committed:
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void handleWebhookReceived(WebhookReceivedEvent event) {
    // Safe to call port.getTransactionStatus() here — dedup record already committed
    // port.getTransactionStatus() may take 1-5 seconds — NOT @Transactional here (P1.1)
}
```

Note: `@TransactionalEventListener` is in `org.springframework.transaction.event` — already on classpath
via `spring-boot-starter-data-jpa`.

### Pattern 7: Outbound HTTP with Existing RestTemplate (noRetryRestTemplate)

For outbound tenant webhook delivery, use `noRetryRestTemplate` (SimpleClientHttpRequestFactory) which does
NOT auto-retry. The `PaymentOrchestratorIT` already injects this bean for circuit breaker tests. Outbound
retry should be handled by Phase 6's own exponential backoff logic — not by the HTTP client.

---

## Standard Stack

All required components are present. The only new code/configuration needed:

### New Components Needed (no new Maven dependencies)

| Component | Location | Purpose |
|-----------|----------|---------|
| `OrangeCallbackController` | `orange/web/` | POST /v1/callbacks/orange — mirrors MtnCallbackController |
| `OrangeIpWhitelistInterceptor` | `orange/web/` | IP whitelist for Orange — mirrors MtnIpWhitelistInterceptor |
| `OrangeWebConfig` | `orange/web/` | Registers interceptor for /v1/callbacks/orange only |
| `WebhookDeliveryService` | `webhook/service/` | Outbound delivery — HMAC signing, HTTP POST, retry tracking |
| `WebhookDeliveryLog` | `webhook/repo/` | Mutable delivery attempt record (not @Immutable) |
| `WebhookDeliveryLogRepository` | `webhook/repo/` | Spring Data JPA repo |
| `WebhookReceivedEvent` | `webhook/contract/` | Internal event record for double-check dispatch |
| V8__tenant_webhook_url.sql | `db/migration/` | Adds webhook_url + webhook_secret to tenant |
| V9__webhook_delivery_log.sql | `db/migration/` | Creates webhook_delivery_log table |

### Existing Components Consumed

| Component | Usage |
|-----------|-------|
| `MtnCallbackController` | Already handles PUT; Phase 6 extends its processCallback delegation |
| `MtnIpWhitelistInterceptor` | Already live; Phase 6 adds Orange parallel |
| `OrangeMoneyPort.getTransactionStatus()` | Double-check for Orange webhooks |
| `MtnMoMoPort.getTransactionStatus()` | Double-check for MTN webhooks |
| `OrangeMoneyPort.processWebhook()` | Existing stub; Phase 6 wires the full dedup+event-publish flow |
| `MtnMoMoPort.processCallback()` | Existing stub; Phase 6 wires dedup+event-publish |
| `EventLogService.append()` | Records state transitions after double-check confirms final status |
| `TransactionRepository.findByTransactionIdForUpdate()` | PESSIMISTIC_WRITE lock before state change |
| `StringRedisTemplate` | `setIfAbsent()` for dedup (same as IdempotencyService) |
| `AppEndpoints.PUBLIC_ENDPOINTS` | Add /v1/callbacks/orange to permit-all list |
| `commons-codec DigestUtils.sha256Hex()` | HMAC-SHA256 for outbound signatures (already in pom.xml) |

### No New Maven Dependencies Required

```bash
# All needed libraries already present:
# - spring-boot-starter-data-redis (StringRedisTemplate, setIfAbsent)
# - commons-codec (DigestUtils.sha256Hex for HMAC)
# - spring-boot-starter-quartz (for retry scheduler if using Quartz)
# - spring-boot-starter-web (RestTemplate for outbound delivery)
# - spring-retry (if using @Retryable for outbound delivery)
# - wiremock-spring-boot (test stubs for tenant webhook URL)
```

---

## Architecture Patterns

### Recommended Package Structure for Phase 6

```
src/main/java/com/softropic/payam/
├── orange/
│   ├── web/                                   # NEW
│   │   ├── OrangeCallbackController.java       # POST /v1/callbacks/orange
│   │   ├── OrangeIpWhitelistInterceptor.java   # mirrors MtnIpWhitelistInterceptor
│   │   └── OrangeWebConfig.java                # registers interceptor for /v1/callbacks/orange
│   └── service/
│       └── OrangeMoneyPort.java               # EXISTING — processWebhook() already stubbed
├── mtn/
│   └── web/
│       └── MtnCallbackController.java         # EXISTING — extend processCallback delegation
├── webhook/                                   # NEW MODULE
│   ├── contract/
│   │   ├── WebhookReceivedEvent.java           # record(transactionId, provider, providerRef, traceId)
│   │   └── OutboundWebhookPayload.java         # DTO sent to tenant (transactionId, status, timestamp, sig)
│   ├── repo/
│   │   ├── WebhookDeliveryLog.java             # Mutable entity — tracks delivery attempts
│   │   └── WebhookDeliveryLogRepository.java
│   └── service/
│       ├── WebhookDoubleCheckHandler.java      # @TransactionalEventListener — calls port.getTransactionStatus()
│       └── WebhookDeliveryService.java         # Outbound delivery — HMAC sign, HTTP POST, retry
└── security/config/
    └── AppEndpoints.java                      # MODIFY — add /v1/callbacks/orange to PUBLIC_ENDPOINTS
```

### Pattern: Double-Check Flow

```
Inbound PUT/POST from Orange/MTN
         |
         v
Controller receives payload (IP whitelist already enforced by interceptor)
         |
         v
Redis setIfAbsent(dedupKey, "SEEN", TTL)  ← atomic; reject if already present (return 200 silently)
         |
         v
Look up Transaction by correlation key
  Orange: transactionRepository.findByPayToken(payToken)
  MTN:    transactionRepository.findByTransactionId(externalId)
         |
         v
Persist minimal webhook receipt record (WebhookDeliveryLog row with RECEIVED status)
COMMIT this record before any provider HTTP call (P1.1 pattern)
         |
         v
publishEvent(new WebhookReceivedEvent(...))  ← in-process Spring event
         |
         v  [AFTER_COMMIT — @TransactionalEventListener]
port.getTransactionStatus(providerRef)  ← live provider API call — outside @Transactional
         |
    PROCESSING?         Final (SUCCESS/FAILED)?
         |                      |
    do nothing           findByTransactionIdForUpdate (PESSIMISTIC_WRITE)
    (poller handles)     tx.applyTransition(final)
                         eventLogService.append(...)
                         publishEvent for outbound tenant delivery
         |
         v
Return 200 OK to provider immediately (before @TransactionalEventListener fires)
```

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| HMAC-SHA256 computation | Custom byte[] manipulation | `javax.crypto.Mac` + `DigestUtils` (commons-codec already present) | Encoding errors in custom implementations; use well-tested library |
| Webhook dedup | Custom DB dedup table with row locking | `StringRedisTemplate.setIfAbsent()` | Exact pattern from IdempotencyService; atomic, TTL-aware, Redis fallback handled |
| IP whitelist CIDR matching | Subnet calculator from scratch | Copy from `MtnIpWhitelistInterceptor` — already implemented with /8, /16, /24, /32 support | Already tested; don't duplicate |
| Outbound retry | Custom retry counter loop | Quartz `WebhookRetryJob` OR `@Retryable` (spring-retry already in pom.xml) | Exponential backoff, persistence across JVM restarts (Quartz) |
| State transition | Direct field assignment on Transaction | `tx.applyTransition(next)` | Only path through state machine guard; direct set bypasses `IllegalStateTransitionException` |
| Event log append | Direct `PaymentEventLogRepository.save()` | `EventLogService.append()` | Maintains SHA-256 hash chain; direct saves break chain integrity |
| HMAC signing for outbound | JWT or base64 encoding | HMAC-SHA256 with `javax.crypto.Mac.getInstance("HmacSHA256")` | Industry standard for webhook signing; `DigestUtils` in pom |

---

## Code Examples

### Redis Dedup (mirrors IdempotencyService exactly)

```java
// Source: IdempotencyService.java — setIfAbsent pattern
// For Orange: key = "webhook:orange:" + payToken + ":" + payload.getCreatetimeAsInstant()
// For MTN: key = "webhook:mtn:" + externalId + ":" + status
private static final Duration DEDUP_TTL = Duration.ofHours(24);

boolean isNew = Boolean.TRUE.equals(
    redis.opsForValue().setIfAbsent(dedupKey, "SEEN", DEDUP_TTL));
if (!isNew) {
    log.info("Duplicate webhook rejected: key={}", dedupKey);
    return ResponseEntity.ok().build();  // Return 200 silently — provider should not retry
}
```

### HMAC-SHA256 Outbound Signing

```java
// Source: commons-codec DigestUtils already in pom.xml; javax.crypto.Mac is JDK standard
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.binary.Hex;

public String computeHmacSha256(String payload, String secret) {
    try {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] sig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return Hex.encodeHexString(sig);
    } catch (Exception e) {
        throw new RuntimeException("HMAC signing failed", e);
    }
}
```

### Outbound Webhook Delivery Header

```java
// Sign: HMAC-SHA256 of raw JSON body bytes using tenant's webhookSecret
// Deliver as header: X-Payam-Signature: sha256=<hex>
HttpHeaders headers = new HttpHeaders();
headers.setContentType(MediaType.APPLICATION_JSON);
headers.set("X-Payam-Signature", "sha256=" + computeHmacSha256(bodyJson, tenant.getWebhookSecret()));
```

### OrangeCallbackController (mirrors MtnCallbackController)

```java
// Source: MtnCallbackController.java — exact structural copy with POST verb
@RestController
public class OrangeCallbackController {

    private final OrangeMoneyPort orangeMoneyPort;

    public OrangeCallbackController(OrangeMoneyPort orangeMoneyPort) {
        this.orangeMoneyPort = orangeMoneyPort;
    }

    /**
     * Accepts Orange POST callback. Returns 200 immediately.
     * IP whitelist enforced upstream by OrangeIpWhitelistInterceptor.
     * HMAC check: conditional (orange.callback-hmac-secret must be configured).
     * Phase 6 applies double-check via WebhookReceivedEvent.
     */
    @PostMapping("/v1/callbacks/orange")
    public ResponseEntity<Void> handleCallback(
            @RequestBody OrangeWebhookPayload payload,
            HttpServletRequest request) {
        orangeMoneyPort.processWebhook(payload, payload.getNotifToken());
        return ResponseEntity.ok().build();
    }
}
```

### V8 DDL

```sql
-- V8__tenant_webhook_url.sql
SET search_path = main;

ALTER TABLE tenant
    ADD COLUMN IF NOT EXISTS webhook_url    VARCHAR(2048),
    ADD COLUMN IF NOT EXISTS webhook_secret VARCHAR(255);
-- Both nullable: not all tenants need outbound webhooks.
-- webhook_secret: HMAC signing key; if null, Phase 6 delivery service skips HMAC.
```

### V9 DDL

```sql
-- V9__webhook_delivery_log.sql
SET search_path = main;

CREATE TABLE main.webhook_delivery_log (
    id                  BIGINT PRIMARY KEY,
    transaction_id      VARCHAR(36) NOT NULL,
    tenant_id           BIGINT NOT NULL REFERENCES main.tenant(id),
    webhook_url         VARCHAR(2048) NOT NULL,
    event_type          VARCHAR(50) NOT NULL,
    http_status         INTEGER,
    attempt_count       INTEGER NOT NULL DEFAULT 0,
    delivered           BOOLEAN NOT NULL DEFAULT FALSE,
    last_attempt_at     TIMESTAMP WITH TIME ZONE,
    next_retry_at       TIMESTAMP WITH TIME ZONE,
    created_date        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_wdl_transaction_id ON main.webhook_delivery_log(transaction_id);
CREATE INDEX idx_wdl_delivered      ON main.webhook_delivery_log(delivered) WHERE delivered = FALSE;
CREATE INDEX idx_wdl_next_retry_at  ON main.webhook_delivery_log(next_retry_at) WHERE delivered = FALSE;
```

---

## State of the Art (this codebase)

| Old Approach | Current Approach | Impact for Phase 6 |
|--------------|------------------|--------------------|
| MTN callback logs but does not double-check | MtnCallbackController + MtnMoMoPort.processCallback() — logs and stores financialTxId, awaits Phase 6 wiring | Phase 6 wires the full dedup + event + double-check + state transition flow |
| Orange webhook: no controller exists | OrangeMoneyPort.processWebhook() stub exists | Phase 6 adds OrangeCallbackController, wires into processWebhook() |
| Tenant has no webhook registration | Tenant entity has no webhookUrl | Phase 6 adds V8 migration + entity fields |
| No outbound delivery infrastructure | Nothing | Phase 6 builds WebhookDeliveryService from scratch |
| Spring Modulith claimed in decisions | NOT present in pom.xml | Use plain @TransactionalEventListener instead; modulith is an option if durability is required |

---

## Open Questions

1. **Orange HMAC header name and format**
   - What we know: OrangeWebhookPayload has no HMAC field. Orange docs are unconfirmed.
   - What's unclear: Does Orange send a signature header? What is the header name? What is the signing algorithm and key?
   - Recommendation: Design `OrangeIpWhitelistInterceptor` to conditionally skip HMAC check when `orange.callback-hmac-secret` is empty (sandbox mode). Implement HMAC check as a separate step gated by config presence. Do not block implementation on this — IP whitelist provides adequate security until confirmed.

2. **MTN PUT callback sandbox verification**
   - What we know: `MtnCallbackController` handles PUT. Phase context notes "verify in sandbox before relying on it."
   - What's unclear: Does the MTN sandbox actually fire PUT callbacks when a payment completes? Or must testing rely on WireMock mocking of the provider callback?
   - Recommendation: Integration tests for Phase 6 should call the callback endpoint directly (simulating what MTN sends). The test does NOT depend on MTN sandbox firing — WireMock stubs the status poll.

3. **Orange payToken dedup TTL**
   - What we know: `orange.pay-token-expiry-threshold-minutes: 8` is the payToken expiry. Dedup TTL should exceed the maximum window where Orange could re-send the same webhook.
   - What's unclear: How long does Orange retry sending a webhook that got no 200? Is 24h dedup TTL sufficient, or could re-sends arrive after 24h?
   - Recommendation: Default to 24h (same as idempotency TTL). Flag as needing sandbox observation.

4. **Outbound webhook retry strategy: @Async + Quartz vs. @Retryable**
   - What we know: Quartz is configured (V5 schema, JDBC store). spring-retry is in pom.xml (`spring-retry`). Both are valid retry mechanisms.
   - What's unclear: The success criteria say "delivery status is queryable" — this implies a persisted retry record. `@Retryable` is in-memory and loses state on restart. Quartz `WebhookRetryJob` with `WebhookDeliveryLog` persistence satisfies queryability.
   - Recommendation: Use `WebhookDeliveryLog` (V9 DDL) + a Quartz `WebhookRetryJob` that polls for `delivered=FALSE AND next_retry_at <= NOW()`. This matches the existing Quartz polling pattern and makes delivery state queryable. `@Retryable` alone does NOT satisfy "delivery status is queryable."

5. **Spring Modulith — add or skip?**
   - What we know: Not present. Phase context mentions it. Prior decision says "durable events via PostgreSQL Event Publication Registry."
   - What's unclear: Was this a firm architectural requirement, or an aspirational note?
   - Recommendation: Implement with `@TransactionalEventListener` first (no new dependency). Document that Spring Modulith can be added if at-least-once delivery for the double-check dispatch is required. The Quartz retry covers at-least-once for outbound delivery; the double-check may be re-triggered by the next poller run if the webhook handler crashes mid-flight.

---

## Sources

### Primary (HIGH confidence)
- `src/main/java/com/softropic/payam/mtn/web/MtnCallbackController.java` — MTN controller pattern
- `src/main/java/com/softropic/payam/mtn/web/MtnIpWhitelistInterceptor.java` — IP whitelist implementation
- `src/main/java/com/softropic/payam/mtn/web/MtnWebConfig.java` — interceptor registration pattern
- `src/main/java/com/softropic/payam/mtn/contract/MtnCallbackPayload.java` — MTN PUT body fields
- `src/main/java/com/softropic/payam/mtn/config/MtnMoMoConfig.java` — callbackIpWhitelist config field
- `src/main/java/com/softropic/payam/orange/contract/OrangeWebhookPayload.java` — Orange POST body fields
- `src/main/java/com/softropic/payam/orange/service/OrangeMoneyPort.java` — processWebhook() stub + getTransactionStatus()
- `src/main/java/com/softropic/payam/mtn/service/MtnMoMoPort.java` — processCallback() stub + getTransactionStatus()
- `src/main/java/com/softropic/payam/orange/service/OrangeTimeUtil.java` — WAT parsing implementation
- `src/main/java/com/softropic/payam/tenant/repo/Tenant.java` — confirmed no webhookUrl field
- `src/main/java/com/softropic/payam/transaction/repo/PaymentEventLog.java` — @Immutable, factory-only
- `src/main/java/com/softropic/payam/transaction/service/EventLogService.java` — append() API
- `src/main/java/com/softropic/payam/transaction/service/IdempotencyService.java` — Redis setIfAbsent pattern
- `src/main/java/com/softropic/payam/transaction/contract/TransactionStatus.java` — state machine; PROCESSING → SUCCESS/FAILED/REVERSED
- `src/main/java/com/softropic/payam/security/config/AppEndpoints.java` — PUBLIC_ENDPOINTS confirmed includes /v1/callbacks/mtn
- `src/main/java/com/softropic/payam/tenant/config/TenantSecurityConfig.java` — how PUBLIC_ENDPOINTS is used for permitAll
- `src/main/resources/application.yaml` — resilience4j config, MTN callback-ip-whitelist, Redis config
- `src/main/resources/db/migration/` — V1 through V7 confirmed; V8 is next
- `pom.xml` — confirmed Spring Modulith absent; commons-codec, spring-retry, quartz, spring-data-redis present

### Secondary (MEDIUM confidence)
- N/A — all findings from direct codebase inspection

### Tertiary (LOW confidence)
- Orange HMAC header name — not in codebase; requires partner confirmation
- Orange payToken dedup TTL adequacy — needs sandbox observation

---

## Metadata

**Confidence breakdown:**
- MTN inbound pattern (controller, interceptor, config): HIGH — code fully present and verified
- Orange inbound pattern (needs to be created): HIGH — pattern to follow is verified from MTN
- Tenant webhookUrl missing: HIGH — Tenant.java and V1 DDL both verified
- PaymentEventLog is @Immutable (needs separate delivery log): HIGH — Hibernate @Immutable confirmed
- Spring Modulith absence: HIGH — pom.xml read in full; no modulith dependency
- Redis dedup pattern: HIGH — IdempotencyService confirmed as exact template to follow
- Next Flyway version (V8): HIGH — all V1-V7 files listed and confirmed
- Circuit breaker config for double-check: HIGH — application.yaml + port source verified
- Orange HMAC header: LOW — unconfirmed, requires partner verification
- Outbound delivery retry mechanism choice (Quartz vs. @Retryable): MEDIUM — both present in pom; tradeoffs documented

**Research date:** 2026-03-24
**Valid until:** 2026-04-24 (stable codebase; pom.xml and migration files are stable)
