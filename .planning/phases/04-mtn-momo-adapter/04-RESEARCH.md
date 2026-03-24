# Phase 4: MTN MoMo Adapter — Research

**Researched:** 2026-03-24
**Domain:** MTN MoMo Collections + Disbursement API — OAuth2, RequestToPay, disbursement, account validation, PUT callback, IP whitelist
**Confidence:** HIGH for structural decisions (codebase is the source of truth); MEDIUM for MTN sandbox/production specifics (verified against project requirements docs and codebase config)

---

## Summary

MTN MoMo uses OAuth2 client credentials (Basic auth to get a Bearer token, Bearer on all calls) with a 3600-second token TTL. The API is asynchronous: `POST /collection/v1_0/requesttopay` returns 202 Accepted immediately; final status arrives via **HTTP PUT callback** to a merchant-configured URL, or via polling `GET /collection/v1_0/requesttopay/{referenceId}`. This PUT-not-POST callback behaviour is the most common production integration mistake (cited as P1.4 in the phase requirements).

The Orange adapter (Phase 3) is the structural template for this phase. The MTN adapter is a parallel implementation under `com.softropic.payam.mtn` package, following the same five-layer pattern: `MtnModule` marker, `MtnMoMoConfig` (`@ConfigurationProperties`), `MtnMoMoClient` (extends `AbstractClient`), `MtnMoMoPort` (implements `MobileMoneyPort`), `MtnStatusPollerJob` (Quartz), and DTOs in `contract/`. The config layer follows `MtnConfig` + `@EnableConfigurationProperties` exactly as `OrangeConfig` does.

The two key differences from Orange that affect implementation: (1) MTN's `providerRef` is the `X-Reference-Id` UUID sent on initiation (not a payToken returned from a separate init call), so there is no payToken-expiry concern; (2) the callback uses HTTP PUT, requiring a dedicated `@PutMapping` endpoint that must be added to `AppEndpoints.PUBLIC_ENDPOINTS` (callbacks arrive unauthenticated from MTN IPs).

**Primary recommendation:** Mirror the Orange adapter structure exactly, implement `MtnMoMoPort implements MobileMoneyPort`, use the existing `OrangeTokenService` Redis pattern for OAuth2 token caching under the `mtn:token:cm` key, implement a `@PutMapping("/v1/callbacks/mtn")` controller, and enforce IP whitelist via a `HandlerInterceptor` (not a Spring Security filter) to keep it separate from the JWT security chain.

---

## Standard Stack

No new Maven dependencies are needed. All required libraries are already in `pom.xml`.

### Core (already in pom.xml)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `spring-boot-starter-web` | 3.x managed | RestTemplate via `AbstractClient` | Existing pattern for all HTTP clients |
| `spring-cloud-starter-circuitbreaker-resilience4j` | managed | Circuit breaker on MTN calls | Already used by Orange adapter |
| `spring-boot-starter-data-redis` | managed | OAuth2 token cache with TTL | Already wired; `StringRedisTemplate` is available |
| `spring-boot-starter-quartz` | managed | Status polling job | Already wired; used by Orange poller |

### Testing (already in pom.xml)
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `wiremock-spring-boot` | 4.0.9 | Mock MTN HTTP calls in integration tests | All adapter IT tests |
| Testcontainers (PostgreSQL + Redis) | managed | Real DB/Redis in IT | IT tests via `TestConfig` |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `HandlerInterceptor` for IP whitelist | Spring Security `RequestMatcher` | Spring Security filter chain runs before dispatching — IP rejection is cleaner there, but the existing security chain is complex and JWT-oriented; a `HandlerInterceptor` registered only for `/v1/callbacks/mtn` is lower-risk |
| `HandlerInterceptor` for IP whitelist | Dedicated Servlet `Filter` | A `Filter` is also acceptable and runs before MVC; either works, but `HandlerInterceptor` is easier to test with `MockMvc` |

---

## Architecture Patterns

### Recommended Package Structure

