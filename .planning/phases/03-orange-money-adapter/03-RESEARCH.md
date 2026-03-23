# Phase 3: Orange Money Adapter — Research

**Researched:** 2026-03-24
**Domain:** Orange Money CM local/USSD API — merchant payment, cashout, C2C, subscriber validation, webhook
**Confidence:** MEDIUM (Orange partner docs are behind a login wall; findings assembled from official SDK repos, community integrations, and codebase pitfalls doc)

---

## Summary

Orange Money Cameroon exposes a country-specific REST API at `https://api-s1.orange.cm/omcoreapis/1.0.2/` that is distinct from the Orange Developer web payment portal (`api.orange.com`). The local/USSD API is the one referred to throughout the existing codebase pitfall notes and is the API this phase implements. Authentication uses OAuth2 client credentials (Basic Auth to get a Bearer token, Bearer token on all subsequent calls). The token has a 60-minute TTL; the payToken returned by `/mp/init` has a ~10-minute TTL.

The standard pattern for implementing a provider adapter in this codebase is to add a new top-level Spring Modulith module (`orange/`) that follows the same `contract / service / repo / infrastructure / config` layering already established. The adapter's outbound HTTP client should reuse the existing `AbstractClient` / `ClientConfiguration` infrastructure already present in `common/client/`. A `MobileMoneyPort` interface (Strategy pattern) should live in `common/payment/` so Phase 5 (orchestration) can select providers at runtime without knowing implementation details.

The two hardest problems are: (1) payToken TTL expiry between init and pay — which requires treating expired-INIT transactions differently from in-flight-PENDING ones (P1.3/P2.2 from PITFALLS.md); and (2) Orange `createtime` timestamps arriving without timezone decoration and requiring explicit `ZoneId.of("Africa/Douala")` interpretation (P5.1). Both must be handled at the adapter level, not deferred upstream.

**Primary recommendation:** Implement the adapter as `com.softropic.payam.orange` module with `OrangeMoneyPort` implementing a common `MobileMoneyPort` interface. The adapter owns init, pay, status poll, subscriber validation, cashout, and C2C; the webhook receiver is deferred to Phase 6 but the adapter must expose a `processWebhook(payload)` hook now so Phase 6 can wire it in.

---

## Standard Stack

The codebase already contains all required dependencies. No new Maven dependencies are needed for the adapter itself.

### Core (already in pom.xml)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `spring-boot-starter-web` | 3.5.11 | RestTemplate / HTTP client base | Already used by `AbstractClient` |
| `spring-cloud-starter-circuitbreaker-resilience4j` | managed by Spring Cloud 2025.0.1 | Circuit breaker on provider calls | Already in pom.xml; used by email module |
| `spring-retry` | managed | Retry on transient errors | Already in pom.xml |
| `spring-boot-starter-quartz` | managed | Polling scheduler for stuck transactions | Listed in prior decisions as already approved |
| `commons-codec` | 1.19.0 | HMAC-SHA256 for webhook verification | Already in pom.xml |
| `spring-boot-starter-data-redis` | managed | idempotency/payToken TTL tracking | Already in pom.xml (Redis configured) |

### Testing (already in pom.xml or standard)
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `wiremock-spring-boot` | 4.0.9 | Mock Orange HTTP calls in IT | All adapter integration tests |
| Testcontainers PostgreSQL + Redis | managed | Real DB/Redis in tests | IT tests only |
| Awaitility | 4.2.0 | Assert async polling behavior | Polling job tests |

**Installation for WireMock (test scope only):**
```xml
<dependency>
    <groupId>org.wiremock.integrations</groupId>
    <artifactId>wiremock-spring-boot</artifactId>
    <version>4.0.9</version>
    <scope>test</scope>
</dependency>
```

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `AbstractClient` + `RestTemplate` | `WebClient` (reactive) | WebClient is non-blocking but the rest of the codebase is MVC/blocking; stay consistent with existing `AbstractClient` pattern |
| Quartz for polling | `@Scheduled` | `@Scheduled` is in-memory and lost on restart; Quartz with JDBC store survives restarts and avoids duplicate job firing in multi-node |
| Custom HMAC util | Spring Security `HMacUtils` | Both acceptable; `commons-codec` `HmacUtils` is already on the classpath |

