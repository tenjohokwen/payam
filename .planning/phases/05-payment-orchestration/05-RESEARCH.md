# Phase 5: Payment Orchestration - Research

**Researched:** 2026-03-24
**Domain:** Payment routing, circuit breakers, error normalization, Quartz scheduling
**Confidence:** HIGH — all findings verified from codebase directly; no external research required for this phase

---

## Summary

Phase 5 wires together the two provider adapters (Phases 3 and 4) behind a single `POST /v1/payments`
endpoint. The `PaymentOrchestrator` component resolves which provider to call (by MSISDN prefix), dispatches
to the appropriate `MobileMoneyPort` implementation, handles `CallNotPermittedException` from Resilience4j,
normalises provider-specific exceptions into Payam's error vocabulary, and logs all transitions via
`EventLogService`.

The codebase is fully ready for Phase 5. No new libraries are required. Both provider ports implement the
same `MobileMoneyPort` interface and produce `ProviderResult`. The circuit breakers are already configured in
`application.yaml` for both providers. The Quartz schedulers are already running per-provider pollers; Phase 5
adds no new poller — the existing `OrangeStatusPollerJob` and `MtnStatusPollerJob` already handle polling.
The only new Quartz work in Phase 5 would be adding a per-transaction timeout scheduler (optional) rather
than the per-provider periodic scanner already in place.

The security context is ready: `POST /v1/payments` falls under `/v1/**` (excluding `/v1/account/**`), so it
is automatically intercepted by `TenantSecurityConfig` (`@Order(1)`) and requires a valid `X-Api-Key`. The
`TenantPrincipal` extracted by `ApiKeyAuthenticationFilter` carries both `tenantRef` and `tenantId` — these
are all the fields the orchestrator needs.

**Primary recommendation:** `PaymentOrchestrator` is a `@Service` that accepts `MobileMoneyPort` beans by
name injection (or by a `Map<MobilePaymentProvider, MobileMoneyPort>`), resolves provider from MSISDN prefix,
builds a `PaymentCommand`, calls `port.initiateMerchantPayment()`, and translates the three possible
outcomes (success, pending, error) into a `PaymentResponse` DTO. A `PaymentResource` (`@RestController`)
wraps the orchestrator and handles idempotency at the HTTP layer.

---

## Standard Stack

All required components are already in the codebase. No new Maven dependencies are needed for Phase 5.