```
src/main/java/com/softropic/payam/mtn/
├── MtnModule.java                          # Plain marker class (mirrors OrangeModule)
├── config/
│   ├── MtnConfig.java                      # @Configuration, @EnableConfigurationProperties
│   ├── MtnMoMoConfig.java                  # @ConfigurationProperties(prefix = "mtn")
│   └── MtnSchedulerConfig.java             # Quartz JobDetail + Trigger beans
├── contract/
│   ├── MtnCallbackPayload.java             # PUT callback body DTO
│   ├── MtnTransactionStatus.java           # enum: PENDING, SUCCESSFUL, FAILED
│   ├── dto/
│   │   ├── MtnTokenResponse.java           # access_token, token_type, expires_in
│   │   ├── RequestToPayRequest.java        # POST body for requesttopay
│   │   ├── RequestToPayStatusResponse.java # GET response: status, financialTransactionId
│   │   ├── DisbursementRequest.java        # POST body for disbursement transfer
│   │   └── AccountBalanceResponse.java     # availableBalance, currency
│   └── exception/
│       ├── MtnApiException.java            # Runtime exception for API errors
│       └── MtnAccountInactiveException.java # Thrown on inactive account validation
├── infrastructure/
│   └── MtnMoMoClient.java                  # extends AbstractClient
├── service/
│   ├── MtnMoMoPort.java                    # implements MobileMoneyPort
│   ├── MtnTokenService.java                # Redis token cache (mirrors OrangeTokenService)
│   ├── MtnStatusPollerJob.java             # QuartzJobBean (mirrors OrangeStatusPollerJob)
│   └── MtnStatusMapper.java                # PENDING/SUCCESSFUL/FAILED → TransactionStatus
└── web/
    ├── MtnCallbackController.java          # @PutMapping("/v1/callbacks/mtn")
    └── MtnIpWhitelistInterceptor.java      # HandlerInterceptor — rejects non-MTN IPs
```

### Pattern 1: OAuth2 Token Caching in Redis

Mirrors `OrangeTokenService` exactly. Use a different Redis key prefix to avoid collision.

```java
// Token key must be distinct from Orange
private static final String TOKEN_KEY  = "mtn:token:cm";
private static final String LOCK_KEY   = "mtn:token:lock";
private static final Duration TTL      = Duration.ofMinutes(55); // expires_in=3600, store for 55 min
private static final Duration LOCK_TTL = Duration.ofSeconds(10);
```

Token endpoint: `POST /collection/token/` with `Authorization: Basic base64(apiUserId:apiKey)`. No `grant_type` form body needed — MTN token endpoint uses Basic auth only, unlike Orange which uses `grant_type=client_credentials` form body.

On HTTP 401 from any MTN API call: call `mtnTokenService.evict()` then retry once (the circuit breaker retry config handles this).

### Pattern 2: RequestToPay Initiation

```java
// The X-Reference-Id IS the providerRef — generate it before the call, store it
String referenceId = UUID.randomUUID().toString();

HttpHeaders headers = toHttpHeaders(Map.of(
    "Authorization",              "Bearer " + token,
    "X-Reference-Id",             referenceId,         // ← this becomes providerRef
    "X-Target-Environment",       config.getTargetEnvironment(),
    "Ocp-Apim-Subscription-Key",  config.getSubscriptionKey(),
    "Content-Type",               "application/json"
));

// POST /collection/v1_0/requesttopay returns 202 — no body
// Store referenceId as transaction.providerRef BEFORE the call for idempotency
```

**Key difference from Orange:** There is no separate "get payToken" step. The `X-Reference-Id` UUID is generated by the merchant and sent as a header. It becomes the `providerRef` for all subsequent polling and callback correlation.

### Pattern 3: Status Polling

```java
// GET /collection/v1_0/requesttopay/{referenceId}
// Response: {"status": "PENDING|SUCCESSFUL|FAILED", "financialTransactionId": "...", ...}
```

Poller structure mirrors `OrangeStatusPollerJob`. Query: `findByTxStatusAndProviderAndLastModifiedDateBefore(PROCESSING, MTN, cutoff)`. Use `tx.getProviderRef()` (the `X-Reference-Id` UUID) as the polling key — NOT `payToken` (MTN does not use payToken).

### Pattern 4: PUT Callback Handling

```java
@RestController
public class MtnCallbackController {

    @PutMapping("/v1/callbacks/mtn")
    public ResponseEntity<Void> handleCallback(
            @RequestBody MtnCallbackPayload payload,
            HttpServletRequest request) {
        // IP whitelist check is handled upstream by MtnIpWhitelistInterceptor
        // Process: find transaction by payload.getExternalId() or referenceId, call port
        return ResponseEntity.ok().build();
    }
}
```

The callback endpoint must be added to `AppEndpoints.PUBLIC_ENDPOINTS` (it is unauthenticated — MTN does not send credentials). The IP whitelist interceptor guards it instead.