---

## Architecture Patterns

### Recommended Module Structure
```
com.softropic.payam.orange/
├── contract/
│   ├── OrangeTransactionType.java      # MP, CASHOUT, C2C, IC2C enum
│   ├── OrangeStatus.java               # SUCCESSFULL, FAILED, PENDING, INITIATED, EXPIRED
│   ├── OrangeWebhookPayload.java       # Inbound webhook DTO
│   ├── dto/
│   │   ├── SubscriberInfoResponse.java
│   │   ├── MerchantInfoResponse.java   # Contains payToken
│   │   ├── PayRequest.java
│   │   ├── PayResponse.java
│   │   ├── CashoutRequest.java
│   │   └── C2CRequest.java
│   └── exception/
│       ├── OrangeApiException.java
│       └── PayTokenExpiredException.java
├── service/
│   ├── OrangeMoneyPort.java            # implements MobileMoneyPort (in common/payment/)
│   └── OrangeTokenService.java         # Manages OAuth2 token lifecycle (60-min TTL)
├── infrastructure/
│   └── OrangeMoneyClient.java          # extends AbstractClient; calls Orange endpoints
├── config/
│   └── OrangeMoneyConfig.java          # @ConfigurationProperties binding for client.*. orange.*
└── OrangeModule.java                   # @ApplicationModule for Spring Modulith boundary
```

The `MobileMoneyPort` interface lives in `common/payment/` and is the contract that Phase 5 depends on:
```java
// common/payment/MobileMoneyPort.java
public interface MobileMoneyPort {
    ProviderResult initiateMerchantPayment(PaymentCommand cmd);
    ProviderResult getTransactionStatus(String providerRef);
    SubscriberStatus validateSubscriber(String msisdn);
}
```

### Pattern 1: Two-Phase Init + Pay with payToken Refresh

Orange Merchant Payment requires two calls. The payToken from `/mp/init` must be used in `/mp/pay` within ~10 minutes. Model this as an internal saga:

**State tracking:**
```java
// Store on the Transaction entity's providerMetadata (JSONB column or extra fields)
// Fields: payToken, payTokenIssuedAt, orangeStep (INIT_OBTAINED, PAY_SENT)
```

**Flow:**
```
1. transactionRepo.save(INITIATED) — commit BEFORE any outbound call (P1.1)
2. GET /infos/subscriber?msisdn=... — validate subscriber active
3. GET /infos/merchant — get payToken, record payTokenIssuedAt
4. POST /mp/pay — submit payment, record step=PAY_SENT
5. Wait for webhook (Phase 6) OR poll GET /mp/paymentstatus/{payToken}
```

**payToken expiry detection:**
- If `/mp/pay` returns HTTP 4xx with an error indicating invalid/expired token, check age: `Duration.between(payTokenIssuedAt, now) > 8 minutes` → throw `PayTokenExpiredException`
- On `PayTokenExpiredException`: mark transaction FAILED, expire idempotency key immediately (allows fresh retry)
- Do NOT treat payToken expiry as an idempotency collision

**Code pattern:**
```java
// Source: PITFALLS.md P1.3 / P2.2 + NdoleStudio orangemoney-go SDK endpoints
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void saveInitiated(String txId) { /* commit row first */ }

public ProviderResult sendToOrange(String txId, PaymentCommand cmd) {
    // No @Transactional — row already committed above
    SubscriberInfoResponse sub = orangeClient.getSubscriberInfo(cmd.getMsisdn());
    if (!sub.isActive()) throw new SubscriberInactiveException();

    MerchantInfoResponse merchant = orangeClient.getMerchantInfo();
    String payToken = merchant.getPayToken();
    Instant tokenIssuedAt = Instant.now();
    // persist payToken + tokenIssuedAt to transaction record (non-transactional update)

    PayResponse payResponse = orangeClient.pay(buildPayRequest(cmd, payToken));
    // persist PAY_SENT step
    return toProviderResult(payResponse);
}
```

### Pattern 2: Status Polling with Quartz + Exponential Backoff

Orange does not always deliver webhooks promptly. A Quartz-persisted job polls transactions stuck in `PROCESSING` state:

```yaml
# application.yaml
spring:
  quartz:
    job-store-type: jdbc
    jdbc:
      initialize-schema: always
    properties:
      org.quartz.threadPool.threadCount: 3
```

```java
// Source: Spring Boot Quartz docs (docs.spring.io)
public class OrangeStatusPollerJob extends QuartzJobBean {
    @Override
    protected void executeInternal(JobExecutionContext ctx) {
        // Query transactions: status=PROCESSING, provider=ORANGE,
        //   updatedAt < now() - 2min, pollAttempts < 15
        // For each: GET /mp/paymentstatus/{payToken}
        // On SUCCESSFULL/FAILED: applyTransition(), expire idempotency key
        // On PENDING: increment pollAttempts, reschedule
        // On pollAttempts >= 15: applyTransition(TIMED_OUT)
    }
}
```

**Polling schedule:** 2 min, 5 min, 10 min, 10 min... (exponential, then fixed). Max 15 attempts (~2 hours total). Use `@Lock(LockModeType.PESSIMISTIC_WRITE)` when reading-and-updating the transaction to avoid race with webhook (P1.2).

### Pattern 3: OAuth2 Token Caching

Orange access tokens last 60 minutes. Cache in Redis with a TTL of 55 minutes (5-minute safety margin):

```java
// Source: pathus90/om4j README + orangemoney-go SDK
public class OrangeTokenService {
    private static final String KEY = "orange:token:cm";
    private static final Duration TTL = Duration.ofMinutes(55);

    public String getAccessToken() {
        String cached = redis.opsForValue().get(KEY);
        if (cached != null) return cached;

        TokenResponse fresh = orangeClient.requestToken(consumerKey); // Basic Auth
        redis.opsForValue().set(KEY, fresh.getAccessToken(), TTL);
        return fresh.getAccessToken();
    }
}
```

### Pattern 4: Webhook Verification (for Phase 6 integration point)

Orange Money's webhook verification does NOT use standard HMAC headers. Based on field knowledge documented in PITFALLS.md (P3.2) and community research:

- Orange CM does not publish a publicly documented HMAC signature header. The `notif_token` returned in the init response is the primary correlation mechanism.
- The double-check pattern is mandatory: on webhook receipt, re-query `GET /mp/paymentstatus/{payToken}` and compare `status`, `amount`, `msisdn` fields before any state transition.
- IP allowlist (Orange CM server IPs): **must be verified with Orange partner — not publicly documented**.
- The adapter should expose: `public OrangeWebhookResult processWebhook(OrangeWebhookPayload payload, String notifToken)` so Phase 6 can call it.

**HMAC note (LOW confidence):** Some community implementations reference `X-Orange-Signature` but this is unverified for the CM local API. Do not implement HMAC validation based on this; implement the double-check pattern instead, which is verified to work.

### Anti-Patterns to Avoid

- **Wrapping init+pay in a single `@Transactional`:** The DB row MUST commit before the outbound call. If the transaction boundary includes the HTTP call, a webhook arriving milliseconds later finds no row (P1.1).
- **Treating all payToken errors as idempotency hits:** A stale payToken requires a fresh init, not a replay of the cached response. Check age before deciding.
- **`LocalDateTime.parse(orangeTimestamp)`:** Always parse Orange timestamps with `LocalDateTime.parse(ts, formatter).atZone(ZoneId.of("Africa/Douala")).toInstant()`. Never assume UTC.
- **`String.equals()` for signature comparison:** Use `MessageDigest.isEqual()` for any byte-level comparison (P3.4).

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Circuit breaking on Orange API calls | Custom retry/fallback logic | `@CircuitBreaker(name="orange")` (Resilience4j, already in pom.xml) | Already proven in email module; handles open/half-open/closed states |
| OAuth2 token refresh | Custom token renewal thread | `OrangeTokenService` + Redis TTL (see Pattern 3 above) | Simple, testable, no race condition |
| Job persistence for polling | `@Scheduled` in-memory | Quartz with `job-store-type: jdbc` | `@Scheduled` fires on every node in multi-node; Quartz JDBC prevents duplicate execution |
| Webhook payload deserialization | Manual JSON string parsing | Jackson `@JsonProperty` on DTOs + Spring MVC `@RequestBody` | Validated, type-safe, handles unknown fields with `FAIL_ON_UNKNOWN_PROPERTIES: false` |
| HMAC timing-safe comparison | `equals()` | `MessageDigest.isEqual()` | Prevents timing oracle attack (P3.4) |
| Status polling backoff | Custom `Thread.sleep` loop | Quartz trigger with misfire policy | Production-safe, survives restarts |