### Core (already present)
| Component | Location | Purpose |
|-----------|----------|---------|
| `MobileMoneyPort` | `common/payment/MobileMoneyPort.java` | Interface both adapters implement |
| `OrangeMoneyPort` | `orange/service/OrangeMoneyPort.java` | Orange adapter — call via `initiateMerchantPayment(cmd)` |
| `MtnMoMoPort` | `mtn/service/MtnMoMoPort.java` | MTN adapter — call via `initiateMerchantPayment(cmd)` |
| `PaymentCommand` | `common/payment/PaymentCommand.java` | Command record passed to ports |
| `ProviderResult` | `common/payment/ProviderResult.java` | Result record returned by ports |
| `TransactionService` | `transaction/service/TransactionService.java` | Creates INITIATED row; call before port |
| `IdempotencyService` | `transaction/service/IdempotencyService.java` | Redis+PG duplicate guard |
| `EventLogService` | `transaction/service/EventLogService.java` | Appends hash-chained event |
| `TransactionRepository` | `transaction/repo/TransactionRepository.java` | Persist status transitions |
| `MobilePaymentProvider` | `common/payment/MobilePaymentProvider.java` | MTN / ORANGE / NEXTTEL enum |
| Resilience4j circuit breakers | `application.yaml` resilience4j section | Already configured for "orange" and "mtn" instances |
| `TenantPrincipal` | `tenant/contract/TenantPrincipal.java` | tenantId available from security context |
| `ApiKeyAuthenticationFilter` | `tenant/config/ApiKeyAuthenticationFilter.java` | Already gates /v1/** except /v1/account/** |
| `AppEndpoints` | `security/config/AppEndpoints.java` | PUBLIC_ENDPOINTS list to add nothing; POST /v1/payments is secured by default |

### Supporting — test layer (already present)
| Component | Purpose |
|-----------|---------|
| `TestConfig` | PostgreSQL + Redis Testcontainers; WireMock via `@EnableWireMock` |
| `TransactionTemplate` | Used in @BeforeEach / @AfterEach for FK-safe cleanup |
| `wiremock-spring-boot` 4.0.9 | WireMock for provider HTTP stubs |

### Nothing new to install
```bash
# No new dependencies for Phase 5
# spring-cloud-starter-circuitbreaker-resilience4j already present
# spring-boot-starter-quartz already present
# spring-boot-starter-data-redis already present
```

---

## Architecture Patterns

### Recommended Project Structure for Phase 5

Phase 5 creates a new `payment` module at the top level:

```
src/main/java/com/softropic/payam/
├── payment/                          # NEW — Phase 5 module
│   ├── api/
│   │   └── PaymentResource.java      # POST /v1/payments controller
│   ├── contract/
│   │   ├── PaymentRequest.java       # Request DTO (msisdn, amount, currency, externalRef, idempotencyKey)
│   │   └── PaymentResponse.java      # Response DTO (transactionId, status, providerRef)
│   └── service/
│       ├── PaymentOrchestrator.java  # Core routing + dispatch service
│       └── MsisdnRouter.java         # MSISDN prefix → MobilePaymentProvider resolver
├── common/payment/                   # EXISTING — no changes needed
├── transaction/                      # EXISTING — used as-is
├── orange/                           # EXISTING — used as-is
└── mtn/                              # EXISTING — used as-is
```

### Pattern 1: MSISDN Prefix Routing

**What:** Resolve the provider from the MSISDN's national subscriber number prefix
**When to use:** Called once per payment initiation before any provider call

MSISDN format in codebase: E.164 (e.g., `+237692954629`)
- Strip country code `+237` or `237` to get national number
- National number starts with `6` for Cameroonian mobile numbers
- After the leading `6`:
  - `65X`, `69X` prefixes → Orange Money (`6[5/9]` notation from SC-1)
  - `6X` where X is NOT `5` or `9` (i.e., `60`, `61`, `62`, `63`, `64`, `66`, `67`, `68`) → MTN

**Example:**
```java
// Source: success criteria SC-1 from ROADMAP.md Phase 5
// "6X→MTN, 6[5/9]→Orange"
// Where "6[5/9]" means: national number starting with 65 or 69 = Orange
public MobilePaymentProvider resolve(String msisdn) {
    // Strip to national: "+237692954629" -> "692954629"
    String national = msisdn.replaceFirst("^\\+?237", "");
    if (national.startsWith("65") || national.startsWith("69")) {
        return MobilePaymentProvider.ORANGE;
    }
    // All other 6X prefixes are MTN for Cameroon
    return MobilePaymentProvider.MTN;
}
```

### Pattern 2: PaymentOrchestrator Dispatch Flow

**What:** The core service coordinating transaction creation, idempotency, port dispatch, and state logging
**When to use:** Called by `PaymentResource` on every `POST /v1/payments`

Critical sequencing from Phase 2 design (P1.1): Transaction row committed BEFORE provider call, in a separate
`@Transactional` boundary. The port methods are NOT `@Transactional` — they run after the DB commit.

```java
// Source: TransactionService.initiate() pattern (Phase 2), MtnMoMoPort/OrangeMoneyPort pattern (Phase 3/4)
// Orchestrator MUST follow this sequence:
//
// 1. idempotencyService.checkAndReserve(tenantId, idempotencyKey)
//    -> if present: return cached response (no provider call)
//
// 2. transactionService.initiate(tenantId, provider, amount, currency, externalRef)
//    -> commits INITIATED row; returns Transaction with transactionId + traceId
//    -> this @Transactional call MUST commit before step 3
//
// 3. Build PaymentCommand from Transaction + request fields
//
// 4. port.initiateMerchantPayment(cmd)
//    -> called OUTSIDE any @Transactional — P1.1
//    -> returns ProviderResult(providerRef, rawStatus, pending, errorCode, errorMessage)
//    -> may throw: SubscriberInactiveException, MtnAccountInactiveException,
//                  CallNotPermittedException (circuit open), HttpClientException (provider error)
//
// 5. idempotencyService.store(tenantId, idempotencyKey, 202, responseJson)
//
// 6. Return PaymentResponse to controller
```

### Pattern 3: Circuit Breaker Exception Handling

**What:** Handle `CallNotPermittedException` (circuit open) from Resilience4j
**Critical prior decision (03-02):** NO `fallbackMethod` on `@CircuitBreaker` — fallback fires for ALL
exceptions, not just circuit-open. Provider domain exceptions must propagate cleanly.

The circuit breakers are on the Port methods, not on the Orchestrator. The Orchestrator catches exceptions
at the orchestration level:

```java
// Source: State.md 03-02 decision + MtnMoMoPort/OrangeMoneyPort comments
// Catch hierarchy for PaymentOrchestrator:

try {
    ProviderResult result = port.initiateMerchantPayment(cmd);
    // ... handle result
} catch (CallNotPermittedException e) {
    // Circuit is OPEN — provider unavailable, fail fast
    // Map to: PROVIDER_UNAVAILABLE error code
    // Apply tx.applyTransition(FAILED) + eventLogService.append(...)
} catch (SubscriberInactiveException | MtnAccountInactiveException e) {
    // Domain validation failure — subscriber not active
    // Map to: SUBSCRIBER_INACTIVE error code
    // Apply tx.applyTransition(FAILED) + eventLogService.append(...)
} catch (HttpClientException e) {
    // Provider returned 4xx/5xx (via RestRequestInterceptor)
    // Map to: PROVIDER_ERROR with e.getHttpStatusCode()
    // Apply tx.applyTransition(FAILED) + eventLogService.append(...)
} catch (Exception e) {
    // Unexpected — map to: INTERNAL_ERROR
    // Apply tx.applyTransition(FAILED) + eventLogService.append(...)
}
```

### Pattern 4: Standardized Error Codes

**What:** Translate provider-specific exceptions/codes into Payam's error vocabulary
**Where to add:** `common/exception/` package — extend `PaymentError` enum or add new `OrchestratorError` enum

Current `PaymentError` enum has generic entries. Phase 5 needs specific codes:
```java
// Source: PaymentError.java in common/exception/ — extend this enum
// Suggested new codes for Phase 5:
PROVIDER_UNAVAILABLE,       // CallNotPermittedException
SUBSCRIBER_INACTIVE,        // SubscriberInactiveException / MtnAccountInactiveException
PROVIDER_ERROR,             // HttpClientException (4xx/5xx from provider)
UNKNOWN_MSISDN_PREFIX,      // MSISDN not matching any known prefix
PAYMENT_ALREADY_PROCESSING, // Duplicate in-flight idempotency (RESERVED status)
```

### Pattern 5: Controller Layer

**What:** `PaymentResource` @RestController — handles HTTP concern only, delegates to orchestrator

```java
// Source: TenantAdminResource pattern + MtnCallbackController pattern
@RestController
public class PaymentResource {

    private final PaymentOrchestrator orchestrator;

    @PostMapping("/v1/payments")
    public ResponseEntity<PaymentResponse> initiatePayment(
            @RequestBody @Valid PaymentRequest request,
            @AuthenticationPrincipal TenantPrincipal principal) {
        // tenantId from principal — NOT from request body (security)
        PaymentResponse response = orchestrator.initiate(principal.getTenantId(), request);
        return ResponseEntity.accepted().body(response);
    }
}
```

**HTTP status:** 202 Accepted (payment is async — PROCESSING state after successful initiation).
The idempotency cached response should also store 202.

### Pattern 6: Port Bean Selection

**What:** How the Orchestrator selects which `MobileMoneyPort` bean to call
**Use:** Spring qualifier-based injection or a `Map<MobilePaymentProvider, MobileMoneyPort>`

Option A (recommended for clarity): inject both ports by type with `@Qualifier`:
```java
// Source: both ports are @Service beans, Spring injects by type
// OrangeMoneyPort is the only MobileMoneyPort bean from the orange package
// MtnMoMoPort is the only MobileMoneyPort bean from the mtn package
// Since two beans implement MobileMoneyPort, use explicit wiring:
@Service
public class PaymentOrchestrator {
    private final OrangeMoneyPort orangePort;  // explicit type reference
    private final MtnMoMoPort mtnPort;          // explicit type reference
    // ...
    private MobileMoneyPort resolvePort(MobilePaymentProvider provider) {
        return switch (provider) {
            case ORANGE -> orangePort;
            case MTN    -> mtnPort;
            case NEXTTEL -> throw new UnsupportedOperationException("NEXTTEL not yet supported");
        };
    }
}
```

### Anti-Patterns to Avoid

- **Do NOT put @Transactional on the orchestrator dispatch method**: Port calls happen after DB commit; wrapping in a transaction would re-open the connection and hold it during outbound HTTP calls (P8.1 / P1.1 violation).
- **Do NOT add a fallbackMethod to @CircuitBreaker on ports**: Prior decision 03-02 established this swallows domain exceptions. Leave ports as-is.
- **Do NOT call OrangeWebhookPayload.getCreatetime() directly**: Phase 5 does not process webhooks, but if any Orange timestamp is needed, always use `getCreatetimeAsInstant()` (P5.1 WAT concern).
- **Do NOT check idempotency in the same @Transactional as transaction creation**: `IdempotencyService.checkAndReserve()` uses Redis setIfAbsent; if Redis is unavailable it falls back to a PostgreSQL read. Keep the idempotency check before the `transactionService.initiate()` call (separate concern).

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Circuit breaking | Custom error counter + flag | Resilience4j `@CircuitBreaker(name="orange")` | Already on ports; catches CallNotPermittedException at orchestrator |
| Idempotency | Custom Map or DB check | `IdempotencyService.checkAndReserve()` | Redis+PG fallback, 24h TTL, thread-safe atomic reservation already implemented |
| Provider status polling | New scheduler | `OrangeStatusPollerJob` + `MtnStatusPollerJob` | Both already scan PROCESSING transactions periodically; Phase 5 does not need a new poller |
| MSISDN validation | External library call | Simple prefix check on national number | The routing decision (6[5/9] = Orange, else MTN) is a domain rule, not a phone validity check |
| Transaction state transition | Direct field set | `tx.applyTransition(next)` | The only mutation point; bypassing it skips the state machine guard → `IllegalStateTransitionException` |
| Event logging | Direct save to PaymentEventLog | `EventLogService.append()` | Maintains hash chain integrity; direct saves break the SHA-256 chain |
| Tenant identity | Parse from request body | `@AuthenticationPrincipal TenantPrincipal` | ApiKeyAuthenticationFilter already populated SecurityContext; use `principal.getTenantId()` |

---

## Common Pitfalls

### Pitfall 1: @Transactional on the Full Dispatch Flow

**What goes wrong:** Wrapping `PaymentOrchestrator.initiate()` in `@Transactional` holds a DB connection open
during the outbound provider HTTP call (which can take 5–30 seconds). Under load, the HikariCP pool (size 25)
exhausts. This is P8.1 in the project's known concerns.

**Why it happens:** Developers see `@Transactional` on `TransactionService.initiate()` and follow the pattern
for the enclosing method.

**How to avoid:** `TransactionService.initiate()` commits the INITIATED row in its own `@Transactional`
boundary. The orchestrator calls it as a plain method call, then calls the port OUTSIDE any transaction. The
orchestrator method itself should NOT be annotated `@Transactional`.

**Warning signs:** DB connections near pool maximum during load tests; provider call latency correlates with
connection timeouts.

### Pitfall 2: Catching Wrong Exception Type from RestRequestInterceptor

**What goes wrong:** Code catches `org.springframework.web.client.HttpClientErrorException` to detect 404
or 422 from providers. The `RestRequestInterceptor` converts ALL 4xx/5xx responses to `HttpClientException`
BEFORE Spring's error handler fires. `HttpClientErrorException` is never thrown for provider calls.

**Why it happens:** Prior decision 04-02 documents this for `MtnMoMoClient.validateAccountHolder()`. New
orchestrator error handling might repeat the mistake.

**How to avoid:** Catch `HttpClientException` (from `common.client.exception`) and inspect
`e.getHttpStatusCode()` for specific status codes.

**Warning signs:** `catch (HttpClientErrorException.NotFound e)` block never executes in tests even when
WireMock returns 404.

### Pitfall 3: MSISDN Prefix Table Out of Sync

**What goes wrong:** New Cameroonian prefixes assigned by ARTEL after coding. Currently `65X` and `69X` are
Orange. Future allocations may add new Orange or MTN prefixes.

**Why it happens:** Prefix routing hardcoded in a switch or constant.

**How to avoid:** Implement `MsisdnRouter` as a service with a config-driven prefix table (configurable via
application.yaml), not a hardcoded switch. This allows adding prefixes without redeployment.

**Warning signs:** Payments to valid Orange numbers return "unknown prefix" errors.

### Pitfall 4: CallNotPermittedException Swallowed by Generic Exception Handler

**What goes wrong:** The orchestrator's generic `catch (Exception e)` block handles `CallNotPermittedException`
before the specific circuit-breaker catch, mapping it to a generic 500 instead of a 503/provider-unavailable.

**Why it happens:** Exception ordering in try-catch — most specific must come first.

**How to avoid:** Always catch `CallNotPermittedException` before `HttpClientException` before `Exception`.
`CallNotPermittedException` is from `io.github.resilience4j.circuitbreaker`.

### Pitfall 5: Idempotency Key Stored Before Transaction Initiation

**What goes wrong:** `IdempotencyService.store()` is called before `transactionService.initiate()` succeeds,
storing a stale/incomplete response that clients will receive as a duplicate response.

**How to avoid:** Store idempotency response AFTER successful provider dispatch and status determination.
Sequence: checkAndReserve → initiate → dispatch → store.

### Pitfall 6: Double-Counting State Transitions

**What goes wrong:** The orchestrator applies `INITIATED → PROCESSING` transition via `applyTransition()`,
but `OrangeMoneyPort.initiateMerchantPayment()` also calls `eventLogService.append()` for
`INITIATED → PROCESSING`. This creates a duplicate event log entry and potentially hits an
`IllegalStateTransitionException` on the second transition attempt.

**How to avoid:** The Port's `eventLogService.append()` call logs the event but does NOT call
`tx.applyTransition()`. The actual state field on `Transaction` is updated by whoever holds the Transaction
entity. Review what Phase 3/4 ports actually update: they call `eventLogService.append()` but the
`TransactionService.initiate()` creates the entity in INITIATED state. The orchestrator's job is to update
status after the port returns. Check OrangeMoneyPort/MtnMoMoPort: they do NOT call `tx.applyTransition()`.
The Orchestrator must do that after `initiateMerchantPayment()` returns — call
`tx.applyTransition(PROCESSING)` when `result.pending() == true`.

**Warning signs:** `IllegalStateTransitionException: Invalid state transition: PROCESSING → PROCESSING` in logs.

---

## Code Examples

### MSISDN Prefix Resolver

```java
// Source: Success Criteria SC-1 from ROADMAP.md Phase 5; stripCountryCode pattern from OrangeMoneyPort
// "+237692954629" → ORANGE (starts with 69)
// "+237675000000" → MTN (starts with 67, not 65/69)
// "+237650000000" → ORANGE (starts with 65)
public MobilePaymentProvider resolve(String msisdn) {
    if (msisdn == null || msisdn.isBlank()) {
        throw new IllegalArgumentException("MSISDN must not be blank");
    }
    String national = msisdn.replaceFirst("^\\+?237", "");
    if (national.startsWith("65") || national.startsWith("69")) {
        return MobilePaymentProvider.ORANGE;
    }
    if (national.startsWith("6")) {
        return MobilePaymentProvider.MTN;
    }
    throw new UnknownMsisdnPrefixException(msisdn);
}
```

### Orchestrator State Transition after Port Returns

```java
// Source: TransactionRepository.findByTransactionIdForUpdate (Phase 2 PESSIMISTIC_WRITE pattern)
// After port.initiateMerchantPayment(cmd) returns:
if (result.pending()) {
    // Port initiated successfully — transaction is now PROCESSING
    // Must use PESSIMISTIC_WRITE lock for state transition (P1.2 pattern from Phase 2)
    Transaction locked = transactionRepository.findByTransactionIdForUpdate(txId).orElseThrow();
    locked.applyTransition(TransactionStatus.PROCESSING);
    // eventLogService.append for orchestrator-level event (optional — port already logged PAYMENT_INITIATED)
} else if (result.errorCode() != null) {
    // Port returned a failure result
    Transaction locked = transactionRepository.findByTransactionIdForUpdate(txId).orElseThrow();
    locked.applyTransition(TransactionStatus.FAILED);
    eventLogService.append(txId, traceId, externalRef,
        TransactionEventType.PROVIDER_FAILED,
        TransactionStatus.INITIATED, TransactionStatus.FAILED,
        "ORCHESTRATOR", result.errorCode());
}
```

### Security Principal Extraction in Controller

```java
// Source: ApiKeyAuthenticationFilter.java — TenantPrincipal is set as Authentication principal
// TenantSecurityConfig securityMatcher includes /v1/payments (it matches /v1/** and is NOT /v1/account/**)
@PostMapping("/v1/payments")
public ResponseEntity<PaymentResponse> initiate(
        @RequestBody @Valid PaymentRequest request,
        @AuthenticationPrincipal TenantPrincipal principal) {
    Long tenantId = principal.getTenantId();   // safe — filter has already validated the API key
    // ...
}
```

### IT Test Setup Pattern (matches existing tests)

```java
// Source: MtnMoMoPortIT.java BeforeEach/AfterEach pattern
@BeforeEach
void setUp() {
    // 1. Seed JWT secret (required by SecurityAdviceFilter on every request)
    transactionTemplate.execute(status -> {
        jdbcTemplate.execute("INSERT INTO main.sec ... ON CONFLICT DO NOTHING");
        return null;
    });
    // 2. Create test tenant + API key
    var provision = tenantService.createTenant("payments-test-tenant", "LIVE");
    tenantId = provision.tenant().getId();
    apiKey = provision.rawApiKey();   // raw key for X-Api-Key header
}

@AfterEach
void tearDown() {
    // FK-safe order: payment_event_log → transaction → tenant_api_key → tenant → sec
    transactionTemplate.execute(status -> {
        jdbcTemplate.execute("DELETE FROM main.payment_event_log");
        jdbcTemplate.execute("DELETE FROM main.transaction");
        jdbcTemplate.execute("DELETE FROM main.tenant_api_key");
        jdbcTemplate.execute("DELETE FROM main.tenant");
        jdbcTemplate.execute("DELETE FROM main.sec");
        return null;
    });
}
```

---

## State of the Art (this codebase)

| Old Approach | Current Approach | Impact for Phase 5 |
|--------------|------------------|--------------------|
| Provider-specific error types bubble to API | Translate to Payam error vocabulary in Orchestrator | Add `PaymentError` enum entries for new codes |
| Per-provider polling jobs only | Per-provider polling already running via Quartz | No new poller needed in Phase 5 |
| Transactions hold DB connection during HTTP | Separate @Transactional boundary before provider call | Orchestrator must NOT be @Transactional during dispatch |

**Deprecated/outdated:**
- `PaymentError` entries like `PAY_CART_TOTAL_UPDATE_EVENT_HANDLER_FAILED`: legacy pre-transaction-core errors; do not model Phase 5 errors in this style. Add a clear `OrchestratorError` enum if the planner decides PaymentError is too crowded.

---

## Open Questions

1. **P8.1 WebClient requirement vs. existing RestTemplate**
   - What we know: ROADMAP SC-2 says "All provider HTTP calls are non-blocking (WebClient) — no database connections held during provider wait". The existing provider ports use a synchronous `RestTemplate`-based `AbstractClient`. Port methods are intentionally not `@Transactional`.
   - What's unclear: Does SC-2 require a full port rewrite to WebClient, or is the existing approach of "not @Transactional on port = DB connection released before provider call" sufficient for Phase 5?
   - Recommendation: The existing synchronous pattern already releases the DB connection before the HTTP call (because port methods are not @Transactional and HikariCP auto-commit=false returns the connection after the @Transactional boundary). "Non-blocking" in SC-2 likely means "don't hold a DB transaction open", which the current design already satisfies. Plan 05-01 should document this as a verified fulfilment of SC-2 rather than triggering a WebClient rewrite. Mark as LOW confidence — planner should confirm with project owner.

2. **PayTokenExpiredException re-initiation (ROADMAP SC-4)**
   - What we know: `OrangeStatusPollerJob` skips polling for expired payToken and logs that "Phase 5 orchestrator must re-initiate". `PayTokenExpiredException` is not re-thrown from `pollTransaction()` (decision 03-04).
   - What's unclear: Should Phase 5's Orchestrator also add a mechanism to detect stale PROCESSING Orange transactions with expired payTokens and re-initiate them? Or is this a Phase 6 concern?
   - Recommendation: Phase 5 Plan 05-01 should include a note that `PayTokenExpiredException` re-initiation is listed as ROADMAP SC-4 but is NOT part of the basic orchestration MVP. The poller already handles the skip gracefully. This can be a follow-on task within Phase 5 or deferred to Phase 6.

3. **NEXTTEL provider**
   - What we know: `MobilePaymentProvider` enum has `NEXTTEL`. No NEXTTEL port implementation exists.
   - What's unclear: Should Phase 5's MSISDN router throw `UnsupportedOperationException` for NEXTTEL or silently ignore it?
   - Recommendation: Throw a domain exception (`UnsupportedOperationException` or new `UnsupportedProviderException`) and map it to a 422 at the controller level. Do not silently route NEXTTEL to either provider.

---

## Sources

### Primary (HIGH confidence)
- `src/main/java/com/softropic/payam/common/payment/` — all shared contracts (MobileMoneyPort, PaymentCommand, ProviderResult, MobilePaymentProvider, SubscriberStatus) verified by direct read
- `src/main/java/com/softropic/payam/orange/service/OrangeMoneyPort.java` — port implementation, exception handling, event log calls, @CircuitBreaker without fallback
- `src/main/java/com/softropic/payam/mtn/service/MtnMoMoPort.java` — port implementation, @CircuitBreaker without fallback, comment "Phase 5 handles circuit state at orchestration level"
- `src/main/java/com/softropic/payam/transaction/service/TransactionService.java` — initiate() @Transactional boundary, field list
- `src/main/java/com/softropic/payam/transaction/service/IdempotencyService.java` — checkAndReserve/store sequence, RESERVED sentinel
- `src/main/java/com/softropic/payam/transaction/repo/TransactionRepository.java` — findByTransactionIdForUpdate (PESSIMISTIC_WRITE)
- `src/main/java/com/softropic/payam/transaction/contract/TransactionStatus.java` — full state machine transitions verified
- `src/main/resources/application.yaml` — Resilience4j circuit breaker instances "orange" and "mtn" config confirmed; ignoreExceptions list confirmed
- `src/main/java/com/softropic/payam/tenant/config/TenantSecurityConfig.java` — /v1/** (excluding /v1/account/**) already guarded by API key
- `src/main/java/com/softropic/payam/security/config/AppEndpoints.java` — PUBLIC_ENDPOINTS confirmed (POST /v1/payments not in list = secured)
- `src/main/java/com/softropic/payam/common/client/RestRequestInterceptor.java` — ALL 4xx/5xx converted to HttpClientException before Spring error handling fires
- `.planning/STATE.md` — accumulated decisions 01-xx through 04-xx reviewed
- `.planning/ROADMAP.md` — Phase 5 success criteria SC-1 through SC-5 reviewed

### Secondary (MEDIUM confidence)
- N/A — all findings from direct codebase inspection

### Tertiary (LOW confidence)
- SC-2 interpretation (WebClient vs. non-@Transactional synchronous) — planner should confirm intent

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all present in codebase, verified by file read
- MSISDN routing rules: HIGH — success criteria explicitly state "6X→MTN, 6[5/9]→Orange"
- Circuit breaker pattern: HIGH — prior decisions 03-02 document exactly how to handle CallNotPermittedException
- State transition sequence: HIGH — P1.1 pattern confirmed in port source code comments
- WebClient requirement interpretation: LOW — SC-2 intent is ambiguous; recommend confirming with owner

**Research date:** 2026-03-24
**Valid until:** 2026-04-24 (stable codebase; decisions unlikely to change)