### Pattern 5: IP Whitelist

```java
@Component
public class MtnIpWhitelistInterceptor implements HandlerInterceptor {

    private final Set<String> allowedCidrs; // from MtnMoMoConfig

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String clientIp = extractClientIp(request); // respect X-Forwarded-For (server.forward-headers-strategy=native is set)
        if (!isAllowed(clientIp)) {
            response.setStatus(403);
            return false;
        }
        return true;
    }
}
```

Register only for `/v1/callbacks/mtn` in `WebMvcConfigurer.addInterceptors()`. This does NOT touch the Spring Security filter chain.

### Pattern 6: Resilience4j Config

Add MTN instances to `application.yaml` mirroring Orange:

```yaml
resilience4j:
  circuitbreaker:
    instances:
      mtn:
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        slidingWindowSize: 10
        permittedNumberOfCallsInHalfOpenState: 3
        ignoreExceptions:
          - com.softropic.payam.mtn.contract.exception.MtnAccountInactiveException
  retry:
    instances:
      mtn:
        maxAttempts: 3
        waitDuration: 1s
        enableExponentialBackoff: true
        exponentialBackoffMultiplier: 2
        retryExceptions:
          - java.net.SocketTimeoutException
          - org.springframework.web.client.ResourceAccessException
```

No `fallbackMethod` on `@CircuitBreaker` — same decision as Orange adapter (prior decision).

### Anti-Patterns to Avoid
- **Using `@PostMapping` for the callback endpoint:** MTN sends HTTP PUT. A `@PostMapping` will receive 405 Method Not Allowed and silently discard callbacks. This is P1.4.
- **Storing payToken for MTN:** MTN does not issue a payToken. The `X-Reference-Id` UUID is the correlation key. Do not repurpose the `pay_token` DB column for MTN.
- **Sharing the same Redis key as Orange:** `orange:token:cm` and `mtn:token:cm` must be distinct keys.
- **Authenticating the callback endpoint:** MTN callbacks arrive without credentials. The endpoint must be in `PUBLIC_ENDPOINTS`. Security is provided by IP whitelist only.
- **Calling `getTransactionStatus` inside the callback handler within the same transaction:** Use `REQUIRES_NEW` propagation for the state transition (same as Orange `persistPayToken` pattern) to avoid holding a DB connection during callback processing.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| OAuth2 token caching with distributed lock | Custom in-memory cache | `StringRedisTemplate` NX+EX lock pattern (copy from `OrangeTokenService`) | Handles multi-node race; already proven in this codebase |
| CIDR matching for IP whitelist | Custom string prefix matcher | `org.apache.commons.net.util.SubnetUtils` or simple exact-match set | Commons-net is on classpath transitively; exact-match list is sufficient for known MTN IP ranges |
| UUID generation for `X-Reference-Id` | Custom ID scheme | `UUID.randomUUID().toString()` | MTN requires a valid RFC 4122 UUID |
| Status polling scheduler | `@Scheduled` | Quartz `QuartzJobBean` (copy from `OrangeSchedulerConfig`) | Quartz with JDBC store survives restarts; already wired |
| Retry on 401 | Manual retry loop | `mtnTokenService.evict()` + Resilience4j `@Retry` | Evict cache on 401, retry picks up fresh token |

**Key insight:** The Orange adapter is already a validated, working reference implementation. Copy its structure directly; do not design from scratch. Every deviation from Orange's pattern requires explicit justification.

---

## Common Pitfalls

### Pitfall 1: POST vs PUT Callback (P1.4)
**What goes wrong:** Spring MVC `@PostMapping("/v1/callbacks/mtn")` rejects MTN callbacks with 405. The transaction stays in PROCESSING forever until poller times it out (up to 75 minutes with 15 attempts × 5 min).
**Why it happens:** MTN's callback spec explicitly states `HTTP PUT`. Developers assume POST because most webhook systems use POST.
**How to avoid:** Use `@PutMapping`. Add an integration test that stubs a PUT request to the callback URL and verifies the transaction transitions to SUCCESS.
**Warning signs:** Transactions always reaching max poll attempts rather than completing via callback.