**Key insight:** Orange's API is simple (5 endpoints) but the surrounding state management is complex. All complexity lives in the adapter, not in new infrastructure.

---

## Common Pitfalls

### Pitfall 1: payToken Expiry Treated as Idempotency Collision (P1.3 / P2.2)
**What goes wrong:** Client sends payment request. Init succeeds, payToken stored. A GC pause delays the pay call. `/mp/pay` returns error (expired token). Client retries with same idempotency key. Adapter returns cached INIT response with dead payToken. Transaction never completes.
**Why it happens:** Idempotency logic returns cached INIT response without checking payToken age.
**How to avoid:** Store `payTokenIssuedAt` on the transaction record. When processing an idempotency replay for an INIT-status transaction, check: `if (Duration.between(payTokenIssuedAt, now) > 8 minutes) { expireIdempotencyKey(); throw PayTokenExpiredException(); }`. The calling layer then creates a fresh transaction.
**Warning signs:** IdempotencyService returning INIT-status cached responses for transactions older than 10 minutes.

### Pitfall 2: Orange `createtime` Parsed as UTC (P5.1)
**What goes wrong:** Orange returns `"createtime": "2024-01-15T10:30:00"` with no timezone offset. Code does `LocalDateTime.parse(ts)` which treats it as UTC. All timestamps are 1 hour early. Daily reconciliation misaligns the last hour of each day.
**Why it happens:** Java's `LocalDateTime.parse()` has no timezone concept.
**How to avoid:**
```java
// Source: PITFALLS.md P5.1 — confirmed Cameroon is UTC+1 (WAT, no DST)
private static final ZoneId WAT = ZoneId.of("Africa/Douala");
private static final DateTimeFormatter ORANGE_FMT =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

public Instant parseOrangeTimestamp(String createtime) {
    return LocalDateTime.parse(createtime, ORANGE_FMT)
                        .atZone(WAT)
                        .toInstant();
}
```
**Warning signs:** Any `LocalDateTime.parse(orangeTimestamp)` without `.atZone(WAT)`.

### Pitfall 3: Webhook Arrives Before DB Row Commits (P1.1)
**What goes wrong:** Orange is documented to deliver webhook within milliseconds of `/mp/pay` response. If the service method that persists the transaction AND calls the provider is wrapped in a single `@Transactional`, the DB row may not be visible to the webhook handler when it queries for the transaction.
**Why it happens:** `@Transactional` boundary holds the row in an uncommitted state during the outbound HTTP call.
**How to avoid:** Two separate methods — one `@Transactional` that only saves the row and commits, then a non-transactional method that calls the provider.
**Warning signs:** A single `@Transactional` service method containing both `repository.save()` and `orangeClient.pay()`.

### Pitfall 4: Webhook + Poller Race on Same Transaction (P1.2)
**What goes wrong:** Both the webhook handler and the Quartz polling job call provider status endpoint simultaneously. Both get SUCCESS. Both call `applyTransition(SUCCESS)`. First one succeeds; second throws `IllegalStateTransitionException` or silently emits a duplicate event.
**How to avoid:** Use `@Lock(LockModeType.PESSIMISTIC_WRITE)` on the `findByTransactionId` call inside any method that transitions state. The second concurrent attempt blocks, then sees the committed SUCCESS state and exits without re-transitioning.

### Pitfall 5: Subscriber Phone Format (LOCAL vs MSISDN)
**What goes wrong:** Orange CM endpoints expect the subscriber MSISDN without the country code prefix (e.g., `692954629` not `+237692954629` or `237692954629`). The existing `PhoneNumberUtil` normalizes to E.164 format. Passing E.164 to Orange returns a 4xx error.
**How to avoid:** Strip the country code (`237`) from the E.164 number before passing to Orange endpoints. `libphonenumber` (already on classpath) can extract the national number: `phoneNumberUtil.format(parsed, PhoneNumberFormat.NATIONAL).replaceAll("[^0-9]", "")`.
**Warning signs:** Tests that pass `+237` prefixed numbers to the Orange client mock and succeed because WireMock accepts any path.

### Pitfall 6: Token Renewal Race Condition in Multi-Node
**What goes wrong:** Two threads simultaneously detect token expiry in Redis and both call the token endpoint. Orange issues two tokens. One is stored and used; the other is wasted.
**How to avoid:** Use Redis `SET NX EX` (setIfAbsent) to acquire a soft lock before token renewal. The losing thread waits 200ms and retries the GET. The winning thread fetches and stores the token.
**Warning signs:** OrangeTokenService that uses `redis.get()` then conditionally `redis.set()` without atomicity.

---

## Code Examples

### Orange CM API — Verified Endpoint Inventory
```
# Source: NdoleStudio/orangemoney-go + community integrations (MEDIUM confidence)
# Base URLs:
#   Sandbox: https://api-s1.orange.cm/omcoreapis/1.0.2/
#   Production: Partner-provided (likely same host, different path version)

POST /token                                     → Get OAuth2 access token (Basic Auth)
GET  /infos/subscriber?msisdn={msisdn}         → Validate subscriber active/inactive
GET  /infos/merchant                            → Get payToken (10-min TTL)
POST /mp/init                                   → Initiate merchant payment (alternative init)
GET  /omcoreapis/1.0.1/mp/pay                  → Execute initiated transaction (note: v1.0.1)
GET  /mp/paymentstatus/{payToken}              → Poll transaction status
POST /cashout                                   → Cashout transaction
POST /c2c                                       → Customer-to-customer transfer
```

**Note on version mismatch:** The pay endpoint uses `1.0.1` while others use `1.0.2`. This is observed in the NdoleStudio Go SDK. Must verify with Orange partner sandbox.

### Authentication
```java
// Source: pathus90/om4j, Ibracilinks/OrangeMoney PHP (MEDIUM confidence)
// Step 1: Get token using HTTP Basic Auth with consumer key as Authorization header
// Header: Authorization: Basic {base64(consumerKey:)}  -- empty password
// Or: Authorization: Basic {base64(clientId:secretId)}
// POST /token with grant_type=client_credentials

// Step 2: All subsequent calls:
// Header: Authorization: Bearer {access_token}
// Header: Content-Type: application/json
// Header: X-AUTH-TOKEN: {consumerKey}  -- some implementations send this additionally
```

### Transaction Status Values
```java
// Source: pathus90/om4j README + NdoleStudio orangemoney-go (HIGH confidence, cross-verified)
// Orange-side status → internal TransactionStatus mapping:
// "INITIATED"   → PROCESSING (payment initiated, customer has not yet confirmed)
// "PENDING"     → PROCESSING (processing in Orange systems)
// "SUCCESSFULL" → SUCCESS    (note: Orange uses double-L spelling — this is correct)
// "FAILED"      → FAILED
// "EXPIRED"     → trigger payToken refresh if in INIT state; else FAILED
```

### Timezone Parsing
```java
// Source: PITFALLS.md P5.1 (HIGH confidence — multiple sources confirm WAT = UTC+1, no DST)
private static final ZoneId WAT = ZoneId.of("Africa/Douala");

public Instant parseOrangeTimestamp(String createtime) {
    // Orange format observed: "2024-01-15T10:30:00" (no offset, no Z)
    return LocalDateTime.parse(createtime,
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
                        .atZone(WAT)
                        .toInstant();
}
```

### Resilience4j Circuit Breaker on Orange Calls
```java
// Source: javaguides.net 2025-03 article on Spring Boot 3 + WebClient + Resilience4j
// (application.yaml)
resilience4j:
  circuitbreaker:
    instances:
      orange:
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        slidingWindowSize: 10
        permittedNumberOfCallsInHalfOpenState: 3
  retry:
    instances:
      orange:
        maxAttempts: 3
        waitDuration: 1s
        enableExponentialBackoff: true
        exponentialBackoffMultiplier: 2
        retryExceptions:
          - java.net.SocketTimeoutException
          - org.springframework.web.client.ResourceAccessException
```