### Pitfall 2: Callback Endpoint Not in PUBLIC_ENDPOINTS
**What goes wrong:** MTN's callback request hits the `JWTAuthorizationFilter`, which rejects it with 401 (no Bearer token on the request). Transaction never completes.
**Why it happens:** All `/v1/**` paths are in `SECURED_ENDPOINTS` in `AppEndpoints`. A new `/v1/callbacks/mtn` endpoint falls under this pattern without explicit exemption.
**How to avoid:** Add `"/v1/callbacks/mtn"` to `AppEndpoints.PUBLIC_ENDPOINTS` before the `SecurityFilterChain` is built.
**Warning signs:** 401 responses appearing in access logs for PUT requests from MTN IP ranges.

### Pitfall 3: Callback Body Missing financialTransactionId
**What goes wrong:** Code assumes `financialTransactionId` is always present in callback. It is absent when `status=FAILED`.
**Why it happens:** MTN only populates `financialTransactionId` on successful transactions.
**How to avoid:** `MtnCallbackPayload.getFinancialTransactionId()` must handle null. Correlation must use `externalId` (your `transactionId`) not `financialTransactionId`.
**Warning signs:** NullPointerException in callback handler on failed transactions.

### Pitfall 4: Token Endpoint Body Format
**What goes wrong:** Sending `grant_type=client_credentials` form body to MTN token endpoint returns 400.
**Why it happens:** Orange uses `application/x-www-form-urlencoded` with `grant_type=client_credentials`. MTN's `/collection/token/` does NOT accept a form body — it uses Basic auth only with an empty or absent body.
**How to avoid:** MTN token call is `POST /collection/token/` with `Authorization: Basic base64(apiUserId:apiKey)` and no request body.
**Warning signs:** 400 response from token endpoint despite correct credentials.

### Pitfall 5: Disbursement Uses Separate Subscription Key
**What goes wrong:** Using the Collections subscription key for disbursement calls returns 401.
**Why it happens:** MTN issues separate subscription keys per product (Collections, Disbursement, Remittance). Each has its own `Ocp-Apim-Subscription-Key`.
**How to avoid:** `MtnMoMoConfig` must have `collectionSubscriptionKey` and `disbursementSubscriptionKey` as separate fields. The disbursement token endpoint is `POST /disbursement/token/` (separate from `/collection/token/`).
**Warning signs:** 401 on disbursement calls even though Collections calls succeed.

### Pitfall 6: X-Reference-Id Must Be Stored BEFORE the API Call
**What goes wrong:** API call succeeds (202) but the application crashes before persisting `providerRef`. The transaction is in PROCESSING state with no reference ID, so polling and callback correlation fail.
**Why it happens:** Unlike Orange where the providerRef comes back in the response, MTN's providerRef is generated and sent by the merchant.
**How to avoid:** Persist `providerRef` (the UUID) to the transaction record BEFORE calling `POST /collection/v1_0/requesttopay`. Use `REQUIRES_NEW` propagation (same as Orange `persistPayToken`).
**Warning signs:** PROCESSING transactions with null `providerRef` in the database.

---

## Code Examples

Verified patterns from project requirements docs and codebase.

### MtnMoMoConfig Structure
```java
// Source: mirrors OrangeMoneyConfig, informed by application.yaml client.momo section
@ConfigurationProperties(prefix = "mtn")
public class MtnMoMoConfig {
    private String collectionBaseUrl;          // https://sandbox.momodeveloper.mtn.com/collection
    private String disbursementBaseUrl;        // https://sandbox.momodeveloper.mtn.com/disbursement
    private String collectionTokenUrl;         // https://sandbox.momodeveloper.mtn.com/collection/token/
    private String disbursementTokenUrl;       // https://sandbox.momodeveloper.mtn.com/disbursement/token/
    private String apiUserId;                  // from sandbox provisioning
    private String apiKey;                     // from sandbox provisioning
    private String collectionSubscriptionKey;  // Ocp-Apim-Subscription-Key (collection product)
    private String disbursementSubscriptionKey;// Ocp-Apim-Subscription-Key (disbursement product)
    private String targetEnvironment;          // "sandbox" or "production"
    private List<String> callbackIpWhitelist;  // MTN IP ranges for callback validation
    private Poller poller = new Poller();

    // Poller nested class mirrors OrangeMoneyConfig.Poller
}
```