```java
@CircuitBreaker(name = "orange", fallbackMethod = "orangeFallback")
@Retry(name = "orange")
public PayResponse callOrangePay(PayRequest req) {
    return orangeMoneyClient.pay(req);
}

private PayResponse orangeFallback(PayRequest req, Throwable t) {
    throw new OrangeApiException("Orange Money unavailable — circuit open", t);
}
```

### WireMock Integration Test Setup
```java
// Source: wiremock.org/docs/spring-boot/ (HIGH confidence — official docs)
@SpringBootTest
@EnableWireMock(@ConfigureWireMock(name = "orange", properties = "orange.base-url"))
class OrangeMoneyPortIT {

    @InjectWireMock("orange")
    WireMockServer orange;

    @Test
    void merchant_payment_completes_end_to_end() {
        orange.stubFor(get(urlPathEqualTo("/infos/subscriber"))
            .willReturn(okJson("{\"status\":\"ACTIF\"}")));
        orange.stubFor(get(urlPathEqualTo("/infos/merchant"))
            .willReturn(okJson("{\"payToken\":\"tok123\",\"message\":\"OK\"}")));
        // ... stub pay + status poll
    }
}
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `@Scheduled` for polling | Quartz JDBC-persisted job | Spring Boot 3 era | No duplicate job on multi-node |
| `RestTemplate` directly | `AbstractClient` wrapper + `ClientConfiguration` | Already in codebase | Interceptors, masking, timeout config centralized |
| No circuit breaker | Resilience4j `@CircuitBreaker` annotation | Already in pom.xml | Prevents cascade failure when Orange is down |
| `LocalDateTime` in payment | `Instant` (UTC) stored, WAT on parse | This phase — first time | Reconciliation accuracy |

**Deprecated/outdated:**
- `RestTemplate` (deprecated in Spring 6 / Boot 3): The codebase uses it via `AbstractClient`. This is an existing debt item. Do NOT introduce `WebClient` in this phase — stay consistent with existing `AbstractClient` pattern. Migrating to WebClient is a separate refactoring task.
- Direct `new RestTemplate()` without `BufferingClientHttpRequestFactory`: Already handled in `AbstractClient` — extend it, don't recreate.

---

## Open Questions

1. **Orange webhook HMAC header name and algorithm**
   - What we know: Orange CM local API does NOT appear to publish a standard HMAC header for the local/USSD API (distinct from Orange webpay portal). Community implementations do not reference one.
   - What's unclear: Whether the partner agreement includes a webhook secret for HMAC; what header name Orange uses if any.
   - Recommendation: Implement double-check pattern (re-query status) as primary verification. Add a placeholder for HMAC verification with a config flag `orange.webhook.hmac-secret` — if blank, skip HMAC; if set, verify. **Verify with Orange partner before go-live.**

2. **payToken TTL exact value**
   - What we know: Community implementations say "~10 minutes"; the om4j README says "The token's validity is 10 minutes." PITFALLS.md says "5–10 minutes (LOW confidence)."
   - What's unclear: Whether sandbox and production TTLs differ.
   - Recommendation: Implement threshold check at 8 minutes (conservative). Test in sandbox and document the actual observed TTL.

3. **`/mp/pay` vs `/mp/init` + `/mp/pay` distinction**
   - What we know: Two distinct flows exist in community SDKs — some show a single `/mp/pay` call with all params; others show `/mp/init` (get token) then a separate pay call.
   - What's unclear: Whether `/mp/init` is required or optional for the Cameroon local API.
   - Recommendation: Implement the two-step flow (init → pay) as described in the pitfalls doc; it is safer and matches the documented "init→pay→push" flow in the phase requirements.

4. **Orange CM IP allowlist ranges**
   - What we know: Orange partner provides IP whitelist for production.
   - What's unclear: Sandbox IP ranges for testing.
   - Recommendation: Leave IP validation as a configurable allowlist (`orange.webhook.allowed-ips`) defaulting to empty (accept-all) in test profiles. **Verify with Orange partner before enabling in production.**

5. **`/cashout` and `/c2c` endpoint exact paths and request bodies**
   - What we know: These endpoints are referenced in the phase requirements and NdoleStudio SDK mentions them by function.
   - What's unclear: Exact request body fields — not publicly documented.
   - Recommendation: Stub with best-available field mapping from community PHP/Node SDKs. Validate all fields in sandbox before go-live.

---

## Sources

### Primary (HIGH confidence)
- `com.softropic.payam` codebase — `AbstractClient`, `ClientConfiguration`, `TcpConfiguration`, `TransactionStatus`, `IdempotencyService`, `EventLogService` — direct inspection
- `.planning/research/PITFALLS.md` — project-specific pitfall analysis (P1.1–P5.1)
- `.planning/codebase/STACK.md`, `ARCHITECTURE.md`, `CONVENTIONS.md`, `INTEGRATIONS.md` — codebase analysis
- `https://docs.spring.io/spring-boot/reference/io/quartz.html` — Spring Boot Quartz auto-configuration
- `https://wiremock.org/docs/spring-boot/` — WireMock Spring Boot integration

### Secondary (MEDIUM confidence)
- `https://github.com/NdoleStudio/orangemoney-go` — Go SDK; endpoint inventory (init, pay, paymentstatus, push)
- `https://github.com/pathus90/om4j` — Java SDK; authentication, payToken lifecycle, status values
- `https://github.com/Ibracilinks/OrangeMoney/blob/master/src/Api.php` — PHP SDK; authentication pattern, basic auth header
- WebSearch finding: `api-s1.orange.cm/omcoreapis/1.0.2/mp/init` confirmed as the Cameroon sandbox base URL
- WebSearch finding: payToken TTL stated as "10 minutes" in om4j documentation
- `https://www.javaguides.net/2025/03/circuit-breaker-pattern-in-microservices.html` — Spring Boot 3 + Resilience4j pattern

### Tertiary (LOW confidence)
- Community reports that Orange CM does not use HMAC headers on local API webhooks
- `X-Orange-Signature` header mentioned in one community implementation — not verified
- `/cashout` and `/c2c` endpoint paths inferred from SDK function names; exact request bodies not confirmed
- Version `1.0.1` for `/mp/pay` vs `1.0.2` for other endpoints — observed in NdoleStudio SDK only

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all dependencies already in pom.xml; no new research needed
- Architecture pattern: HIGH — follows established codebase pattern exactly
- Orange API endpoints: MEDIUM — confirmed from multiple community SDKs; not from official partner docs
- payToken TTL: MEDIUM — multiple sources agree on ~10 min; exact value needs sandbox verification
- Webhook HMAC: LOW — not publicly documented for CM local API; double-check pattern is the safe fallback
- Timezone handling: HIGH — WAT = UTC+1 (Africa/Douala), no DST, confirmed by multiple sources
- Polling strategy: HIGH — Quartz pattern well-documented; threshold values are configurable

**Research date:** 2026-03-24
**Valid until:** 2026-04-24 (30 days — Orange API is stable; Orange partner verification is the variable)

---

## Verification Checklist for Implementation

Before closing any task in this phase:
- [ ] payToken is stored with `payTokenIssuedAt` timestamp
- [ ] INIT transactions older than 8 min return `PayTokenExpiredException`, not cached INIT response
- [ ] Orange `createtime` always parsed through `parseOrangeTimestamp()` with `Africa/Douala` ZoneId
- [ ] Transaction row committed before any outbound HTTP call (no single `@Transactional` wrapping both)
- [ ] `@Lock(LockModeType.PESSIMISTIC_WRITE)` on state-transition queries
- [ ] Quartz job uses JDBC store (`job-store-type: jdbc`)
- [ ] Phone numbers strip country code before passing to Orange endpoints
- [ ] HMAC comparison uses `MessageDigest.isEqual()`, never `String.equals()`
- [ ] `OrangeMoneyPort` implements `MobileMoneyPort` interface in `common/payment/`
- [ ] All IT tests use WireMock `@EnableWireMock` + `@InjectWireMock`, not real Orange sandbox
- [ ] Webhook receiver exposes `processWebhook()` hook for Phase 6 wiring
- [ ] Orange IP allowlist is configurable and defaults to empty (accept-all) in test profiles