### application.yaml additions (dev profile)
```yaml
mtn:
  collection-base-url: https://sandbox.momodeveloper.mtn.com/collection
  disbursement-base-url: https://sandbox.momodeveloper.mtn.com/disbursement
  collection-token-url: https://sandbox.momodeveloper.mtn.com/collection/token/
  disbursement-token-url: https://sandbox.momodeveloper.mtn.com/disbursement/token/
  api-user-id: ${MTN_API_USER_ID:dev_mtn_user_id}
  api-key: ${MTN_API_KEY:dev_mtn_api_key}
  collection-subscription-key: ${MTN_COLLECTION_SUB_KEY:dev_mtn_collection_key}
  disbursement-subscription-key: ${MTN_DISBURSEMENT_SUB_KEY:dev_mtn_disbursement_key}
  target-environment: sandbox
  callback-ip-whitelist:
    - "196.0.0.0/8"       # Verify against MTN production documentation before go-live
  poller:
    initial-delay-seconds: 120
    interval-seconds: 300
    max-attempts: 15
```

### MtnTokenService (key difference from OrangeTokenService)
```java
// Source: mirrors OrangeTokenService but with different key prefix and no form body
private static final String TOKEN_KEY = "mtn:token:cm";    // distinct from orange:token:cm
private static final String LOCK_KEY  = "mtn:token:lock";
private static final Duration TTL     = Duration.ofMinutes(55); // MTN expires_in = 3600

// Token fetch — POST with Basic auth, NO form body (unlike Orange)
public MtnTokenResponse fetchToken() {
    String credentials = config.getApiUserId() + ":" + config.getApiKey();
    String basicAuth = "Basic " + Base64.getEncoder()
            .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

    HttpHeaders headers = toHttpHeaders(Map.of(
            "Authorization", basicAuth,
            "Ocp-Apim-Subscription-Key", config.getCollectionSubscriptionKey()
    ));
    // POST with null body — MTN does not accept form body on token endpoint
    ResponseEntity<MtnTokenResponse> response = makeHttpRequest(
            config.getCollectionTokenUrl(), HttpMethod.POST, null,
            MtnTokenResponse.class, headers);
    ...
}
```

### RequestToPayRequest DTO
```java
// Source: mtn-api.md requirements doc
public class RequestToPayRequest {
    private String amount;      // "1000.00"
    private String currency;    // "XAF"
    private String externalId;  // your transactionId
    private Party payer;        // {partyIdType: "MSISDN", partyId: "237XXXXXXXXX"}
    private String payerMessage;
    private String payeeNote;

    public record Party(String partyIdType, String partyId) {}
}
```

**Cameroon note:** Unlike Orange which requires the national number (stripped of `+237`), MTN accepts full E.164 MSISDN with country code (e.g., `"237692954629"` without the `+`). Verify in sandbox before finalising — the existing `PaymentCommand.msisdn()` field uses E.164 with `+` prefix, so stripping the `+` is sufficient.

### MtnCallbackPayload DTO
```java
// Source: mtn-api.md requirements doc and mtn-use-cases.md
@JsonIgnoreProperties(ignoreUnknown = true)
public class MtnCallbackPayload {
    @JsonProperty("financialTransactionId") private String financialTransactionId; // null on FAILED
    @JsonProperty("externalId")             private String externalId;             // your transactionId
    @JsonProperty("status")                 private String status;                 // SUCCESSFUL, FAILED
    @JsonProperty("reason")                 private String reason;                 // present on FAILED
}
```

### Flyway migration for MTN fields
```sql
-- V7__transaction_mtn_fields.sql
-- MTN uses providerRef (X-Reference-Id UUID) — already in transaction table
-- No new columns needed if providerRef is sufficient
-- Add mtn_financial_tx_id if you need to store the financialTransactionId from callback
SET search_path = main;
ALTER TABLE transaction
    ADD COLUMN IF NOT EXISTS mtn_financial_tx_id VARCHAR(255);
```

---

## Key Differences from Orange Adapter

| Aspect | Orange | MTN |
|--------|--------|-----|
| Token endpoint body | `grant_type=client_credentials` form body | No body — Basic auth header only |
| ProviderRef origin | Returned in pay response (`payToken`) | Generated by merchant (`X-Reference-Id` UUID) |
| ProviderRef persistence | After pay call | BEFORE initiation call |
| PayToken expiry concern | Yes — 8-minute threshold + `assertPayTokenFresh()` | No — UUID is stable, no expiry |
| Callback method | POST (see Phase 6) | HTTP PUT — critical difference |
| Callback correlation | `payToken` field in body | `externalId` (your transactionId) in body |
| Status polling key | `payToken` | `X-Reference-Id` UUID (= `providerRef`) |
| Account validation response | 200 with `{"status":"ACTIF"}` body | 200 OK (active) / 404 (inactive) — no body |
| MSISDN format | National (strip `+237`) | E.164 without `+` (strip `+` only) — verify in sandbox |
| Disbursement auth | Same token as payments | Separate disbursement product key + token endpoint |
| Test token endpoint path | `/token` | `/collection/token/` (trailing slash matters) |

---

## State of the Art

| Old Approach | Current Approach | Impact |
|--------------|------------------|--------|
| Polling only | Callback (PUT) + polling as fallback | Callbacks complete transactions in seconds; polling is the safety net |
| Single Subscription Key | Per-product subscription keys | Collections, Disbursement, Remittance each have their own key |

---

## Open Questions

1. **MTN Production IP Ranges for Callback Whitelist**
   - What we know: Sandbox callbacks originate from `sandbox.momodeveloper.mtn.com`. Production IP ranges are region-specific and not publicly documented.
   - What's unclear: The exact CIDR blocks for Cameroon/Central Africa production MTN MoMo callbacks.
   - Recommendation: For sandbox, accept any IP (or the sandbox host's IP). For production, obtain IP ranges from MTN Cameroon directly before go-live. Store as `mtn.callback-ip-whitelist` config list so it can be updated without code changes. The phase requirement calls this out as "P1.4 — verify production IP ranges for whitelist."

2. **MSISDN Format for RequestToPay (Cameroon)**
   - What we know: The codebase stores MSISDNs in E.164 format with `+` prefix (e.g., `+237692954629`). MTN API docs show `partyId: "256774290781"` (no `+`, full country code).
   - What's unclear: Whether MTN Cameroon sandbox accepts `237XXXXXXXXX` (strip `+` only) or `XXXXXXXXX` (strip `+237`).
   - Recommendation: Strip only the `+` prefix for MTN (send `237XXXXXXXXX`), then verify in sandbox. Document the verified behaviour in a comment on the adapter method.

3. **Callback `externalId` vs `financialTransactionId` for Correlation**
   - What we know: `externalId` in the callback is mapped from the `externalId` field in the `RequestToPayRequest` body, which should be set to our `transactionId`. `financialTransactionId` is only present on SUCCESSFUL status.
   - What's unclear: Whether MTN always echoes back `externalId` reliably or whether there are edge cases where it is absent.
   - Recommendation: Correlate using `externalId` (set it to `transactionId` on initiation). Store `financialTransactionId` separately when available (new `mtn_financial_tx_id` column) for reconciliation, but do not rely on it for state transitions.

---

## Sources

### Primary (HIGH confidence)
- `/requirements/mtn-api.md` — MTN MoMo Collections API endpoint reference (project-local doc)
- `/requirements/mtn-use-cases.md` — Use case flows, confirms "HTTP PUT" callback (section 5.2)
- `src/main/resources/application.yaml` — Existing `client.momo` config block confirms sandbox endpoints and headers
- `src/main/java/com/softropic/payam/orange/` — Orange adapter is the structural reference for all MTN patterns
- `src/main/java/com/softropic/payam/security/config/AppEndpoints.java` — Confirms PUBLIC_ENDPOINTS must be updated for callback

### Secondary (MEDIUM confidence)
- `src/main/resources/application-dev.yaml` — Confirms `client.momo.headers.X-Target-Environment: sandbox` and `Ocp-Apim-Subscription-Key` header name

### Tertiary (LOW confidence — flag for sandbox validation)
- MTN MoMo sandbox behaviour for token endpoint (no form body) — derived from mtn-api.md, should be verified with actual sandbox call
- MSISDN format for Cameroon (strip `+` vs strip `+237`) — LOW confidence, must verify in sandbox

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all dependencies already in pom.xml, confirmed from Orange adapter
- Architecture: HIGH — mirroring Orange is explicit prior decision; structure is fully documented
- MTN API endpoints: HIGH — confirmed from project requirements docs and existing application.yaml config
- Callback PUT method: HIGH — confirmed in mtn-use-cases.md section 5.2 and noted in phase requirements as P1.4
- IP whitelist specifics: LOW — production IPs not publicly documented, sandbox verification required
- MSISDN format: LOW — must verify in sandbox

**Research date:** 2026-03-24
**Valid until:** 2026-04-24 (MTN sandbox API is stable; IP ranges need verification before production)
